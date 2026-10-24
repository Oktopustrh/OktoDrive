package no.oktodrive.app

import android.util.Log
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

/**
 * Minimal Mumble control-protocol client.
 *
 * The control messages are generated from BSD-licensed upstream Mumble
 * protobuf definitions. Audio, UDP and PTT are still intentionally not
 * implemented here.
 */
internal class MumbleClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val fingerprint: String,
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
    @Volatile private var lastRequestedChannelId: Int? = null
    private val channels = sortedMapOf<Int, Channel>()
    private val users = mutableMapOf<Int, User>()

    fun connect() {
        desiredConnected.set(true)
        if (!workerRunning.compareAndSet(false, true)) return

        Thread({
            var reconnectAttempt = 0
            var everConnected = false
            try {
                OktoDriveApp.startConnectionProtection()
                Log.i(TAG, "Foreground connection protection started")
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

                        Log.i(TAG, "Connecting to $host:$port as '$username' reconnecting=$reconnecting")
                        listener.onStatus(
                            if (reconnecting) "Kobler til Mumble igjen …" else "Kobler til Mumble …",
                            false
                        )

                        val tls = ServerCertificate.openPinned(host, port, fingerprint)
                        socket = tls
                        tls.soTimeout = READ_TIMEOUT_MS
                        val input = DataInputStream(tls.inputStream)
                        val output = DataOutputStream(tls.outputStream)
                        controlOutput = output

                        send(
                            output,
                            TYPE_VERSION,
                            MumbleVersion.newBuilder()
                                .setVersionV1(0x010500)
                                .setRelease("OktoDrive 0.1")
                                .setOs("Android")
                                .build()
                                .toByteArray()
                        )

                        val auth = Authenticate.newBuilder()
                            .setUsername(username)
                            .setOpus(true)
                            .apply {
                                if (password.isNotEmpty()) setPassword(password)
                            }
                            .build()
                        send(output, TYPE_AUTHENTICATE, auth.toByteArray())

                        var lastPing = System.currentTimeMillis()
                        while (desiredConnected.get()) {
                            if (System.currentTimeMillis() - lastPing > PING_INTERVAL_MS) {
                                val ping = Ping.newBuilder()
                                    .setTimestamp(System.currentTimeMillis())
                                    .build()
                                send(output, TYPE_PING, ping.toByteArray())
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

                                when (type) {
                                    TYPE_REJECT -> {
                                        val reject = Reject.parseFrom(payload)
                                        val rejectType = if (reject.hasType()) reject.type.name else "Unknown"
                                        val reason = if (reject.hasReason() && reject.reason.isNotBlank()) {
                                            reject.reason
                                        } else {
                                            "Ingen nærmere årsak oppgitt"
                                        }
                                        val message = "Innlogging avvist [$rejectType]: $reason"
                                        Log.w(TAG, message)
                                        throw FatalConnectionException(message)
                                    }

                                    TYPE_SERVER_SYNC -> {
                                        val sync = ServerSync.parseFrom(payload)
                                        ownSession = if (sync.hasSession()) sync.session else null
                                        connectedThisAttempt = true
                                        everConnected = true
                                        reconnectAttempt = 0
                                        Log.i(TAG, "ServerSync received; ownSession=$ownSession")
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
                                            val name = if (state.hasName()) {
                                                state.name
                                            } else {
                                                old?.name ?: "Kanal $id"
                                            }
                                            val parent = if (state.hasParent()) state.parent else old?.parent
                                            channels[id] = Channel(id, name, parent)
                                            Log.d(
                                                TAG,
                                                "ChannelState id=$id name='$name' parent=$parent canEnter=" +
                                                    (if (state.hasCanEnter()) state.canEnter else "unknown")
                                            )
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
                                            val updated = User(
                                                session = session,
                                                name = if (state.hasName()) {
                                                    state.name
                                                } else {
                                                    previous?.name ?: "Bruker $session"
                                                },
                                                channelId = if (state.hasChannelId()) {
                                                    state.channelId
                                                } else {
                                                    previous?.channelId
                                                },
                                                selfMute = if (state.hasSelfMute()) {
                                                    state.selfMute
                                                } else {
                                                    previous?.selfMute ?: false
                                                },
                                                selfDeaf = if (state.hasSelfDeaf()) {
                                                    state.selfDeaf
                                                } else {
                                                    previous?.selfDeaf ?: false
                                                },
                                                mutedByServer = if (state.hasMute()) {
                                                    state.mute
                                                } else {
                                                    previous?.mutedByServer ?: false
                                                },
                                                suppressed = if (state.hasSuppress()) {
                                                    state.suppress
                                                } else {
                                                    previous?.suppressed ?: false
                                                }
                                            )
                                            users[session] = updated
                                            listener.onUsers(snapshotUsers())

                                            if (session == ownSession && state.hasChannelId()) {
                                                val channelId = state.channelId
                                                Log.i(
                                                    TAG,
                                                    "Own UserState confirmed channel=$channelId " +
                                                        "requested=$lastRequestedChannelId"
                                                )
                                                if (channelId == lastRequestedChannelId) {
                                                    lastRequestedChannelId = null
                                                }
                                                listener.onActiveChannel(channelId)
                                            }
                                        }
                                    }

                                    TYPE_PERMISSION_DENIED -> {
                                        val denied = PermissionDenied.parseFrom(payload)
                                        val permission = if (denied.hasPermission()) denied.permission else null
                                        val channelId = if (denied.hasChannelId()) denied.channelId else null
                                        val deniedSession = if (denied.hasSession()) denied.session else null
                                        val reason = if (denied.hasReason() && denied.reason.isNotBlank()) {
                                            denied.reason
                                        } else {
                                            null
                                        }
                                        val denyType = if (denied.hasType()) denied.type.number else null
                                        val invalidName = if (denied.hasName() && denied.name.isNotBlank()) {
                                            denied.name
                                        } else {
                                            null
                                        }
                                        val channelName = channelId?.let { channels[it]?.name }
                                        val detail = buildString {
                                            append("type=${denyType ?: "?"}")
                                            append(", channel=${channelId ?: "?"}")
                                            append(", session=${deniedSession ?: "?"}")
                                            append(", permission=${permission ?: "?"}")
                                            if (invalidName != null) append(", name='$invalidName'")
                                        }
                                        Log.w(
                                            TAG,
                                            "PermissionDenied $detail reason=${reason ?: "<none>"}"
                                        )
                                        listener.onError(
                                            when {
                                                reason != null && channelName != null ->
                                                    "Kan ikke gå inn i $channelName: $reason\n\nMumble: $detail"
                                                reason != null ->
                                                    "Mumble-serveren avviste handlingen: $reason\n\nMumble: $detail"
                                                channelName != null ->
                                                    "Du har ikke tilgang til kanalen $channelName.\n\nMumble: $detail"
                                                else ->
                                                    "Mumble-serveren avviste kanalbyttet.\n\nMumble: $detail"
                                            }
                                        )
                                    }

                                    else -> {
                                        Log.v(TAG, "Ignoring Mumble control message type=$type bytes=$length")
                                    }
                                }
                            } catch (_: SocketTimeoutException) {
                                // Short read timeout lets us send pings and notice explicit disconnects.
                            }
                        }
                    } catch (e: FatalConnectionException) {
                        Log.e(TAG, "Fatal Mumble connection error: ${e.localizedMessage}", e)
                        desiredConnected.set(false)
                        listener.onError(e.localizedMessage ?: "Mumble-serveren avviste forbindelsen")
                    } catch (e: Exception) {
                        if (desiredConnected.get()) {
                            reconnectAttempt += 1
                            val delayMs = reconnectDelayMs(reconnectAttempt)
                            Log.w(
                                TAG,
                                "Mumble connection lost: ${e.javaClass.simpleName}: ${e.localizedMessage}; " +
                                    "retry in ${delayMs}ms",
                                e
                            )
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
                            Log.i(TAG, "Connection attempt ended; reconnect remains enabled")
                        }
                    }
                }
            } finally {
                desiredConnected.set(false)
                workerRunning.set(false)
                clearConnectionState()
                listener.onUsers(emptyList())
                try {
                    OktoDriveApp.stopConnectionProtection()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not stop foreground protection", e)
                }
                Log.i(TAG, "Mumble connection worker stopped")
                listener.onStatus(if (everConnected) "Frakoblet" else "Ikke tilkoblet", false)
            }
        }, "OktoDrive-Mumble").start()
    }

    fun switchChannel(channelId: Int): Boolean {
        val output = controlOutput ?: return false
        val session = ownSession ?: return false
        if (!desiredConnected.get()) return false

        lastRequestedChannelId = channelId
        Log.i(TAG, "Queueing UserState channel switch: session=$session channel=$channelId")
        Thread({
            try {
                val state = UserState.newBuilder()
                    .setSession(session)
                    .setChannelId(channelId)
                    .build()
                Log.i(TAG, "Sending UserState channel switch: session=$session channel=$channelId")
                send(output, TYPE_USER_STATE, state.toByteArray())
            } catch (e: Exception) {
                lastRequestedChannelId = null
                Log.e(TAG, "Channel switch write failed", e)
                listener.onError(
                    "Kanalbytte feilet ved sending: ${e.javaClass.simpleName}: " +
                        (e.localizedMessage ?: "ukjent feil")
                )
            }
        }, "OktoDrive-ChannelSwitch").start()
        return true
    }

    fun disconnect() {
        Log.i(TAG, "Disconnect requested; automatic reconnect disabled")
        desiredConnected.set(false)
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun snapshotUsers(): List<User> = users.values
        .sortedWith(compareBy<User>({ it.channelId ?: Int.MAX_VALUE }, { it.name.lowercase() }))

    private fun clearConnectionState() {
        controlOutput = null
        ownSession = null
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
        private const val TYPE_AUTHENTICATE = 2
        private const val TYPE_PING = 3
        private const val TYPE_REJECT = 4
        private const val TYPE_SERVER_SYNC = 5
        private const val TYPE_CHANNEL_REMOVE = 6
        private const val TYPE_CHANNEL_STATE = 7
        private const val TYPE_USER_REMOVE = 8
        private const val TYPE_USER_STATE = 9
        private const val TYPE_PERMISSION_DENIED = 12
    }
}
