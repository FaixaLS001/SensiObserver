package com.sensi.observer.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * Detecta o botão de tiro apertado pela mudança de cor da região marcada.
 *
 * Sem calibração ele aprende sozinho a cor do botão solto (média lenta) e considera apertado
 * quando a cor se afasta bastante dela. Com calibração (botão solto, depois apertado) usa as
 * duas cores medidas e um limite entre elas, o que é bem mais confiável.
 */
class FireDetector {
    var calibrated = false
        private set
    var threshold = 18f
        private set
    var pressed = false
        private set

    /** Distância (soma das diferenças RGB) da cor atual até a cor do botão solto. Para o painel. */
    var lastDistance = 0f
        private set

    private var idle = FloatArray(3)
    private var base = FloatArray(3)
    private var baseInit = false
    private var above = 0
    private var below = 0

    fun setCalibration(idleRgb: FloatArray, threshold: Float) {
        idle = idleRgb.copyOf()
        this.threshold = threshold
        calibrated = true
    }

    fun clearCalibration() {
        calibrated = false
        threshold = 18f
        baseInit = false
    }

    fun reset() {
        pressed = false
        above = 0
        below = 0
        baseInit = false
    }

    fun update(r: Float, g: Float, b: Float): Boolean {
        if (!calibrated && !baseInit) {
            base[0] = r; base[1] = g; base[2] = b
            baseInit = true
        }
        val ref = if (calibrated) idle else base
        val d = abs(r - ref[0]) + abs(g - ref[1]) + abs(b - ref[2])
        lastDistance = d
        val on = threshold
        val off = threshold * 0.6f

        if (!pressed) {
            if (d >= on) {
                above++
                if (above >= 2) {
                    pressed = true
                    below = 0
                }
            } else {
                above = 0
            }
        } else {
            if (d < off) {
                below++
                if (below >= 2) {
                    pressed = false
                    above = 0
                }
            } else {
                below = 0
            }
        }

        if (!calibrated && !pressed && d < on) {
            base[0] += (r - base[0]) * 0.02f
            base[1] += (g - base[1]) * 0.02f
            base[2] += (b - base[2]) * 0.02f
        }
        return pressed
    }
}

/** Resultado da calibração do botão de tiro. */
class FireCalibration(
    val ok: Boolean,
    val idle: FloatArray,
    val press: FloatArray,
    val separation: Float,
    val noise: Float,
    val threshold: Float,
    val message: String
)

/**
 * Calibra o botão de tiro em duas fases: primeiro amostras com o botão SOLTO, depois APERTADO.
 * Só aceita se a cor muda bastante em relação à oscilação normal do cenário atrás do botão.
 */
class FireCalibrator {
    private val idleS = ArrayList<FloatArray>()
    private val pressS = ArrayList<FloatArray>()

    fun addIdle(r: Float, g: Float, b: Float) {
        idleS.add(floatArrayOf(r, g, b))
    }

    fun addPressed(r: Float, g: Float, b: Float) {
        pressS.add(floatArrayOf(r, g, b))
    }

    fun finish(): FireCalibration {
        if (idleS.size < 8 || pressS.size < 8) {
            return FireCalibration(false, FloatArray(3), FloatArray(3), 0f, 0f, 0f, "Poucas amostras: tente de novo.")
        }
        val idle = mean(idleS)
        val press = mean(pressS)
        val sep = l1(idle, press)
        val dists = FloatArray(idleS.size) { l1(idleS[it], idle) }
        dists.sort()
        val noise = dists[(dists.size * 95 / 100).coerceAtMost(dists.size - 1)]
        val thr = max(noise * 1.4f, sep * 0.45f).coerceAtLeast(6f)
        return when {
            sep < 10f -> FireCalibration(
                false, idle, press, sep, noise, thr,
                "O botão quase não muda de cor quando apertado. Aumente o raio do botão ou marque outro ponto."
            )
            thr > sep * 0.85f -> FireCalibration(
                false, idle, press, sep, noise, thr,
                "O cenário atrás do botão muda demais. Tente de novo em um lugar parado."
            )
            else -> FireCalibration(true, idle, press, sep, noise, thr, "Botão de tiro calibrado.")
        }
    }

    private fun mean(l: List<FloatArray>): FloatArray {
        val m = FloatArray(3)
        for (a in l) for (i in 0..2) m[i] += a[i]
        for (i in 0..2) m[i] = m[i] / l.size
        return m
    }

    private fun l1(a: FloatArray, b: FloatArray) = abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])
}

/**
 * Detecta acertos pelo marcador que aparece em volta da mira: vermelho = cabeça (capa),
 * branco = corpo. Compara a contagem de pixels de cada cor com o "normal" dos quadros
 * anteriores e emite um evento por acerto (em rajada, cada nova subida conta de novo).
 */
class HitDetector {
    companion object {
        private const val HIST = 33
        const val NONE = 0
        const val BODY = 1
        const val HEAD = 2
    }

    var thrHead = 6
    var thrBody = 10

    // Para o painel e para o diagnóstico.
    var excessRed = 0
        private set
    var excessWhite = 0
        private set
    var lastEventTime = 0.0
        private set

    private val hr = IntArray(HIST)
    private val hw = IntArray(HIST)
    private var hn = 0
    private var hi = 0
    private val tmp = IntArray(HIST)

    private var active = 0
    private var pendingT = -1.0
    private var pendingClass = 0
    private var lastEmitT = -1e9
    private var valleyN = 0f

    // Calibração: -1 desligada, 0 corpo, 1 cabeça.
    private var calMode = -1
    private var calPeak = 0

    fun reset() {
        hn = 0
        hi = 0
        active = 0
        pendingT = -1.0
        pendingClass = 0
        lastEmitT = -1e9
    }

    private fun baseline(h: IntArray): Int {
        val m = minOf(hn - 3, HIST - 3)
        if (m < 8) return Int.MAX_VALUE / 4
        // Ignora os 3 quadros mais recentes (o marcador pode estar começando neles).
        for (k in 0 until m) tmp[k] = h[(hi - 4 - k + 2 * HIST) % HIST]
        java.util.Arrays.sort(tmp, 0, m)
        return tmp[m / 4]
    }

    private fun push(red: Int, white: Int) {
        hr[hi] = red
        hw[hi] = white
        hi = (hi + 1) % HIST
        if (hn < HIST) hn++
    }

    /**
     * Processa um quadro. Devolve [NONE], [BODY] ou [HEAD] quando um acerto é confirmado agora.
     * O instante do acerto (um pouco antes) fica em [lastEventTime].
     */
    fun update(tMs: Double, red: Int, white: Int): Int {
        val baseR = baseline(hr)
        val baseW = baseline(hw)
        push(red, white)
        if (baseR >= Int.MAX_VALUE / 8) return NONE

        val exR = red - baseR
        val exW = white - baseW
        excessRed = exR
        excessWhite = exW

        if (calMode >= 0) {
            val v = if (calMode == 1) exR else exW
            if (v > calPeak) calPeak = v
        }

        val cls = if (exR >= thrHead) HEAD else if (exW >= thrBody) BODY else NONE
        val nx = maxOf(exR.toFloat() / thrHead, exW.toFloat() / thrBody)
        var emit = NONE

        if (active == NONE) {
            if (cls != NONE && tMs - lastEmitT >= 40.0) {
                active = cls
                pendingT = tMs
                pendingClass = cls
                valleyN = nx
            }
        } else {
            if (pendingT >= 0.0) {
                if (cls > pendingClass) pendingClass = cls
                // Espera uns 25 ms para ver se o marcador branco vira vermelho (cabeça).
                if (tMs - pendingT >= 25.0 || pendingClass == HEAD || cls == NONE) {
                    emit = pendingClass
                    lastEventTime = pendingT
                    lastEmitT = pendingT
                    pendingT = -1.0
                    valleyN = nx
                }
            } else if (cls != NONE) {
                // Marcador ainda na tela: uma nova subida depois de um vale é outro acerto.
                if (nx < valleyN) valleyN = nx
                if (nx - valleyN >= 0.6f && nx >= 1f && tMs - lastEmitT >= 70.0) {
                    emit = cls
                    lastEventTime = tMs
                    lastEmitT = tMs
                    valleyN = nx
                }
            }
            if (cls == NONE) active = NONE
        }
        return emit
    }

    // ---------------------------------------------------------------- calibração

    /** Começa a medir o pico do marcador: [head] = true para cabeça, false para corpo. */
    fun beginCalibration(head: Boolean) {
        calMode = if (head) 1 else 0
        calPeak = 0
    }

    /**
     * Termina a medição. Devolve o pico medido (0 se nada foi visto) e ajusta o limite para
     * 40% dele. Devolve -1 se não havia calibração em andamento.
     */
    fun endCalibration(): Int {
        if (calMode < 0) return -1
        val peak = calPeak
        if (peak >= 3) {
            val thr = max(3, (peak * 0.4f).toInt())
            if (calMode == 1) thrHead = thr else thrBody = thr
        }
        calMode = -1
        return peak
    }

    val calibrating: Boolean get() = calMode >= 0
}
