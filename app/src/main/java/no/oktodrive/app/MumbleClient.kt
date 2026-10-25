package no.oktodrive.app

import android.util.Log
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import no.oktodrive.app.proto.Authenticate
import no.oktodrive.app.proto.ChannelRemove
import no.oktodrive.app.proto.ChannelState
import no.oktodrive.app.proto.PermissionDenied
import no.oktodrive.app.proto.Ping
import no.oktodrive.app.proto.Reject
import no.oktodrive.app.proto.ServerSync
import no.oktodrive.app.proto.UserRemove
import no.oktodrive.app.proto.UserState
import no.oktodrive.app.proto.Version as MumbleVersion
import no.oktodrive.app.proto.udp.Audio as UdpAudio
import no.oktodrive.app.proto.udp.Ping as UdpPing

internal class MumbleClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String, connected: Boolean)
        fun onChannels(channels: List<Channel>)
        fun onActiveChannel(channelId: Int)
        fun onError(message: String)
        fun onUsers(users: List<User>) {}
    }

    data class Channel(val id: Int, val name: String, val parent: Int?)

    data class User(
        val session: Int,
        val name: String,
        val channelId: Int?,
        val selfMute: Boolean,
        val selfDeaf: Boolean,
        val mutedByServer: Boolean,
        val suppressed: Boolean
    )

    private val desiredConnected = AtomicBoolean(false)
    private val workerRunning = AtomicBoolean(false)
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var controlOutput: DataOutputStream? = null
    @Volatile private var ownSession: Int? = null
    @Volatile private var serverVersionV1: Int? = null
    @Volatile private var lastRequestedChannelId: Int? = null
    private val channels = sortedMapOf<Int, Channel>()
    private val users = mutableMapOf<Int, User>()
    private val playback = PlaybackAudioEngine()

    fun connect() {
        desiredConnected.set(true)
        if (!workerRunning.compareAndSet(false, true)) return

        Thread({
            var reconnectAttempt = 0
            var everConnected = false
            try {
                OktoDriveApp.startConnectionProtection()
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground protection", e)
            }

            try {
                while (desiredConnected.get()) {
                    var connectedThisAttempt = false
                    try {
                        val reconnecting = reconnectAttempt > 0 || everConnected
                        channels.clear()
                        users.clear()
                        listener.onChannels(emptyList())
                        listener.onUsers(emptyList())
                        listener.onStatus(
                            if (reconnecting) "Kobler til Mumble igjen …" else "Kobler til Mumble …",
                            false
                        )

                        val tls = ServerCertificate.open(host, port, ClientIdentity.keyManagers())
                        socket = tls
                        tls.soTimeout = READ_TIMEOUT_MS
                        val input = DataInputStream(tls.inputStream)
                        val output = DataOutputStream(tls.outputStream)
                        controlOutput = output

                        send(
                            output,
                            TYPE_VERSION,
                            MumbleVersion.newBuilder()
                                .setVersionV1(PROTOBUF_UDP_VERSION)
                                .setRelease("OktoDrive 0.1")
                                .setOs("Android")
                                .build()
                                .toByteArray()
                        )

                        val auth = Authenticate.newBuilder()
                            .setUsername(username)
                            .setOpus(true)
                            .apply { if (password.isNotEmpty()) setPassword(password) }
                            .build()
                        send(output, TYPE_AUTHENTICATE, auth.toByteArray())

                        var lastPing = System.currentTimeMillis()
                        while (desiredConnected.get()) {
                            if (System.currentTimeMillis() - lastPing > PING_INTERVAL_MS) {
                                send(
                                    output,
                                    TYPE_PING,
                                    Ping.newBuilder().setTimestamp(System.currentTimeMillis()).build().toByteArray()
                                )
                                lastPing = System.currentTimeMillis()
                            }

                            try {
                                val type = input.readUnsignedShort()
                                val length = input.readInt()
                                if (length < 0 || length > MAX_CONTROL_FRAME_BYTES) {
                                    throw IOException("Invalid Mumble frame size: $length")
                                }
                                val payload = ByteArray(length)
                                input.readFully(payload)
                                handleControlMessage(type, payload)

                                if (type == TYPE_SERVER_SYNC) {
                                    connectedThisAttempt = true
                                    everConnected = true
                                    reconnectAttempt = 0
                                }
                            } catch (_: SocketTimeoutException) {
                            }
                        }
                    } catch (e: FatalConnectionException) {
                        desiredConnected.set(false)
                        listener.onError(e.localizedMessage ?: "Mumble-serveren avviste forbindelsen")
                    } catch (e: Exception) {
                        if (desiredConnected.get()) {
                            reconnectAttempt += 1
                            val delayMs = reconnectDelayMs(reconnectAttempt)
                            Log.w(TAG, "Mumble connection lost; retry in ${delayMs}ms", e)
                            listener.onStatus(
                                "Forbindelsen brutt – prøver igjen om ${delayMs / 1000} s",
                                false
                            )
                            sleepForReconnect(delayMs)
                        }
                    } finally {
                        clearConnectionState()
                        listener.onUsers(emptyList())
                        if (connectedThisAttempt && desiredConnected.get()) {
                            Log.i(TAG, "Connection ended; reconnect remains enabled")
                        }
                    }
                }
            } finally {
                desiredConnected.set(false)
                workerRunning.set(false)
                clearConnectionState()
                playback.stop()
                listener.onUsers(emptyList())
                try {
                    OktoDriveApp.stopConnectionProtection()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not stop foreground protection", e)
                }
                listener.onStatus(if (everConnected) "Frakoblet" else "Ikke tilkoblet", false)
            }
        }, "OktoDrive-Mumble").start()
    }

    private fun handleControlMessage(type: Int, payload: ByteArray) {
        when (type) {
            TYPE_VERSION -> {
                val version = MumbleVersion.parseFrom(payload)
                serverVersionV1 = if (version.hasVersionV1()) version.versionV1 else null
                Log.i(TAG, "Server version_v1=${serverVersionV1 ?: 0}")
            }

            TYPE_UDP_TUNNEL -> handleIncomingVoicePacket(payload)

            TYPE_REJECT -> {
                val reject = Reject.parseFrom(payload)
                val rejectType = if (reject.hasType()) reject.type.name else "Unknown"
                val reason = if (reject.hasReason() && reject.reason.isNotBlank()) {
                    reject.reason
                } else {
                    "Ingen nærmere årsak oppgitt"
                }
                throw FatalConnectionException("Innlogging avvist [$rejectType]: $reason")
            }

            TYPE_SERVER_SYNC -> {
                val sync = ServerSync.parseFrom(payload)
                ownSession = if (sync.hasSession()) sync.session else null
                forceTcpTunnel()
                listener.onStatus("Tilkoblet Mumble som $username", true)
                listener.onChannels(channels.values.toList())
                listener.onUsers(snapshotUsers())
                ownSession?.let { session ->
                    users[session]?.channelId?.let(listener::onActiveChannel)
                }
            }

            TYPE_CHANNEL_REMOVE -> {
                val removed = ChannelRemove.parseFrom(payload)
                channels.remove(removed.channelId)
                listener.onChannels(channels.values.toList())
            }

            TYPE_CHANNEL_STATE -> {
                val state = ChannelState.parseFrom(payload)
                if (state.hasChannelId()) {
                    val id = state.channelId
                    val old = channels[id]
                    val name = if (state.hasName()) state.name else old?.name ?: "Kanal $id"
                    val parent = if (state.hasParent()) state.parent else old?.parent
                    channels[id] = Channel(id, name, parent)
                    listener.onChannels(channels.values.toList())
                }
            }

            TYPE_USER_REMOVE -> {
                val removed = UserRemove.parseFrom(payload)
                users.remove(removed.session)
                listener.onUsers(snapshotUsers())
            }

            TYPE_USER_STATE -> {
                val state = UserState.parseFrom(payload)
                if (state.hasSession()) {
                    val session = state.session
                    val previous = users[session]
                    users[session] = User(
                        session = session,
                        name = if (state.hasName()) state.name else previous?.name ?: "Bruker $session",
                        channelId = if (state.hasChannelId()) state.channelId else previous?.channelId,
                        selfMute = if (state.hasSelfMute()) state.selfMute else previous?.selfMute ?: false,
                        selfDeaf = if (state.hasSelfDeaf()) state.selfDeaf else previous?.selfDeaf ?: false,
                        mutedByServer = if (state.hasMute()) state.mute else previous?.mutedByServer ?: false,
                        suppressed = if (state.hasSuppress()) state.suppress else previous?.suppressed ?: false
                    )
                    listener.onUsers(snapshotUsers())

                    if (session == ownSession && state.hasChannelId()) {
                        if (state.channelId == lastRequestedChannelId) lastRequestedChannelId = null
                        listener.onActiveChannel(state.channelId)
                    }
                }
            }

            TYPE_PERMISSION_DENIED -> {
                val denied = PermissionDenied.parseFrom(payload)
                val reason = if (denied.hasReason() && denied.reason.isNotBlank()) denied.reason else null
                val channelId = if (denied.hasChannelId()) denied.channelId else null
                val channelName = channelId?.let { channels[it]?.name }
                listener.onError(
                    when {
                        reason != null && channelName != null -> "Kan ikke gå inn i $channelName: $reason"
                        reason != null -> "Mumble-serveren avviste handlingen: $reason"
                        channelName != null -> "Du har ikke tilgang til kanalen $channelName."
                        else -> "Mumble-serveren avviste handlingen."
                    }
                )
            }
        }
    }

    // Previously injected by CI into build 143; keep identical behavior in local/release builds.
    private fun forceTcpTunnel() {
        val output = controlOutput ?: return
        try {
            val ping = UdpPing.newBuilder().setTimestamp(System.currentTimeMillis()).build().toByteArray()
            val packet = ByteArray(ping.size + 1)
            packet[0] = UDP_TYPE_PING.toByte()
            ping.copyInto(packet, destinationOffset = 1)
            send(output, TYPE_UDP_TUNNEL, packet)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request tunneled receive audio", e)
        }
    }

    private fun handleIncomingVoicePacket(packet: ByteArray) {
        if (packet.isEmpty()) return
        try {
            val first = packet[0].toInt() and 0xff
            when {
                (first ushr 5) == LEGACY_OPUS_TYPE -> parseLegacyOpus(packet)
                first == UDP_TYPE_AUDIO -> parseProtobufAudio(packet)
                else -> Log.v(TAG, "Ignoring tunneled UDP type=$first bytes=${packet.size}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not decode incoming tunneled voice packet", t)
        }
    }

    private fun parseProtobufAudio(packet: ByteArray) {
        val audio = UdpAudio.parseFrom(packet.copyOfRange(1, packet.size))
        val sender = audio.senderSession
        if (sender == 0) return
        playback.enqueue(
            PlaybackAudioEngine.Frame(
                senderSession = sender,
                frameNumber = audio.frameNumber,
                opusData = audio.opusData.toByteArray(),
                terminator = audio.isTerminator
            )
        )
    }

    private fun parseLegacyOpus(packet: ByteArray) {
        var offset = 1
        val (session, afterSession) = readMumbleVarInt(packet, offset)
        offset = afterSession
        val (frame, afterFrame) = readMumbleVarInt(packet, offset)
        offset = afterFrame
        val (sizeField, afterSize) = readMumbleVarInt(packet, offset)
        offset = afterSize

        val terminator = (sizeField and LEGACY_TERMINATOR_BIT) != 0L
        val opusSize = (sizeField and LEGACY_SIZE_MASK).toInt()
        if (offset + opusSize > packet.size) {
            throw IOException("Invalid legacy Opus frame size=$opusSize packet=${packet.size}")
        }

        playback.enqueue(
            PlaybackAudioEngine.Frame(
                senderSession = session.toInt(),
                frameNumber = frame,
                opusData = packet.copyOfRange(offset, offset + opusSize),
                terminator = terminator
            )
        )
    }

    private fun readMumbleVarInt(data: ByteArray, start: Int): Pair<Long, Int> {
        var offset = start
        fun next(): Int {
            if (offset >= data.size) throw IOException("Truncated Mumble varint")
            return data[offset++].toInt() and 0xff
        }

        val first = next()
        val value = when {
            (first and 0x80) == 0 -> (first and 0x7f).toLong()
            (first and 0xc0) == 0x80 -> ((first and 0x3f).toLong() shl 8) or next().toLong()
            (first and 0xe0) == 0xc0 -> ((first and 0x1f).toLong() shl 16) or
                (next().toLong() shl 8) or next().toLong()
            (first and 0xf0) == 0xe0 -> ((first and 0x0f).toLong() shl 24) or
                (next().toLong() shl 16) or (next().toLong() shl 8) or next().toLong()
            first == 0xf0 -> (next().toLong() shl 24) or (next().toLong() shl 16) or
                (next().toLong() shl 8) or next().toLong()
            first == 0xf4 -> {
                var result = 0L
                repeat(8) { result = (result shl 8) or next().toLong() }
                result
            }
            else -> throw IOException("Unsupported signed Mumble varint prefix=0x${first.toString(16)}")
        }
        return value to offset
    }

    fun setSpeakerMuted(muted: Boolean) {
        playback.setMuted(muted)
    }

    fun sendVoiceFrame(opusData: ByteArray, frameNumber: Long, terminator: Boolean): Boolean {
        val output = controlOutput ?: return false
        if (!desiredConnected.get() || ownSession == null) return false
        return try {
            send(output, TYPE_UDP_TUNNEL, buildVoicePacket(opusData, frameNumber, terminator))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Voice tunnel write failed", e)
            false
        }
    }

    private fun buildVoicePacket(opusData: ByteArray, frameNumber: Long, terminator: Boolean): ByteArray {
        val useProtobufUdp = (serverVersionV1 ?: 0) >= PROTOBUF_UDP_VERSION
        return if (useProtobufUdp) {
            val audio = UdpAudio.newBuilder()
                .setTarget(0)
                .setFrameNumber(frameNumber)
                .setOpusData(ByteString.copyFrom(opusData))
                .setIsTerminator(terminator)
                .build()
                .toByteArray()
            ByteArray(audio.size + 1).also { packet ->
                packet[0] = UDP_TYPE_AUDIO.toByte()
                audio.copyInto(packet, destinationOffset = 1)
            }
        } else {
            val out = ByteArrayOutputStream(opusData.size + 16)
            out.write(LEGACY_OPUS_HEADER)
            writeMumbleVarInt(out, frameNumber)
            writeMumbleVarInt(
                out,
                opusData.size.toLong() or if (terminator) LEGACY_TERMINATOR_BIT else 0L
            )
            out.write(opusData)
            out.toByteArray()
        }
    }

    private fun writeMumbleVarInt(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0)
        when {
            value < 0x80L -> out.write(value.toInt())
            value < 0x4000L -> {
                out.write(((value shr 8) or 0x80L).toInt())
                out.write((value and 0xffL).toInt())
            }
            value < 0x200000L -> {
                out.write(((value shr 16) or 0xc0L).toInt())
                out.write(((value shr 8) and 0xffL).toInt())
                out.write((value and 0xffL).toInt())
            }
            value < 0x10000000L -> {
                out.write(((value shr 24) or 0xe0L).toInt())
                out.write(((value shr 16) and 0xffL).toInt())
                out.write(((value shr 8) and 0xffL).toInt())
                out.write((value and 0xffL).toInt())
            }
            value < 0x1_0000_0000L -> {
                out.write(0xf0)
                out.write(((value shr 24) and 0xffL).toInt())
                out.write(((value shr 16) and 0xffL).toInt())
                out.write(((value shr 8) and 0xffL).toInt())
                out.write((value and 0xffL).toInt())
            }
            else -> {
                out.write(0xf4)
                for (shift in 56 downTo 0 step 8) {
                    out.write(((value shr shift) and 0xffL).toInt())
                }
            }
        }
    }

    fun switchChannel(channelId: Int): Boolean {
        val output = controlOutput ?: return false
        val session = ownSession ?: return false
        if (!desiredConnected.get()) return false

        lastRequestedChannelId = channelId
        Thread({
            try {
                send(
                    output,
                    TYPE_USER_STATE,
                    UserState.newBuilder()
                        .setSession(session)
                        .setChannelId(channelId)
                        .build()
                        .toByteArray()
                )
            } catch (e: Exception) {
                lastRequestedChannelId = null
                listener.onError(
                    "Kanalbytte feilet ved sending: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
        }, "OktoDrive-ChannelSwitch").start()
        return true
    }

    fun disconnect() {
        desiredConnected.set(false)
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun snapshotUsers(): List<User> = users.values
        .sortedWith(compareBy<User>({ it.channelId ?: Int.MAX_VALUE }, { it.name.lowercase() }))

    private fun clearConnectionState() {
        controlOutput = null
        ownSession = null
        serverVersionV1 = null
        lastRequestedChannelId = null
        users.clear()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun reconnectDelayMs(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 5_000L
        4 -> 10_000L
        5 -> 20_000L
        else -> 30_000L
    }

    private fun sleepForReconnect(delayMs: Long) {
        var remaining = delayMs
        while (desiredConnected.get() && remaining > 0) {
            val step = minOf(remaining, 250L)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            remaining -= step
        }
    }

    private fun send(out: DataOutputStream, type: Int, payload: ByteArray) {
        synchronized(out) {
            out.writeShort(type)
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    private class FatalConnectionException(message: String) : IOException(message)

    companion object {
        private const val TAG = "OktoDriveMumble"
        private const val READ_TIMEOUT_MS = 1500
        private const val PING_INTERVAL_MS = 15_000L
        private const val MAX_CONTROL_FRAME_BYTES = 2_000_000

        private const val TYPE_VERSION = 0
        private const val TYPE_UDP_TUNNEL = 1
        private const val TYPE_AUTHENTICATE = 2
        private const val TYPE_PING = 3
        private const val TYPE_REJECT = 4
        private const val TYPE_SERVER_SYNC = 5
        private const val TYPE_CHANNEL_REMOVE = 6
        private const val TYPE_CHANNEL_STATE = 7
        private const val TYPE_USER_REMOVE = 8
        private const val TYPE_USER_STATE = 9
        private const val TYPE_PERMISSION_DENIED = 12

        private const val PROTOBUF_UDP_VERSION = 0x010500
        private const val UDP_TYPE_AUDIO = 0
        private const val UDP_TYPE_PING = 1
        private const val LEGACY_OPUS_TYPE = 4
        private const val LEGACY_OPUS_HEADER = LEGACY_OPUS_TYPE shl 5
        private const val LEGACY_TERMINATOR_BIT = 1L shl 13
        private const val LEGACY_SIZE_MASK = (1L shl 13) - 1
    }
}
