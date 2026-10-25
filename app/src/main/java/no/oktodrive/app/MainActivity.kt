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
    private var radioUiState = RadioUiState()
    private var pttAudioEngine: PttAudioEngine? = null
    private var pttTransmitting = false

    private val navy = Color.rgb(10, 49, 105)
    private val blue = Color.rgb(25, 118, 210)
    private val paleBlue = Color.rgb(235, 246, 255)
    private val border = Color.rgb(199, 214, 232)
    private val muted = Color.rgb(88, 112, 145)
    private val disabledText = Color.rgb(150, 164, 184)

    private val releaseApi: String
        get() = if (packageName.endsWith(".dev"))
            "https://api.github.com/repos/Oktopustrh/OktoDrive/releases/tags/dev-latest"
        else "https://api.github.com/repos/Oktopustrh/OktoDrive/releases/latest"
    private val hardwarePtt = HardwareButtonState()
    private val hardwareMute = HardwareButtonState()

    override fun onPause() {
        stopPtt()
        super.onPause()
    }

    override fun onDestroy() {
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

    private fun configureButtons() {
        stopPtt()
        AlertDialog.Builder(this)
            .setTitle("TM7 / Android-puck")
            .setItems(arrayOf("Lær PTT-knapp", "Lær høyttaler-mute", "Vis lagrede knapper", "Fjern knappkoblinger")) { _, which ->
                when (which) {
                    0 -> learnButton("ptt")
                    1 -> learnButton("mute")
                    2 -> AlertDialog.Builder(this).setTitle("Lagrede knapper")
                        .setMessage("PTT: " + (binding("ptt")?.toString() ?: "Ikke valgt") +
                            "\nMute: " + (binding("mute")?.toString() ?: "Ikke valgt"))
                        .setPositiveButton("OK", null).show()
                    3 -> {
                        prefs.edit().remove("button_ptt_code").remove("button_ptt_scan")
                            .remove("button_mute_code").remove("button_mute_scan").apply()
                        Toast.makeText(this, "Knappkoblinger fjernet.", Toast.LENGTH_SHORT).show()
                    }
                }
            }.setNegativeButton("Lukk", null).show()
    }

    private fun learnButton(action: String) {
        stopPtt()
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (action == "ptt") "Lær PTT-knapp" else "Lær mute-knapp")
            .setMessage("Trykk og slipp den fysiske knappen. Ingen tale sendes under innlæring.\n\nHvis ingenting registreres, sender enheten ikke vanlige knappesignaler til appen.")
            .setNegativeButton("Avbryt", null).create()
        var candidate: HardwareButton? = null
        dialog.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME ||
                code == KeyEvent.KEYCODE_POWER) return@setOnKeyListener false
            if (code == KeyEvent.KEYCODE_UNKNOWN && event.scanCode == 0) return@setOnKeyListener false
            val key = HardwareButton(code, event.scanCode)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                candidate = key
                dialog.setMessage("Registrert: " + KeyEvent.keyCodeToString(code) +
                    " (scan " + event.scanCode + "). Slipp knappen for å lagre.")
            } else if (event.action == KeyEvent.ACTION_UP && candidate == key) {
                if (!event.isCanceled) {
                    val other = if (action == "ptt") "mute" else "ptt"
                    if (binding(other) == key) {
                        Toast.makeText(this, "Knappen er allerede brukt til den andre funksjonen.", Toast.LENGTH_LONG).show()
                    } else {
                        prefs.edit().putInt("button_" + action + "_code", code)
                            .putInt("button_" + action + "_scan", event.scanCode).apply()
                        Toast.makeText(this, "Knappen er lagret. Test den mens appen er åpen.", Toast.LENGTH_LONG).show()
                    }
                }
                dialog.dismiss()
            }
            true
        }
        dialog.show()
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
                        activeChannelId = null
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
                    activeChannelId = channelId
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
        activeChannelText.text = "👥  Aktiv talegruppe:   ${channel?.name ?: "Ingen"}"
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
                    if (isActive) paleBlue else Color.WHITE,
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
            background = rounded(Color.WHITE, 12, if (focused) blue else border, if (focused) 2 else 1)
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
                pttButton.background = rounded(navy, 12)
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
        background = rounded(Color.WHITE, 18, Color.rgb(226, 235, 245))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(22))
            setBackgroundColor(Color.rgb(247, 251, 255))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        radioUiState = radioUiState.copy(
            mode = runCatching {
                UiMode.valueOf(prefs.getString("ui_mode", UiMode.AUTO.name) ?: UiMode.AUTO.name)
            }.getOrDefault(UiMode.AUTO)
        )
        checkForUpdates(manual = false)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(12), dp(14))
            background = rounded(paleBlue, 18)
        }

        val mascot = ImageView(this).apply {
            setImageResource(R.drawable.oktodrive_driver_mascot)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "OktoDrive-maskot i lastebil med radio"
            setPadding(0, 0, dp(8), 0)
        }
        header.addView(mascot, LinearLayout.LayoutParams(dp(104), dp(72)))

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("OktoDrive", 28f, navy, true))
            addView(
                text("●  Ikke tilkoblet", 17f, Color.rgb(190, 55, 45)).also {
                    connectionStatus = it
                }
            )
        }
        header.addView(
            titleBlock,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val menuButton = TextView(this).apply {
            text = "⋮"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(navy)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener { anchor ->
                PopupMenu(this@MainActivity, anchor).apply {
                    menu.add("Se etter oppdatering")
                    menu.add("Konfigurer radioknapper")
                    menu.add("Grensesnitt: Automatisk")
                    menu.add("Grensesnitt: Mobil")
                    menu.add("Grensesnitt: Radio")
                    menu.add("Om OktoDrive")
                    setOnMenuItemClickListener { item ->
                        when (item.title.toString()) {
                            "Se etter oppdatering" -> checkForUpdates(manual = true)
                            "Konfigurer radioknapper" -> configureButtons()
                            "Grensesnitt: Automatisk" -> setUiMode(UiMode.AUTO)
                            "Grensesnitt: Mobil" -> setUiMode(UiMode.MOBILE)
                            "Grensesnitt: Radio" -> setUiMode(UiMode.RADIO)
                            "Om OktoDrive" -> Toast.makeText(
                                this@MainActivity,
                                "OktoDrive – tidlig utviklerversjon",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        true
                    }
                    show()
                }
            }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(54), dp(54)))
        root.addView(header)
        root.addView(spacer(14))

        val serverCard = card()
        serverCard.addView(text("⚙  Serverinnstillinger", 22f, navy, true))
        serverCard.addView(spacer(14))

        val (addressBox, addressInput) = field(
            "Adresse",
            prefs.getString("host", "ts.veiensvenner.no") ?: "ts.veiensvenner.no",
            hint = "ts.veiensvenner.no"
        )
        serverCard.addView(addressBox)
        serverCard.addView(spacer(10))

        val (portBox, portInput) = field(
            "Port (standard: 64738)",
            prefs.getString("port", "64738") ?: "64738"
        )
        portInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        serverCard.addView(portBox)
        serverCard.addView(spacer(10))

        val (nameBox, nameInput) = field("Navn", prefs.getString("name", "") ?: "")
        serverCard.addView(nameBox)
        serverCard.addView(spacer(10))

        val (passwordBox, passwordInput) = field(
            "Serverpassord (om nødvendig)",
            hint = "Ikke lagret"
        )
        passwordInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        serverCard.addView(passwordBox)
        serverCard.addView(spacer(12))

        val testButton = Button(this).apply {
            text = "🔗   TEST SERVERFORBINDELSE"
            textSize = 16f
            minHeight = dp(58)
            setTextColor(Color.WHITE)
            background = rounded(blue, 12)
        }
        testButton.setOnClickListener {
            val host = addressInput.text.toString().trim()
            val port = portInput.text.toString().toIntOrNull()
            val name = nameInput.text.toString().trim()
            if (host.isBlank() || port == null || port !in 1..65535 || name.isBlank()) {
                AlertDialog.Builder(this)
                    .setMessage("Fyll inn serveradresse, gyldig port og brukernavn.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                prefs.edit()
                    .putString("host", host)
                    .putString("port", port.toString())
                    .putString("name", name)
                    .apply()
                testServer(host, port, connectionStatus, testButton)
            }
        }
        serverCard.addView(
            testButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        connectButton = Button(this).apply {
            text = "KOBLE TIL MUMBLE"
            textSize = 16f
            minHeight = dp(58)
            setTextColor(Color.WHITE)
            background = rounded(navy, 12)
        }
        connectButton.setOnClickListener {
            if (mumbleConnected) {
                mumble?.disconnect()
            } else {
                val host = addressInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull()
                val name = nameInput.text.toString().trim()
                if (host.isBlank() || port == null || port !in 1..65535 || name.isBlank()) {
                    AlertDialog.Builder(this)
                        .setMessage("Fyll inn serveradresse, port og brukernavn.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    prefs.edit()
                        .putString("host", host)
                        .putString("port", port.toString())
                        .putString("name", name)
                        .apply()
                    startMumble(host, port, name, passwordInput.text.toString())
                }
            }
        }
        serverCard.addView(spacer(10))
        serverCard.addView(
            connectButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(serverCard)
        root.addView(spacer(14))

        val channelCard = card()
        channelCard.addView(text("👥  Kanaler", 22f, navy, true))
        channelCard.addView(spacer(10))

        channelList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        channelCard.addView(channelList)
        renderChannels()

        channelCard.addView(spacer(14))
        activeChannelText = text("👥  Aktiv talegruppe:   Ingen", 16f, navy, true)
        channelCard.addView(activeChannelText)
        channelCard.addView(spacer(10))
        channelCard.addView(text("◉  Dobbeltvakt:   Ikke aktiv", 16f, navy, true))
        channelCard.addView(spacer(12))

        muteButton = Button(this).apply {
            textSize = 16f
            minHeight = dp(58)
            isAllCaps = false
            background = rounded(Color.WHITE, 12, border)
            setOnClickListener { toggleSpeakerMute() }
        }
        channelCard.addView(
            muteButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        mumble?.setSpeakerMuted(radioUiState.speakerMuted)
        updateMuteButton()
        channelCard.addView(spacer(10))

        pttButton = Button(this).apply {
            textSize = 17f
            minHeight = dp(82)
            isAllCaps = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startPtt()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        stopPtt()
                        performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        channelCard.addView(
            pttButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        updatePttButton()

        channelCard.addView(spacer(12))
        channelCard.addView(
            text(
                "ⓘ  PTT-test: mikrofon → Opus → Mumble over kryptert TCP-tunnel. Mottakslyd er aktiv.",
                13f,
                muted
            )
        )

        root.addView(channelCard)
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 4101
    }
}
