package no.oktodrive.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class RadioBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_start", false)) return
        // A deliberate exit lasts until the next boot or an explicit app launch.
        prefs.edit().putBoolean("radio_exited", false).apply()
        if (RadioStartupPolicy.canLaunchFromBoot(Build.VERSION.SDK_INT, true,
                prefs.getBoolean("setup_complete", false))) {
            try {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { }
        }
        // On Android 10+ the user selects OktoDrive as HOME for reliable startup.
        // Do not try to bypass background activity/microphone restrictions.
    }
}
