package com.sensi.observer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sensi.observer.engine.Scope
import kotlin.math.abs
import kotlin.math.max

/**
 * Bolinha flutuante sobre o jogo: arraste para mover; toque para abrir o menu.
 *
 * Menu: pausar/retomar, trocar a mira em uso (Geral, Ponto vermelho, 2x, 4x, AWM), abrir o painel
 * de marcar e calibrar, ligar/desligar os números ao vivo, finalizar (gera o relatório) ou
 * descartar a sessão.
 *
 * Painel "Marcar e calibrar":
 *  1 Mira / 2 Tiro / 3 Giro / 4 Dedo - marca na tela cada posição (− e + mudam o tamanho);
 *  Tiro / Capa / Corpo / Resposta     - calibrações guiadas.
 *
 * A bolinha aparece na captura de tela: o serviço recebe o retângulo dela em [maskRect] e o
 * trata como "não é cenário". Deixe-a longe da mira, do botão de tiro e da área de giro.
 */
class FloatingControls(
    private val ctx: Context,
    private val markers: Markers,
    private val host: Host
) {
    interface Host {
        fun isPaused(): Boolean
        fun currentScope(): Int
        fun onTogglePause()
        fun onCycleScope()
        fun onFinish()
        fun onDiscard()
        fun onCalibrateFire()
        fun onCalibrateHit(head: Boolean)
        fun onCalibrateResponse()

        /** Texto dos números ao vivo (já formatado). */
        fun liveText(): String
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density

    private val green = Color.parseColor("#1F9D63")
    private val amber = Color.parseColor("#E8890C")

    private val bubbleBg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(green)
        setStroke(dp(2), Color.parseColor("#80FFFFFF"))
    }

    private val bubble = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_bubble_running)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = bubbleBg
        alpha = 0.92f
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
    }

    /** Números ao vivo ou mensagem da calibração em andamento, ao lado da bolinha. */
    private val statusTv = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 11f
        typeface = Typeface.MONOSPACE
        visibility = View.GONE
        maxWidth = dp(250)
        setPadding(dp(8), dp(5), dp(8), dp(5))
        background = panelBg()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) }
    }

    // ---- menu vertical (abre ao tocar na bolinha)

    private val btnPause: TextView = menuItem()
    private val btnScope: TextView = menuItem()
    private val btnPanel: TextView = menuItem().apply { text = "Marcar e calibrar" }
    private val btnLive: TextView = menuItem()
    private val btnFinish: TextView = menuItem().apply { text = "Finalizar e ver relatório" }
    private val btnDiscard: TextView = menuItem().apply {
        text = "Descartar sessão"
        setTextColor(Color.parseColor("#FF8FA3"))
    }

    private val menu = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(4), dp(6), dp(4), dp(6))
        background = panelBg()
        addView(btnPause)
        addView(btnScope)
        addView(btnPanel)
        addView(btnLive)
        addView(btnFinish)
        addView(btnDiscard)
    }

    // ---- painel de marcar e calibrar

    private val btnAim: TextView = trayButton()
    private val btnFire: TextView = trayButton()
    private val btnRead: TextView = trayButton()
    private val btnDrag: TextView = trayButton()
    private val btnMinus: TextView = trayButton().apply { text = "−  tamanho" }
    private val btnPlus: TextView = trayButton().apply { text = "+  tamanho" }
    private val btnDone: TextView = trayButton().apply { text = "OK" }
    private val btnCalFire: TextView = trayButton().apply { text = "Tiro" }
    private val btnCalHead: TextView = trayButton().apply { text = "Capa" }
    private val btnCalBody: TextView = trayButton().apply { text = "Corpo" }
    private val btnCalResp: TextView = trayButton().apply { text = "Resposta" }
    private val btnClose: TextView = trayButton().apply { text = "Fechar" }

    private fun title(t: String) = TextView(ctx).apply {
        text = t
        setTextColor(Color.parseColor("#B8B2FF"))
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(6), dp(8), dp(0))
    }

    private fun row(vararg v: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (x in v) addView(x)
    }

    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(4), dp(4), dp(4), dp(6))
        background = panelBg()
        addView(title("MARCAR NA TELA"))
        addView(row(btnAim, btnFire, btnRead, btnDrag))
        addView(row(btnMinus, btnPlus, btnDone))
        addView(title("CALIBRAR (guiado)"))
        addView(row(btnCalFire, btnCalHead, btnCalBody, btnCalResp))
        addView(row(btnClose))
    }

    private val topRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(bubble)
        addView(statusTv)
    }

    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(topRow)
        addView(menu)
        addView(panel)
    }

    private var lp: WindowManager.LayoutParams? = null
    private var added = false

    @Volatile
    private var menuOpen = false

    @Volatile
    private var panelOpen = false

    private var posSaved = false
    private var savedX = 0
    private var savedY = 0
    private var statusMsg: String? = null

    private val expanded: Boolean get() = menuOpen || panelOpen

    /** true enquanto o menu ou o painel estão abertos. */
    val isOpen: Boolean get() = menuOpen || panelOpen

    /**
     * Retângulo da bolinha (e do que está aberto) em pixels da tela: esquerda, topo, direita,
     * base. O serviço esconde essa região da leitura do cenário.
     */
    @Volatile
    var maskRect: IntArray? = null
        private set

    init {
        btnPause.setOnClickListener {
            host.onTogglePause()
            refresh()
            closeAll()
        }
        btnScope.setOnClickListener {
            host.onCycleScope()
            refresh()
        }
        btnPanel.setOnClickListener { openPanel() }
        btnLive.setOnClickListener {
            ObsSettings.showLive = !ObsSettings.showLive
            refresh()
            updateLive()
        }
        btnFinish.setOnClickListener { host.onFinish() }
        btnDiscard.setOnClickListener { host.onDiscard() }

        btnAim.setOnClickListener { togglePlacing(Markers.AIM) }
        btnFire.setOnClickListener { togglePlacing(Markers.FIRE) }
        btnRead.setOnClickListener { togglePlacing(Markers.READ) }
        btnDrag.setOnClickListener { togglePlacing(Markers.DRAG) }
        btnMinus.setOnClickListener { markers.resize(-1) }
        btnPlus.setOnClickListener { markers.resize(1) }
        btnDone.setOnClickListener {
            markers.stopPlacing()
            refresh()
            applyLayout()
        }
        btnCalFire.setOnClickListener { closeAll(); host.onCalibrateFire() }
        btnCalHead.setOnClickListener { closeAll(); host.onCalibrateHit(true) }
        btnCalBody.setOnClickListener { closeAll(); host.onCalibrateHit(false) }
        btnCalResp.setOnClickListener { closeAll(); host.onCalibrateResponse() }
        btnClose.setOnClickListener { closeAll() }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val l = lp ?: return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = l.x
                        startY = l.y
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (abs(dx) > 8 * d || abs(dy) > 8 * d)) moved = true
                        if (moved && !expanded) {
                            l.x = (startX + dx).toInt()
                            l.y = (startY + dy).toInt()
                            wm.updateViewLayout(root, l)
                            updateMask()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            if (!expanded) savePosition(l)
                        } else if (panelOpen) {
                            closeAll()
                        } else {
                            toggleMenu()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun panelBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 16f * d
        setColor(Color.parseColor("#F2171433"))
        setStroke(dp(1), Color.parseColor("#4D6C5CFF"))
    }

    private fun rippleBg(): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
        null,
        GradientDrawable().apply {
            cornerRadius = 10f * d
            setColor(Color.WHITE)
        }
    )

    private fun menuItem(): TextView = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = rippleBg()
    }

    private fun trayButton(): TextView = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(9), dp(10), dp(9), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
    }

    private fun style(btn: TextView, active: Boolean, activeColor: Int = green) {
        btn.background = GradientDrawable().apply {
            cornerRadius = 10f * d
            setColor(if (active) activeColor else Color.parseColor("#2EFFFFFF"))
        }
    }

    // ---------------------------------------------------------------- mostrar / esconder

    fun show() {
        if (added) return
        val size = ScreenInfo.realSize(ctx)
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        l.gravity = Gravity.TOP or Gravity.START

        val bs = dp(46)
        val sx = ObsSettings.bubbleX
        val sy = ObsSettings.bubbleY
        l.x = (if (sx >= 0) sx else dp(8)).coerceIn(0, max(0, size.first - bs))
        l.y = (if (sy >= 0) sy else size.second / 4).coerceIn(0, max(0, size.second - bs))

        lp = l
        wm.addView(root, l)
        added = true
        refresh()
        updateMask()
    }

    fun hide() {
        if (!added) return
        val l = lp
        if (l != null && !expanded) savePosition(l)
        try {
            wm.removeView(root)
        } catch (e: Exception) {
        }
        added = false
        menuOpen = false
        panelOpen = false
        posSaved = false
        maskRect = null
    }

    /** Atualiza cores e textos conforme o estado atual. */
    fun refresh() {
        val paused = host.isPaused()
        bubbleBg.setColor(if (paused) amber else green)
        bubble.setImageResource(if (paused) R.drawable.ic_bubble_paused else R.drawable.ic_bubble_running)
        btnPause.text = if (paused) "Retomar" else "Pausar"
        btnScope.text = "Mira em uso: ${Scope.of(host.currentScope()).label}  (trocar)"
        btnLive.text = if (ObsSettings.showLive) "Números ao vivo: ligados" else "Números ao vivo: desligados"

        val placing = markers.placing
        btnAim.text = "1 Mira"
        btnFire.text = "2 Tiro"
        btnRead.text = "3 Giro"
        btnDrag.text = "4 Dedo"
        style(btnAim, placing == Markers.AIM, amber)
        style(btnFire, placing == Markers.FIRE, amber)
        style(btnRead, placing == Markers.READ, amber)
        style(btnDrag, placing == Markers.DRAG, amber)
        val sizable = placing == Markers.AIM || placing == Markers.FIRE || placing == Markers.READ
        btnMinus.alpha = if (sizable) 1f else 0.4f
        btnPlus.alpha = if (sizable) 1f else 0.4f
        style(btnMinus, false)
        style(btnPlus, false)
        style(btnDone, placing != Markers.NONE, green)
        for (b in arrayOf(btnCalFire, btnCalHead, btnCalBody, btnCalResp, btnClose)) style(b, false)
    }

    private fun savePosition(l: WindowManager.LayoutParams) {
        ObsSettings.bubbleX = l.x
        ObsSettings.bubbleY = l.y
    }

    // ---------------------------------------------------------------- texto ao lado da bolinha

    /** Mensagem fixa (calibração em andamento). null volta aos números ao vivo. */
    fun setStatus(msg: String?) {
        statusMsg = msg
        updateLive()
    }

    /** Chamar de tempos em tempos (thread principal) para atualizar os números ao vivo. */
    fun updateLive() {
        if (!added) return
        val msg = statusMsg
        val text: String? = when {
            msg != null -> msg
            ObsSettings.showLive && !host.isPaused() && !expanded -> host.liveText()
            else -> null
        }
        if (text == null) {
            if (statusTv.visibility != View.GONE) {
                statusTv.visibility = View.GONE
                applyLayout()
            }
            return
        }
        val changedVisibility = statusTv.visibility != View.VISIBLE
        val changedText = statusTv.text.toString() != text
        if (changedText) statusTv.text = text
        statusTv.visibility = View.VISIBLE
        if (changedVisibility || changedText) applyLayout()
    }

    // ---------------------------------------------------------------- abrir / fechar

    private fun toggleMenu() {
        menuOpen = !menuOpen
        if (menuOpen) {
            panelOpen = false
            markers.stopPlacing()
            refresh()
        }
        applyLayout()
    }

    private fun openPanel() {
        menuOpen = false
        panelOpen = true
        refresh()
        applyLayout()
    }

    /** Fecha menu e painel e termina qualquer posicionamento. */
    fun closeAll() {
        menuOpen = false
        panelOpen = false
        markers.stopPlacing()
        refresh()
        applyLayout()
    }

    private fun togglePlacing(which: Int) {
        if (markers.placing == which) markers.stopPlacing() else markers.startPlacing(which)
        refresh()
        applyLayout()
    }

    /**
     * Mostra/esconde as partes conforme o estado e mantém tudo dentro da tela.
     * Ao fechar tudo, a bolinha volta para onde estava antes de abrir.
     */
    private fun applyLayout() {
        val l = lp ?: return
        if (!added) return

        val want = menuOpen || panelOpen
        if (want && !posSaved) {
            savedX = l.x
            savedY = l.y
            posSaved = true
        }
        menu.visibility = if (menuOpen) View.VISIBLE else View.GONE
        panel.visibility = if (panelOpen) View.VISIBLE else View.GONE

        val size = ScreenInfo.realSize(ctx)
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        if (want) {
            l.x = l.x.coerceIn(0, max(0, size.first - root.measuredWidth))
            l.y = l.y.coerceIn(0, max(0, size.second - root.measuredHeight))
        } else if (posSaved) {
            l.x = savedX
            l.y = savedY
            posSaved = false
        } else {
            // O texto ao lado da bolinha pode ter crescido: não deixa sair da tela.
            l.x = l.x.coerceIn(0, max(0, size.first - root.measuredWidth))
        }
        try {
            wm.updateViewLayout(root, l)
        } catch (e: Exception) {
        }
        updateMask()
    }

    private fun updateMask() {
        val l = lp ?: return
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val m = dp(6)
        maskRect = intArrayOf(
            max(0, l.x - m), max(0, l.y - m),
            l.x + root.measuredWidth + m, l.y + root.measuredHeight + m
        )
    }
}
