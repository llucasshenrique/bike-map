package com.ebike.router.service

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class AudioGuidanceService(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var lastSpokenText = ""
    private var lastSpokenTime = 0L
    var isMuted = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("pt", "BR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(1.05f)
            isInitialized = true
        }
    }

    fun speak(text: String, force: Boolean = false) {
        if (isMuted || !isInitialized) return
        val now = System.currentTimeMillis()
        if (!force && text == lastSpokenText && (now - lastSpokenTime) < 4000) {
            return
        }
        lastSpokenText = text
        lastSpokenTime = now

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_guidance_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
