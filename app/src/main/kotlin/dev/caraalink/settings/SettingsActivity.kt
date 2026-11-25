package dev.caraalink.settings

import dev.caraalink.R

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, RootPreferenceFragment())
                .commit()
        }
    }

    class RootPreferenceFragment : PreferenceFragmentCompat() {

        // AA has no mid-session resolution/DPI renegotiation (confirmed --
        // see claude/roadmap.md): the only way a changed setting here takes
        // effect on an already-running AA session is a full reconnect,
        // which CarSession arms dev.caraalink.aa.AaReconnectBus to do.
        private val videoSettingKeys = setOf(
            "car_orientation", "video_resolution_mode", "video_resolution_custom_width",
            "video_resolution_custom_height", "video_frame_rate", "video_dpi_auto", "video_dpi_manual",
            "aa_auto_crop", "video_downscale_720p", "aa_audio_route", "aa_carbit_mic",
        )
        private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in videoSettingKeys) {
                updateDpiSummary()
                val reconnect = dev.caraalink.aa.AaReconnectBus.onVideoSettingsChanged
                if (reconnect != null) {
                    Toast.makeText(requireContext(), getString(R.string.pref_reconnecting), Toast.LENGTH_SHORT).show()
                    reconnect()
                }
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsListener)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsListener)
            super.onPause()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(dev.caraalink.R.xml.root_preferences, rootKey)
            updateDpiSummary()
            findPreference<Preference>("start_hu_emulator")?.apply {
                isVisible = dev.caraalink.BuildConfig.DEBUG
                setOnPreferenceClickListener {
                    HuEmulatorDialog.show(requireContext())
                    true
                }
            }

            findPreference<Preference>("developer_tools")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), dev.caraalink.diagnostics.DeveloperToolsActivity::class.java))
                true
            }
            findPreference<Preference>("car_profiles")?.setOnPreferenceClickListener {
                showCarProfilesDialog()
                true
            }
        }

        private fun showCarProfilesDialog() {
            val context = requireContext()
            val profiles = CarProfiles.listProfiles(context)
            val items = (profiles + getString(R.string.profile_save_new)).toTypedArray()
            android.app.AlertDialog.Builder(context)
                .setTitle(getString(R.string.profile_title))
                .setItems(items) { _, index ->
                    if (index == profiles.size) {
                        promptSaveProfile()
                    } else {
                        showProfileActionsDialog(profiles[index])
                    }
                }
                .setNegativeButton(getString(R.string.action_close), null)
                .show()
        }

        private fun promptSaveProfile() {
            val context = requireContext()
            val input = android.widget.EditText(context).apply { hint = getString(R.string.profile_name_hint) }
            android.app.AlertDialog.Builder(context)
                .setTitle(getString(R.string.profile_save_title))
                .setView(input)
                .setPositiveButton(getString(R.string.action_save)) { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        CarProfiles.saveProfile(context, name)
                        Toast.makeText(context, getString(R.string.profile_saved, name), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
        }

        private fun showProfileActionsDialog(name: String) {
            val context = requireContext()
            android.app.AlertDialog.Builder(context)
                .setTitle(name)
                .setItems(arrayOf(getString(R.string.action_load), getString(R.string.action_delete))) { _, which ->
                    if (which == 0) {
                        CarProfiles.loadProfile(context, name)
                        Toast.makeText(context, getString(R.string.profile_loaded, name), Toast.LENGTH_SHORT).show()
                    } else {
                        CarProfiles.deleteProfile(context, name)
                        Toast.makeText(context, getString(R.string.profile_deleted, name), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
        }

        /**
         * Shows the actual computed density value so the owner can see what's
         * really being sent while calibrating manually -- empirically (not
         * the simple dp=px*density/160 model originally assumed), a HIGHER
         * declared density makes AA's UI look SMALLER on a real car screen,
         * confirmed from live testing. No confirmed correct target value
         * yet, so this doesn't change the formula, just makes the current
         * number visible instead of hidden.
         */
        private fun updateDpiSummary() {
            val context = requireContext()
            val config = dev.caraalink.aa.AaServiceDiscovery.configFromSettings(context)
            findPreference<androidx.preference.SwitchPreferenceCompat>("video_dpi_auto")?.summary =
                getString(R.string.pref_dpi_auto_summary, config.density)
            findPreference<Preference>("video_dpi_manual")?.summary =
                getString(R.string.pref_dpi_manual_summary, config.density)
        }
    }
}
