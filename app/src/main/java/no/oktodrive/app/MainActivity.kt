package no.oktodrive.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Early OktoDrive radio UI.
 *
 * Mumble login, channel discovery and confirmed channel switching are implemented.
 * Opus send/receive and hold-to-talk use the encrypted Mumble TCP tunnel.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("server_settings", MODE_PRIVATE) }

    private var mumble: MumbleClient? = null
    private lateinit var channelList: LinearLayout
    private lateinit var activeChannelText: TextView
    private lateinit var muteButton: Button
    private lateinit var pttButton: Button
    private lateinit var connectButton: Button
    private lateinit var connectionStatus: TextView
    private var mumbleConnected = false
    private var channels: List<MumbleClient.Channel> = emptyList()
    private var users: List<MumbleClient.User> = emptyList()
    private var activeChannelId: Int? = null
    private var requestedChannelId: Int? = null
    private var radioUiState = RadioUiState()
    private var pttAudioEngine: PttAudioEngine? = null
    private var pttTransmitting = false

    private val navy = Color.rgb(207, 219, 235)
    private val surface = Color.rgb(10, 18, 30)
    private val profileControls = mutableListOf<View>()
    private val blue = Color.rgb(36, 76, 118)
    private val paleBlue = Color.rgb(8, 16, 27)
    private val border = Color.rgb(39, 55, 75)
    private val muted = Color.rgb(145, 162, 183)
    private val disabledText = Color.rgb(113, 129, 149)

    private val releaseApi: String
        get() = if (packageName.endsWith(".dev"))
            "https://api.github.com/repos/Oktopustrh/OktoDrive/releases/tags/dev-latest"
        else "https://api.github.com/repos/Oktopustrh/OktoDrive/releases/latest"
    private val hardwarePtt = HardwareButtonState()
    private val hardwareMute = HardwareButtonState()
    private val channelKeys = mapOf("up" to HardwareButtonState(), "down" to HardwareButtonState())
    private val buttonActions = listOf("ptt", "mute", "up", "down")
    private val buttonLabels = listOf("PTT – snakk", "Høyttaler av/på", "Kanal opp", "Kanal ned")
    private var settingUp = false
    private var learning = false
    private var serverPassword = ""
    private lateinit var speakerText: TextView
    private val displayState = RadioDisplayState()
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshSpeakers = object : Runnable {
        override fun run() { updateSpeaker(); uiHandler.postDelayed(this, 250) }
    }

    override fun onPause() {
        stopPtt()
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(refreshSpeakers)
        pttAudioEngine?.stop()
        mumble?.disconnect()
        super.onDestroy()
    }

    private fun testServer(host: String, port: Int, status: TextView, button: Button) {
        status.text = "●  Tester server …"
        status.setTextColor(muted)
        button.isEnabled = false
        Thread {
            try {
                ServerCertificate.open(host, port, ClientIdentity.keyManagers()).use { }
                runOnUiThread {
                    finishServerTest(status, button)
                    AlertDialog.Builder(this)
                        .setTitle("Sikker TLS-forbindelse bekreftet")
                        .setMessage(
                            if (ServerCertificate.usesTemporaryPin(host, port))
                                "Serverens sertifikat samsvarer med den faste SHA-256-pinnen."
                            else "Serverens CA-kjede og vertsnavn er validert."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    finishServerTest(status, button)
                    AlertDialog.Builder(this)
                        .setTitle("Servertest")
                        .setMessage("Kunne ikke opprette sikker TLS-forbindelse: ${e.localizedMessage ?: e.javaClass.simpleName}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun finishServerTest(status: TextView, button: Button) {
        status.text = "●  Ikke tilkoblet"
        status.setTextColor(Color.rgb(190, 55, 45))
        button.isEnabled = true
    }

    private fun readJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OktoDrive-Android")
        }
        try {
            if (connection.responseCode == 404) throw java.io.FileNotFoundException()
            if (connection.responseCode != 200)
                throw IllegalStateException("Tjenesten svarte " + connection.responseCode)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    @Suppress("DEPRECATION")
    private fun installedSigners(): Set<String> {
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
        return signatures.map { signature ->
            java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.toSet()
    }

    private fun checkForUpdates(manual: Boolean) {
        Thread {
            try {
                val release = readJson(releaseApi)
                val assetName = if (packageName.endsWith(".dev")) "update-dev.json" else "update-stable.json"
                val assets = release.getJSONArray("assets")
                var manifestUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name") == assetName) manifestUrl = asset.getString("browser_download_url")
                }
                val address = manifestUrl ?: throw java.io.FileNotFoundException()
                val uri = java.net.URI(address)
                require(uri.scheme == "https" && uri.host == "github.com" &&
                    uri.rawPath.startsWith("/Oktopustrh/OktoDrive/releases/download/")) {
                    "Ugyldig oppdateringskilde"
                }
                val manifest = readJson(address)
                val update = AppUpdate(
                    manifest.getString("applicationId"), manifest.getLong("versionCode"),
                    manifest.getString("versionName"), manifest.getInt("minSdk"),
                    manifest.getString("certificateSha256"), manifest.getString("downloadUrl")
                )
                val installed = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION")
                val current = if (android.os.Build.VERSION.SDK_INT >= 28) installed.longVersionCode
                    else installed.versionCode.toLong()
                val reason = UpdatePolicy.rejection(update, packageName, current,
                    android.os.Build.VERSION.SDK_INT, installedSigners())
                runOnUiThread {
                    if (reason == null) {
                        AlertDialog.Builder(this)
                            .setTitle("Ny OktoDrive-versjon: " + update.versionName)
                            .setMessage("Last ned og åpne APK-filen. Android ber deg bekrefte installasjonen. Innstillingene beholdes ved oppdatering.")
                            .setNegativeButton("Senere", null)
                            .setPositiveButton("Last ned") { _, _ ->
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                                } catch (_: android.content.ActivityNotFoundException) {
                                    Toast.makeText(this, "Ingen nettleser er tilgjengelig for nedlasting.", Toast.LENGTH_LONG).show()
                                }
                            }.show()
                    } else if (manual) {
                        AlertDialog.Builder(this).setTitle("Oppdatering")
                            .setMessage(reason).setPositiveButton("OK", null).show()
                    }
                }
            } catch (_: java.io.FileNotFoundException) {
                if (manual) runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Oppdatering")
                        .setMessage("Ingen oppdatering er publisert for denne appvarianten ennå.")
                        .setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Oppdatering")
                        .setMessage("Kunne ikke sjekke oppdateringer: " + e.localizedMessage)
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun binding(action: String): HardwareButton? {
        if (!prefs.contains("button_" + action + "_code")) return null
        return HardwareButton(prefs.getInt("button_" + action + "_code", -1),
            prefs.getInt("button_" + action + "_scan", -1))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (settingUp || learning) return super.dispatchKeyEvent(event)
        for ((action, state) in channelKeys) {
            if (binding(action)?.matches(event.keyCode, event.scanCode) == true) {
                if (event.action == KeyEvent.ACTION_DOWN) state.press(event.repeatCount)
                if (event.action == KeyEvent.ACTION_UP && state.release() && !event.isCanceled)
                    changeChannel(if (action == "up") 1 else -1)
                return true
            }
        }
        val ptt = binding("ptt")
        val mute = binding("mute")
        if (ptt?.matches(event.keyCode, event.scanCode) == true) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (hardwarePtt.press(event.repeatCount)) startPtt()
                KeyEvent.ACTION_UP -> { hardwarePtt.release(); stopPtt() }
            }
            return true
        }
        if (mute?.matches(event.keyCode, event.scanCode) == true) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> hardwareMute.press(event.repeatCount)
                KeyEvent.ACTION_UP -> if (hardwareMute.release() && !event.isCanceled) toggleSpeakerMute()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) stopPtt()
    }

    private fun applyTm7PlusButtons() {
        stopPtt()
        val editor = prefs.edit()
        for ((action, key) in Tm7PlusButtons.bindings) {
            editor.putInt("button_${action}_code", key.keyCode)
                .putInt("button_${action}_scan", key.scanCode)
        }
        if (binding("mute") in Tm7PlusButtons.bindings.values) {
            editor.remove("button_mute_code").remove("button_mute_scan")
        }
        editor.putBoolean("tm7_measured_buttons_v1", true).apply()
    }

    private fun configureButtons() {
        stopPtt()
        AlertDialog.Builder(this).setTitle("Radioknapper")
            .setItems(buttonLabels.toTypedArray()) { _, index -> learnButton(buttonActions[index]) }
            .setNeutralButton("Inrico TM-7 Plus") { _, _ ->
                applyTm7PlusButtons()
                Toast.makeText(this, "TM-7 Plus: PTT 230, pil opp 19, pil ned 20. Klart til bruk.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Lukk", null).show()
    }

    private fun learnButton(action: String, done: (() -> Unit)? = null) {
        stopPtt()
        learning = true
        val label = buttonLabels[buttonActions.indexOf(action)]
        val dialog = AlertDialog.Builder(this).setTitle(label)
            .setMessage("Trykk og slipp knappen du vil bruke til $label.\n\nIngen tale sendes under oppsettet.")
            .setNegativeButton("Bruk skjermen") { _, _ ->
                prefs.edit().remove("button_${action}_code").remove("button_${action}_scan").apply()
                done?.invoke()
            }
            .setCancelable(done == null).create()
        var candidate: HardwareButton? = null
        dialog.setOnDismissListener { learning = false }
        dialog.setOnKeyListener { _, code, event ->
            if (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER))
                return@setOnKeyListener false
            if (code == KeyEvent.KEYCODE_UNKNOWN && event.scanCode == 0) return@setOnKeyListener false
            val key = HardwareButton(code, event.scanCode)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                candidate = key
                dialog.setMessage("Knappen er registrert. Slipp for å lagre.")
            } else if (event.action == KeyEvent.ACTION_UP && candidate == key) {
                candidate = null
                if (event.isCanceled) return@setOnKeyListener true
                if (buttonActions.any { it != action && binding(it) == key }) {
                    dialog.setMessage("Denne knappen er allerede i bruk. Velg en annen knapp, eller bruk skjermen.")
                } else {
                    prefs.edit().putInt("button_${action}_code", code)
                        .putInt("button_${action}_scan", event.scanCode).apply()
                    dialog.dismiss()
                    done?.invoke()
                }
            }
            true
        }
        dialog.show()
    }

    private fun changeChannel(direction: Int) {
        if (!mumbleConnected || pttTransmitting) return
        // Keep browsing after a permission denial, without pretending that the server moved us.
        val ids = channels.map { it.id }
        val from = requestedChannelId?.takeIf { it in ids } ?: activeChannelId
        val target = RadioDisplayState.adjacent(ids, from, direction)
        if (target != null && mumble?.switchChannel(target) == true) requestedChannelId = target
    }

    private fun updateSpeaker() {
        if (!::speakerText.isInitialized) return
        val names = displayState.active(android.os.SystemClock.elapsedRealtime()).map { session ->
            users.firstOrNull { it.session == session }?.name ?: "En deltaker"
        }
        val caption = when {
            pttTransmitting -> "Du snakker"
            !mumbleConnected -> "Koble til for å snakke"
            names.isNotEmpty() -> "Snakker nå\n" + names.joinToString(", ")
            else -> "Lytter …"
        }
        if (speakerText.text.toString() != caption) speakerText.text = caption
    }

    private fun startMumble(host: String, port: Int, name: String, password: String) {
        connectButton.isEnabled = false
        mumble = MumbleClient(host, port, name, password, object : MumbleClient.Listener {
            override fun onStatus(message: String, connected: Boolean) {
                runOnUiThread {
                    mumbleConnected = connected
                    connectionStatus.text = "●  $message"
                    connectionStatus.setTextColor(
                        if (connected) Color.rgb(25, 130, 65) else Color.rgb(190, 55, 45)
                    )
                    connectButton.text = if (connected) "KOBLE FRA" else "KOBLE TIL MUMBLE"
                    connectButton.isEnabled = connected || message != "Kobler til Mumble …"
                    if (!connected && message != "Kobler til Mumble …") {
                        stopPtt()
                        displayState.clear()
                        activeChannelId = null
                        requestedChannelId = null
                        updateActiveChannelLabel()
                    }
                    renderChannels()
                    updatePttButton()
                }
            }

            override fun onChannels(channels: List<MumbleClient.Channel>) {
                runOnUiThread {
                    this@MainActivity.channels = channels
                    updateActiveChannelLabel()
                    renderChannels()
                }
            }

            override fun onActiveChannel(channelId: Int) {
                runOnUiThread {
                    displayState.clear()
                    activeChannelId = channelId
                    requestedChannelId = channelId
                    updateActiveChannelLabel()
                    renderChannels()
                }
            }

            override fun onUsers(users: List<MumbleClient.User>) {
                runOnUiThread {
                    this@MainActivity.users = users
                    renderChannels()
                }
            }

            override fun onVoice(session: Int, finished: Boolean) {
                runOnUiThread {
                    if (mumbleConnected) displayState.voice(session, finished, android.os.SystemClock.elapsedRealtime())
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Mumble-tilkobling")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        })
        mumble?.setSpeakerMuted(radioUiState.speakerMuted)
        mumble?.connect()
    }

    private fun updateActiveChannelLabel() {
        if (!::activeChannelText.isInitialized) return
        val channel = channels.firstOrNull { it.id == activeChannelId }
        activeChannelText.text = channel?.name ?: "Ingen kanal"
    }

    private fun renderChannels() {
        if (!::channelList.isInitialized) return
        channelList.removeAllViews()
        if (channels.isEmpty()) {
            channelList.addView(text("Ingen kanaler mottatt ennå.", 15f, muted))
            return
        }

        channels.forEachIndexed { index, channel ->
            val isActive = channel.id == activeChannelId
            val button = Button(this).apply {
                text = if (isActive) "✓  ${channel.name}" else channel.name
                textSize = 16f
                minHeight = dp(52)
                isAllCaps = false
                isEnabled = mumbleConnected
                setTextColor(if (mumbleConnected) navy else disabledText)
                background = rounded(
                    if (isActive) paleBlue else surface,
                    10,
                    if (isActive) blue else border,
                    if (isActive) 2 else 1
                )
                setOnClickListener {
                    if (!mumbleConnected) return@setOnClickListener
                    if (channel.id == activeChannelId) return@setOnClickListener
                    val accepted = mumble?.switchChannel(channel.id) == true
                    if (!accepted) {
                        Toast.makeText(
                            this@MainActivity,
                            "Kan ikke sende kanalbytte uten aktiv Mumble-forbindelse.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            channelList.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            if (isActive) {
                val activeUsers = users.filter { it.channelId == channel.id }
                val usersBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(8), dp(12), dp(10))
                    background = rounded(Color.rgb(248, 252, 255), 10, border)
                    addView(text("Brukere i ${channel.name}", 13f, muted, true))
                    addView(spacer(4))
                    if (activeUsers.isEmpty()) {
                        addView(text("Ingen andre brukere i kanalen.", 14f, muted))
                    } else {
                        activeUsers.forEachIndexed { userIndex, user ->
                            val state = when {
                                user.selfDeaf -> "  · døvet"
                                user.selfMute || user.mutedByServer || user.suppressed -> "  · dempet"
                                else -> ""
                            }
                            addView(text("•  ${user.name}$state", 15f, navy))
                            if (userIndex != activeUsers.lastIndex) addView(spacer(3))
                        }
                    }
                }
                channelList.addView(usersBox)
            }

            if (index != channels.lastIndex) channelList.addView(spacer(6))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        fill: Int,
        radius: Int = 18,
        strokeColor: Int? = null,
        strokeWidth: Int = 1
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (strokeColor != null) setStroke(dp(strokeWidth), strokeColor)
    }

    private fun text(
        value: String,
        size: Float = 18f,
        color: Int = navy,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun spacer(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun field(
        label: String,
        value: String = "",
        hint: String = "",
        focused: Boolean = false
    ): Pair<LinearLayout, EditText> {
        val input = EditText(this).apply {
            setText(value)
            this.hint = hint
            textSize = 18f
            setTextColor(navy)
            setHintTextColor(Color.rgb(155, 165, 178))
            setSingleLine(true)
            background = null
            setPadding(0, 0, 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(surface, 12, if (focused) blue else border, if (focused) 2 else 1)
            addView(text(label, 14f, if (focused) blue else muted))
            addView(input)
        }
        return layout to input
    }

    private fun setUiMode(mode: UiMode) {
        radioUiState = radioUiState.copy(mode = mode)
        prefs.edit().putString("ui_mode", mode.name).apply()
        Toast.makeText(
            this,
            "Grensesnitt satt til ${when (mode) { UiMode.AUTO -> "Automatisk"; UiMode.MOBILE -> "Mobil"; UiMode.RADIO -> "Radio" }}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleSpeakerMute() {
        radioUiState = radioUiState.copy(speakerMuted = !radioUiState.speakerMuted)
        mumble?.setSpeakerMuted(radioUiState.speakerMuted)
        updateMuteButton()
    }

    private fun updateMuteButton() {
        if (!::muteButton.isInitialized) return
        muteButton.text = if (radioUiState.speakerMuted) "🔇  LYD AV – TRYKK FOR LYD" else "🔊  MUTE HØYTTALER"
        muteButton.setTextColor(if (radioUiState.speakerMuted) Color.rgb(190, 55, 45) else navy)
    }

    private fun startPtt() {
        if (!mumbleConnected) {
            Toast.makeText(this, "Koble til Mumble først.", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        if (pttAudioEngine == null) {
            pttAudioEngine = PttAudioEngine(
                sendFrame = { opus, frame, terminator -> mumble?.sendVoiceFrame(opus, frame, terminator) == true },
                onState = { state ->
                    runOnUiThread {
                        pttTransmitting = pttAudioEngine?.isRunning() == true
                        updatePttButton()
                        if (state.startsWith("PTT-feil") || state.startsWith("Mikrofon")) {
                            Toast.makeText(this, state, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
        pttTransmitting = pttAudioEngine?.start() == true
        updatePttButton()
    }

    private fun stopPtt() {
        hardwarePtt.release()
        hardwareMute.release()
        channelKeys.values.forEach { it.release() }
        pttAudioEngine?.stop()
        pttTransmitting = false
        updatePttButton()
    }

    private fun updatePttButton() {
        if (!::pttButton.isInitialized) return
        pttButton.isEnabled = mumbleConnected
        when {
            pttTransmitting -> {
                pttButton.text = "🔴  SENDER – SLIPP FOR Å STOPPE"
                pttButton.setTextColor(Color.WHITE)
                pttButton.background = rounded(Color.rgb(190, 55, 45), 12)
            }
            mumbleConnected -> {
                pttButton.text = "🎙  HOLD INNE FOR Å SNAKKE"
                pttButton.setTextColor(Color.WHITE)
                pttButton.background = rounded(blue, 12)
            }
            else -> {
                pttButton.text = "🎙  PTT – KOBLE TIL FØRST"
                pttButton.setTextColor(disabledText)
                pttButton.background = rounded(Color.rgb(232, 239, 247), 12)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Mikrofon klar. Hold PTT inne for å snakke.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Mikrofontilgang er nødvendig for PTT.", Toast.LENGTH_LONG).show()
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = rounded(surface, 18, border)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = paleBlue
        window.navigationBarColor = paleBlue
        window.decorView.systemUiVisibility = 0
        showRadio()
        // Install the measured bindings once, including upgrades from an incorrect learned code.
        // Later manual remapping is preserved; the preset can be restored in Radioknapper.
        if (Tm7PlusButtons.isDevice(android.os.Build.MODEL) &&
            !prefs.getBoolean("tm7_measured_buttons_v1", false)) {
            applyTm7PlusButtons()
        }
        uiHandler.post(refreshSpeakers)
        if (!prefs.getBoolean("setup_complete", false)) showSetup()
        else if (!prefs.contains("device_profile")) chooseDeviceProfile()
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 17f
        isAllCaps = false
        minHeight = dp(48)
        setTextColor(navy)
        background = rounded(surface, 12, border)
        setOnClickListener { action() }
    }

    private fun showRadio() {
        profileControls.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(paleBlue)
            // Android 15+ draws behind system bars; keep the radio controls reachable.
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(16) + insets.systemWindowInsetLeft,
                    dp(8) + insets.systemWindowInsetTop,
                    dp(16) + insets.systemWindowInsetRight,
                    dp(12) + insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("OktoDrive", 22f, navy, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton("⚙") { showSettings() }.apply {
            contentDescription = "Innstillinger"
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        root.addView(header)
        connectionStatus = text("Ikke tilkoblet", 14f, muted)
        root.addView(connectionStatus)
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val display: LinearLayout
        val controls: LinearLayout
        if (landscape) {
            val body = LinearLayout(this)
            display = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), dp(12), 0) }
            controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(display, LinearLayout.LayoutParams(0, -2, 1.1f))
            body.addView(controls, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(body)
        } else { display = root; controls = root }
        activeChannelText = text("Ingen kanal", 32f, navy, true).apply { gravity = Gravity.CENTER }
        display.addView(activeChannelText)
        speakerText = text("Koble til for å snakke", 22f, navy).apply {
            gravity = Gravity.CENTER; minHeight = dp(64)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        display.addView(speakerText)
        val navigation = LinearLayout(this)
        navigation.addView(actionButton("− Kanal") { changeChannel(-1) }, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(actionButton("Kanal +") { changeChannel(1) }, LinearLayout.LayoutParams(0, -2, 1f))
        display.addView(navigation)
        profileControls.add(navigation)
        pttButton = Button(this).apply {
            textSize = 18f; minHeight = dp(72); isAllCaps = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> startPtt()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopPtt(); performClick() }
                }
                true
            }
        }
        controls.addView(pttButton)
        muteButton = actionButton("Høyttaler") { toggleSpeakerMute() }
        controls.addView(muteButton)
        profileControls.add(pttButton)
        profileControls.add(muteButton)
        applyDeviceProfile()
        connectButton = actionButton("Koble til") {
            if (mumbleConnected) { stopPtt(); mumble?.disconnect() } else connectSaved()
        }
        controls.addView(connectButton)
        updatePttButton()
        updateMuteButton()
    }

    private fun applyDeviceProfile() {
        val visibility = if (prefs.getString("device_profile", "mobile") == "radio") View.GONE else View.VISIBLE
        profileControls.forEach { it.visibility = visibility }
    }

    private fun chooseDeviceProfile(onChosen: (() -> Unit)? = null) {
        stopPtt()
        AlertDialog.Builder(this)
            .setTitle("Velg enhetsprofil")
            .setItems(arrayOf("Radio – fysiske knapper", "Mobil – skjermknapper")) { _, index ->
                prefs.edit().putString("device_profile", if (index == 0) "radio" else "mobile").apply()
                applyDeviceProfile()
                onChosen?.invoke()
            }
            .setCancelable(prefs.contains("device_profile") && onChosen == null)
            .show()
    }

    private fun connectSaved() {
        if (settingUp) return
        val name = prefs.getString("name", "").orEmpty().trim()
        if (name.isEmpty()) { showSetup(); return }
        mumble?.disconnect()
        startMumble(prefs.getString("host", "ts.veiensvenner.no") ?: "ts.veiensvenner.no",
            prefs.getString("port", "64738")?.toIntOrNull() ?: 64738, name, serverPassword)
    }

    private fun showSetup() {
        stopPtt()
        settingUp = true
        val (box, input) = field("Navnet ditt", prefs.getString("name", "").orEmpty(), "Slik vises du på radioen")
        val dialog = AlertDialog.Builder(this).setTitle("Velkommen til OktoDrive")
            .setView(box).setCancelable(false).setPositiveButton("Neste", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) { input.error = "Skriv inn navnet ditt"; return@setOnClickListener }
                prefs.edit().putString("name", name).apply()
                dialog.dismiss()
                chooseDeviceProfile {
                    if (prefs.getString("device_profile", "mobile") == "radio" &&
                        !prefs.getBoolean("tm7_measured_buttons_v1", false)) setupButton(0)
                    else setupButton(buttonActions.size)
                }
            }
        }
        dialog.show()
    }

    private fun setupButton(index: Int) {
        if (index < buttonActions.size) {
            learnButton(buttonActions[index]) { setupButton(index + 1) }
        } else {
            AlertDialog.Builder(this).setTitle("Radioen er klar")
                .setMessage("Trykk Koble til for å begynne. Gi tilgang til mikrofonen når du blir spurt. Du kan endre navn og knapper under tannhjulet.")
                .setCancelable(false)
                .setNegativeButton("Senere") { _, _ -> finishSetup(false) }
                .setPositiveButton("Koble til") { _, _ -> finishSetup(true) }.show()
        }
    }

    private fun finishSetup(connect: Boolean) {
        prefs.edit().putBoolean("setup_complete", true).apply()
        settingUp = false
        if (connect) {
            connectSaved()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    private fun showSettings() {
        stopPtt()
        AlertDialog.Builder(this).setTitle("OktoDrive")
            .setItems(arrayOf("Velg kanal", "Oppsett", "Om OktoDrive")) { _, index ->
                when (index) {
                    0 -> {
                        val available = channels.toList()
                        AlertDialog.Builder(this).setTitle("Velg kanal")
                            .setItems(available.map { it.name }.toTypedArray()) { _, i ->
                                if (mumbleConnected) mumble?.switchChannel(available[i].id)
                            }.setNegativeButton("Tilbake") { _, _ -> showSettings() }.show()
                    }
                    1 -> showSetupSettings()
                    2 -> AlertDialog.Builder(this).setTitle("Om OktoDrive")
                        .setMessage(packageManager.getPackageInfo(packageName, 0).versionName)
                        .setPositiveButton("Tilbake") { _, _ -> showSettings() }.show()
                }
            }.setNegativeButton("Lukk", null).show()
    }

    private fun showSetupSettings() {
        AlertDialog.Builder(this).setTitle("Oppsett")
            .setItems(arrayOf("Navnet ditt", "Radioknapper", "Enhetsprofil", "Avanserte serverinnstillinger", "Se etter oppdatering")) { _, index ->
                when (index) {
                    0 -> {
                        val (box, input) = field("Navnet ditt", prefs.getString("name", "").orEmpty())
                        val dialog = AlertDialog.Builder(this).setTitle("Navnet ditt").setView(box)
                            .setNegativeButton("Avbryt", null).setPositiveButton("Lagre", null).create()
                        dialog.setOnShowListener {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val name = input.text.toString().trim()
                                if (name.isBlank()) input.error = "Skriv inn navnet ditt"
                                else { prefs.edit().putString("name", name).apply(); dialog.dismiss()
                                    Toast.makeText(this, "Navnet brukes neste gang du kobler til.", Toast.LENGTH_LONG).show() }
                            }
                        }
                        dialog.show()
                    }
                    1 -> configureButtons()
                    2 -> chooseDeviceProfile()
                    3 -> serverSettings()
                    4 -> checkForUpdates(true)
                }
            }.setNegativeButton("Tilbake") { _, _ -> showSettings() }.show()
    }

    private fun serverSettings() {
        val box = card()
        val (hostBox, host) = field("Server", prefs.getString("host", "ts.veiensvenner.no").orEmpty())
        val (portBox, port) = field("Port", prefs.getString("port", "64738").orEmpty())
        val (passBox, password) = field("Passord (lagres ikke)", serverPassword)
        port.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        password.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        box.addView(hostBox); box.addView(portBox); box.addView(passBox)
        val dialog = AlertDialog.Builder(this).setTitle("Serverinnstillinger")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Avbryt", null).setPositiveButton("Lagre", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = port.text.toString().toIntOrNull()
                if (host.text.isBlank()) host.error = "Skriv inn servernavn"
                else if (number == null || number !in 1..65535) port.error = "Velg port mellom 1 og 65535"
                else {
                    prefs.edit().putString("host", host.text.toString().trim()).putString("port", number.toString()).apply()
                    serverPassword = password.text.toString()
                    dialog.dismiss()
                    Toast.makeText(this, "Brukes neste gang du kobler til.", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 4101
    }
}
