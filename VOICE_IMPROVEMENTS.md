# Voice Recognition Improvements & Advanced Features

## Problem Statement (Bengali)
**Original Issue:** "Amar voice SE ekbar reply দিলে পরে বার relpy দে না আবার অনেক সময় কতক্ষণ পরে আমার কথা শুনতে পারে. advanced feather add koren"

**Translation:** After replying once to voice input, it doesn't reply again, and sometimes it takes a long time to recognize speech. Add advanced features.

## Solutions Implemented

### 1. Faster Continuous Voice Listening
**Problem:** Voice assistant only replied once and didn't continue listening.

**Solutions:**
- **Adaptive Restart Delays:** Implemented intelligent delays (100ms-2s) based on conversation state
  - Active conversation (within 30s of last input): 100ms delay for instant response
  - No errors: 200ms quick restart
  - Some errors: 500ms-1s cooldown
  - Many errors: 2s cooldown with exponential backoff
- **Conversation State Tracking:** System now tracks if user is in an active conversation for faster subsequent responses
- **Reduced Timeouts:** 
  - STT timeout: 15s → 12s (20% faster)
  - TTS timeout: 30s → 25s (17% faster)

### 2. Improved Voice Recognition Speed
**Problem:** Voice recognition was slow and had delays.

**Solutions:**
- **Shorter Silence Timeouts:** Configurable based on sensitivity level
  - Low (0): 6-8s (more accurate, patient)
  - Normal (1): 4-6s (balanced)
  - High (2): 2.5-3.5s (faster response)
  - Max (3): 1.5-2.5s (instant response)
- **Offline Recognition Priority:** Enabled `EXTRA_PREFER_OFFLINE` for faster local processing
- **Better Partial Results:** Real-time display of partial recognition results in notification
- **Multiple Results:** Changed `MAX_RESULTS` from 1 to 3 for better accuracy
- **Audio Source Optimization:** On Android 13+, uses `VOICE_RECOGNITION` audio source for better noise cancellation

### 3. Advanced Wake Word Detection
**Problem:** Wake word sensitivity was fixed and couldn't be adjusted.

**Solutions:**
- **Configurable Sensitivity:** Wake word ("Jarvis") detection now uses user preference
  - Low (0): 0.5 sensitivity - fewer false positives, harder to trigger
  - Normal (1): 0.65 sensitivity - balanced
  - High (2): 0.75 sensitivity - easier to trigger
  - Max (3): 0.85 sensitivity - maximum sensitivity

### 4. Better Error Handling
**Problem:** Errors caused the listening loop to break or delay.

**Solutions:**
- **Smarter Error Classification:** NO_MATCH and TIMEOUT are not counted as real errors (they're expected)
- **Exponential Backoff:** Prevents tight error loops with adaptive recovery
- **Error State Isolation:** Errors in one cycle don't affect the next conversation

### 5. Enhanced Audio Focus Management
**Problem:** Microphone conflicts with other apps.

**Solutions:**
- **Exclusive Audio Focus:** Uses `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` for guaranteed mic access
- **Audio Focus Tracking:** Monitors focus state with callbacks
- **Delayed Focus Gain Support:** Waits for audio focus if needed
- **Better Resource Management:** Properly releases audio focus after each listening cycle

### 6. New User Preferences Added
Two new settings available in PreferenceManager:

```kotlin
// Enable continuous listening mode (auto restart after each response)
var continuousListeningMode: Boolean
    default: true

// Enable audio feedback beeps for listening state
var enableAudioFeedback: Boolean
    default: false
```

## Technical Details

### Files Modified
1. **LiveVoiceAgent.kt**
   - Added adaptive delay calculation with conversation state tracking
   - Improved error handling and classification
   - Enhanced audio focus management
   - Better partial results handling
   - Optimized silence timeouts

2. **WakeWordService.kt**
   - Made wake word sensitivity configurable from user preferences
   - Added logging of sensitivity level

3. **PreferenceManager.kt**
   - Added `continuousListeningMode` setting
   - Added `enableAudioFeedback` setting
   - Added preference keys for new settings

4. **VoiceEngine.kt**
   - Reduced silence timeouts for faster response
   - Enabled offline recognition
   - Increased MAX_RESULTS to 3

### Key Improvements Summary

| Aspect | Before | After | Improvement |
|--------|--------|-------|-------------|
| STT Timeout | 15s | 12s | 20% faster |
| TTS Timeout | 30s | 25s | 17% faster |
| Restart Delay (active) | 200ms (fixed) | 100-200ms (adaptive) | 50% faster in conversation |
| Restart Delay (errors) | 500-1000ms | 500-2000ms (exponential) | Better error recovery |
| Silence Timeout (High) | 3-4s | 2.5-3.5s | 17% faster |
| Silence Timeout (Max) | 2-3s | 1.5-2.5s | 25% faster |
| Wake Word Sensitivity | Fixed (0.7) | Configurable (0.5-0.85) | User choice |
| Error Classification | All errors counted | Smart filtering | Better reliability |
| Audio Focus | Basic | Exclusive with tracking | No conflicts |
| Recognition Results | 1 | 3 | Better accuracy |

## Usage

### For Users
1. **Voice Sensitivity Settings:** Go to Settings → Voice Sensitivity
   - Select Low/Normal/High/Max based on your preference
   - Higher = faster response but may trigger more easily
   - Lower = more accurate but needs clearer speech

2. **Wake Word Sensitivity:** Same setting controls wake word detection
   - Higher values make "Hey Jarvis" easier to detect

3. **Continuous Mode:** Already enabled by default
   - Voice assistant will keep listening after each response
   - No need to say "Jarvis" again between commands in active conversation

### For Developers
The adaptive delay algorithm:
```kotlin
private fun calculateAdaptiveDelay(): Long {
    val timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessfulListenTime
    if (isInActiveConversation && timeSinceLastSuccess < 30_000) {
        return MIN_RESTART_DELAY_MS  // 100ms - instant in conversation
    }
    
    return when {
        consecutiveSttErrors >= 5 -> MAX_RESTART_DELAY_MS  // 2s
        consecutiveSttErrors >= 3 -> 1000L
        consecutiveSttErrors > 0 -> 500L
        else -> 200L
    }
}
```

## Testing Recommendations
1. Test continuous conversation flow (ask multiple questions without wake word)
2. Verify faster response time in active conversations
3. Test different sensitivity levels
4. Verify wake word detection with different sensitivities
5. Test error recovery (cover mic, network issues, etc.)
6. Verify no audio conflicts with other apps

## Future Enhancements (Possible)
- Audio feedback beeps implementation (setting exists but not implemented)
- Voice activity detection visualization
- Custom wake word support
- Multi-language wake word detection
- Cloud-based speech recognition fallback
- Voice command history and learning
