package no.oktodrive.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Initial radio UI. No Mumble network connection or audio transmission yet.
 * Never display a fake connected state or claim that PTT transmits audio.
 */
class MainActivity : Activity() {
    private fun label(value: String, size: Float = 18f): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(25, 35, 45))
            setPadding(0, 12, 0, 12)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 28)
            setBackgroundColor(Color.WHITE)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(label("OktoDrive", 30f))
        val status = label("● Ikke tilkoblet", 20f)
        root.addView(status)
        root.addView(label("Serverinnstillinger", 22f))

        val server = EditText(this).apply {
            hint = "Serveradresse"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        root.addView(server)
        val port = EditText(this).apply {
            hint = "Port (standard: 64738)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        root.addView(port)
        val username = EditText(this).apply {
            hint = "Brukernavn"
            setSingleLine(true)
        }
        root.addView(username)

        root.addView(Button(this).apply {
            text = "Koble til (kommer senere)"
            isEnabled = false
        })
        root.addView(label("Kanaler", 22f))
        root.addView(label("Kanallisten vises etter at Mumble-tilkoblingen er implementert."))
        root.addView(label("Aktiv talegruppe: Ingen"))
        root.addView(label("Dobbeltvakt: Ikke aktiv"))

        val ptt = Button(this).apply {
            text = "PTT – ikke tilgjengelig ennå"
            isEnabled = false
            textSize = 20f
            minHeight = 140
        }
        root.addView(ptt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(label("Dette er en grensesnitt-test. Ingen lyd sendes eller mottas.", 14f))
    }
}
