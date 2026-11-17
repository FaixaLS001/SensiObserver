package com.sensi.observer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.sensi.observer.UiKit.Companion.MATCH
import com.sensi.observer.UiKit.Companion.WRAP
import com.sensi.observer.engine.Advisor
import com.sensi.observer.engine.ReportText
import com.sensi.observer.engine.Scope
import com.sensi.observer.engine.SessionStore
import com.sensi.observer.engine.SessionSummary
import java.io.File
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var ui: UiKit
    private lateinit var store: SessionStore

    private lateinit var tvOverlay: TextView
    private lateinit var tvAccess: TextView
    private lateinit var tvFireCal: TextView
    private lateinit var tvHitCal: TextView
    private lateinit var tvGainCal: TextView
    private lateinit var tvDevice: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var statusDot: View
    private lateinit var btnStart: Button
    private lateinit var sessionsBox: LinearLayout
    private lateinit var tvSessions: TextView
    private lateinit var swLive: SwitchCompat

    /** Atualizam na volta ao app o que a bolinha pode ter mudado (posições dos marcadores). */
    private val syncs = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_CODE, res.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                handler.postDelayed({ refresh() }, 700)
                toast("Abra o Free Fire na horizontal.")
                // Volta para o jogo/app que estava por baixo.
                moveTaskToBack(true)
            } else {
                toast("Compartilhamento de tela não autorizado.")
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObsSettings.init(this)
        store = SessionStore(File(filesDir, "sessions"))
        ui = UiKit(this)
        setContentView(buildUi())
        applyBarStyle()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Ícones da barra de status e de navegação escuros no tema claro e claros no escuro. */
    private fun applyBarStyle() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller?.isAppearanceLightStatusBars = !night
        controller?.isAppearanceLightNavigationBars = !night
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.color(R.color.vp_bg))
        }
        page.addView(ui.header("Observador de Sensi", "Observa seu combate e acha a sensi perfeita"))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(20))
        }

        // 1) Como usar
        val c1 = ui.card(1, "Como usar")
        content.addView(c1)
        c1.addView(
            ui.note(
                "1. Confira abaixo (cartão 3) a sensibilidade que está no Free Fire agora.\n" +
                    "2. Toque em INICIAR, autorize o compartilhamento de tela e abra o jogo na horizontal.\n" +
                    "3. Na bolinha: Marcar e calibrar. Marque 1 Mira, 2 Tiro e 3 Giro. Depois calibre Tiro, " +
                    "Capa e Corpo num boneco de treino.\n" +
                    "4. Jogue treino e partidas. Ao trocar de mira (ponto vermelho, 2x, 4x, AWM), troque também " +
                    "\"Mira em uso\" na bolinha.\n" +
                    "5. Toque em Finalizar. O relatório mostra a sensi, o DPI e a resolução recomendados, e a " +
                    "sua % de capa.\n" +
                    "6. Coloque os valores no jogo, atualize o cartão 3 e jogue de novo. A cada sessão a " +
                    "recomendação chega mais perto da sensi perfeita."
            )
        )

        // 2) Permissões
        val c2 = ui.card(2, "Permissões")
        content.addView(c2)
        tvOverlay = ui.chip()
        c2.addView(
            ui.permRow("Sobreposição na tela", tvOverlay, "Permitir sobreposição na tela") {
                startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        )
        tvAccess = ui.chip()
        c2.addView(
            ui.permRow("Acessibilidade (opcional)", tvAccess, "Ativar serviço de acessibilidade") {
                startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        c2.addView(
            ui.note(
                "A acessibilidade só serve para a calibração de Resposta (arrastos de teste que medem " +
                    "quanto a câmera gira por pixel de dedo). O app não lê o que você toca no jogo.\n" +
                    "Se o Android bloquear a ativação (app instalado fora da loja): Configurações > Apps > " +
                    "Observador de Sensi > menu ⋮ > \"Permitir configurações restritas\"."
            )
        )

        // 3) Sensibilidade atual
        val c3 = ui.card(3, "Sensibilidade que está no jogo")
        content.addView(c3)
        tvDevice = ui.note("")
        c3.addView(tvDevice)
        val names = arrayOf("Geral", "Ponto vermelho", "Mira 2x", "Mira 4x", "Mira AWM")
        for (s in Scope.values()) {
            c3.addView(ui.slider(names[s.id], 0, 200, ObsSettings.sens(s.id), "") {
                ObsSettings.setSens(s.id, it)
            })
        }
        c3.addView(
            ui.note(
                "Escala de 0 a 200 do jogo. As recomendações partem destes números: eles precisam ser os " +
                    "mesmos que estão no Free Fire durante a sessão."
            )
        )
        c3.addView(ui.note("Quantos dedos você usa:"), ui.lp(6))
        val fingerLabels = arrayOf("2 dedos (dois polegares)", "3 dedos", "4 dedos (garra)")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (i in fingerLabels.indices) {
            val rb = RadioButton(this).apply {
                text = fingerLabels[i]
                id = View.generateViewId()
                tag = i + 2
                textSize = 14f
                setTextColor(ui.color(R.color.vp_text))
                buttonTintList = ColorStateList.valueOf(ui.color(R.color.vp_accent))
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            }
            group.addView(rb)
            if (i + 2 == ObsSettings.fingers) group.check(rb.id)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val rb = g.findViewById<RadioButton>(checkedId)
            if (rb != null) ObsSettings.fingers = rb.tag as Int
        }
        c3.addView(group)
        c3.addView(
            ui.slider("Qualidade da captura (vale ao iniciar)", 25, 60, ObsSettings.scalePct, "%") {
                ObsSettings.scalePct = it
            }
        )
        c3.addView(
            ui.note("Menor = mais leve para o celular. Maior = mede o giro da câmera com mais detalhe.")
        )

        // 4) Marcadores
        val c4 = ui.card(4, "Marcadores na tela")
        content.addView(c4)
        c4.addView(
            ui.note(
                "O app precisa saber onde estão a mira, o botão de tiro e um trecho de cenário limpo para " +
                    "medir a câmera. O mais fácil é marcar direto na tela, no jogo: bolinha > Marcar e " +
                    "calibrar. Os controles abaixo são o ajuste fino (valores em % da tela)."
            )
        )
        c4.addView(pm("Mira - horizontal", 0, 100, { ObsSettings.aimXPm }, { ObsSettings.aimXPm = it }))
        c4.addView(pm("Mira - vertical", 0, 100, { ObsSettings.aimYPm }, { ObsSettings.aimYPm = it }))
        c4.addView(pm("Região do X de acerto (meia-largura)", 2, 14, { ObsSettings.hitSizePm }, { ObsSettings.hitSizePm = it }))
        c4.addView(pm("Botão de tiro - horizontal", 0, 100, { ObsSettings.fireXPm }, { ObsSettings.fireXPm = it }))
        c4.addView(pm("Botão de tiro - vertical", 0, 100, { ObsSettings.fireYPm }, { ObsSettings.fireYPm = it }))
        c4.addView(pm("Botão de tiro - raio", 2, 20, { ObsSettings.fireRadiusPm }, { ObsSettings.fireRadiusPm = it }))
        c4.addView(pm("Área de giro - horizontal", 0, 100, { ObsSettings.readXPm }, { ObsSettings.readXPm = it }))
        c4.addView(pm("Área de giro - vertical", 0, 100, { ObsSettings.readYPm }, { ObsSettings.readYPm = it }))
        c4.addView(pm("Área de giro - tamanho", 20, 90, { ObsSettings.readSizePm }, { ObsSettings.readSizePm = it }))
        c4.addView(pm("Dedo que gira a câmera - horizontal", 0, 100, { ObsSettings.dragXPm }, { ObsSettings.dragXPm = it }))
        c4.addView(pm("Dedo que gira a câmera - vertical", 0, 100, { ObsSettings.dragYPm }, { ObsSettings.dragYPm = it }))
        c4.addView(
            ui.note(
                "Área de giro: escolha um pedaço do cenário sem botões nem placar. Deixe a bolinha longe " +
                    "da mira, do botão de tiro e da área de giro: ela aparece na captura."
            )
        )

        // 5) Calibrações
        val c5 = ui.card(5, "Calibrações")
        content.addView(c5)
        tvFireCal = ui.chip()
        tvHitCal = ui.chip()
        tvGainCal = ui.chip()
        c5.addView(ui.statusRow("Botão de tiro", tvFireCal))
        c5.addView(ui.statusRow("Acerto (capa e corpo)", tvHitCal))
        c5.addView(ui.statusRow("Resposta do dedo", tvGainCal))
        c5.addView(
            ui.note(
                "As calibrações são feitas na bolinha, durante o jogo, e ficam salvas. Sem elas o app ainda " +
                    "funciona, mas adivinha o tiro e pode não ver os acertos."
            )
        )
        c5.addView(ui.button("Apagar calibrações", UiKit.Kind.DANGER) { confirmClearCal() }, ui.lp(8))

        // 6) Opções
        val c6 = ui.card(6, "Opções")
        content.addView(c6)
        c6.addView(ui.switch("Mostrar a bolinha (vale ao iniciar)", ObsSettings.showBubble) {
            ObsSettings.showBubble = it
        })
        swLive = ui.switch("Números ao vivo ao lado da bolinha", ObsSettings.showLive) {
            ObsSettings.showLive = it
        }
        c6.addView(swLive)
        c6.addView(ui.switch("Salvar linha do tempo ms a ms (CSV)", ObsSettings.recordCsv) {
            ObsSettings.recordCsv = it
        })
        c6.addView(ui.switch("Ignorar o HUD parado ao medir a câmera", ObsSettings.useStaticMask) {
            ObsSettings.useStaticMask = it
        })
        c6.addView(ui.switch("Procurar o acerto só no X (diagonais)", ObsSettings.useDiagonalHit) {
            ObsSettings.useDiagonalHit = it
        })
        c6.addView(
            ui.note(
                "O CSV guarda, quadro a quadro, quanto a câmera girou, se você atirava e se acertou. Fica " +
                    "na pasta do app (Android/data/com.sensi.observer/files)."
            )
        )

        // 7) Sessões
        val c7 = ui.card(7, "Sessões e relatórios")
        content.addView(c7)
        tvSessions = ui.note("")
        c7.addView(tvSessions)
        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c7.addView(sessionsBox)
        c7.addView(ui.button("Apagar todas as sessões", UiKit.Kind.DANGER) { confirmClearSessions() }, ui.lp(8))

        page.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        page.addView(bottomBar())
        return page
    }

    private fun bottomBar(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(this).apply {
            setBackgroundColor(ui.color(R.color.vp_stroke))
            layoutParams = LinearLayout.LayoutParams(MATCH, ui.dp(1))
        })
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
            setBackgroundColor(ui.color(R.color.vp_surface))
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { rightMargin = ui.dp(10) }
        }
        bar.addView(statusDot)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        tvStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(ui.color(R.color.vp_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        tvStatusSub = TextView(this).apply {
            textSize = 12f
            setTextColor(ui.color(R.color.vp_text_secondary))
        }
        texts.addView(tvStatus)
        texts.addView(tvStatusSub)
        bar.addView(texts)
        btnStart = ui.button("INICIAR", UiKit.Kind.PRIMARY) { onStartStop() }
        btnStart.minimumWidth = ui.dp(130)
        bar.addView(btnStart, LinearLayout.LayoutParams(WRAP, WRAP))
        wrap.addView(bar)
        return wrap
    }

    /** Controle deslizante para um valor em milésimos, mostrado em % (e atualizado ao voltar). */
    private fun pm(label: String, lo: Int, hi: Int, get: () -> Int, set: (Int) -> Unit): View {
        val (box, bar) = ui.sliderPair(label, lo, hi, get() / 10, "%", null) { set(it * 10) }
        syncs.add { bar.progress = (get() / 10 - lo).coerceIn(0, hi - lo) }
        return box
    }

    // ------------------------------------------------------------------ sessões

    private fun rebuildSessions() {
        sessionsBox.removeAllViews()
        val list = store.loadAll().sortedByDescending { it.id }
        tvSessions.text = if (list.isEmpty()) {
            "Nenhuma sessão ainda. Toque em INICIAR e jogue; ao finalizar, o relatório aparece aqui."
        } else {
            "${list.size} sessão(ões). Toque em uma para ver o relatório e a sensi recomendada."
        }
        for (s in list) sessionsBox.addView(sessionRow(s))
    }

    private fun sessionRow(s: SessionSummary): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            background = ui.rounded(ui.color(R.color.vp_surface_variant), 14f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ui.dp(8) }
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, ReportActivity::class.java)
                        .putExtra(ReportActivity.EXTRA_ID, s.id)
                )
            }
        }
        row.addView(TextView(this).apply {
            text = "${ReportText.date(s.id)} · ${ReportText.duration(s.durationMs)}"
            textSize = 15f
            setTextColor(ui.color(R.color.vp_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val score = Advisor.overallScoreOf(s)
        val parts = ArrayList<String>()
        parts.add(if (score.isNaN()) "sem nota" else "nota ${score.roundToInt()}/100")
        parts.add(if (s.headRate.isNaN()) "capa —" else "capa ${(s.headRate * 100).roundToInt()}%")
        parts.add("${s.totalAcq} arrastos")
        parts.add("Geral ${s.sens[0]}")
        row.addView(TextView(this).apply {
            text = parts.joinToString(" · ")
            textSize = 13f
            setTextColor(ui.color(R.color.vp_text_secondary))
        })
        return row
    }

    private fun confirmClearSessions() {
        if (store.loadAll().isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Apagar todas as sessões?")
            .setMessage("As recomendações voltam a partir da próxima sessão. As calibrações ficam.")
            .setPositiveButton("Apagar") { _, _ ->
                store.deleteAll()
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmClearCal() {
        AlertDialog.Builder(this)
            .setTitle("Apagar calibrações?")
            .setMessage("Botão de tiro, acerto e resposta do dedo serão medidos de novo.")
            .setPositiveButton("Apagar") { _, _ ->
                ObsSettings.clearCalibrations()
                store.clearGains()
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ iniciar / parar

    private fun onStartStop() {
        if (CaptureService.running) {
            startService(
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_FINISH)
            )
            handler.postDelayed({ refresh() }, 900)
            return
        }
        if (!AndroidSettings.canDrawOverlays(this)) {
            toast("Permita a sobreposição na tela primeiro.")
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun refresh() {
        // A bolinha também liga/desliga os números ao vivo: mantém a chave igual ao salvo.
        if (swLive.isChecked != ObsSettings.showLive) swLive.isChecked = ObsSettings.showLive
        rebuildSessions()
        ui.setChip(tvOverlay, AndroidSettings.canDrawOverlays(this), "Permitida", "Pendente")
        ui.setChip(tvAccess, ObserverAccessibilityService.instance != null, "Ativa", "Desativada")
        ui.setChip(tvFireCal, ObsSettings.fireCalOk, "Calibrado", "Não calibrado")
        val head = ObsSettings.hitHeadCalibrated
        val body = ObsSettings.hitBodyCalibrated
        ui.setChip(
            tvHitCal, head && body,
            "Calibrado",
            if (!head && !body) "Não calibrado" else if (head) "Falta o corpo" else "Falta a capa"
        )
        val gains = store.loadGains().size
        ui.setChip(tvGainCal, gains > 0, "$gains medição(ões)", "Não medida")
        syncs.forEach { it() }

        val d = ScreenInfo.deviceInfo(this)
        tvDevice.text = "Seu aparelho: tela ${d.widthPx}x${d.heightPx} · DPI ${d.densityDpi} · ${d.refreshHz.roundToInt()} Hz"

        val running = CaptureService.running
        btnStart.text = if (running) "FINALIZAR" else "INICIAR"
        ui.styleButton(btnStart, if (running) UiKit.Kind.STOP else UiKit.Kind.PRIMARY)
        tvStatus.text = if (running) "Observando" else "Parado"
        tvStatusSub.text = if (running) {
            "Jogue; finalize para ver o relatório"
        } else {
            "Pronto para iniciar"
        }
        statusDot.background = ui.oval(
            ui.color(if (running) R.color.vp_success else R.color.vp_text_secondary)
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
