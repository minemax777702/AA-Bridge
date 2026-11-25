package dev.caraalink.settings

import android.content.Context
import org.json.JSONObject
import dev.caraalink.diagnostics.HuCompatibility

/** One resumable, explicitly identified HU test run; never automatically apply to a different car. */
internal class HuWizardStore(context: Context) {
    private val prefs = context.getSharedPreferences("hu_wizard_v1", Context.MODE_PRIVATE)

    data class Run(
        val target: String,
        val label: String,
        val originalSignature: String,
        val originalCapture: Boolean,
        val selected: String = "",
        val results: Map<String, Map<HuWizardPlan.Check, HuWizardPlan.Verdict>> = emptyMap(),
        val skipped: Set<String> = emptySet(),
        val summary: Boolean = false,
    )

    fun load(): Run? = runCatching {
        val raw = prefs.getString("run", null) ?: return null
        val json = JSONObject(raw)
        val results = linkedMapOf<String, Map<HuWizardPlan.Check, HuWizardPlan.Verdict>>()
        val stored = json.optJSONObject("results") ?: JSONObject()
        for (profile in HuTestProfiles.all) {
            val values = stored.optJSONObject(profile.signature) ?: continue
            val answers = HuWizardPlan.Check.entries.mapNotNull { check ->
                val verdict = runCatching { HuWizardPlan.Verdict.valueOf(values.optString(check.name)) }.getOrNull()
                if (verdict != null && check in HuWizardPlan.checksFor(profile) && verdict in HuWizardPlan.options(check)) check to verdict else null
            }.toMap()
            results[profile.signature] = answers
        }
        val skipped = json.optJSONArray("skipped")
        val skippedSet = (0 until (skipped?.length() ?: 0)).mapNotNull { skipped?.optString(it) }.toSet()
        Run(json.getString("target"), json.getString("label"), json.getString("original"),
            json.optBoolean("originalCapture"), json.optString("selected"), results, skippedSet, json.optBoolean("summary"))
    }.getOrNull()

    fun save(run: Run) {
        val results = JSONObject()
        for ((signature, answers) in run.results) {
            val checks = JSONObject()
            answers.forEach { (check, verdict) -> checks.put(check.name, verdict.name) }
            results.put(signature, checks)
        }
        val json = JSONObject().put("target", run.target).put("label", run.label)
            .put("original", run.originalSignature).put("originalCapture", run.originalCapture)
            .put("selected", run.selected).put("results", results)
            .put("skipped", org.json.JSONArray(run.skipped.toList())).put("summary", run.summary)
        prefs.edit().putString("run", json.toString()).apply()
    }

    fun clear() { prefs.edit().remove("run").apply() }

    companion object {
        /** Capability fingerprint only: matching model/screen does not uniquely identify a physical HU. */
        fun fingerprint(evidence: HuCompatibility.Evidence): String =
            listOf(evidence.protocol, evidence.name, evidence.screen.substringBefore(" · ")).joinToString("|")
    }
}
