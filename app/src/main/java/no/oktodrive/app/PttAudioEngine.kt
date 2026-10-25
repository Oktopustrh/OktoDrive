package no.oktodrive.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import io.voxkit.kopus.Channels
import io.voxkit.kopus.Opus
import io.voxkit.kopus.OpusApplication
import io.voxkit.kopus.OpusEncoder
import io.voxkit.kopus.SampleRate
import io.voxkit.kopus.encoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First real PTT audio path: Android microphone -> 48 kHz mono PCM -> Opus.
 * Transport is delegated to MumbleClient so the capture/codec layer stays UI-independent.
 */
internal class PttAudioEngine(
    private val sendFrame: (opus: ByteArray, frameNumber: Long, terminator: Boolean) -> Boolean,
    private val onState: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var frameNumber = 0L

    fun isRunning(): Boolean = running.get()

    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            running.set(false)
            onState("Mikrofon kunne ikke initialiseres (buffer=$minBuffer)")
            return false
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            running.set(false)
            onState("Mikrofon kunne ikke initialiseres")
            return false
        }
        recorder = audioRecord

        worker = Thread({
            var encoder: OpusEncoder? = null
            try {
                val activeEncoder = Opus.encoder(
                    sampleRate = SampleRate.RATE_48K,
                    channels = Channels.MONO,
                    application = OpusApplication.VOIP
                )
                encoder = activeEncoder
                val pcm = ShortArray(FRAME_SAMPLES)
                val encoded = ByteArray(OpusEncoder.DEFAULT_OUTPUT_BUFFER_SIZE)
                audioRecord.startRecording()
                onState("PTT sender")

                while (running.get()) {
                    var filled = 0
                    while (running.get() && filled < FRAME_SAMPLES) {
                        val read = audioRecord.read(pcm, filled, FRAME_SAMPLES - filled)
                        if (read < 0) throw IllegalStateException("AudioRecord.read feilet: $read")
                        if (read == 0) continue
                        filled += read
                    }
                    if (!running.get() || filled != FRAME_SAMPLES) break

                    val encodedLength = activeEncoder.encode(pcm, FRAME_SAMPLES, encoded)
                    if (encodedLength <= 0) continue
                    val packet = encoded.copyOf(encodedLength)
                    if (!sendFrame(packet, frameNumber, false)) {
                        throw IllegalStateException("Mumble-forbindelsen tok ikke imot lydpakken")
                    }
                    frameNumber += 1
                }
            } catch (t: Throwable) {
                Log.e(TAG, "PTT capture failed", t)
                onState("PTT-feil: ${t.localizedMessage ?: t.javaClass.simpleName}")
            } finally {
                try {
                    sendFrame(ByteArray(0), frameNumber, true)
                } catch (_: Throwable) {
                }
                try {
                    audioRecord.stop()
                } catch (_: Throwable) {
                }
                audioRecord.release()
                recorder = null
                try {
                    encoder?.close()
                } catch (_: Throwable) {
                }
                running.set(false)
                onState("PTT klar")
            }
        }, "OktoDrive-PTT-Audio").also { it.start() }

        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "OktoDrivePTT"
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960 // 20 ms at 48 kHz
    }
}
