package com.sensi.observer.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Uma seção do relatório: título e linhas de texto. */
class ReportSection(val title: String, val lines: List<String>, val kind: String = "text")

/**
 * Monta o texto do relatório (usado na tela e no "Compartilhar"). Números em português do Brasil.
 */
object ReportText {

    private val br = Locale("pt", "BR")

    private fun n(v: Double, dec: Int = 1): String = if (v.isNaN()) "—" else String.format(br, "%.${dec}f", v)
    private fun pct(v: Double): String = if (v.isNaN()) "—" else "${(v * 100).roundToInt()}%"
    private fun ms(v: Double): String = if (v.isNaN()) "—" else "${v.roundToInt()} ms"

    fun duration(ms: Double): String {
        val s = (ms / 1000).roundToInt()
        return if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }

    fun date(id: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", br).format(Date(id))

    fun pullLabel(m: String) = when (m) {
        "aggressive" -> "agressivo"
        "precise" -> "preciso"
        else -> "equilibrado"
    }

    fun speedLabel(m: String) = when (m) {
        "very_fast" -> "muito rápida"
        "fast" -> "rápida"
        "slow" -> "lenta"
        else -> "média"
    }

    fun rangeLabel(m: String) = when (m) {
        "high" -> "alta"
        "low" -> "baixa"
        else -> "média"
    }

    fun sections(s: SessionSummary, rec: Recommendation, gains: List<GainRecord>): List<ReportSection> {
        val out = ArrayList<ReportSection>()
        val d = s.device

        // --- resumo
        val sum = ArrayList<String>()
        sum.add("${date(s.id)} · ${duration(s.durationMs)} · ${d.widthPx}x${d.heightPx} · DPI ${d.densityDpi} · ${d.refreshHz.roundToInt()} Hz")
        sum.add("FPS do jogo: média ${n(s.fpsMean, 0)}, pior 5% ${n(s.fpsP5, 0)}. Leitura de movimento válida em ${n(s.validPct, 0)}% do tempo.")
        if (s.totalHits > 0) {
            sum.add("Nível: ${rec.levelLabel} · capa ${pct(s.headRate)} (${s.totalHead} de ${s.totalHits} acertos)")
        } else {
            sum.add("Nível: ${rec.levelLabel}")
        }
        val score = Advisor.overallScoreOf(s)
        if (!score.isNaN()) sum.add("Nota da sessão: ${score.roundToInt()} de 100")
        sum.add("Arrastos de mira medidos: ${s.totalAcq} · rajadas de tiro: ${s.totalBursts}")
        if (rec.sessionsUsed > 1) sum.add("Sessões usadas na recomendação (mesma tela e DPI): ${rec.sessionsUsed}")
        out.add(ReportSection("Resumo", sum))

        // --- sensibilidade
        val sens = rec.scopes.map { r ->
            val label = Scope.of(r.scope).label
            val conf = when (r.confidence) { 2 -> "confiança alta"; 1 -> "confiança média"; else -> "confiança baixa" }
            when (r.status) {
                "sem dados" -> "$label: ${r.current} (sem dados) — ${r.reason}"
                "ok" -> "$label: ${r.current} → ${r.suggested} ✔ estabilizada ($conf)"
                else -> {
                    val sign = if (r.deltaPct > 0) "+" else ""
                    "$label: ${r.current} → ${r.suggested} ($sign${r.deltaPct}%) ($conf) — ${r.reason}"
                }
            }
        }
        out.add(ReportSection("Sensibilidade recomendada (escala 0 a 200)", sens, "sens"))

        // --- DPI e resolução
        out.add(ReportSection("DPI e resolução", listOf(rec.dpi.text, rec.resolution.text)))

        // --- arrasto, por mira
        val arrasto = ArrayList<String>()
        val tiro = ArrayList<String>()
        for (st in s.scopes) {
            val label = Scope.of(st.scope).label
            if (st.acq > 0) {
                val g = GainEstimator.gainFor(gains, st.scope, s.sens[st.scope], d.densityDpi, d.widthPx, d.heightPx)
                val ampPx = st.ampMed * d.heightPx
                val peakPx = st.peakMed * d.heightPx
                val sb = StringBuilder("$label (${st.acq} arrastos): ")
                sb.append("amplitude ${pct(st.ampMed)} da altura da tela (${ampPx.roundToInt()} px)")
                if (g != null) {
                    val fingerPx = ampPx / g
                    sb.append(", dedo ${(fingerPx / d.pxPerMm()).roundToInt()} mm")
                }
                sb.append(" · pico ${peakPx.roundToInt()} px/s aos ${ms(st.tPeakMed)} · duração ${ms(st.durMed)}")
                sb.append(" · erro ${if (st.errMean >= 0) "+" else ""}${(st.errMean * 100).roundToInt()}% (passou do alvo em ${pct(st.overRate)}, ficou curto em ${pct(st.underRate)})")
                sb.append(" · ${n(st.corrPerAcq)} correções · estabiliza em ${ms(st.settleMed)}")
                if (st.skipped > 0) sb.append(" · ${st.skipped} giros rápidos demais ficaram fora")
                arrasto.add(sb.toString())
            } else if (st.skipped > 0) {
                arrasto.add("$label: ${st.skipped} arrastos rápidos demais para medir (FPS baixo?)")
            }
            if (st.bursts > 0 || st.hits > 0) {
                val sb = StringBuilder("$label: ${st.bursts} rajadas, ${st.hits} acertos")
                if (st.hits > 0) sb.append(" (${st.headHits} na cabeça = ${pct(st.headRate)})")
                sb.append(" · grude ${ms(st.stickMed)} · tiro dura ${ms(st.fireMed)} · 1º acerto em ${ms(st.firstHitMed)}")
                if (!st.pull250Mean.isNaN()) sb.append(" · puxada ${pct(st.pull250Mean)} da tela em 250 ms (${(st.pull250Mean * d.heightPx).roundToInt()} px)")
                if (!st.jitterIdx.isNaN()) sb.append(" · tremor ${pct(st.jitterIdx)}")
                tiro.add(sb.toString())
            }
        }
        if (arrasto.isEmpty()) arrasto.add("Nenhum arrasto de mira medido ainda.")
        if (tiro.isEmpty()) tiro.add("Nenhuma rajada de tiro detectada. Calibre o botão de tiro pela bolinha.")
        out.add(ReportSection("Seu arrasto (medido quadro a quadro)", arrasto))
        out.add(ReportSection("Tiro, capa e puxada", tiro))

        // --- dicas
        if (rec.tips.isNotEmpty()) out.add(ReportSection("Dicas para se destacar", rec.tips))

        // --- gerador
        val g = rec.generator
        val gl = ArrayList<String>()
        gl.add("Tempo de grude: ${g.stickTimeMs?.let { "$it ms" } ?: "—"}")
        gl.add("Tempo de disparo: ${g.fireTimeMs?.let { "$it ms" } ?: "—"}")
        gl.add("Headshot: ${g.headshotRatio?.let { "$it%" } ?: "—"}")
        gl.add("Estilo de puxada: ${pullLabel(g.pullMode)} · velocidade: ${speedLabel(g.pullSpeed)}")
        gl.add("Combate perto: ${rangeLabel(g.closeRange)} · longe: ${rangeLabel(g.longRange)}")
        gl.add("Taxa de atualização: ${g.refreshRate} Hz · dedos: ${g.fingers}")
        out.add(ReportSection("Valores medidos para o Gerador", gl))

        // --- avisos
        if (rec.warnings.isNotEmpty()) out.add(ReportSection("Qualidade da medição", rec.warnings, "warn"))
        return out
    }

    fun plain(s: SessionSummary, rec: Recommendation, gains: List<GainRecord>): String {
        val sb = StringBuilder("OBSERVADOR DE SENSI — Relatório\n")
        for (sec in sections(s, rec, gains)) {
            sb.append('\n').append(sec.title.uppercase(br)).append('\n')
            for (l in sec.lines) sb.append("• ").append(l).append('\n')
        }
        return sb.toString()
    }
}
