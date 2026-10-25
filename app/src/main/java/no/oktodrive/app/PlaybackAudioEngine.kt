package no.oktodrive.app

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import io.voxkit.kopus.Channels
import io.voxkit.kopus.Opus
import io.voxkit.kopus.OpusDecoder
import io.voxkit.kopus.SampleRate
import io.voxkit.kopus.decoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First incoming-audio path: tunneled Mumble Opus frames -> PCM -> Android AudioTrack.
 *
 * Frames are decoded on a dedicated worker so the Mumble control socket is never blocked
 * by AudioTrack writes. A decoder is kept per sender session so normal talk bursts retain
 * Opus decoder state.
 */
internal class PlaybackAudioEngine {
    data class Frame(
        val senderSession: Int,
        val frameNumber: Long,
        val opusData: ByteArray,
        val terminator: Boolean
    )

    private val running = AtomicBoolean(true)
    private val muted = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<Frame>(MAX_QUEUE_FRAMES)
    private val decoders = mutableMapOf<Int, OpusDecoder>()
    private val worker: Thread

    init {
        worker = Thread(::playbackLoop, "OktoDrive-Playback").also { it.start() }
    }

    fun enqueue(frame: Frame) {
        if (!running.get()) return
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
            Log.w(TAG, "Playback queue full; dropped oldest frame")
        }
    }

    fun setMuted(value: Boolean) {
        muted.set(value)
        if (value) queue.clear()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        queue.clear()
        worker.interrupt()
    }

    @Suppress("DEPRECATION")
    private fun playbackLoop() {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer initialization failed: $minBuffer")
            return
        }

        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
            AudioTrack.MODE_STREAM
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize")
            track.release()
            return
        }

        try {
            track.play()
            while (running.get()) {
                val frame = try {
                    queue.poll(500, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    if (!running.get()) break else continue
                } ?: continue

                if (frame.terminator) {
                    closeDecoder(frame.senderSession)
                    continue
                }
                if (frame.opusData.isEmpty()) continue

                val decoder = decoders.getOrPut(frame.senderSession) {
                    Opus.decoder(SampleRate.RATE_48K, Channels.MONO)
                }
                val pcm = ShortArray(MAX_DECODE_SAMPLES)
                val decodedSamples = try {
                    decoder.decode(frame.opusData, MAX_DECODE_SAMPLES, pcm)
                } catch (t: Throwable) {
                    Log.w(TAG, "Opus decode failed for session=${frame.senderSession}", t)
                    closeDecoder(frame.senderSession)
                    continue
                }
                if (decodedSamples <= 0 || muted.get()) continue

                var written = 0
                while (running.get() && written < decodedSamples && !muted.get()) {
                    val count = track.write(pcm, written, decodedSamples - written)
                    if (count <= 0) {
                        Log.w(TAG, "AudioTrack.write returned $count")
                        break
                    }
                    written += count
                }
            }
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            track.release()
            decoders.values.forEach { decoder ->
                try { decoder.close() } catch (_: Throwable) {}
            }
            decoders.clear()
        }
    }

    private fun closeDecoder(session: Int) {
        val decoder = decoders.remove(session) ?: return
        try { decoder.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "OktoDrivePlayback"
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960
        private const val MAX_DECODE_SAMPLES = 5_760 // Opus maximum 120 ms at 48 kHz
        private const val MAX_QUEUE_FRAMES = 100
    }
}
