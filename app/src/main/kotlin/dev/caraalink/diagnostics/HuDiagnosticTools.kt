package dev.caraalink.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.caraalink.link.CarbitAudioTransport

/** Current-link tools. No retained callbacks after disconnect; no vehicle-state overrides. */
internal object HuDiagnosticTools {
    data class State(val sessionId: String = "", val calibrating: Boolean = false,
        val samples: List<HuCalibration.Point> = emptyList(), val result: HuCalibration.Result? = null,
        val applied: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    data class Binding(val owner: Any, val sessionId: String, val ready: () -> Boolean,
        val show: () -> Unit, val close: () -> Unit, val redraw: () -> Unit,
        val audio: () -> CarbitAudioTransport?, val apply: (HuCalibration.Mapping?) -> Unit)
    @Volatile private var binding: Binding? = null

    @Synchronized fun attach(value: Binding) {
        binding = value
        mutable.value = State(sessionId = value.sessionId)
    }
    @Synchronized fun detach(owner: Any) {
        if (binding?.owner !== owner) return
        binding = null
        mutable.value = State()
    }
    fun ready(session: String): Boolean = binding?.let { it.sessionId == session && it.ready() } == true
    fun audio(session: String): CarbitAudioTransport? = binding?.takeIf { it.sessionId == session && it.ready() }?.audio()
    @Synchronized fun begin(session: String): Boolean {
        val b = binding?.takeIf { it.sessionId == session && it.ready() } ?: return false
        if (mutable.value.calibrating) return false
        mutable.value = State(sessionId = session, calibrating = true, applied = mutable.value.applied)
        b.show()
        return true
    }
    @Synchronized fun tap(owner: Any, point: HuCalibration.Point): Boolean {
        val b = binding?.takeIf { it.owner === owner } ?: return false
        val s = mutable.value
        if (!s.calibrating) return false
        if (s.samples.size >= HuCalibration.targets.size) return true
        val samples = s.samples + point
        mutable.value = s.copy(samples = samples, result = HuCalibration.fit(samples))
        b.redraw()
        return true
    }
    @Synchronized fun stop(session: String) {
        val b = binding?.takeIf { it.sessionId == session } ?: return
        val s = mutable.value
        if (!s.calibrating) return
        mutable.value = s.copy(calibrating = false)
        b.close()
    }
    @Synchronized fun apply(session: String): Boolean {
        val b = binding?.takeIf { it.sessionId == session } ?: return false
        val s = mutable.value
        val result = s.result?.takeIf { it.valid } ?: return false
        if (!s.calibrating) return false
        b.apply(result.mapping)
        mutable.value = s.copy(applied = true, calibrating = false)
        b.close()
        return true
    }
    @Synchronized fun reset(session: String) {
        val b = binding?.takeIf { it.sessionId == session } ?: return
        b.apply(null)
        if (mutable.value.calibrating) b.close()
        mutable.value = State(sessionId = session)
    }
}
