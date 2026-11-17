package com.sensi.observer.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Transforma a linha do tempo (quadro a quadro) em medidas de jogabilidade:
 * arrastos de mira (amplitude, velocidade, erro para mais ou para menos), tiros e rajadas,
 * acertos (corpo/cabeça), puxada de capa, tremor ao atirar, FPS e uma nota por mira.
 *
 * Tudo é calculado em alturas de tela (H), H/s e ms; nada depende da resolução.
 */
object SessionAnalyzer {

    // Movimento intencional (H/s). Entra acima de START e sai abaixo de END.
    const val START_SPEED = 0.30
    const val END_SPEED = 0.12
    private const val MIN_SEG_MS = 24.0
    private const val MIN_SEG_AMP = 0.004
    private const val MERGE_GAP_MS = 40.0
    private const val MAX_GAP_MS = 120.0

    // Um arrasto de mira de verdade (flick) tem pelo menos esta amplitude (H).
    private const val MIN_MAIN_AMP = 0.06
    private const val GROUP_GAP_MS = 300.0
    private const val SETTLE_WINDOW_MS = 450.0

    private const val BURST_GAP_MS = 300.0
    private const val HIT_AFTER_MS = 400.0
    private const val LINK_BEFORE_MS = 1500.0

    /** Tremor de 6 px numa tela de 1080 px de altura = índice 1. */
    private const val JITTER_FULL_H = 0.0055

    private const val PROFILE_BIN_MS = 8.0
    const val PROFILE_BINS = 50

    class Seg(
        val t0: Double,
        val t1: Double,
        val dx: Double,
        val dy: Double,
        val peak: Double,
        val tPeak: Double,
        /** Teve quadros sem leitura dentro (ou colados): movimento rápido demais para medir direito. */
        val bad: Boolean = false
    ) {
        val amp: Double get() = hypot(dx, dy)
        val dur: Double get() = t1 - t0
    }

    class Acq(
        val main: Seg,
        val ncorr: Int,
        val same: Double,
        val opp: Double,
        val lat: Double,
        val settleEnd: Double,
        val scope: Int,
        /** Medição não confiável (giro rápido demais): fica fora das estatísticas de erro. */
        val bad: Boolean = false
    ) {
        /** + = o arrasto ficou curto (precisou continuar), - = passou do ponto (precisou voltar). */
        val err: Double get() = ((same - opp) / main.amp).coerceIn(-1.0, 1.0)
    }

    private class Press(val t0: Double, val t1: Double)

    private class RawBurst(val t0: Double, val t1: Double, val presses: Int, val scope: Int)

    private class Hit(val t: Double, val head: Boolean, val scope: Int)

    // ------------------------------------------------------------------------------ principal

    fun analyze(
        tl: Timeline,
        device: DeviceInfo,
        sens: IntArray,
        fingers: Int,
        startEpochMs: Long,
        gains: List<GainRecord>,
        fireCalibrated: Boolean
    ): SessionSummary {
        val n = tl.size
        val dur = if (n > 0) tl.t[n - 1] else 0.0

        val vel = Velocity(tl)
        val segs = segments(tl, vel)
        val presses = presses(tl)
        val raw = bursts(tl, presses)
        val acqs = acquisitions(tl, segs, raw.map { it.t0 })
        val hits = hits(tl)
        val idleJ = idleJitter(tl, vel)

        // --- rajadas com acertos, puxada, tremor e ligação com o arrasto anterior
        val used = BooleanArray(acqs.size)
        val bursts = ArrayList<Burst>()
        val burstJit = ArrayList<Double>()
        val burstAcqIdx = ArrayList<Int>()
        for (b in raw) {
            var hb = 0
            var hh = 0
            var first = -1.0
            for (h in hits) {
                if (h.t >= b.t0 - 80.0 && h.t <= b.t1 + HIT_AFTER_MS) {
                    if (h.head) hh++ else hb++
                    if (first < 0.0) first = max(0.0, h.t - b.t0)
                }
            }
            // Arrasto que levou à mira: o último que terminou antes (ou logo depois) do início do tiro.
            var link = -1
            for (k in acqs.indices.reversed()) {
                val m = acqs[k].main
                if (m.t1 <= b.t0 + 150.0 && b.t0 - m.t1 <= LINK_BEFORE_MS) {
                    link = k
                    break
                }
            }
            var stick = -1.0
            var amp = 0.0
            if (link >= 0 && !used[link] && !acqs[link].bad) {
                used[link] = true
                // Grude: da mira estabilizada até o primeiro tiro.
                stick = max(0.0, b.t0 - acqs[link].settleEnd)
                amp = acqs[link].main.amp
            } else {
                link = -1
            }
            val p250 = -sumLy(tl, b.t0, min(b.t1, b.t0 + 250.0))
            val pAll = -sumLy(tl, b.t0, b.t1)
            val jit = jitter(tl, b.t0 + 100.0, b.t1, idleJ)
            bursts.add(Burst(b.t0, b.t1 - b.t0, b.scope, b.presses, hb, hh, first, stick, amp, p250, pAll))
            burstJit.add(jit)
            burstAcqIdx.add(link)
        }

        // --- estatísticas por mira
        val list = ArrayList<ScopeStats>()
        for (sc in 0 until Scope.COUNT) {
            val a = acqs.filter { it.scope == sc && !it.bad }
            val skipped = acqs.count { it.scope == sc && it.bad }
            val bi = bursts.indices.filter { bursts[it].scope == sc }
            val bs = bi.map { bursts[it] }
            val hs = hits.filter { it.scope == sc }
            if (a.isEmpty() && bs.isEmpty() && hs.isEmpty() && skipped == 0) continue
            list.add(scopeStats(sc, a, bs, bi.map { burstJit[it] }, hs, skipped))
        }

        // --- FPS e qualidade da leitura
        var sumDt = 0.0
        val dts = ArrayList<Double>()
        var validFrames = 0
        var activeMs = 0.0
        for (i in 1 until n) {
            val dt = tl.t[i] - tl.t[i - 1]
            if (dt > 0.0 && dt < 250.0) {
                sumDt += dt
                dts.add(dt)
                if (tl.flag(i, Flag.VALID)) activeMs += dt
            }
            if (tl.flag(i, Flag.VALID)) validFrames++
        }
        dts.sort()
        val fpsMean = if (dts.isNotEmpty()) 1000.0 * dts.size / sumDt else Double.NaN
        val p95 = if (dts.isNotEmpty()) dts[min(dts.size - 1, (dts.size * 0.95).toInt())] else Double.NaN
        val fpsP5 = if (p95 > 0) 1000.0 / p95 else Double.NaN
        val validPct = if (n > 0) 100.0 * validFrames / n else 0.0

        return SessionSummary(
            id = startEpochMs,
            durationMs = dur,
            activeMs = activeMs,
            device = device,
            sens = sens.copyOf(),
            fingers = fingers,
            fpsMean = fpsMean,
            fpsP5 = fpsP5,
            validPct = validPct,
            fireCalibrated = fireCalibrated,
            hitDetected = hits.isNotEmpty(),
            gains = gains,
            scopes = list,
            bursts = if (bursts.size > 1500) bursts.subList(0, 1500) else bursts,
            flickProfile = profile(tl, vel, acqs),
            noteIdleJitterH = idleJ
        )
    }

    // ------------------------------------------------------------------------------ velocidade

    /** Velocidades quadro a quadro (H/s) e deslocamento efetivo (com buracos curtos preenchidos). */
    class Velocity(tl: Timeline) {
        val n = tl.size
        val ex = DoubleArray(n)
        val ey = DoubleArray(n)
        val sp = DoubleArray(n)
        val ss = DoubleArray(n)

        /** true = quadro sem leitura válida de movimento (ou salto de tempo). */
        val inv = BooleanArray(n)

        init {
            var holdVx = 0.0
            var holdVy = 0.0
            var invalidRun = 0
            for (i in 1 until n) {
                val dt = tl.t[i] - tl.t[i - 1]
                if (dt <= 0.0 || dt > MAX_GAP_MS) {
                    invalidRun = 0
                    inv[i] = true
                    continue
                }
                if (!tl.flag(i, Flag.VALID)) inv[i] = true
                if (tl.flag(i, Flag.VALID)) {
                    ex[i] = tl.lx[i].toDouble()
                    ey[i] = tl.ly[i].toDouble()
                    holdVx = ex[i] / dt * 1000.0
                    holdVy = ey[i] / dt * 1000.0
                    invalidRun = 0
                } else {
                    invalidRun++
                    // Borrão de movimento rápido: segura a velocidade anterior por até 2 quadros.
                    if (invalidRun <= 2 && hypot(holdVx, holdVy) > START_SPEED) {
                        ex[i] = holdVx * dt / 1000.0
                        ey[i] = holdVy * dt / 1000.0
                    } else {
                        holdVx = 0.0
                        holdVy = 0.0
                    }
                }
                sp[i] = hypot(ex[i], ey[i]) / dt * 1000.0
            }
            for (i in 0 until n) {
                val a = if (i > 0) sp[i - 1] else sp[i]
                val c = if (i < n - 1) sp[i + 1] else sp[i]
                ss[i] = (a + 2.0 * sp[i] + c) / 4.0
            }
        }
    }

    // ------------------------------------------------------------------------------ segmentos

    private fun segments(tl: Timeline, v: Velocity): List<Seg> {
        val n = tl.size
        val out = ArrayList<Seg>()
        var inSeg = false
        var s0 = 0
        var low = 0
        var prevEnd = 0

        fun close(from: Int, to: Int) {
            if (to <= from) return
            var dx = 0.0
            var dy = 0.0
            var peak = 0.0
            var pk = from
            for (i in from..to) {
                dx += v.ex[i]
                dy += v.ey[i]
                if (v.ss[i] > peak) {
                    peak = v.ss[i]
                    pk = i
                }
            }
            val t0 = tl.t[max(0, from - 1)]
            val t1 = tl.t[to]
            var bad = false
            for (i in max(1, from - 2)..min(n - 1, to + 2)) if (v.inv[i]) bad = true
            out.add(Seg(t0, t1, dx, dy, peak, tl.t[pk] - t0, bad))
            prevEnd = to
        }

        for (i in 1 until n) {
            val gap = tl.t[i] - tl.t[i - 1] > MAX_GAP_MS
            if (!inSeg) {
                if (!gap && v.ss[i] >= START_SPEED) {
                    inSeg = true
                    low = 0
                    s0 = i
                    // Inclui o começo da subida de velocidade (abaixo do limite de entrada).
                    var back = 0
                    while (s0 - 1 > prevEnd && s0 - 1 >= 1 && back < 3 && v.ss[s0 - 1] >= END_SPEED) {
                        s0--
                        back++
                    }
                }
            } else {
                if (gap) {
                    close(s0, i - 1 - low)
                    inSeg = false
                    continue
                }
                if (v.ss[i] < END_SPEED) {
                    low++
                    if (low >= 2) {
                        close(s0, i - low)
                        inSeg = false
                    }
                } else {
                    low = 0
                }
            }
        }
        if (inSeg) close(s0, n - 1 - low)

        // Junta pedaços separados por pausas muito curtas e descarta os insignificantes.
        val merged = ArrayList<Seg>()
        for (s in out) {
            val last = merged.lastOrNull()
            if (last != null && s.t0 - last.t1 < MERGE_GAP_MS) {
                val tp = if (s.peak > last.peak) s.tPeak + (s.t0 - last.t0) else last.tPeak
                merged[merged.size - 1] = Seg(
                    last.t0, s.t1, last.dx + s.dx, last.dy + s.dy, max(last.peak, s.peak), tp,
                    last.bad || s.bad
                )
            } else {
                merged.add(s)
            }
        }
        return merged.filter { it.dur >= MIN_SEG_MS && it.amp >= MIN_SEG_AMP }
    }

    // ------------------------------------------------------------------------------ arrastos de mira

    /** Arrastos de mira de uma linha do tempo (para testes e para o gráfico do relatório). */
    fun detect(tl: Timeline): List<Acq> {
        val v = Velocity(tl)
        val raw = bursts(tl, presses(tl))
        return acquisitions(tl, segments(tl, v), raw.map { it.t0 })
    }

    private fun acquisitions(tl: Timeline, segs: List<Seg>, fireStarts: List<Double>): List<Acq> {
        val out = ArrayList<Acq>()
        var i = 0
        while (i < segs.size) {
            var j = i
            while (j + 1 < segs.size && segs[j + 1].t0 - segs[j].t1 < GROUP_GAP_MS) j++
            val group = segs.subList(i, j + 1)
            i = j + 1

            val main = group.maxByOrNull { it.amp } ?: continue
            if (main.amp < MIN_MAIN_AMP) continue
            val ux = main.dx / main.amp
            val uy = main.dy / main.amp
            var same = 0.0
            var opp = 0.0
            var lat = 0.0
            var n = 0
            var end = main.t1
            var bad = main.bad
            // Depois que o tiro começa, o movimento é puxada/acompanhamento, não ajuste de mira.
            var fireAt = Double.MAX_VALUE
            for (f in fireStarts) {
                if (f >= main.t0 - 100.0) {
                    if (f - main.t1 <= LINK_BEFORE_MS) fireAt = f
                    break
                }
            }
            for (s in group) {
                if (s === main) continue
                if (s.t0 < main.t1 - 5.0) continue
                if (s.t0 - main.t1 > SETTLE_WINDOW_MS) continue
                if (s.t0 >= fireAt) continue
                if (s.bad) bad = true
                val along = s.dx * ux + s.dy * uy
                val perp = abs(-s.dx * uy + s.dy * ux)
                if (along >= 0) same += along else opp += -along
                lat += perp
                n++
                end = max(end, s.t1)
            }
            val sc = scopeAtTime(tl, main.t0)
            out.add(Acq(main, n, same, opp, lat, end, sc, bad))
        }
        return out
    }

    private fun scopeAtTime(tl: Timeline, tMs: Double): Int {
        val i = indexAtOrAfter(tl, tMs)
        return if (i < 0) 0 else tl.scopeAt(min(i, tl.size - 1)).coerceIn(0, Scope.COUNT - 1)
    }

    /** Primeiro índice com t >= tMs (busca binária), ou -1 se a linha do tempo está vazia. */
    fun indexAtOrAfter(tl: Timeline, tMs: Double): Int {
        var lo = 0
        var hi = tl.size - 1
        if (hi < 0) return -1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tl.t[mid] < tMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // ------------------------------------------------------------------------------ tiros

    private fun presses(tl: Timeline): List<Press> {
        val out = ArrayList<Press>()
        var on = false
        var s = 0.0
        var lastOn = 0.0
        for (i in 0 until tl.size) {
            val f = tl.flag(i, Flag.FIRE)
            if (f) {
                if (!on) {
                    on = true
                    s = tl.t[i]
                }
                lastOn = tl.t[i]
            } else if (on) {
                // Ignora soltar por menos de 50 ms (falha de leitura), senão fecha o toque.
                if (tl.t[i] - lastOn > 50.0) {
                    out.add(Press(s, lastOn))
                    on = false
                }
            }
        }
        if (on) out.add(Press(s, lastOn))
        return out
    }

    private fun bursts(tl: Timeline, presses: List<Press>): List<RawBurst> {
        val out = ArrayList<RawBurst>()
        var i = 0
        while (i < presses.size) {
            var j = i
            while (j + 1 < presses.size && presses[j + 1].t0 - presses[j].t1 <= BURST_GAP_MS) j++
            val t0 = presses[i].t0
            out.add(RawBurst(t0, presses[j].t1, j - i + 1, scopeAtTime(tl, t0)))
            i = j + 1
        }
        return out
    }

    private fun hits(tl: Timeline): List<Hit> {
        val out = ArrayList<Hit>()
        for (i in 0 until tl.size) {
            val b = tl.flag(i, Flag.HIT_BODY)
            val h = tl.flag(i, Flag.HIT_HEAD)
            if (b || h) out.add(Hit(tl.t[i], h, tl.scopeAt(i).coerceIn(0, Scope.COUNT - 1)))
        }
        return out
    }

    /** Soma do movimento vertical da câmera entre t0 e t1 (H). Negativo = mira subiu. */
    private fun sumLy(tl: Timeline, t0: Double, t1: Double): Double {
        var s = 0.0
        var i = max(0, indexAtOrAfter(tl, t0))
        while (i < tl.size && tl.t[i] <= t1) {
            if (tl.flag(i, Flag.VALID)) s += tl.ly[i]
            i++
        }
        return s
    }

    // ------------------------------------------------------------------------------ tremor

    /**
     * Tremor: quanto a posição da câmera oscila (H, valor eficaz) em relação à sua própria
     * média móvel de 150 ms, entre t0 e t1. NaN se há quadros de menos.
     */
    private fun rawJitter(tl: Timeline, t0: Double, t1: Double): Double {
        if (t1 - t0 < 160.0) return Double.NaN
        var a = max(0, indexAtOrAfter(tl, t0 - 80.0))
        val z = min(tl.size - 1, indexAtOrAfter(tl, t1 + 80.0))
        if (z - a < 8) return Double.NaN
        val m = z - a + 1
        val px = DoubleArray(m)
        val py = DoubleArray(m)
        var cx = 0.0
        var cy = 0.0
        for (k in 0 until m) {
            val i = a + k
            if (tl.flag(i, Flag.VALID)) {
                cx += tl.lx[i]
                cy += tl.ly[i]
            }
            px[k] = cx
            py[k] = cy
        }
        var sum = 0.0
        var cnt = 0
        var lo = 0
        var hi = 0
        var sx = 0.0
        var sy = 0.0
        var win = 0
        for (k in 0 until m) {
            val tk = tl.t[a + k]
            while (hi < m && tl.t[a + hi] <= tk + 75.0) {
                sx += px[hi]; sy += py[hi]; win++; hi++
            }
            while (lo < m && tl.t[a + lo] < tk - 75.0) {
                sx -= px[lo]; sy -= py[lo]; win--; lo++
            }
            if (tk >= t0 && tk <= t1 && win > 2) {
                val rx = px[k] - sx / win
                val ry = py[k] - sy / win
                sum += rx * rx + ry * ry
                cnt++
            }
        }
        if (cnt < 6) return Double.NaN
        return sqrt(sum / cnt)
    }

    /** Tremor medido com a câmera parada (sem tiro nem movimento): é o ruído de medida + mão. */
    private fun idleJitter(tl: Timeline, v: Velocity): Double {
        var best = 0.0
        var count = 0
        var sumSq = 0.0
        var i = 0
        while (i < tl.size && count < 12) {
            // Procura um trecho de pelo menos 500 ms sem tiro e sem movimento.
            var j = i
            while (j + 1 < tl.size && !tl.flag(j + 1, Flag.FIRE) && v.ss[j + 1] < END_SPEED &&
                tl.flag(j + 1, Flag.VALID)
            ) j++
            if (tl.t[j] - tl.t[i] >= 500.0) {
                val r = rawJitter(tl, tl.t[i] + 80.0, tl.t[j] - 80.0)
                if (!r.isNaN()) {
                    sumSq += r * r
                    count++
                }
                i = j + 1
            } else {
                i++
            }
        }
        if (count > 0) best = sqrt(sumSq / count)
        return best
    }

    private fun jitter(tl: Timeline, t0: Double, t1: Double, idle: Double): Double {
        val r = rawJitter(tl, t0, t1)
        if (r.isNaN()) return Double.NaN
        val net = sqrt(max(0.0, r * r - idle * idle))
        return (net / JITTER_FULL_H).coerceIn(0.0, 1.0)
    }

    // ------------------------------------------------------------------------------ por mira

    private fun scopeStats(
        sc: Int,
        a: List<Acq>,
        b: List<Burst>,
        jit: List<Double>,
        h: List<Hit>,
        skipped: Int
    ): ScopeStats {
        val hits = h.size
        val head = h.count { it.head }
        val withHit = b.count { it.hitsBody + it.hitsHead > 0 }
        val headRate = if (hits > 0) head.toDouble() / hits else Double.NaN
        val hitRate = if (b.isNotEmpty()) withHit.toDouble() / b.size else Double.NaN

        val errMean = mean(a.map { it.err })
        val over = if (a.isNotEmpty()) a.count { it.opp / it.main.amp >= 0.12 }.toDouble() / a.size else Double.NaN
        val under = if (a.isNotEmpty()) a.count { it.same / it.main.amp >= 0.12 }.toDouble() / a.size else Double.NaN
        val jitIdx = mean(jit.filter { !it.isNaN() })
        val settle = median(a.map { it.settleEnd - it.main.t1 })

        val score = score(hits, head, b.size, withHit, a.size, errMean, settle, jitIdx)

        return ScopeStats(
            scope = sc,
            skipped = skipped,
            acq = a.size,
            bursts = b.size,
            hits = hits,
            headHits = head,
            ampMed = median(a.map { it.main.amp }),
            peakMed = median(a.map { it.main.peak }),
            durMed = median(a.map { it.main.dur }),
            tPeakMed = median(a.map { it.main.tPeak }),
            errMean = errMean,
            overRate = over,
            underRate = under,
            corrPerAcq = mean(a.map { it.ncorr.toDouble() }),
            settleMed = settle,
            stickMed = median(b.filter { it.stickMs >= 0 }.map { it.stickMs }),
            fireMed = median(b.map { it.durMs }),
            firstHitMed = median(b.filter { it.firstHitMs >= 0 }.map { it.firstHitMs }),
            pull250Mean = mean(b.map { it.pull250H }),
            pullAllMean = mean(b.map { it.pullAllH }),
            jitterIdx = jitIdx,
            headRate = headRate,
            hitRate = hitRate,
            score = score
        )
    }

    /** Nota 0 a 100. Poucas amostras puxam cada componente para o meio (não premia sorte). */
    fun score(
        hits: Int,
        head: Int,
        bursts: Int,
        burstsWithHit: Int,
        acq: Int,
        errMean: Double,
        settleMed: Double,
        jitterIdx: Double
    ): Double {
        var wSum = 0.0
        var acc = 0.0
        fun add(w: Double, v: Double) {
            wSum += w
            acc += w * v
        }
        if (hits > 0) add(0.35, (head + 0.5 * 6) / (hits + 6))
        if (bursts > 0) add(0.15, (burstsWithHit + 0.5 * 4) / (bursts + 4))
        if (acq > 0 && !errMean.isNaN()) {
            val c = 1.0 - min(1.0, abs(errMean) / 0.35)
            add(0.25, (c * acq + 0.5 * 6) / (acq + 6))
        }
        if (!settleMed.isNaN()) add(0.10, 1.0 - ((settleMed - 120.0) / 400.0).coerceIn(0.0, 1.0))
        if (!jitterIdx.isNaN()) add(0.15, 1.0 - jitterIdx)
        return if (wSum <= 0.0) Double.NaN else 100.0 * acc / wSum
    }

    // ------------------------------------------------------------------------------ perfil do arrasto

    /** Velocidade média (H/s) a cada 8 ms desde o início dos arrastos de mira, lida da linha do tempo. */
    private fun profile(tl: Timeline, v: Velocity, acqs: List<Acq>): FloatArray {
        val sum = DoubleArray(PROFILE_BINS)
        val cnt = IntArray(PROFILE_BINS)
        for (aq in acqs) {
            val m = aq.main
            var i = max(1, indexAtOrAfter(tl, m.t0))
            while (i < tl.size && tl.t[i] <= m.t1 + 120.0) {
                val k = ((tl.t[i] - m.t0) / PROFILE_BIN_MS).toInt()
                if (k in 0 until PROFILE_BINS) {
                    sum[k] += v.sp[i]
                    cnt[k]++
                }
                i++
            }
        }
        return FloatArray(PROFILE_BINS) { if (cnt[it] > 0) (sum[it] / cnt[it]).toFloat() else 0f }
    }

    // ------------------------------------------------------------------------------ utilitários

    fun mean(l: List<Double>): Double = if (l.isEmpty()) Double.NaN else l.sum() / l.size

    fun median(l: List<Double>): Double {
        if (l.isEmpty()) return Double.NaN
        val s = l.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}
