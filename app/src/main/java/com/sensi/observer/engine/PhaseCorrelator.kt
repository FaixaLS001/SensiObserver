package com.sensi.observer.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** FFT 2D (radix-2) sobre quadrados de lado potência de 2. Matrizes guardadas por linhas. */
internal class Fft2D(val n: Int) {
    private val cosT = DoubleArray(n / 2)
    private val sinT = DoubleArray(n / 2)
    private val rev = IntArray(n)
    private val tr = DoubleArray(n)
    private val ti = DoubleArray(n)

    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "n precisa ser potência de 2" }
        for (i in 0 until n / 2) {
            val a = -2.0 * PI * i / n
            cosT[i] = cos(a)
            sinT[i] = sin(a)
        }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    private fun fft1(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val a = re[i]; re[i] = re[j]; re[j] = a
                val b = im[i]; im[i] = im[j]; im[j] = b
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) -sinT[k] else sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }

    /** Transformada direta ou inversa (a inversa já divide por n*n). */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (r in 0 until n) {
            System.arraycopy(re, r * n, tr, 0, n)
            System.arraycopy(im, r * n, ti, 0, n)
            fft1(tr, ti, inverse)
            System.arraycopy(tr, 0, re, r * n, n)
            System.arraycopy(ti, 0, im, r * n, n)
        }
        for (c in 0 until n) {
            for (r in 0 until n) {
                tr[r] = re[r * n + c]
                ti[r] = im[r * n + c]
            }
            fft1(tr, ti, inverse)
            for (r in 0 until n) {
                re[r * n + c] = tr[r]
                im[r * n + c] = ti[r]
            }
        }
        if (inverse) {
            val s = 1.0 / (n.toDouble() * n)
            for (i in 0 until n * n) {
                re[i] *= s
                im[i] *= s
            }
        }
    }
}

/** Resultado de uma medição de movimento entre dois quadros seguidos. */
class MotionEstimate {
    /** Deslocamento do CONTEÚDO da imagem, em pixels do recorte (dx > 0 = foi para a direita). */
    var dx = 0.0
    var dy = 0.0

    /** Altura do pico da correlação (0 a 1): quanto da imagem se explica só por um deslocamento. */
    var peak = 0.0

    /** Pico em relação ao ruído da correlação. */
    var psr = 0.0
    var valid = false

    /** 0 a 1: confiança usada nas estatísticas. */
    val confidence: Double
        get() = if (!valid) 0.0 else (peak / 0.35).coerceIn(0.0, 1.0)
}

/**
 * Mede o quanto a imagem andou entre dois quadros por correlação de fase.
 * No jogo, a câmera girando faz o cenário inteiro deslizar: esse deslizamento (com o sinal
 * trocado) é o movimento da câmera, e é o que o app usa para "ver" o arrasto do dedo.
 *
 * Recebe recortes n x n de luminância. Guarda o espectro do quadro anterior, então cada
 * chamada custa uma FFT direta e uma inversa.
 */
class PhaseCorrelator(val n: Int = 128) {
    private val fft = Fft2D(n)
    private val window = DoubleArray(n * n)
    private val lowpass = DoubleArray(n * n)
    private var pr = DoubleArray(n * n)
    private var pi = DoubleArray(n * n)
    private var cr = DoubleArray(n * n)
    private var ci = DoubleArray(n * n)
    private val rr = DoubleArray(n * n)
    private val ri = DoubleArray(n * n)
    private var hasPrev = false

    /** Pico mínimo e relação pico/ruído para aceitar a medição. */
    var minPeak = 0.06
    var minPsr = 7.0

    init {
        for (y in 0 until n) {
            val wy = 0.5 - 0.5 * cos(2.0 * PI * (y + 0.5) / n)
            for (x in 0 until n) {
                val wx = 0.5 - 0.5 * cos(2.0 * PI * (x + 0.5) / n)
                window[y * n + x] = wx * wy
            }
        }
        // Passa-baixa suave no domínio da frequência: tira o ruído de alta frequência.
        for (ky in 0 until n) {
            val fy = minOf(ky, n - ky).toDouble() / (n / 2)
            for (kx in 0 until n) {
                val fx = minOf(kx, n - kx).toDouble() / (n / 2)
                lowpass[ky * n + kx] = exp(-(fx * fx + fy * fy) / (2 * 0.5 * 0.5))
            }
        }
    }

    /** Esquece o quadro anterior (depois de pausa, troca de tamanho de tela etc.). */
    fun reset() {
        hasPrev = false
    }

    /**
     * Mede o deslocamento de [patch] em relação ao recorte anterior.
     * Devolve false (e out.valid = false) quando não há como medir.
     */
    fun update(patch: FloatArray, out: MotionEstimate): Boolean {
        val total = n * n
        var mean = 0.0
        for (i in 0 until total) mean += patch[i]
        mean /= total
        for (i in 0 until total) {
            cr[i] = (patch[i] - mean) * window[i]
            ci[i] = 0.0
        }
        fft.transform(cr, ci, false)

        if (!hasPrev) {
            swapSpectra()
            hasPrev = true
            out.valid = false
            out.peak = 0.0
            out.psr = 0.0
            return false
        }

        // Espectro cruzado normalizado: Rn = C * conj(P) / |C * conj(P)|.
        for (k in 0 until total) {
            val a = cr[k] * pr[k] + ci[k] * pi[k]
            val b = ci[k] * pr[k] - cr[k] * pi[k]
            val mag = hypot(a, b) + 1e-9
            rr[k] = a / mag * lowpass[k]
            ri[k] = b / mag * lowpass[k]
        }
        fft.transform(rr, ri, true)
        swapSpectra()

        // Pico da correlação.
        var best = -Double.MAX_VALUE
        var bi = 0
        for (k in 0 until total) {
            if (rr[k] > best) {
                best = rr[k]
                bi = k
            }
        }
        val by = bi / n
        val bx = bi % n

        // Ruído: média e desvio fora da vizinhança do pico.
        var sum = 0.0
        var sum2 = 0.0
        var cnt = 0
        for (y in 0 until n) {
            val dy = wrapDist(y - by)
            for (x in 0 until n) {
                val dx = wrapDist(x - bx)
                if (dy <= 3 && dx <= 3) continue
                val v = rr[y * n + x]
                sum += v
                sum2 += v * v
                cnt++
            }
        }
        val mu = sum / cnt
        val sd = sqrt(maxOf(1e-12, sum2 / cnt - mu * mu))
        val psr = (best - mu) / sd

        // Subpixel: centroide 3x3 ao redor do pico (só valores acima da média do ruído).
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        for (j in -1..1) {
            for (i in -1..1) {
                val yy = (by + j + n) % n
                val xx = (bx + i + n) % n
                val w = maxOf(0.0, rr[yy * n + xx] - mu)
                sw += w
                sx += w * i
                sy += w * j
            }
        }
        var fx = bx.toDouble()
        var fy = by.toDouble()
        if (sw > 0.0) {
            fx += sx / sw
            fy += sy / sw
        }
        // Desembrulha: índices acima de n/2 são deslocamentos negativos.
        if (fx > n / 2.0) fx -= n
        if (fy > n / 2.0) fy -= n

        out.dx = fx
        out.dy = fy
        out.peak = best
        out.psr = psr
        out.valid = best >= minPeak && psr >= minPsr
        return out.valid
    }

    private fun wrapDist(d: Int): Int {
        val a = if (d < 0) -d else d
        return if (a > n / 2) n - a else a
    }

    private fun swapSpectra() {
        val a = pr; pr = cr; cr = a
        val b = pi; pi = ci; ci = b
    }
}
