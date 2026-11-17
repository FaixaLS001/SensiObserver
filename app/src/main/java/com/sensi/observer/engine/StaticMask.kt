package com.sensi.observer.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Aprende sozinho quais pixels do recorte são HUD (ficam parados na tela mesmo com a câmera
 * girando) e os esconde da medição de movimento. Sem isso, qualquer elemento fixo dentro da
 * área de leitura "puxa" a medição para zero e os movimentos lentos saem 20% a 50% menores.
 *
 * Como funciona: nos quadros em que a câmera anda pelo menos [MIN_MOTION] px, um pixel do
 * cenário muda de valor; um pixel de HUD não muda. Quem continua sem mudar por vários quadros
 * seguidos vira "parado" e é trocado pela média do recorte antes da correlação.
 */
class StaticMask(private val n: Int) {
    companion object {
        const val MIN_MOTION = 1.5
        private const val DECAY = 0.88f
        private const val ON = 0.85f
    }

    private val score = FloatArray(n * n)
    private val rawPrev = FloatArray(n * n)
    private val rawCur = FloatArray(n * n)
    private var hasPrev = false

    /** Quantos pixels estão mascarados agora (para diagnóstico). */
    var maskedCount = 0
        private set

    fun reset() {
        hasPrev = false
    }

    /** Guarda o recorte cru e troca os pixels parados pela média. Altera [patch] no lugar. */
    fun apply(patch: FloatArray) {
        System.arraycopy(patch, 0, rawCur, 0, n * n)
        var sum = 0.0
        var cnt = 0
        for (i in 0 until n * n) {
            if (score[i] < ON) {
                sum += patch[i]
                cnt++
            }
        }
        maskedCount = n * n - cnt
        if (maskedCount == 0) return
        val mean = if (cnt > 0) (sum / cnt).toFloat() else patch[0]
        for (i in 0 until n * n) if (score[i] >= ON) patch[i] = mean
    }

    /**
     * Aprende com o quadro que acabou de ser medido. Chamar depois de [apply] e da correlação,
     * com o deslocamento encontrado (em pixels do recorte).
     */
    fun learn(valid: Boolean, dx: Double, dy: Double) {
        if (hasPrev && valid && hypot(dx, dy) >= MIN_MOTION) {
            var sumD = 0.0
            for (i in 0 until n * n) sumD += abs(rawCur[i] - rawPrev[i])
            val meanD = (sumD / (n * n)).toFloat()
            val thr = max(2.5f, 0.2f * meanD)
            for (i in 0 until n * n) {
                val staticLike = abs(rawCur[i] - rawPrev[i]) <= thr
                score[i] = score[i] * DECAY + (if (staticLike) 1f - DECAY else 0f)
            }
        }
        System.arraycopy(rawCur, 0, rawPrev, 0, n * n)
        hasPrev = true
    }
}
