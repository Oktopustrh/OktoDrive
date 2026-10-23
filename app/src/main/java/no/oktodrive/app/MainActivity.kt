package no.oktodrive.app

import android.app.Activity
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Initial radio UI. No Mumble network connection or audio transmission yet.
 * Never display a fake connected state or claim that PTT transmits audio.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("server_settings", MODE_PRIVATE) }

    /**
     * Tests TLS reachability only. This is NOT a Mumble login/session.
     * Keep PTT disabled until the actual Mumble protocol and audio are implemented.
     */
    private fun testServer(host: String, port: Int, status: TextView, button: Button) {
        status.text = "●  Tester server …"
        status.setTextColor(muted)
        button.isEnabled = false
        Thread {
            val result = try {
                val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                socket.use {
                    it.soTimeout = 6000
                    it.connect(java.net.InetSocketAddress(host, port), 6000)
                    it.startHandshake()
                }
                "TLS-kontakt OK. Mumble-innlogging er ennå ikke implementert."
            } catch (e: Exception) {
                "Kunne ikke nå serveren: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                status.text = "●  Ikke tilkoblet"
                status.setTextColor(Color.rgb(190, 55, 45))
                button.isEnabled = true
                AlertDialog.Builder(this)
                    .setTitle("Servertest")
                    .setMessage(result)
                    .setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private val releaseApi = "https://api.github.com/repos/Oktopustrh/OktoDrive/releases/latest"

    private fun checkForUpdates(manual: Boolean) {
        Thread {
            try {
                val connection = (URL(releaseApi).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "OktoDrive-Android")
                }
                try {
                    val code = connection.responseCode
                    if (code == 404) {
                        if (manual) runOnUiThread {
                            AlertDialog.Builder(this).setMessage("Ingen publisert OktoDrive-versjon ennå. Utviklingsbygg ligger foreløpig på GitHub Actions.")
                                .setPositiveButton("OK", null).show()
                        }
                        return@Thread
                    }
                    if (code != 200) throw IllegalStateException("GitHub svarte HTTP $code")
                    val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val assets = release.optJSONArray("assets")
                    var apkUrl: String? = null
                    if (assets != null) for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                    val tag = release.optString("tag_name")
                    val version = tag.removePrefix("v").split(".")
                    val publishedCode = version.getOrNull(0)?.toIntOrNull()?.let { major ->
                        val minor = version.getOrNull(1)?.toIntOrNull() ?: 0
                        val patch = version.getOrNull(2)?.toIntOrNull() ?: 0
                        major * 1000000 + minor * 1000 + patch
                    }
                    val installed = packageManager.getPackageInfo(packageName, 0)
                    @Suppress("DEPRECATION")
                    val currentCode = if (android.os.Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
                    val downloadUrl = apkUrl
                    if (downloadUrl != null && publishedCode != null && publishedCode > currentCode) {
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("Ny OktoDrive-versjon: $tag")
                                .setMessage("Vil du laste ned oppdateringen? Android ber deg bekrefte installasjonen.")
                                .setNegativeButton("Senere", null)
                                .setPositiveButton("Last ned") { _, _ ->
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                                }.show()
                        }
                    } else if (manual) {
                        runOnUiThread {
                            AlertDialog.Builder(this).setMessage("Ingen nyere publisert APK er tilgjengelig akkurat nå.")
                                .setPositiveButton("OK", null).show()
                        }
                    }
                } finally { connection.disconnect() }
            } catch (e: Exception) {
                if (manual) runOnUiThread {
                    AlertDialog.Builder(this).setMessage("Kunne ikke sjekke oppdateringer: ${e.localizedMessage}")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private lateinit var connectionStatus: TextView

    private val navy = Color.rgb(10, 49, 105)
    private val blue = Color.rgb(25, 118, 210)
    private val paleBlue = Color.rgb(235, 246, 255)
    private val border = Color.rgb(199, 214, 232)
    private val muted = Color.rgb(88, 112, 145)
    private val disabledText = Color.rgb(150, 164, 184)

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
        this.text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun spacer(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun field(label: String, value: String = "", hint: String = "", focused: Boolean = false): Pair<LinearLayout, EditText> {
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

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = rounded(Color.WHITE, 18, Color.rgb(226, 235, 245))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(22))
            setBackgroundColor(Color.rgb(247, 251, 255))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        checkForUpdates(manual = false)

        // Header / branding
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(12), dp(14))
            background = rounded(paleBlue, 18)
        }

        header.addView(text("🐙📻", 34f), LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("OktoDrive", 28f, navy, true))
            addView(text("●  Ikke tilkoblet", 17f, Color.rgb(190, 55, 45)).also { connectionStatus = it })
        }
        header.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val menuButton = TextView(this).apply {
            text = "⋮"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(navy)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener { anchor ->
                PopupMenu(this@MainActivity, anchor).apply {
                    menu.add("Se etter oppdatering")
                    menu.add("Om OktoDrive")
                    setOnMenuItemClickListener { item ->
                        when (item.title.toString()) {
                            "Se etter oppdatering" -> checkForUpdates(manual = true)
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

        // Server settings card
        val serverCard = card()
        serverCard.addView(text("⚙  Serverinnstillinger", 22f, navy, true))
        serverCard.addView(spacer(14))
        val (addressBox, addressInput) = field("Adresse", prefs.getString("host", "") ?: "", hint = "mumble.example.no")
        serverCard.addView(addressBox)
        serverCard.addView(spacer(10))
        val (portBox, portInput) = field("Port (standard: 64738)", prefs.getString("port", "64738") ?: "64738")
        portInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        serverCard.addView(portBox)
        serverCard.addView(spacer(10))
        val (nameBox, nameInput) = field("Navn", prefs.getString("name", "") ?: "")
        serverCard.addView(nameBox)
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
                AlertDialog.Builder(this).setMessage("Fyll inn serveradresse, gyldig port og brukernavn.")
                    .setPositiveButton("OK", null).show()
            } else {
                prefs.edit().putString("host", host).putString("port", port.toString())
                    .putString("name", name).apply()
                testServer(host, port, connectionStatus, testButton)
            }
        }
        serverCard.addView(testButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(serverCard)
        root.addView(spacer(14))

        // Channels card
        val channelCard = card()
        channelCard.addView(text("👥  Kanaler", 22f, navy, true))
        channelCard.addView(spacer(10))
        channelCard.addView(text("Kanallisten vises etter at Mumble-tilkoblingen er implementert.", 15f, muted))
        channelCard.addView(spacer(14))
        channelCard.addView(text("👥  Aktiv talegruppe:   Ingen", 16f, navy, true))
        channelCard.addView(spacer(10))
        channelCard.addView(text("◉  Dobbeltvakt:   Ikke aktiv", 16f, navy, true))
        channelCard.addView(spacer(14))

        channelCard.addView(Button(this).apply {
            text = "🎙   PTT – IKKE TILGJENGELIG ENNÅ"
            isEnabled = false
            textSize = 17f
            minHeight = dp(82)
            background = rounded(Color.rgb(232, 239, 247), 12)
            setTextColor(disabledText)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        channelCard.addView(spacer(12))
        channelCard.addView(text("ⓘ  Dette er en grensesnitt-test. Ingen lyd sendes eller mottas.", 13f, muted))

        root.addView(channelCard)
    }
}
