package com.mahasamuha.assistant.voice

import android.service.voice.VoiceInteractionService
import android.util.Log

const val TAG = "Assistant"

/**
 * Bound by the system while this app holds the assistant role. Sessions are
 * created by [AssistantSessionService]; this service only needs to exist.
 */
class AssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(TAG, "AssistantService.onReady")
    }

    override fun onShutdown() {
        Log.i(TAG, "AssistantService.onShutdown")
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        Log.i(TAG, "AssistantService.onLaunchVoiceAssistFromKeyguard")
    }
}
