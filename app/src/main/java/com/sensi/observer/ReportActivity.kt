package com.sensi.observer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.sensi.observer.UiKit.Companion.MATCH
import com.sensi.observer.UiKit.Companion.WRAP
import com.sensi.observer.engine.Advisor
import com.sensi.observer.engine.Recommendation
import com.sensi.observer.engine.ReportText
import com.sensi.observer.engine.Scope
import com.sensi.observer.engine.SessionStore
import com.sensi.observer.engine.SessionSummary
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Relatório de uma sessão: nível de capa, sensibilidade recomendada por mira, DPI e resolução,
 * o que foi medido do seu arrasto e do seu tiro, dicas e o formato do seu arrasto.
 */
class ReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "session_id"
    }

    private lateinit var ui: UiKit
    private lateinit var store: SessionStore
    private var session: SessionSummary? = null
    private var rec: Recommendation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObsSettings.init(this)
        store = SessionStore(File(filesDir, "sessions"))
        ui = UiKit(this)

        val all = store.loadAll()
        val wanted = intent.getLongExtra(EXTRA_ID, 0L)
        val s = all.firstOrNull { it.id == wanted } ?: all.maxByOrNull { it.id }
        session = s
        if (s != null) {
            val gains = store.loadGains()
            // Só sessões até esta entram na conta (uma sessão antiga não "vê o futuro").
            rec = Advisor.recommend(all.filter { it.id < s.id }, s, gains)
        }

        setContentView(buildUi())
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller?.isAppearanceLightStatusBars = !night
        controller?.isAppearanceLightNavigationBars = !night
    }

    private fun buildUi(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.color(R.color.vp_bg))
        }
        val s = session
        val r = rec
        page.addView(
            ui.header(
                "Relatório",
                if (s != null) "${ReportText.date(s.id)} · ${ReportText.duration(s.durationMs)}" else "Nenhuma sessão"
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(20))
        }

        if (s == null || r == null) {
            content.addView(ui.note("Ainda não há nenhuma sessão. Toque em INICIAR, jogue e finalize."))
            content.addView(ui.button("Voltar", UiKit.Kind.PRIMARY) { finish() }, ui.lp(12))
            page.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            return page
        }

        content.addView(verdictCard(s, r))

        for (sec in ReportText.sections(s, r, store.loadGains())) {
            when (sec.kind) {
                "sens" -> content.addView(sensCard(sec.title, r, s))
                "warn" -> {
                    val c = ui.card(null, sec.title)
                    for (l in sec.lines) c.addView(ui.line(l, ui.color(R.color.vp_warning)))
                    content.addView(c)
                }
                else -> {
                    val c = ui.card(null, sec.title)
                    for (l in sec.lines) c.addView(ui.line(l))
                    content.addView(c)
                }
            }
        }

        content.addView(profileCard(s))

        val csv = File(getExternalFilesDir(null) ?: filesDir, "sessao_${s.id}.csv")
        if (csv.exists()) {
            val c = ui.card(null, "Linha do tempo (CSV)")
            c.addView(ui.note("Quadro a quadro (ms a ms) em:\n${csv.absolutePath}"))
            content.addView(c)
        }

        content.addView(
            ui.button("Usei estes valores no jogo", UiKit.Kind.PRIMARY) { applySuggested(r) }, ui.lp(4)
        )
        content.addView(
            ui.note(
                "Depois de colocar as sensibilidades no Free Fire, toque aqui: o app passa a usar esses " +
                    "números como ponto de partida da próxima sessão."
            )
        )
        content.addView(ui.button("Compartilhar relatório", UiKit.Kind.TONAL) { share(s, r) }, ui.lp(8))
        content.addView(ui.button("Apagar esta sessão", UiKit.Kind.DANGER) { confirmDelete(s) }, ui.lp(8))
        content.addView(ui.button("Voltar", UiKit.Kind.TONAL) { finish() }, ui.lp(8))

        page.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return page
    }

    // ------------------------------------------------------------------ cartões

    /** Nível de capa em destaque. */
    private fun verdictCard(s: SessionSummary, r: Recommendation): View {
        val c = ui.card(null, "Seu nível")
        val tint = ui.color(
            when {
                r.level >= 3 -> R.color.vp_success
                r.level >= 1 -> R.color.vp_accent_text
                r.level == 0 -> R.color.vp_warning
                else -> R.color.vp_text_secondary
            }
        )
        c.addView(TextView(this).apply {
            text = r.levelLabel
            textSize = 26f
            setTextColor(tint)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, ui.dp(2), 0, ui.dp(2))
        })
        val parts = ArrayList<String>()
        if (!s.headRate.isNaN()) {
            parts.add("capa ${(s.headRate * 100).roundToInt()}% (${s.totalHead} de ${s.totalHits} acertos)")
        }
        val score = Advisor.overallScoreOf(s)
        if (!score.isNaN()) parts.add("nota ${score.roundToInt()}/100")
        parts.add("${s.totalAcq} arrastos medidos")
        c.addView(ui.note(parts.joinToString(" · ")))
        if (r.converged) {
            c.addView(
                TextView(this).apply {
                    text = "✔ Sensibilidade estabilizada: seus arrastos já casam com a sensi. Foque em treino e mira."
                    textSize = 13f
                    setTextColor(ui.color(R.color.vp_success))
                    setPadding(0, ui.dp(4), 0, 0)
                }
            )
        }
        return c
    }

    /** Uma linha por mira: atual → sugerida, variação e o motivo. */
    private fun sensCard(title: String, r: Recommendation, s: SessionSummary): View {
        val c = ui.card(null, title)
        for (sr in r.scopes) {
            val label = Scope.of(sr.scope).label
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                background = ui.rounded(ui.color(R.color.vp_surface_variant), 14f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ui.dp(8) }
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(ui.color(R.color.vp_text))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            when (sr.status) {
                "sem dados" -> top.addView(TextView(this).apply {
                    text = "${sr.current}  (sem dados)"
                    textSize = 14f
                    setTextColor(ui.color(R.color.vp_text_secondary))
                })
                "ok" -> {
                    top.addView(TextView(this).apply {
                        text = "${sr.current}  ✔"
                        textSize = 18f
                        setTextColor(ui.color(R.color.vp_success))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                }
                else -> {
                    top.addView(TextView(this).apply {
                        text = "${sr.current} → ${sr.suggested}"
                        textSize = 18f
                        setTextColor(ui.color(R.color.vp_accent_text))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                }
            }
            box.addView(top)

            val sub = StringBuilder()
            when (sr.status) {
                "sem dados" -> sub.append(sr.reason)
                "ok" -> sub.append("Estabilizada. ").append(confidenceLabel(sr.confidence))
                else -> {
                    val sign = if (sr.deltaPct > 0) "+" else ""
                    sub.append("$sign${sr.deltaPct}% · ").append(confidenceLabel(sr.confidence))
                        .append(" · ").append(sr.reason)
                }
            }
            box.addView(TextView(this).apply {
                text = sub.toString()
                textSize = 13f
                setTextColor(ui.color(R.color.vp_text_secondary))
                setLineSpacing(0f, 1.1f)
                setPadding(0, ui.dp(4), 0, 0)
            })
            c.addView(box)
        }
        c.addView(ui.note("Escala de 0 a 200 do Free Fire. Mude uma mira de cada vez se quiser sentir a diferença."))
        return c
    }

    private fun confidenceLabel(c: Int) = when (c) {
        2 -> "confiança alta"
        1 -> "confiança média"
        else -> "confiança baixa"
    }

    /** Formato do arrasto: velocidade da câmera a cada 8 ms desde o início do giro. */
    private fun profileCard(s: SessionSummary): View {
        val c = ui.card(null, "Formato do seu arrasto")
        val data = s.flickProfile
        val peak = data.maxOrNull() ?: 0f
        if (peak <= 0.001f) {
            c.addView(ui.note("Sem arrastos medidos nesta sessão."))
            return c
        }
        c.addView(
            ui.note(
                "Velocidade média da câmera a cada 8 ms desde o começo do giro. Mostra se você acelera " +
                    "forte, freia cedo ou arrasta devagar."
            )
        )
        c.addView(
            ProfileView(this, data),
            LinearLayout.LayoutParams(MATCH, ui.dp(150)).apply { topMargin = ui.dp(6) }
        )
        c.addView(ui.note("Pico: ${(peak * 100).roundToInt() / 100.0} alturas de tela por segundo · base: ${data.size * 8} ms"))
        return c
    }

    private inner class ProfileView(context: Context, val data: FloatArray) : View(context) {
        private val d = resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * d
            color = ui.color(R.color.vp_accent)
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ColorUtils.setAlphaComponent(ui.color(R.color.vp_accent), 0x30)
        }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * d
            color = ui.color(R.color.vp_stroke)
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * d
            color = ui.color(R.color.vp_text_secondary)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val left = 6f * d
            val right = w - 6f * d
            val top = 8f * d
            val bottom = h - 18f * d
            var mx = 0f
            for (v in data) mx = max(mx, v)
            if (mx <= 0f) return
            val n = data.size

            for (i in 0..2) {
                val y = bottom - (bottom - top) * i / 2f
                canvas.drawLine(left, y, right, y, grid)
            }
            val path = Path()
            val area = Path()
            for (i in 0 until n) {
                val x = left + (right - left) * i / max(1, n - 1)
                val y = bottom - (bottom - top) * (data[i] / mx)
                if (i == 0) {
                    path.moveTo(x, y)
                    area.moveTo(x, bottom)
                    area.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            area.lineTo(right, bottom)
            area.close()
            canvas.drawPath(area, fill)
            canvas.drawPath(path, line)

            canvas.drawText("0 ms", left, h - 3f * d, label)
            val end = "${n * 8} ms"
            canvas.drawText(end, right - label.measureText(end), h - 3f * d, label)
        }
    }

    // ------------------------------------------------------------------ ações

    private fun applySuggested(r: Recommendation) {
        var n = 0
        for (sr in r.scopes) {
            if (sr.status == "sem dados") continue
            ObsSettings.setSens(sr.scope, sr.suggested)
            n++
        }
        toast(if (n == 0) "Não há sugestões para aplicar." else "Sensibilidades atualizadas no app ($n).")
    }

    private fun share(s: SessionSummary, r: Recommendation) {
        val text = ReportText.plain(s, r, store.loadGains())
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Observador de Sensi - relatório")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Compartilhar relatório"))
    }

    private fun confirmDelete(s: SessionSummary) {
        AlertDialog.Builder(this)
            .setTitle("Apagar esta sessão?")
            .setPositiveButton("Apagar") { _, _ ->
                store.delete(s.id)
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
