package dev.caraalink.settings

/** Pure ranking logic. Only user-observed successful tests can confirm compatibility. */
internal object HuWizardPlan {
    enum class Check { MUSIC, GUIDANCE, VOICE, TOUCH, PINCH }
    enum class Verdict { HU, PHONE, PASS, FAIL, SKIP }
    data class Result(val profile: HuTestProfiles.Profile, val answers: Map<Check, Verdict>) {
        val checks get() = checksFor(profile)
        val answered get() = checks.count { it in answers }
        val passed get() = checks.count { successful(answers[it]) }
        val failed get() = checks.count { answers[it] == Verdict.FAIL }
        val complete get() = answered == checks.size
        val confirmed get() = complete && passed == checks.size
        val huAudio get() = listOf(Check.MUSIC, Check.GUIDANCE).count { answers[it] == Verdict.HU }
    }

    fun checksFor(profile: HuTestProfiles.Profile): List<Check> =
        Check.entries.filter { it != Check.PINCH || profile.gestures != "SINGLE" }

    fun successful(verdict: Verdict?) = verdict in setOf(Verdict.HU, Verdict.PHONE, Verdict.PASS)
    fun options(check: Check): List<Verdict> = when (check) {
        Check.MUSIC, Check.GUIDANCE -> listOf(Verdict.HU, Verdict.PHONE, Verdict.FAIL, Verdict.SKIP)
        else -> listOf(Verdict.PASS, Verdict.FAIL, Verdict.SKIP)
    }

    /** Preserve all candidates; capability flags affect order, never manufacture a pass. */
    fun candidates(pcmDeclared: Boolean?, micDeclared: Boolean?): List<HuTestProfiles.Profile> {
        val routeOrder = if (pcmDeclared == true)
            listOf("carbit_experimental", "phone_direct", "bt_preferred", "device_only")
        else listOf("phone_direct", "bt_preferred", "device_only", "carbit_experimental")
        return listOf(HuTestProfiles.automatic) + HuTestProfiles.manual.sortedWith(
            compareBy<HuTestProfiles.Profile> { it.gestures == "SINGLE" }
                .thenBy { routeOrder.indexOf(it.route) }
                .thenBy { it.remoteMic != (micDeclared == true) }
        )
    }

    /** Prefer audible HU output, then full gestures, then lower protocol complexity. */
    fun rank(results: List<Result>): List<Result> = results.sortedWith(
        compareByDescending<Result> { it.confirmed }
            .thenByDescending { it.huAudio }
            .thenByDescending { it.answers[Check.PINCH] == Verdict.PASS }
            .thenByDescending { it.passed }
            .thenBy { it.failed }
            .thenByDescending { it.complete }
            .thenBy { it.profile.route == "carbit_experimental" }
            .thenBy { it.profile.signature }
    )

    fun recommendation(results: List<Result>): Result? = rank(results).firstOrNull { it.confirmed }
}
