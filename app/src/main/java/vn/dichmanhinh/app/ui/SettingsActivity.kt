package vn.dichmanhinh.app.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import vn.dichmanhinh.app.App
import vn.dichmanhinh.app.BuildConfig
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.engine.Speaker
import vn.dichmanhinh.app.service.TranslateTileService

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val container = FrameLayout(this).apply { id = R.id.settings_container }
        setContentView(container)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var probe: TextToSpeech? = null

        private fun click(key: String, f: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener { f(); true }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()
            val prefs = Prefs(ctx)

            for (k in listOf("google_key", "gemini_key", "ai_key")) {
                findPreference<EditTextPreference>(k)?.apply {
                    setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                        if (p.text.isNullOrBlank()) getString(R.string.not_set) else "••••••••"
                    }
                }
            }
            findPreference<EditTextPreference>("glossary")?.setOnBindEditTextListener {
                it.isSingleLine = false
                it.minLines = 4
                it.hint = getString(R.string.pref_glossary_hint)
            }

            // Ngôn ngữ đích cho "dịch chữ đang gõ"
            findPreference<ListPreference>("typed_dst")?.apply {
                val langs = Languages.all(ctx)
                entries = langs.map { Languages.name(ctx, it) }.toTypedArray()
                entryValues = langs.toTypedArray()
                if (value == null) value = "en"
            }

            // Giao diện
            findPreference<ListPreference>("theme")?.setOnPreferenceChangeListener { _, v ->
                App.applyTheme(v as String); true
            }
            findPreference<ListPreference>("app_lang")?.setOnPreferenceChangeListener { _, v ->
                App.applyLanguage(v as String); true
            }

            // Giọng đọc: liệt kê giọng của ngôn ngữ đích
            populateVoices(prefs)

            click("tts_test") { Speaker.speak(ctx, getString(R.string.tts_sample), prefs.dst) }
            click("style_preview") { startActivity(Intent(ctx, StylePreviewActivity::class.java)) }
            click("open_accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            click("open_models") { startActivity(Intent(ctx, ModelsActivity::class.java)) }
            click("open_history") { startActivity(Intent(ctx, HistoryActivity::class.java)) }
            click("open_tutorial") { Dialogs.tutorial(ctx) }
            click("open_privacy") { Dialogs.privacy(ctx) }
            click("share_app") { Dialogs.shareApp(ctx) }

            // ---- giữ biểu tượng nổi không bị hệ thống tắt
            click("fix_battery") {
                val pm = ctx.getSystemService(PowerManager::class.java)
                if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    Toast.makeText(ctx, R.string.fix_battery_ok, Toast.LENGTH_SHORT).show()
                } else try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            click("fix_appinfo") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
            click("fix_autostart") {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.pref_fix_autostart)
                    .setMessage(R.string.fix_autostart_msg)
                    .setPositiveButton(R.string.open_app_info) { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
            click("fix_tile") {
                if (Build.VERSION.SDK_INT >= 33) {
                    val sbm = ctx.getSystemService(StatusBarManager::class.java)
                    sbm.requestAddTileService(
                        ComponentName(ctx, TranslateTileService::class.java),
                        getString(R.string.tile_label),
                        android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_notif),
                        ctx.mainExecutor
                    ) { }
                } else {
                    AlertDialog.Builder(ctx).setMessage(R.string.fix_tile_old).setPositiveButton(R.string.close, null).show()
                }
            }
            click("fix_acc_restart") {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.pref_fix_acc)
                    .setMessage(R.string.fix_acc_msg)
                    .setPositiveButton(R.string.pref_acc) { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
            findPreference<Preference>("about")?.summary = getString(R.string.about_text, BuildConfig.VERSION_NAME)
        }

        private fun populateVoices(prefs: Prefs) {
            val lp = findPreference<ListPreference>("tts_voice") ?: return
            lp.entries = arrayOf(getString(R.string.voice_default))
            lp.entryValues = arrayOf("")
            probe = TextToSpeech(requireContext().applicationContext) { st ->
                val t = probe
                if (st != TextToSpeech.SUCCESS || t == null || !isAdded) return@TextToSpeech
                val lang = java.util.Locale.forLanguageTag(prefs.dst).language
                val vs = (t.voices ?: emptySet()).filter { it.locale.language == lang && !it.isNetworkConnectionRequired }
                    .sortedBy { it.name }
                lp.entries = (listOf(getString(R.string.voice_default)) + vs.map { "${it.locale.displayName} – ${it.name}" }).toTypedArray()
                lp.entryValues = (listOf("") + vs.map { it.name }).toTypedArray()
            }
        }

        override fun onDestroy() {
            probe?.shutdown()
            probe = null
            super.onDestroy()
        }
    }
}
