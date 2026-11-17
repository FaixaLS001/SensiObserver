package com.sensi.observer.engine

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.floor

/**
 * Onde olhar em cada quadro, em pixels da imagem capturada (não da tela real).
 * O serviço recalcula isto a partir das posições marcadas pelo usuário.
 */
class Layout {
    var capW = 0
    var capH = 0

    // Área de leitura do movimento (quadrado).
    var readCx = 0f
    var readCy = 0f
    var readSize = 0f

    // Mira do jogo e metade do lado da região onde o marcador de acerto aparece.
    var aimX = 0f
    var aimY = 0f
    var hitHalf = 0f

    // Botão de tiro (círculo).
    var fireX = 0f
    var fireY = 0f
    var fireRadius = 0f

    // Retângulo a ignorar (a bolinha flutuante aparece na captura e não pode contar como cenário).
    var hasMask = false
    var maskL = 0
    var maskT = 0
    var maskR = 0
    var maskB = 0
}

/** O que se extrai de um quadro. */
class FrameFeatures(val n: Int) {
    val patch = FloatArray(n * n)
    var fireR = 0f
    var fireG = 0f
    var fireB = 0f
    var redDiag = 0
    var whiteDiag = 0
    var redRing = 0
    var whiteRing = 0

    /** Parte do recorte de movimento que ficou coberta pela máscara (0 a 1). */
    var maskedFrac = 0f
}

/**
 * Lê um quadro RGBA_8888 (como o ImageReader entrega) e tira dele só o necessário:
 * o recorte de luminância para medir o movimento, a cor do botão de tiro e a contagem de
 * pixels vermelhos/brancos ao redor da mira (marcador de acerto).
 * Reaproveita os vetores: nada é alocado por quadro.
 */
class FrameReader(private val n: Int = 128) {

    private val xi = IntArray(n)
    private val xw = FloatArray(n)
    private val maskedX = BooleanArray(n)

    private fun luma(b: ByteBuffer, i: Int): Int {
        val r = b.get(i).toInt() and 0xFF
        val g = b.get(i + 1).toInt() and 0xFF
        val bl = b.get(i + 2).toInt() and 0xFF
        return (77 * r + 150 * g + 29 * bl) shr 8
    }

    fun read(buf: ByteBuffer, rowStride: Int, pixelStride: Int, lay: Layout, out: FrameFeatures) {
        readPatch(buf, rowStride, pixelStride, lay, out)
        readFire(buf, rowStride, pixelStride, lay, out)
        readHit(buf, rowStride, pixelStride, lay, out)
    }

    // ------------------------------------------------------------------ movimento

    private fun readPatch(buf: ByteBuffer, rs: Int, ps: Int, lay: Layout, out: FrameFeatures) {
        val size = lay.readSize
        val scale = size / n
        val left = lay.readCx - size / 2f
        val top = lay.readCy - size / 2f
        val maxX = lay.capW - 2
        val maxY = lay.capH - 2

        for (x in 0 until n) {
            val fx = left + (x + 0.5f) * scale - 0.5f
            var x0 = floor(fx).toInt()
            var w = fx - x0
            if (x0 < 0) { x0 = 0; w = 0f }
            if (x0 > maxX) { x0 = maxX; w = 0f }
            xi[x] = x0
            xw[x] = w
            maskedX[x] = lay.hasMask && (x0 + 0.5f) >= lay.maskL && (x0 + 0.5f) < lay.maskR
        }

        var sum = 0.0
        var cnt = 0
        var masked = 0
        val p = out.patch
        for (y in 0 until n) {
            val fy = top + (y + 0.5f) * scale - 0.5f
            var y0 = floor(fy).toInt()
            var wy = fy - y0
            if (y0 < 0) { y0 = 0; wy = 0f }
            if (y0 > maxY) { y0 = maxY; wy = 0f }
            val rowMasked = lay.hasMask && (y0 + 0.5f) >= lay.maskT && (y0 + 0.5f) < lay.maskB
            val r0 = y0 * rs
            val r1 = r0 + rs
            for (x in 0 until n) {
                if (rowMasked && maskedX[x]) {
                    p[y * n + x] = Float.NaN
                    masked++
                    continue
                }
                val i0 = r0 + xi[x] * ps
                val i1 = r1 + xi[x] * ps
                val wx = xw[x]
                val a = luma(buf, i0)
                val b = luma(buf, i0 + ps)
                val c = luma(buf, i1)
                val d = luma(buf, i1 + ps)
                val v = (a * (1f - wx) + b * wx) * (1f - wy) + (c * (1f - wx) + d * wx) * wy
                p[y * n + x] = v
                sum += v
                cnt++
            }
        }
        out.maskedFrac = masked.toFloat() / (n * n)
        if (masked > 0) {
            // O que ficou escondido vira a média: não cria borda nem movimento falso.
            val mean = if (cnt > 0) (sum / cnt).toFloat() else 128f
            for (i in 0 until n * n) if (p[i].isNaN()) p[i] = mean
        }
    }

    // ------------------------------------------------------------------ botão de tiro

    private fun readFire(buf: ByteBuffer, rs: Int, ps: Int, lay: Layout, out: FrameFeatures) {
        val r = lay.fireRadius
        if (r < 2f) {
            out.fireR = 0f; out.fireG = 0f; out.fireB = 0f
            return
        }
        val step = maxOf(1, (r / 6f).toInt())
        val cx = lay.fireX.toInt()
        val cy = lay.fireY.toInt()
        val ri = r.toInt()
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var c = 0
        var dy = -ri
        while (dy <= ri) {
            val y = cy + dy
            if (y in 0 until lay.capH) {
                var dx = -ri
                while (dx <= ri) {
                    val x = cx + dx
                    if (x in 0 until lay.capW && dx * dx + dy * dy <= ri * ri) {
                        val i = y * rs + x * ps
                        sr += buf.get(i).toInt() and 0xFF
                        sg += buf.get(i + 1).toInt() and 0xFF
                        sb += buf.get(i + 2).toInt() and 0xFF
                        c++
                    }
                    dx += step
                }
            }
            dy += step
        }
        if (c == 0) {
            out.fireR = 0f; out.fireG = 0f; out.fireB = 0f
        } else {
            out.fireR = sr.toFloat() / c
            out.fireG = sg.toFloat() / c
            out.fireB = sb.toFloat() / c
        }
    }

    // ------------------------------------------------------------------ acerto

    /**
     * Conta pixels "vermelho vivo" (acerto na cabeça) e "branco puro" (acerto no corpo) ao redor
     * da mira. O marcador de acerto é um X, então as diagonais contam à parte do anel inteiro
     * (a mira em si fica nos eixos e não deve contar como acerto).
     */
    private fun readHit(buf: ByteBuffer, rs: Int, ps: Int, lay: Layout, out: FrameFeatures) {
        val half = lay.hitHalf
        var redD = 0
        var whiteD = 0
        var redA = 0
        var whiteA = 0
        if (half >= 3f) {
            val cx = lay.aimX.toInt()
            val cy = lay.aimY.toInt()
            val hi = half.toInt()
            val rin = (0.2f * half)
            val rin2 = rin * rin
            val rout2 = half * half
            for (dy in -hi..hi) {
                val y = cy + dy
                if (y < 0 || y >= lay.capH) continue
                for (dx in -hi..hi) {
                    val x = cx + dx
                    if (x < 0 || x >= lay.capW) continue
                    val d2 = (dx * dx + dy * dy).toFloat()
                    if (d2 < rin2 || d2 > rout2) continue
                    val i = y * rs + x * ps
                    val r = buf.get(i).toInt() and 0xFF
                    val g = buf.get(i + 1).toInt() and 0xFF
                    val b = buf.get(i + 2).toInt() and 0xFF
                    val isRed = r >= 170 && r - g >= 90 && r - b >= 90
                    val mx = maxOf(r, maxOf(g, b))
                    val mn = minOf(r, minOf(g, b))
                    val isWhite = mn >= 225 && mx - mn <= 35
                    if (!isRed && !isWhite) continue
                    val ax = abs(dx)
                    val ay = abs(dy)
                    val big = maxOf(ax, ay)
                    val small = minOf(ax, ay)
                    val diag = small * 100 >= 35 * big
                    if (isRed) {
                        redA++
                        if (diag) redD++
                    } else {
                        whiteA++
                        if (diag) whiteD++
                    }
                }
            }
        }
        out.redDiag = redD
        out.whiteDiag = whiteD
        out.redRing = redA
        out.whiteRing = whiteA
    }
}
