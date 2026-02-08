package com.jarvis.ai.service

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * JarvisNotificationListener — Captures incoming notifications from messaging apps.
 *
 * This runs independently of the AccessibilityService and provides a reliable
 * second channel for detecting new messages. Even if the user is in a different app,
 * we still capture the notification content here.
 *
 * Flow:
 * 1. Notification arrives from WhatsApp/Telegram/Messenger
 * 2. We extract sender, text, conversation title, and reply action
 * 3. We emit a [NotificationMessage] on [messageFlow]
 * 4. The VoiceEngine / AI Brain subscribes and decides what to do
 *    (read aloud, auto-reply, etc.)
 */
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotifListener"

        @Volatile
        var instance: JarvisNotificationListener? = null
            private set

        val isRunning: Boolean get() = instance != null

        /** SharedFlow that other components collect from. */
        private val _messageFlow = MutableSharedFlow<NotificationMessage>(
            replay = 1,
            extraBufferCapacity = 32
        )
        val messageFlow: SharedFlow<NotificationMessage> = _messageFlow.asSharedFlow()

        /** Keeps the last N notifications for context. */
        private val recentNotifications = ArrayDeque<NotificationMessage>(50)

        fun getRecentNotifications(count: Int = 10): List<NotificationMessage> {
            return synchronized(recentNotifications) {
                recentNotifications.takeLast(count)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------ //
    //  Lifecycle                                                          //
    // ------------------------------------------------------------------ //

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification Listener CONNECTED")
    }

    override fun onListenerDisconnected() {
        instance = null
        serviceScope.cancel()
        Log.i(TAG, "Notification Listener DISCONNECTED")
        super.onListenerDisconnected()
    }

    // ------------------------------------------------------------------ //
    //  Notification Processing                                            //
    // ------------------------------------------------------------------ //

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName ?: return

        // Only process notifications from social/messaging apps
        if (packageName !in JarvisAccessibilityService.SOCIAL_PACKAGES) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Extract core fields
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: ""

        // For group messages, extract the individual messages
        val messages = extractMessagingStyleMessages(extras)

        // Skip empty or summary notifications
        if (text.isBlank() && bigText.isBlank() && messages.isEmpty()) return

        // Detect the reply action (so we can auto-reply later)
        val replyAction = findReplyAction(notification)

        // Build our notification message object
        val appName = getAppDisplayName(packageName)
        val effectiveText = bigText.ifBlank { text }

        val notifMessage = NotificationMessage(
            id = sbn.key,
            packageName = packageName,
            appName = appName,
            sender = title,
            text = effectiveText,
            conversationTitle = conversationTitle.ifBlank { title },
            subText = subText,
            summaryText = summaryText,
            groupMessages = messages,
            hasReplyAction = replyAction != null,
            replyActionIntent = replyAction,
            timestamp = sbn.postTime,
            isGroupMessage = conversationTitle.isNotBlank()
        )

        Log.d(TAG, "[$appName] $title: $effectiveText")

        // Store and emit
        synchronized(recentNotifications) {
            if (recentNotifications.size >= 50) recentNotifications.removeFirst()
            recentNotifications.addLast(notifMessage)
        }

        serviceScope.launch {
            _messageFlow.emit(notifMessage)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Could track dismissed notifications if needed
        Log.d(TAG, "Notification removed: ${sbn?.key}")
    }

    // ------------------------------------------------------------------ //
    //  PUBLIC API: Direct Reply                                           //
    // ------------------------------------------------------------------ //

    /**
     * Sends a direct reply to a notification that supports inline reply.
     * This works even when the messaging app is not in the foreground.
     */
    fun replyToNotification(notificationMessage: NotificationMessage, replyText: String): Boolean {
        val replyAction = notificationMessage.replyActionIntent ?: run {
            Log.w(TAG, "No reply action available for this notification")
            return false
        }

        return try {
            val intent = Intent().apply {
                val remoteInputs = replyAction.remoteInputs
                if (remoteInputs.isNullOrEmpty()) return false

                val bundle = Bundle().apply {
                    for (remoteInput in remoteInputs) {
                        putCharSequence(remoteInput.resultKey, replyText)
                    }
                }
                android.app.RemoteInput.addResultsToIntent(remoteInputs, this, bundle)
            }

            replyAction.actionIntent.send(this, 0, intent)
            Log.i(TAG, "Reply sent: '$replyText' to ${notificationMessage.sender}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply", e)
            false
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    /**
     * Extracts individual messages from MessagingStyle notifications.
     * Modern WhatsApp/Telegram use this style for group chats.
     */
    private fun extractMessagingStyleMessages(extras: Bundle): List<GroupMessage> {
        val messages = mutableListOf<GroupMessage>()

        try {
            // Android's EXTRA_MESSAGES contains Parcelable[] of Bundle
            val msgArray = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            msgArray?.forEach { parcelable ->
                if (parcelable is Bundle) {
                    val sender = parcelable.getCharSequence("sender")?.toString() ?: "Unknown"
                    val msgText = parcelable.getCharSequence("text")?.toString() ?: ""
                    val time = parcelable.getLong("time", 0L)
                    if (msgText.isNotBlank()) {
                        messages.add(GroupMessage(sender = sender, text = msgText, timestamp = time))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract messaging style messages", e)
        }

        return messages
    }

    /**
     * Finds the reply action in a notification's action list.
     * Reply actions have RemoteInput attached.
     */
    private fun findReplyAction(notification: Notification): Notification.Action? {
        return notification.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }
    }

    private fun getAppDisplayName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp" -> "WhatsApp"
            "com.whatsapp.w4b" -> "WhatsApp Business"
            "org.telegram.messenger" -> "Telegram"
            "com.facebook.orca" -> "Messenger"
            "com.facebook.katana" -> "Facebook"
            "com.instagram.android" -> "Instagram"
            "com.twitter.android" -> "X (Twitter)"
            "com.snapchat.android" -> "Snapchat"
            else -> packageName
        }
    }

    // ------------------------------------------------------------------ //
    //  Data Classes                                                       //
    // ------------------------------------------------------------------ //

    data class NotificationMessage(
        val id: String,
        val packageName: String,
        val appName: String,
        val sender: String,
        val text: String,
        val conversationTitle: String,
        val subText: String,
        val summaryText: String,
        val groupMessages: List<GroupMessage>,
        val hasReplyAction: Boolean,
        val replyActionIntent: Notification.Action?,
        val timestamp: Long,
        val isGroupMessage: Boolean
    ) {
        /** Formatted string for LLM context injection. */
        fun toContextString(): String {
            return buildString {
                append("[$appName] ")
                if (isGroupMessage) append("Group '$conversationTitle' - ")
                append("$sender: $text")
                if (groupMessages.isNotEmpty()) {
                    append("\n  Recent messages in conversation:")
                    groupMessages.forEach { msg ->
                        append("\n    ${msg.sender}: ${msg.text}")
                    }
                }
            }
        }
    }

    data class GroupMessage(
        val sender: String,
        val text: String,
        val timestamp: Long
    )
}
