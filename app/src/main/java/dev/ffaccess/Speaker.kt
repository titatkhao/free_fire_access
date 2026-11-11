package dev.ffaccess
import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech

/** Uses the system default TTS engine, voice, rate and pitch, played on the MEDIA stream. */
class Speaker(ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                ready = true
            }
        }
    }
    fun speak(text: String, interrupt: Boolean = true) {
        if (!ready) return
        tts?.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }
    fun shutdown() { tts?.shutdown() }
}
