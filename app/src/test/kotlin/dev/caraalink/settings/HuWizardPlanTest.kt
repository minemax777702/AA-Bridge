package dev.caraalink.settings

import org.junit.Assert.*
import org.junit.Test

class HuWizardPlanTest {
    private val multi = HuTestProfiles.manual.first { it.gestures == "MULTI" }
    private val single = HuTestProfiles.manual.first { it.gestures == "SINGLE" }

    @Test fun noRecommendationWithoutCompleteSuccessfulTests() {
        assertNull(HuWizardPlan.recommendation(emptyList()))
        assertNull(HuWizardPlan.recommendation(listOf(HuWizardPlan.Result(multi,
            mapOf(HuWizardPlan.Check.MUSIC to HuWizardPlan.Verdict.HU)))))
        val all = HuWizardPlan.checksFor(multi).associateWith { HuWizardPlan.Verdict.PASS }
        assertNull(HuWizardPlan.recommendation(listOf(HuWizardPlan.Result(multi, all + (HuWizardPlan.Check.VOICE to HuWizardPlan.Verdict.SKIP)))))
    }

    @Test fun prefersHeadUnitAudioAndFullGestures() {
        fun result(profile: HuTestProfiles.Profile, audio: HuWizardPlan.Verdict) =
            HuWizardPlan.Result(profile, HuWizardPlan.checksFor(profile).associateWith {
                if (it == HuWizardPlan.Check.MUSIC || it == HuWizardPlan.Check.GUIDANCE) audio else HuWizardPlan.Verdict.PASS
            })
        val preferred = result(multi, HuWizardPlan.Verdict.HU)
        val others = listOf(result(multi, HuWizardPlan.Verdict.PHONE), result(single, HuWizardPlan.Verdict.HU), preferred)
        assertEquals(preferred, HuWizardPlan.recommendation(others))
        assertEquals(4, HuWizardPlan.checksFor(single).size)
    }

    @Test fun candidateOrderingNeverRemovesProfiles() {
        for (audio in listOf(null, true, false)) for (mic in listOf(null, true, false)) {
            val candidates = HuWizardPlan.candidates(audio, mic)
            assertEquals(HuTestProfiles.all.toSet(), candidates.toSet())
            assertEquals(HuTestProfiles.automatic, candidates.first())
            assertEquals(if (audio == true) "carbit_experimental" else "phone_direct", candidates[1].route)
            assertEquals(mic == true, candidates[1].remoteMic)
        }
    }
}
