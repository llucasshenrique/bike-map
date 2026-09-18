package com.ebike.router.service

import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.speech.tts.TextToSpeech
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * Independent review of the audio-focus/ducking behavior added in commit
 * 9d254b8 (device-locale TTS + audio focus). Written by a different agent
 * than the one that implemented the feature.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioGuidanceServiceTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

    /**
     * TextToSpeech's real engine-bind + onInit callback is asynchronous even under Robolectric's
     * shadow; the callback is posted to the (paused-by-default) main looper rather than run
     * inline. Idling the looper here flushes it so `isInitialized` is true before speak() calls
     * in each test, matching how the real app only starts speaking once TTS reports SUCCESS.
     */
    private fun createInitializedService(): AudioGuidanceService {
        val service = AudioGuidanceService(context)
        ShadowLooper.idleMainLooper()
        // Robolectric's TextToSpeech shadow does not fire onInit on its own; invoke it directly
        // so the service reaches the same "ready" state the real app waits for before speaking.
        val shadowTts = org.robolectric.Shadows.shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        shadowTts.onInitListener?.onInit(TextToSpeech.SUCCESS)
        return service
    }

    @Test
    fun `speaking requests audio focus with navigation-guidance usage`() {
        val service = createInitializedService()

        service.speak("Vire à esquerda")

        val shadowAudioManager = shadowOf(audioManager)
        val lastRequest = shadowAudioManager.lastAudioFocusRequest
        assertNotNull("expected an audio focus request to have been made", lastRequest)
        assertEquals(
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            lastRequest!!.audioFocusRequest.audioAttributes.usage
        )
        // AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK == 3 (android.media.AudioManager); referenced by
        // value because this project's unit-test classpath does not expose the named constant.
        assertEquals(3, lastRequest.durationHint)
    }

    @Test
    fun `muted service never requests audio focus`() {
        val service = createInitializedService()
        service.isMuted = true

        service.speak("Should not be spoken")

        assertNull(shadowOf(audioManager).lastAudioFocusRequest)
    }

    @Test
    fun `stop abandons the audio focus request`() {
        val service = createInitializedService()
        service.speak("Continue em frente")
        val shadowAudioManager = shadowOf(audioManager)
        assertNotNull(shadowAudioManager.lastAudioFocusRequest)

        service.stop()

        assertNotNull(
            "expected the focus request to be abandoned after stop()",
            shadowAudioManager.lastAbandonedAudioFocusRequest
        )
    }

    @Test
    fun `repeated identical text within the debounce window does not request focus again`() {
        val service = createInitializedService()

        service.speak("Rotatória à frente")
        val firstRequest = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull(firstRequest)

        service.speak("Rotatória à frente")
        val secondRequest = shadowOf(audioManager).lastAudioFocusRequest

        // A real new AudioFocusRequest.Builder().build() always yields a new object, so identity
        // equality here proves no second focus request was made for the deduped call.
        assertTrue(
            "second identical speak() within the debounce window should not request focus again",
            firstRequest === secondRequest
        )
    }

    @Test
    fun `forced repeat of identical text bypasses the debounce window and requests focus again`() {
        val service = createInitializedService()

        service.speak("Curva à direita")
        val firstRequest = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull(firstRequest)

        service.speak("Curva à direita", force = true)
        val secondRequest = shadowOf(audioManager).lastAudioFocusRequest

        assertTrue(
            "force=true should bypass the debounce and issue a fresh focus request",
            firstRequest !== secondRequest
        )
    }
}
