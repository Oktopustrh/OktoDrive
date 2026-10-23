package no.oktodrive.app

import android.app.Activity
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

    private fun field(label: String, value: String = "", hint: String = "", focused: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(Color.WHITE, 12, if (focused) blue else border, if (focused) 2 else 1)

            addView(text(label, 14f, if (focused) blue else muted))
            addView(EditText(this@MainActivity).apply {
                setText(value)
                this.hint = hint
                textSize = 18f
                setTextColor(navy)
                setHintTextColor(Color.rgb(155, 165, 178))
                setSingleLine(true)
                background = null
                setPadding(0, 0, 0, 0)
            })
        }
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
            addView(text("●  Ikke tilkoblet", 17f, Color.rgb(190, 55, 45)))
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
                            "Se etter oppdatering" -> Toast.makeText(
                                this@MainActivity,
                                "Oppdateringssjekk kommer i neste trinn.",
                                Toast.LENGTH_SHORT
                            ).show()
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
        serverCard.addView(field("Adresse", "127.0.0.1"))
        serverCard.addView(spacer(10))
        serverCard.addView(field("Port (standard: 64738)", hint = "64738"))
        serverCard.addView(spacer(10))
        serverCard.addView(field("Navn", "oktopus", focused = true))
        serverCard.addView(spacer(12))

        serverCard.addView(Button(this).apply {
            text = "🔗   KOBLE TIL (KOMMER SENERE)"
            isEnabled = false
            textSize = 16f
            minHeight = dp(58)
            background = rounded(Color.rgb(232, 239, 247), 12)
            setTextColor(disabledText)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
