package com.jarvis.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * JarvisAccessibilityService — The "Eyes & Hands" of Jarvis.
 *
 * Responsibilities:
 * 1. Read all text nodes visible on screen (especially messaging apps).
 * 2. Detect which app is in the foreground.
 * 3. Find specific UI elements by view ID or text content.
 * 4. Perform programmatic clicks, text input, scrolling, and gestures.
 * 5. Emit screen-change events so the AI brain can react.
 *
 * Architecture note: This service exposes a static [instance] reference so the
 * rest of the app (ViewModel / VoiceEngine) can call methods like
 * [readScreenText], [clickNodeByText], [typeText] without binding.
 */
class JarvisAccessibilityService : AccessibilityService() {

    // ------------------------------------------------------------------ //
    //  Companion / Singleton Access                                       //
    // ------------------------------------------------------------------ //

    companion object {
        private const val TAG = "JarvisA11y"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        /** Quick check from anywhere in the app. */
        val isRunning: Boolean get() = instance != null

        // Package names of supported social/messaging apps
        val SOCIAL_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.facebook.orca",       // Messenger
            "com.facebook.katana",     // Facebook
            "com.instagram.android",
            "com.twitter.android",
            "com.snapchat.android"
        )

        // Known view IDs for rapid element lookup (WhatsApp-specific examples)
        private const val WA_MESSAGE_LIST = "com.whatsapp:id/message_list"  // RecyclerView
        private const val WA_INPUT_FIELD = "com.whatsapp:id/entry"          // EditText
        private const val WA_SEND_BUTTON = "com.whatsapp:id/send"          // ImageButton
        private const val WA_CONTACT_NAME = "com.whatsapp:id/conversation_contact_name"

        // Telegram view IDs
        private const val TG_MESSAGE_LIST = "org.telegram.messenger:id/chat_list_view"
        private const val TG_INPUT_FIELD = "org.telegram.messenger:id/message_input_field"
        private const val TG_SEND_BUTTON = "org.telegram.messenger:id/send_button"
    }

    // ------------------------------------------------------------------ //
    //  Event Flow — other components observe this                         //
    // ------------------------------------------------------------------ //

    private val _screenEvents = MutableSharedFlow<ScreenEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val screenEvents: SharedFlow<ScreenEvent> = _screenEvents.asSharedFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Tracks the currently foreground package. */
    var currentPackage: String = ""
        private set

    // ------------------------------------------------------------------ //
    //  Lifecycle                                                          //
    // ------------------------------------------------------------------ //

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Reinforce config programmatically (belt-and-suspenders with XML).
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN or
                    AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }

        Log.i(TAG, "Jarvis Accessibility Service CONNECTED")
    }

    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        Log.i(TAG, "Jarvis Accessibility Service DESTROYED")
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    // ------------------------------------------------------------------ //
    //  Event Dispatch                                                     //
    // ------------------------------------------------------------------ //

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return
        currentPackage = pkg

        when (event.eventType) {
            // --- Window changed (user switched apps or opened a chat) ---
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                serviceScope.launch {
                    _screenEvents.emit(
                        ScreenEvent.WindowChanged(
                            packageName = pkg,
                            className = event.className?.toString() ?: "",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                Log.d(TAG, "Window changed -> $pkg / ${event.className}")
            }

            // --- Content changed (new message appeared, UI updated) ---
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg in SOCIAL_PACKAGES) {
                    serviceScope.launch {
                        _screenEvents.emit(
                            ScreenEvent.ContentChanged(
                                packageName = pkg,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }

            // --- Notification posted (we also get these via NotificationListener) ---
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text?.joinToString(" ") ?: ""
                if (text.isNotBlank() && pkg in SOCIAL_PACKAGES) {
                    serviceScope.launch {
                        _screenEvents.emit(
                            ScreenEvent.NotificationDetected(
                                packageName = pkg,
                                text = text,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                    Log.d(TAG, "Notification from $pkg: $text")
                }
            }

            // --- View scrolled (user scrolling chat) ---
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Could be used to detect when user is reading older messages
            }

            else -> { /* Ignore other event types */ }
        }
    }

    // ------------------------------------------------------------------ //
    //  PUBLIC API: Screen Reading                                         //
    // ------------------------------------------------------------------ //

    /**
     * Reads ALL visible text from the current screen.
     * Returns a structured list of [ScreenNode] with text, view IDs, bounds, etc.
     */
    fun readScreenText(): List<ScreenNode> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<ScreenNode>()
        traverseNode(rootNode, nodes, depth = 0)
        rootNode.recycle()
        return nodes
    }

    /**
     * Reads only the text content, concatenated. Good for quick LLM context.
     */
    fun readScreenTextFlat(): String {
        return readScreenText()
            .filter { it.text.isNotBlank() }
            .joinToString("\n") { it.text }
    }

    /**
     * Reads the last N messages from a messaging app's chat list.
     * Works for WhatsApp, Telegram, Messenger.
     */
    fun readLastMessages(count: Int = 5): List<MessageInfo> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val messages = mutableListOf<MessageInfo>()

        // Determine which message list ID to look for
        val messageListId = when (currentPackage) {
            "com.whatsapp", "com.whatsapp.w4b" -> WA_MESSAGE_LIST
            "org.telegram.messenger" -> TG_MESSAGE_LIST
            else -> null
        }

        if (messageListId != null) {
            val listNodes = rootNode.findAccessibilityNodeInfosByViewId(messageListId)
            listNodes?.firstOrNull()?.let { listNode ->
                extractMessagesFromList(listNode, messages)
                listNode.recycle()
            }
        } else {
            // Fallback: just grab all text nodes
            val allNodes = readScreenText()
            allNodes.takeLast(count).forEach { node ->
                messages.add(MessageInfo(sender = "", text = node.text, timestamp = ""))
            }
        }

        rootNode.recycle()
        return messages.takeLast(count)
    }

    /**
     * Gets the contact/chat name if in a chat window.
     */
    fun getCurrentChatName(): String? {
        val rootNode = rootInActiveWindow ?: return null

        val nameId = when (currentPackage) {
            "com.whatsapp", "com.whatsapp.w4b" -> WA_CONTACT_NAME
            else -> null
        }

        val name = if (nameId != null) {
            rootNode.findAccessibilityNodeInfosByViewId(nameId)
                ?.firstOrNull()?.text?.toString()
        } else {
            // Fallback: first text node is often the title
            null
        }

        rootNode.recycle()
        return name
    }

    // ------------------------------------------------------------------ //
    //  PUBLIC API: Interaction (Clicks, Typing, Scrolling)                //
    // ------------------------------------------------------------------ //

    /**
     * Finds a node containing [text] and performs a click on it.
     * Returns true if the click was dispatched successfully.
     */
    fun clickNodeByText(text: String, exact: Boolean = false): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val target = findNodeByText(rootNode, text, exact)

        val result = if (target != null) {
            performClickOnNode(target)
        } else {
            Log.w(TAG, "clickNodeByText: no node found with text='$text'")
            false
        }

        rootNode.recycle()
        return result
    }

    /**
     * Finds a node by its resource view ID and clicks it.
     * Example: clickNodeById("com.whatsapp:id/send")
     */
    fun clickNodeById(viewId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        val target = nodes?.firstOrNull()

        val result = if (target != null) {
            performClickOnNode(target)
        } else {
            Log.w(TAG, "clickNodeById: no node found with id='$viewId'")
            false
        }

        rootNode.recycle()
        return result
    }

    /**
     * Types text into the currently focused input field, or into the field
     * identified by [viewId].
     */
    fun typeText(text: String, viewId: String? = null): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        val inputNode = if (viewId != null) {
            rootNode.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
        } else {
            findFocusedInputNode(rootNode)
        }

        if (inputNode == null) {
            Log.w(TAG, "typeText: no input node found")
            rootNode.recycle()
            return false
        }

        // Focus the node first
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        // Set the text via ACTION_SET_TEXT
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        Log.d(TAG, "typeText result=$result, text='$text'")
        rootNode.recycle()
        return result
    }

    /**
     * Types a message into WhatsApp/Telegram and sends it.
     */
    fun sendMessage(message: String): Boolean {
        val (inputId, sendId) = when (currentPackage) {
            "com.whatsapp", "com.whatsapp.w4b" -> WA_INPUT_FIELD to WA_SEND_BUTTON
            "org.telegram.messenger" -> TG_INPUT_FIELD to TG_SEND_BUTTON
            else -> return false
        }

        // Type the message
        if (!typeText(message, inputId)) return false

        // Small delay to let the UI update and show the send button
        Thread.sleep(200)

        // Click send
        return clickNodeById(sendId)
    }

    /**
     * Performs a scroll gesture (up or down) on the screen.
     */
    fun scroll(direction: ScrollDirection): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        val scrollable = findScrollableNode(rootNode)
        val action = when (direction) {
            ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        val result = scrollable?.performAction(action) ?: false
        rootNode.recycle()
        return result
    }

    /**
     * Performs a tap gesture at specific screen coordinates.
     * Useful when no clickable node is found but we know the location.
     */
    fun tapAtCoordinates(x: Float, y: Float, durationMs: Long = 100): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var result = false
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = true
                Log.d(TAG, "Tap gesture completed at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap gesture cancelled at ($x, $y)")
            }
        }, null)

        return result
    }

    /**
     * Performs a swipe gesture from one point to another.
     */
    fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var result = false
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = true
            }
        }, null)

        return result
    }

    /**
     * Presses the global Back button.
     */
    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Presses the global Home button.
     */
    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Opens the recent apps view.
     */
    fun openRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * Opens the notification shade.
     */
    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    // ------------------------------------------------------------------ //
    //  INTERNAL: Tree Traversal & Node Helpers                            //
    // ------------------------------------------------------------------ //

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        result: MutableList<ScreenNode>,
        depth: Int
    ) {
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (text.isNotBlank() || contentDesc.isNotBlank()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            result.add(
                ScreenNode(
                    text = text.ifBlank { contentDesc },
                    viewId = viewId,
                    className = node.className?.toString() ?: "",
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    depth = depth
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, result, depth + 1)
            child.recycle()
        }
    }

    private fun extractMessagesFromList(
        listNode: AccessibilityNodeInfo,
        messages: MutableList<MessageInfo>
    ) {
        for (i in 0 until listNode.childCount) {
            val child = listNode.getChild(i) ?: continue
            val textParts = mutableListOf<String>()
            extractAllText(child, textParts)
            if (textParts.isNotEmpty()) {
                messages.add(
                    MessageInfo(
                        sender = textParts.firstOrNull() ?: "",
                        text = textParts.drop(1).joinToString(" "),
                        timestamp = ""
                    )
                )
            }
            child.recycle()
        }
    }

    private fun extractAllText(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.let { if (it.isNotBlank()) result.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractAllText(child, result)
            child.recycle()
        }
    }

    private fun findNodeByText(
        node: AccessibilityNodeInfo,
        text: String,
        exact: Boolean
    ): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val match = if (exact) {
            nodeText.equals(text, ignoreCase = true)
        } else {
            nodeText.contains(text, ignoreCase = true)
        }

        if (match) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text, exact)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findFocusedInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedInputNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        // Try clicking the node directly
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Walk up to find a clickable parent
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return result
            }
            val grandparent = parent.parent
            parent.recycle()
            parent = grandparent
        }
        // Last resort: tap at the node's center coordinates
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return tapAtCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    // ------------------------------------------------------------------ //
    //  Data Classes                                                       //
    // ------------------------------------------------------------------ //

    data class ScreenNode(
        val text: String,
        val viewId: String,
        val className: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val depth: Int
    )

    data class MessageInfo(
        val sender: String,
        val text: String,
        val timestamp: String
    )

    enum class ScrollDirection { UP, DOWN }

    sealed class ScreenEvent {
        data class WindowChanged(
            val packageName: String,
            val className: String,
            val timestamp: Long
        ) : ScreenEvent()

        data class ContentChanged(
            val packageName: String,
            val timestamp: Long
        ) : ScreenEvent()

        data class NotificationDetected(
            val packageName: String,
            val text: String,
            val timestamp: Long
        ) : ScreenEvent()
    }
}
