package com.sensi.observer.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Recomendação para uma mira. */
class ScopeRec(
    val scope: Int,
    val current: Int,
    val suggested: Int,
    val deltaPct: Int,
    /** 0 = baixa, 1 = média, 2 = alta. */
    val confidence: Int,
    /** "ajustar", "ok" (estabilizada) ou "sem dados". */
    val status: String,
    val reason: String,
    val samples: Int
)

class DpiRec(
    val current: Int,
    val suggested: Int,
    val text: String,
    /** DPI a testar na próxima partida (0 = nenhum teste sugerido). */
    val experiment: Int
)

class ResRec(val suggestedW: Int, val suggestedH: Int, val native: Boolean, val text: String)

/** Valores medidos no mesmo formato dos campos do Gerador de sensibilidade. */
class GeneratorProfile(
    val stickTimeMs: Int?,
    val fireTimeMs: Int?,
    val headshotRatio: Int?,
    val pullMode: String,       // aggressive | balanced | precise
    val pullSpeed: String,      // very_fast | fast | medium | slow
    val closeRange: String,     // high | medium | low
    val longRange: String,
    val refreshRate: Int,
    val fingers: Int
)

class Recommendation(
    val scopes: List<ScopeRec>,
    val dpi: DpiRec,
    val resolution: ResRec,
    val tips: List<String>,
    val generator: GeneratorProfile,
    val level: Int,                 // 0 a 4
    val levelLabel: String,
    val converged: Boolean,
    val warnings: List<String>,
    val sessionsUsed: Int
)

/**
 * Decide o que mudar. A ideia central: se, depois de um arrasto, o jogador precisa voltar
 * (passou do alvo) ou continuar (ficou curto), a sensibilidade não casa com o movimento que ele
 * faz com a mão. O app corrige parte do erro a cada sessão, e a cada nova sessão o erro medido
 * mostra se acertou. Com várias sessões em sensibilidades diferentes, ajusta também uma curva
 * nota x sensibilidade e puxa a recomendação para o topo dela.
 */
object Advisor {
    const val MIN_ACQ = 10
    private const val DAMP = 0.6
    private val DPI_LIST = intArrayOf(320, 360, 400, 420, 440, 480, 540, 560, 600, 640)

    fun recommend(history: List<SessionSummary>, latest: SessionSummary, gains: List<GainRecord>): Recommendation {
        val group = (history.filter { it.id != latest.id && it.device.sameProfile(latest.device) } + latest)
            .sortedBy { it.id }
        val dev = latest.device
        val warnings = ArrayList<String>()
        val tips = ArrayList<String>()

        // --- qualidade da medição
        if (latest.validPct < 55.0) {
            warnings.add("A leitura de movimento só funcionou em ${latest.validPct.roundToInt()}% do tempo. Reposicione a área de leitura em um trecho do cenário sem HUD (marque na bolinha).")
        }
        if (!latest.fireCalibrated) {
            warnings.add("Botão de tiro sem calibração: o app adivinhou quando você atirou. Calibre pela bolinha para medir melhor o grude e a puxada.")
        }
        if (!latest.hitDetected) {
            warnings.add("Nenhum acerto foi detectado. Calibre o acerto (cabeça e corpo) pela bolinha, num boneco de treino, para medir a capa.")
        }
        if (!latest.fpsMean.isNaN() && latest.fpsP5 < 40.0) {
            warnings.add("O jogo oscilou até ${latest.fpsP5.roundToInt()} FPS. Isso atrapalha a mira mais que qualquer sensi.")
        }

        // --- por mira
        val recs = ArrayList<ScopeRec>()
        for (sc in Scope.values()) {
            recs.add(scopeRec(sc, group, latest, gains))
        }

        // --- capa
        val headRate = latest.headRate
        val level: Int
        val levelLabel: String
        if (latest.totalHits < 8 || headRate.isNaN()) {
            level = -1
            levelLabel = "Sem acertos suficientes"
        } else if (headRate >= 0.8) {
            level = 4; levelLabel = "Full capa"
        } else if (headRate >= 0.65) {
            level = 3; levelLabel = "Quase full capa"
        } else if (headRate >= 0.45) {
            level = 2; levelLabel = "Capa mediana"
        } else if (headRate >= 0.25) {
            level = 1; levelLabel = "Pouca capa"
        } else {
            level = 0; levelLabel = "Treinar capa"
        }

        val withData = recs.filter { it.status != "sem dados" }
        val converged = withData.isNotEmpty() && withData.all { it.status == "ok" }

        generalTips(latest, gains, tips)

        return Recommendation(
            scopes = recs,
            dpi = dpiRec(history + latest, group, latest, converged, gains),
            resolution = resRec(latest),
            tips = tips,
            generator = generatorProfile(latest),
            level = level,
            levelLabel = levelLabel,
            converged = converged,
            warnings = warnings,
            sessionsUsed = group.size
        )
    }

    // ------------------------------------------------------------------------------ por mira

    private fun scopeRec(sc: Scope, group: List<SessionSummary>, latest: SessionSummary, gains: List<GainRecord>): ScopeRec {
        val cur = latest.sens[sc.id].coerceIn(0, 200)
        val st = latest.stats(sc.id)
        val n = st?.acq ?: 0
        if (st == null || n < MIN_ACQ || st.errMean.isNaN()) {
            val skipped = st?.skipped ?: 0
            val extra = if (skipped > 0) " ($skipped giros rápidos demais ficaram de fora)" else ""
            return ScopeRec(
                sc.id, cur, cur, 0, 0, "sem dados",
                "Poucos arrastos medidos nesta mira ($n de $MIN_ACQ)$extra. Jogue mais com ela e marque a mira certa na bolinha.",
                n
            )
        }

        // Erro médio (encolhido quando há poucas amostras) e a correção parcial.
        val e = st.errMean * n / (n + 4.0)
        val fE = if (abs(e) < 0.05) 1.0 else (1.0 + e.coerceIn(-0.5, 0.8)).pow(DAMP)
        val jit = if (st.jitterIdx.isNaN()) 0.0 else st.jitterIdx
        val fJ = when {
            jit > 0.75 -> 0.94
            jit > 0.60 -> 0.97
            else -> 1.0
        }
        val a = GainEstimator.sensExponent(gains, sc.id, latest.device.densityDpi, latest.device.widthPx, latest.device.heightPx) ?: 1.0
        val sensFactor = (fE * fJ).pow(1.0 / a).coerceIn(0.75, 1.30)
        var target = cur * sensFactor

        // Curva nota x sensibilidade com várias sessões.
        val pts = group.mapNotNull { s ->
            val x = s.stats(sc.id)
            if (x != null && x.acq >= MIN_ACQ && !x.score.isNaN() && s.sens[sc.id] > 0) Triple(s.sens[sc.id].toDouble(), x.score, x.acq.toDouble()) else null
        }
        var curveNote = ""
        val vertex = if (pts.size >= 3) vertexOf(pts) else null
        if (vertex != null) {
            val w = (0.12 * pts.size).coerceAtMost(0.5)
            target = exp(w * ln(vertex) + (1 - w) * ln(target))
            curveNote = " O histórico de ${pts.size} sessões aponta o melhor resultado perto de ${vertex.roundToInt()}."
        }

        val suggested = target.roundToInt().coerceIn(sc.minSens, sc.maxSens)
        val delta = ((suggested.toDouble() / cur - 1.0) * 100.0).roundToInt()
        val stable = abs(delta) <= 3 && abs(e) < 0.07 && jit < 0.6
        val conf = when {
            n >= 30 && latest.validPct >= 70.0 -> 2
            n >= 15 -> 1
            else -> 0
        }

        val sb = StringBuilder()
        val pctE = (abs(st.errMean) * 100).roundToInt()
        if (stable) {
            sb.append("Estabilizada: depois do arrasto sua mira quase não precisa de ajuste (erro ${pctE}%).")
        } else {
            if (e <= -0.04) sb.append("Você passa do alvo e volta (erro médio de ${pctE}% do arrasto, em ${(st.overRate * 100).roundToInt()}% dos arrastos): a sensi está alta para o seu movimento.")
            else if (e >= 0.04) sb.append("Seu arrasto fica curto e você continua puxando (erro médio de ${pctE}%, em ${(st.underRate * 100).roundToInt()}% dos arrastos): a sensi está baixa para o seu movimento.")
            else sb.append("O erro do arrasto é pequeno (${pctE}%).")
            if (fJ < 1.0) sb.append(" A mira treme ao atirar (${(jit * 100).roundToInt()}%), então reduzi um pouco mais.")
        }
        sb.append(curveNote)
        return ScopeRec(sc.id, cur, suggested, delta, conf, if (stable) "ok" else "ajustar", sb.toString(), n)
    }

    /** Vértice da parábola nota(ln sens) ajustada por mínimos quadrados ponderados, se for um topo. */
    private fun vertexOf(pts: List<Triple<Double, Double, Double>>): Double? {
        val xs = pts.map { ln(it.first) }
        val lo = xs.min()
        val hi = xs.max()
        if (hi - lo < 0.06) return null
        val mx = xs.sum() / xs.size
        // Resolve [S4 S3 S2; S3 S2 S1; S2 S1 S0] [a b c] = [Sx2y Sxy Sy]
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (i in pts.indices) {
            val x = xs[i] - mx
            val y = pts[i].second
            val w = pts[i].third
            s0 += w; s1 += w * x; s2 += w * x * x; s3 += w * x * x * x; s4 += w * x * x * x * x
            t0 += w * y; t1 += w * x * y; t2 += w * x * x * y
        }
        val det = s4 * (s2 * s0 - s1 * s1) - s3 * (s3 * s0 - s1 * s2) + s2 * (s3 * s1 - s2 * s2)
        if (abs(det) < 1e-12) return null
        val a = (t2 * (s2 * s0 - s1 * s1) - s3 * (t1 * s0 - s1 * t0) + s2 * (t1 * s1 - s2 * t0)) / det
        val b = (s4 * (t1 * s0 - s1 * t0) - t2 * (s3 * s0 - s1 * s2) + s2 * (s3 * t0 - t1 * s2)) / det
        if (a >= -1e-6) return null
        val xv = -b / (2 * a) + mx
        val lim = 0.22
        if (xv < lo - lim || xv > hi + lim) return null
        return exp(xv)
    }

    // ------------------------------------------------------------------------------ DPI e resolução

    private fun dpiRec(all: List<SessionSummary>, group: List<SessionSummary>, latest: SessionSummary, converged: Boolean, gains: List<GainRecord>): DpiRec {
        val dev = latest.device
        val cur = dev.densityDpi
        val txt = StringBuilder("DPI atual: $cur. ")
        var suggested = cur
        var experiment = 0

        val best = bestDpi(all, latest)
        if (best != null) {
            suggested = best.first
            txt.append(best.second)
        } else if (!converged) {
            txt.append("Mantenha o DPI fixo enquanto a sensi não estabilizar: mudar os dois ao mesmo tempo atrapalha a comparação entre as partidas.")
        } else if (group.size < 3) {
            txt.append("A sensi já está estável. Jogue mais 2 partidas para confirmar antes de mexer no DPI.")
        } else {
            val next = nextDpiToTest(cur)
            experiment = next
            txt.append("A sensi estabilizou neste DPI. Para saber se outro DPI rende mais capa, teste $next em 2 partidas (Opções do desenvolvedor > Menor largura); o app compara as notas e diz qual ficou melhor.")
        }
        val a = GainEstimator.sensExponent(gains, 0, dev.densityDpi, dev.widthPx, dev.heightPx) ?: 1.0
        val n = GainEstimator.dpiExponent(gains, 0, dev.widthPx, dev.heightPx, a)
        if (n != null) {
            txt.append(" Pelas suas calibrações, cada +10% de DPI muda a resposta da câmera em cerca de ${(((1.1).pow(n) - 1) * 100).roundToInt()}%.")
        }
        return DpiRec(cur, suggested, txt.toString(), experiment)
    }

    private fun nextDpiToTest(cur: Int): Int {
        val lower = DPI_LIST.filter { it < cur }.maxOrNull()
        val higher = DPI_LIST.filter { it > cur }.minOrNull()
        return lower ?: higher ?: cur
    }

    /** Escolhe o DPI de melhor nota quando há sessões em DPIs diferentes (chamado pela tela de relatório). */
    fun bestDpi(all: List<SessionSummary>, latest: SessionSummary): Pair<Int, String>? {
        val same = all.filter { it.device.widthPx == latest.device.widthPx && it.device.heightPx == latest.device.heightPx }
        val byDpi = same.groupBy { it.device.densityDpi }
            .mapValues { (_, l) -> l.mapNotNull { s -> overallScore(s) } }
            .filter { it.value.size >= 2 }
        if (byDpi.size < 2) return null
        val means = byDpi.mapValues { it.value.average() }
        val best = means.maxByOrNull { it.value } ?: return null
        val cur = means[latest.device.densityDpi]
        val text = if (best.key == latest.device.densityDpi || cur == null)
            "O DPI ${best.key} é o que teve a melhor nota média (${best.value.roundToInt()}). Continue com ele."
        else
            "O DPI ${best.key} teve nota média ${best.value.roundToInt()} contra ${cur.roundToInt()} no DPI atual (${latest.device.densityDpi}). Vale trocar."
        return Pair(best.key, text)
    }

    private fun overallScore(s: SessionSummary): Double? {
        var w = 0.0
        var a = 0.0
        for (st in s.scopes) {
            if (st.score.isNaN() || st.acq < MIN_ACQ) continue
            w += st.acq
            a += st.score * st.acq
        }
        return if (w > 0) a / w else null
    }

    fun overallScoreOf(s: SessionSummary): Double = overallScore(s) ?: Double.NaN

    private fun resRec(latest: SessionSummary): ResRec {
        val d = latest.device
        val low = !latest.fpsMean.isNaN() && latest.fpsP5 < 45.0
        if (!low) {
            return ResRec(d.widthPx, d.heightPx, true, "Mantenha a resolução nativa (${d.widthPx}x${d.heightPx}). O FPS está estável.")
        }
        val w = (d.widthPx * 0.85).roundToInt() / 2 * 2
        val h = (d.heightPx * 0.85).roundToInt() / 2 * 2
        return ResRec(
            w, h, false,
            "O FPS caiu para ${latest.fpsP5.roundToInt()} nos piores momentos. Teste ${w}x${h} (cerca de 85%) ou reduza os gráficos do jogo; mira estável depende de FPS estável."
        )
    }

    // ------------------------------------------------------------------------------ dicas

    private fun generalTips(s: SessionSummary, gains: List<GainRecord>, tips: MutableList<String>) {
        val sts = s.scopes.filter { it.acq >= 4 }
        val stick = SessionAnalyzer.median(sts.map { it.stickMed }.filter { !it.isNaN() })
        val firstHit = SessionAnalyzer.median(sts.map { it.firstHitMed }.filter { !it.isNaN() })
        if (!stick.isNaN()) {
            if (stick > 280.0) tips.add("Você espera ${stick.roundToInt()} ms depois de estabilizar a mira para atirar. Quem fecha capa costuma atirar entre 120 e 220 ms: confie no arrasto e dispare mais cedo.")
            else if (stick < 70.0) tips.add("Você atira quase junto com o fim do arrasto (${stick.roundToInt()} ms). Dê uns 100 ms para a mira assentar e a capa tende a subir.")
            else tips.add("Seu grude (da mira parada até o tiro) está em ${stick.roundToInt()} ms: dentro do bom.")
        }
        if (!firstHit.isNaN()) tips.add("Do início do tiro ao primeiro acerto: ${firstHit.roundToInt()} ms.")
        val pulls = sts.map { it.pull250Mean }.filter { !it.isNaN() }
        if (pulls.isNotEmpty()) {
            val p = SessionAnalyzer.mean(pulls)
            val px = p * s.device.heightPx
            tips.add("Sua puxada de capa nos primeiros 250 ms do tiro sobe ${(p * 100).roundToInt()}% da altura da tela na câmera (${px.roundToInt()} px na tela).")
        }
        // Distância do dedo, quando há calibração.
        var any = false
        for (st in sts) {
            val g = GainEstimator.gainFor(gains, st.scope, s.sens[st.scope], s.device.densityDpi, s.device.widthPx, s.device.heightPx) ?: continue
            if (st.ampMed.isNaN()) continue
            val finger = st.ampMed * s.device.heightPx / g
            val mm = finger / s.device.pxPerMm()
            tips.add("${Scope.of(st.scope).label}: seu arrasto típico move o dedo ${mm.roundToInt()} mm (${finger.roundToInt()} px).")
            any = true
        }
        if (!any && sts.isNotEmpty()) {
            tips.add("Para ver a distância real do dedo em mm, faça a calibração de resposta pela bolinha (usa a acessibilidade só nesse teste).")
        }
        val sk = s.scopes.sumOf { it.skipped }
        if (sk > 0) tips.add("$sk giros rápidos demais para medir ficaram fora da conta. Com FPS mais alto o app mede mais deles.")
        val jit = SessionAnalyzer.mean(sts.map { it.jitterIdx }.filter { !it.isNaN() })
        if (!jit.isNaN() && jit > 0.6) tips.add("A mira treme bastante enquanto você atira. Apoiar o aparelho, soltar um pouco a pressão do dedo ou baixar a sensi nas miras com zoom ajuda.")
    }

    // ------------------------------------------------------------------------------ Gerador

    private fun generatorProfile(s: SessionSummary): GeneratorProfile {
        val sts = s.scopes
        val stick = SessionAnalyzer.median(sts.map { it.stickMed }.filter { !it.isNaN() })
        val fireT = SessionAnalyzer.median(sts.map { it.fireMed }.filter { !it.isNaN() })
        val head = s.headRate
        val acqTotal = sts.sumOf { it.acq }
        fun wmean(f: (ScopeStats) -> Double): Double {
            var w = 0.0
            var a = 0.0
            for (st in sts) {
                val v = f(st)
                if (v.isNaN() || st.acq == 0) continue
                w += st.acq
                a += v * st.acq
            }
            return if (w > 0) a / w else Double.NaN
        }
        val peak = SessionAnalyzer.median(sts.map { it.peakMed }.filter { !it.isNaN() })
        val err = wmean { it.errMean }
        val over = wmean { it.overRate }
        val corr = wmean { it.corrPerAcq }
        val jit = wmean { it.jitterIdx }

        val pullSpeed = when {
            peak.isNaN() -> "medium"
            peak >= 7.0 -> "very_fast"
            peak >= 4.5 -> "fast"
            peak >= 2.5 -> "medium"
            else -> "slow"
        }
        val pullMode = when {
            acqTotal < MIN_ACQ || err.isNaN() -> "balanced"
            over >= 0.4 || err <= -0.15 -> "aggressive"
            abs(err) < 0.07 && corr < 0.7 && (jit.isNaN() || jit < 0.5) -> "precise"
            else -> "balanced"
        }
        val totalB = sts.sumOf { it.bursts }
        var close = "medium"
        var long = "medium"
        if (totalB >= 5) {
            val shareClose = sts.filter { it.scope <= 1 }.sumOf { it.bursts }.toDouble() / totalB
            val shareLong = sts.filter { it.scope >= 2 }.sumOf { it.bursts }.toDouble() / totalB
            fun lvl(x: Double) = if (x >= 0.5) "high" else if (x >= 0.2) "medium" else "low"
            close = lvl(shareClose)
            long = lvl(shareLong)
        }
        val hz = s.device.refreshHz
        val refresh = intArrayOf(60, 90, 120, 144).minByOrNull { abs(it - hz) } ?: 60
        return GeneratorProfile(
            stickTimeMs = if (stick.isNaN()) null else stick.roundToInt().coerceIn(50, 800),
            fireTimeMs = if (fireT.isNaN()) null else fireT.roundToInt().coerceIn(50, 600),
            headshotRatio = if (head.isNaN()) null else (head * 100).roundToInt().coerceIn(10, 90),
            pullMode = pullMode,
            pullSpeed = pullSpeed,
            closeRange = close,
            longRange = long,
            refreshRate = refresh,
            fingers = s.fingers
        )
    }
}
