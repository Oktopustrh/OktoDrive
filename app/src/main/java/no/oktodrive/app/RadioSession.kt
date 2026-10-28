package no.oktodrive.app

import android.os.Handler
import android.os.Looper

/** Process-scoped connection, kept alive by MumbleForegroundService, not by an Activity. */
internal object RadioSession {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var observer: MumbleClient.Listener? = null
    private var config: List<String>? = null
    var client: MumbleClient? = null
        private set
    private var status = "Kobler til automatisk …"
    private var connected = false
    private var channels = emptyList<MumbleClient.Channel>()
    private var users = emptyList<MumbleClient.User>()
    private var activeChannel: Int? = null
    private var pendingError: String? = null

    fun attach(listener: MumbleClient.Listener) {
        observer = listener
        listener.onStatus(status, connected)
        listener.onChannels(channels)
        listener.onUsers(users)
        activeChannel?.let(listener::onActiveChannel)
        pendingError?.let { listener.onError(it); pendingError = null }
    }

    fun detach(listener: MumbleClient.Listener?) {
        if (observer === listener) observer = null
    }

    fun start(host: String, port: Int, name: String, password: String) {
        val nextConfig = listOf(host, port.toString(), name, password)
        if (client?.isActive() == true && config == nextConfig) return
        generation++
        val token = generation
        client?.disconnect()
        config = nextConfig
        connected = false
        activeChannel = null
        pendingError = null
        val bridge = object : MumbleClient.Listener {
            private fun deliver(block: () -> Unit) {
                main.post { if (token == generation) block() }
            }
            override fun onStatus(message: String, connected: Boolean) = deliver {
                status = message
                this@RadioSession.connected = connected
                if (!connected) activeChannel = null
                observer?.onStatus(message, connected)
            }
            override fun onChannels(channels: List<MumbleClient.Channel>) = deliver {
                this@RadioSession.channels = channels.toList()
                observer?.onChannels(channels)
            }
            override fun onUsers(users: List<MumbleClient.User>) = deliver {
                this@RadioSession.users = users.toList()
                observer?.onUsers(users)
            }
            override fun onActiveChannel(channelId: Int) = deliver {
                activeChannel = channelId
                observer?.onActiveChannel(channelId)
            }
            override fun onVoice(session: Int, finished: Boolean) = deliver {
                observer?.onVoice(session, finished)
            }
            override fun onError(message: String) = deliver {
                if (observer != null) observer?.onError(message) else pendingError = message
            }
        }
        client = MumbleClient(host, port, name, password, bridge)
        try { OktoDriveApp.startConnectionProtection() } catch (_: Exception) { }
        client?.connect()
    }

    fun sessionPassword(): String = config?.getOrNull(3).orEmpty()

    fun restoreIfNeeded() {
        if (client != null) return
        val prefs = OktoDriveApp.context().getSharedPreferences("server_settings", 0)
        val name = prefs.getString("name", "").orEmpty().trim()
        if (RadioStartupPolicy.shouldConnect(prefs.getBoolean("setup_complete", false),
                name, prefs.getBoolean("radio_exited", false))) {
            start(prefs.getString("host", "ts.veiensvenner.no").orEmpty(),
                prefs.getString("port", "64738")?.toIntOrNull() ?: 64738, name, "")
        }
    }

    fun stop(stopProtection: Boolean = true) {
        generation++ // Ignore callbacks from the connection that is being closed.
        client?.disconnect()
        client = null
        config = null
        connected = false
        channels = emptyList()
        users = emptyList()
        activeChannel = null
        pendingError = null
        status = "Radioen er avsluttet"
        observer?.onStatus(status, false)
        observer?.onChannels(channels)
        observer?.onUsers(users)
        if (stopProtection) OktoDriveApp.stopConnectionProtection()
    }
}
