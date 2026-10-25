package no.oktodrive.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build

class OktoDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        @Volatile
        private lateinit var appContext: Context

        internal fun context(): Context {
            check(::appContext.isInitialized) { "OktoDriveApp context is not initialized" }
            return appContext
        }

        fun startConnectionProtection() {
            if (!::appContext.isInitialized) return
            val intent = Intent(appContext, MumbleForegroundService::class.java)
                .setAction(MumbleForegroundService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stopConnectionProtection() {
            if (!::appContext.isInitialized) return
            appContext.stopService(Intent(appContext, MumbleForegroundService::class.java))
        }
    }
}
