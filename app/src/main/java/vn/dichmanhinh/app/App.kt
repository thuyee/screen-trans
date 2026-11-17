package vn.dichmanhinh.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import vn.dichmanhinh.app.data.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val p = Prefs(this)
        applyTheme(p.theme)
    }

    companion object {
        const val CHANNEL = "service"

        lateinit var instance: App
            private set

        private val ui = Handler(Looper.getMainLooper())

        /** Toast gọi được từ mọi luồng. */
        fun toast(msg: String) {
            ui.post { Toast.makeText(instance, msg, Toast.LENGTH_LONG).show() }
        }

        fun applyTheme(v: String) {
            AppCompatDelegate.setDefaultNightMode(
                when (v) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }

        fun applyLanguage(tag: String) {
            AppCompatDelegate.setApplicationLocales(
                if (tag == "system") LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag)
            )
        }
    }
}
