package dev.caraalink.diagnostics

import kotlin.math.abs
import kotlin.math.hypot

/** Normalized coordinates. Fit five targets, verify on four independent targets. */
internal object HuCalibration {
    data class Point(val x: Double, val y: Double)
    data class Mapping(val swap: Boolean, val sx: Double, val sy: Double, val tx: Double, val ty: Double) {
        fun map(p: Point): Point = Point(
            sx * (if (swap) p.y else p.x) + tx,
            sy * (if (swap) p.x else p.y) + ty,
        )
    }
    data class Result(val mapping: Mapping, val maxError: Double, val originalError: Double) {
        val valid get() = maxError <= .045
    }
    val targets = listOf(Point(.12,.15), Point(.88,.15), Point(.88,.85), Point(.12,.85), Point(.5,.5),
        Point(.5,.2), Point(.8,.5), Point(.5,.8), Point(.2,.5))

    fun fit(samples: List<Point>): Result? {
        if (samples.size != targets.size || samples.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        fun axis(input: List<Double>, output: List<Double>): Pair<Double,Double>? {
            val x = input.average(); val y = output.average()
            val variance = input.sumOf { (it-x)*(it-x) }
            if (variance < .000001) return null
            val scale = input.indices.sumOf { (input[it]-x)*(output[it]-y) } / variance
            return if (scale.isFinite() && abs(scale) in .25..4.0) scale to (y-scale*x) else null
        }
        val fit = samples.take(5)
        val expected = targets.take(5)
        return listOf(false,true).mapNotNull { swap ->
            val (sx,tx) = axis(fit.map { if (swap) it.y else it.x }, expected.map { it.x }) ?: return@mapNotNull null
            val (sy,ty) = axis(fit.map { if (swap) it.x else it.y }, expected.map { it.y }) ?: return@mapNotNull null
            val m = Mapping(swap,sx,sy,tx,ty)
            val error = samples.indices.maxOf { i -> m.map(samples[i]).let { hypot(it.x-targets[i].x,it.y-targets[i].y) } }
            val original = samples.indices.maxOf { i -> hypot(samples[i].x-targets[i].x,samples[i].y-targets[i].y) }
            Result(m,error,original)
        }.minByOrNull { it.maxError }
    }
}
