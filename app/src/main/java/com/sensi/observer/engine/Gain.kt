package com.sensi.observer.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/** Um arrasto de teste feito pela calibração de resposta. Tempos na mesma base da [Timeline]. */
class SwipeRun(
    val tStartMs: Double,
    val tEndMs: Double,
    val fingerDxPx: Double,
    val fingerDyPx: Double
)

class GainResult(
    val ok: Boolean,
    val gain: Double,
    val used: Int,
    val spread: Double,
    val message: String
)

/**
 * "Resposta" da sensibilidade: quantos pixels a câmera gira por pixel que o dedo anda.
 * O Android não deixa um app ler o toque de outro app, então o app mede isso uma vez, fazendo
 * alguns arrastos de teste com tamanho conhecido no campo de treino e vendo o quanto a câmera
 * girou. Com esse número, todo movimento de câmera observado vira distância de dedo.
 */
object GainEstimator {

    fun estimate(tl: Timeline, runs: List<SwipeRun>, heightPx: Int): GainResult {
        val gains = ArrayList<Double>()
        for (r in runs) {
            val len = hypot(r.fingerDxPx, r.fingerDyPx)
            if (len < 20.0) continue
            val ux = r.fingerDxPx / len
            val uy = r.fingerDyPx / len
            val a = SessionAnalyzer.indexAtOrAfter(tl, r.tStartMs)
            val z = SessionAnalyzer.indexAtOrAfter(tl, r.tEndMs + 350.0)
            if (a < 0 || z <= a) continue
            var cx = 0.0
            var cy = 0.0
            var valid = 0
            var total = 0
            for (i in a..z) {
                total++
                if (tl.flag(i, Flag.VALID)) {
                    valid++
                    cx += tl.lx[i]
                    cy += tl.ly[i]
                }
            }
            if (total < 6 || valid < total * 0.7) continue
            val along = (cx * ux + cy * uy) * heightPx
            gains.add(abs(along) / len)
        }
        if (gains.size < 3) {
            return GainResult(
                false, 0.0, gains.size, 0.0,
                "Não consegui medir a câmera girando. Fique no campo de treino, com a mira livre, e tente de novo."
            )
        }
        gains.sort()
        val med = SessionAnalyzer.median(gains)
        val q1 = gains[gains.size / 4]
        val q3 = gains[(gains.size * 3 / 4).coerceAtMost(gains.size - 1)]
        val spread = if (med > 0) (q3 - q1) / med else 1.0
        if (med < 0.02) {
            return GainResult(false, med, gains.size, spread, "A câmera quase não se mexeu. O ponto de arrasto está sobre a área de girar a câmera?")
        }
        val msg = if (spread > 0.35) "Medido, mas os arrastos deram resultados diferentes entre si. Repita para confirmar."
        else "Resposta medida."
        return GainResult(true, med, gains.size, spread, msg)
    }

    /** Expoente da sensibilidade: gain ~ sens^a. Só existe com 2+ valores de sens medidos. */
    fun sensExponent(records: List<GainRecord>, scope: Int, densityDpi: Int, w: Int, h: Int): Double? {
        val r = records.filter {
            it.scope == scope && it.densityDpi == densityDpi && it.widthPx == w && it.heightPx == h && it.gain > 0 && it.sens > 0
        }
        val xs = r.map { ln(it.sens.toDouble()) }
        val ys = r.map { ln(it.gain) }
        if (xs.size < 2) return null
        if (xs.max() - xs.min() < 0.1) return null
        val a = slope(xs, ys) ?: return null
        return a.coerceIn(0.3, 3.0)
    }

    /** Expoente do DPI: gain ~ dpi^n (já descontada a sensibilidade). Precisa de 2+ DPIs. */
    fun dpiExponent(records: List<GainRecord>, scope: Int, w: Int, h: Int, sensExp: Double): Double? {
        val r = records.filter { it.scope == scope && it.widthPx == w && it.heightPx == h && it.gain > 0 && it.sens > 0 }
        val xs = r.map { ln(it.densityDpi.toDouble()) }
        val ys = r.map { ln(it.gain) - sensExp * ln(it.sens.toDouble()) }
        if (xs.size < 2 || xs.max() - xs.min() < 0.05) return null
        return slope(xs, ys)?.coerceIn(-3.0, 3.0)
    }

    /** Resposta esperada para (mira, sens, DPI): a medida mais recente que serve, ajustada se der. */
    fun gainFor(records: List<GainRecord>, scope: Int, sens: Int, densityDpi: Int, w: Int, h: Int): Double? {
        val same = records.filter { it.scope == scope && it.densityDpi == densityDpi && it.widthPx == w && it.heightPx == h }
        if (same.isEmpty()) return null
        val exact = same.filter { abs(it.sens - sens) <= 3 }.maxByOrNull { it.timeMs }
        if (exact != null) return exact.gain
        val a = sensExponent(records, scope, densityDpi, w, h) ?: return null
        val last = same.maxByOrNull { it.timeMs } ?: return null
        return last.gain * exp(a * ln(sens.toDouble() / last.sens))
    }

    private fun slope(xs: List<Double>, ys: List<Double>): Double? {
        val n = xs.size
        val mx = xs.sum() / n
        val my = ys.sum() / n
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        return if (den < 1e-9) null else num / den
    }
}
