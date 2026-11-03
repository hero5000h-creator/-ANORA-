package app.anora.quran

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * The recitation player, native (Media3 / ExoPlayer) instead of the web view's <audio>.
 *
 * The page still decides what to play: it loads one surah file, seeks to an ayah and follows the ayah
 * timings. This plugin only plays, and reports where it is:
 *  - "time"   {ms, playing}  every 200 ms while playing, and on every change
 *  - "loaded" {dur}           once the file is ready
 *  - "ended"                  at the end of the file
 *  - "halt"   {reason}        the system paused it (headphones unplugged, another app took the audio)
 *  - "error"  {msg}
 *
 * ExoPlayer keeps the audio focus itself: it pauses for a call and resumes after it, ducks for short
 * sounds, and pauses when headphones are unplugged. The web view no longer plays sound, so nothing fights
 * over the focus any more.
 */
@CapacitorPlugin(name = "Audio")
class AudioPlugin : Plugin() {

    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                emitTime()
                main.postDelayed(this, 200)
            }
        }
    }

    private fun get(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context).build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true
        )
        p.setHandleAudioBecomingNoisy(true)
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                emitTime()
                main.removeCallbacks(ticker)
                if (isPlaying) main.postDelayed(ticker, 200)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !readySent) {
                    readySent = true
                    notifyListeners("loaded", JSObject().put("dur", p.duration))
                }
                if (state == Player.STATE_ENDED) {
                    emitTime()
                    notifyListeners("ended", JSObject())
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    notifyListeners("halt", JSObject().put("reason", reason))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                notifyListeners("error", JSObject().put("msg", error.errorCodeName))
            }
        })
        player = p
        return p
    }

    private var readySent = false

    private fun emitTime() {
        val p = player ?: return
        notifyListeners("time", JSObject().put("ms", p.currentPosition).put("playing", p.isPlaying))
    }

    /** ExoPlayer must be used from the main thread; plugin calls arrive on a background one. */
    private fun onMain(call: PluginCall, block: (ExoPlayer) -> Unit) {
        main.post {
            try {
                block(get())
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "audio error")
            }
        }
    }

    @PluginMethod
    fun load(call: PluginCall) {
        val url = call.getString("url")
        if (url.isNullOrEmpty()) {
            call.reject("no url")
            return
        }
        val at = call.getLong("ms") ?: 0L
        onMain(call) { p ->
            readySent = false
            p.setMediaItem(MediaItem.fromUri(url), at)
            p.prepare()
        }
    }

    @PluginMethod
    fun play(call: PluginCall) = onMain(call) { p ->
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    @PluginMethod
    fun pause(call: PluginCall) = onMain(call) { p -> p.pause() }

    @PluginMethod
    fun seek(call: PluginCall) {
        val ms = call.getLong("ms") ?: 0L
        onMain(call) { p ->
            p.seekTo(ms)
            emitTime()
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) = onMain(call) { p ->
        p.stop()
        p.clearMediaItems()
    }

    override fun handleOnDestroy() {
        main.removeCallbacks(ticker)
        player?.release()
        player = null
    }
}
