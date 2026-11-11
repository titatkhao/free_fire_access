package dev.ffaccess
import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

enum class Prio { LOW, NORMAL, HIGH }

/**
 * Uses the system default TTS engine, voice, rate and pitch, played on the MEDIA stream.
 *
 * Speech rules (so announcements never lag behind the game):
 *  - A newer message with the same key replaces the older one, even mid-sentence (rapid hits: only the latest health is spoken).
 *  - A higher priority message interrupts a lower one. HIGH (hit, zone, defeat) cuts off screen reading immediately.
 *  - Stale LOW/NORMAL messages waiting in line are dropped when a HIGH one arrives.
 *  - flush = true (your own taps) stops everything and speaks right now.
 * Call from the main thread only.
 */
class Speaker(ctx: Context) {
    private class Msg(val id: String, val key: String, val text: String, val prio: Prio)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var counter = 0
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Msg>()
    private var current: Msg? = null
    private var currentStart = 0L

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { finished(utteranceId) }
                    @Deprecated("required by the abstract class")
                    override fun onError(utteranceId: String?) { finished(utteranceId) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { finished(utteranceId) }
                })
                ready = true
                main.post { pump() }
            }
        }
    }

    private fun finished(id: String?) = main.post { if (current?.id == id) { current = null; pump() } }

    fun speak(text: String, prio: Prio = Prio.NORMAL, key: String = text, flush: Boolean = false) {
        val m = Msg("u${counter++}", key, text, prio)
        if (flush) {
            queue.clear(); current = null
            queue.add(m); pump()
            return
        }
        // an utterance whose "done" callback never came must not block everything forever
        if (current != null && SystemClock.elapsedRealtime() - currentStart > 8000) current = null
        val cur = current
        queue.removeAll { it.key == key }                                   // newer message with the same key wins
        if (prio == Prio.HIGH) queue.removeAll { it.prio != Prio.HIGH }     // drop stale chatter
        if (prio == Prio.LOW) queue.removeAll { it.prio == Prio.LOW }       // keep only the newest low-priority message
        if (cur != null && (cur.key == key || prio.ordinal > cur.prio.ordinal)) {
            current = null          // interrupt what is being said now
            queue.add(0, m)
        } else queue.add(m)
        pump()
    }

    /** Speaks the best waiting message. Always cuts off whatever the engine is still saying. */
    private fun pump() {
        if (!ready || current != null) return
        var best: Msg? = null
        for (m in queue) if (best == null || m.prio.ordinal > best.prio.ordinal) best = m
        val next = best ?: return
        queue.remove(next)
        current = next
        currentStart = SystemClock.elapsedRealtime()
        tts?.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, next.id)
    }

    /** Stop everything right now. */
    fun stop() { queue.clear(); current = null; tts?.stop() }

    /** Stop unless an important game announcement is being spoken or waiting. */
    fun stopIfNotCritical() {
        queue.removeAll { it.prio != Prio.HIGH }
        val c = current
        if (c != null && c.prio != Prio.HIGH) { current = null; tts?.stop(); pump() }
    }

    fun shutdown() { tts?.shutdown() }
}
