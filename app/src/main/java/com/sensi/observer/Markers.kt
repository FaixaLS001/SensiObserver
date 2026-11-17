package com.sensi.observer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Camada de tela cheia onde o jogador marca, em cima do jogo, as quatro posições que o app usa:
 *  - AIM:  a mira do jogo (e a região ao redor onde o X de acerto aparece);
 *  - FIRE: o botão de tiro;
 *  - READ: a área do cenário usada para medir o giro da câmera;
 *  - DRAG: onde o dedo pressiona para girar a câmera (só para a calibração de resposta).
 *
 * Fora do posicionamento a camada NÃO desenha nada (a captura de tela enxerga o que o app
 * desenha, e marcadores à mostra sujariam a medição) e não recebe toques: eles passam direto para
 * o jogo. Posicionando, a camada captura os toques: tocar ou arrastar leva o marcador até lá.
 */
class Markers(private val ctx: Context) {

    companion object {
        const val NONE = 0
        const val AIM = 1
        const val FIRE = 2
        const val READ = 3
        const val DRAG = 4

        private const val SIZE_STEP = 6
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: MarkerView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var added = false

    // Posições em fração da tela (0 a 1) e tamanhos em milésimos da altura.
    private var aimX = 0.5f
    private var aimY = 0.5f
    private var hitSize = 50
    private var fireX = 0.88f
    private var fireY = 0.68f
    private var fireR = 70
    private var readX = 0.5f
    private var readY = 0.42f
    private var readSize = 480
    private var dragX = 0.76f
    private var dragY = 0.52f

    /** Qual marcador está sendo posicionado agora. */
    @Volatile
    var placing = NONE
        private set

    fun show() {
        if (added) return
        load()
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        l.gravity = Gravity.TOP or Gravity.START
        // Sem isso o Android 12+ bloqueia os toques no jogo (ver TouchSafe).
        l.alpha = TouchSafe.MARKER_WINDOW_ALPHA
        if (Build.VERSION.SDK_INT >= 28) {
            l.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val v = MarkerView(ctx)
        view = v
        lp = l
        wm.addView(v, l)
        added = true
    }

    fun hide() {
        if (!added) return
        stopPlacing()
        try {
            view?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        view = null
        added = false
    }

    fun startPlacing(which: Int) {
        if (!added || which == NONE) return
        if (placing != NONE) stopPlacing()
        load()
        placing = which
        setTouchable(true)
        view?.invalidate()
    }

    fun stopPlacing() {
        if (placing == NONE) return
        save()
        placing = NONE
        setTouchable(false)
        view?.invalidate()
    }

    /** Aumenta ou diminui o tamanho do marcador que está sendo posicionado. */
    fun resize(direction: Int) {
        val d = direction * SIZE_STEP
        when (placing) {
            AIM -> hitSize = (hitSize + d).coerceIn(20, 140)
            FIRE -> fireR = (fireR + d).coerceIn(20, 200)
            READ -> readSize = (readSize + d * 4).coerceIn(200, 900)
            else -> return
        }
        save()
        view?.invalidate()
    }

    private fun load() {
        aimX = ObsSettings.aimXPm / 1000f
        aimY = ObsSettings.aimYPm / 1000f
        hitSize = ObsSettings.hitSizePm
        fireX = ObsSettings.fireXPm / 1000f
        fireY = ObsSettings.fireYPm / 1000f
        fireR = ObsSettings.fireRadiusPm
        readX = ObsSettings.readXPm / 1000f
        readY = ObsSettings.readYPm / 1000f
        readSize = ObsSettings.readSizePm
        dragX = ObsSettings.dragXPm / 1000f
        dragY = ObsSettings.dragYPm / 1000f
    }

    private fun save() {
        when (placing) {
            AIM -> {
                ObsSettings.aimXPm = (aimX * 1000f).roundToInt()
                ObsSettings.aimYPm = (aimY * 1000f).roundToInt()
                ObsSettings.hitSizePm = hitSize
            }
            FIRE -> {
                ObsSettings.fireXPm = (fireX * 1000f).roundToInt()
                ObsSettings.fireYPm = (fireY * 1000f).roundToInt()
                ObsSettings.fireRadiusPm = fireR
            }
            READ -> {
                ObsSettings.readXPm = (readX * 1000f).roundToInt()
                ObsSettings.readYPm = (readY * 1000f).roundToInt()
                ObsSettings.readSizePm = readSize
            }
            DRAG -> {
                ObsSettings.dragXPm = (dragX * 1000f).roundToInt()
                ObsSettings.dragYPm = (dragY * 1000f).roundToInt()
            }
        }
    }

    private fun setTouchable(touchable: Boolean) {
        val l = lp ?: return
        val v = view ?: return
        l.flags = if (touchable) {
            l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        // Posicionando a camada recebe os toques e pode ficar opaca; em uso precisa ficar translúcida.
        l.alpha = if (touchable) 1f else TouchSafe.MARKER_WINDOW_ALPHA
        try {
            wm.updateViewLayout(v, l)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- desenho

    private inner class MarkerView(context: Context) : View(context) {

        private val d = resources.displayMetrics.density

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * d
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
        }
        private val bannerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC212121") }

        private val amber = Color.parseColor("#FFC107")
        private val red = Color.parseColor("#FF5A5F")
        private val cyan = Color.parseColor("#58E0FF")
        private val green = Color.parseColor("#19C37D")

        override fun onDraw(c: Canvas) {
            val p = placing
            // Fora do posicionamento não desenha nada: a captura de tela não pode ver os marcadores.
            if (p == NONE) return

            // Tamanho real da tela: é nele que o app calcula as posições.
            val real = ScreenInfo.realSize(ctx)
            val w = real.first.toFloat()
            val h = real.second.toFloat()

            val fx = when (p) {
                AIM -> aimX
                FIRE -> fireX
                READ -> readX
                else -> dragX
            }
            val fy = when (p) {
                AIM -> aimY
                FIRE -> fireY
                READ -> readY
                else -> dragY
            }
            guides(c, fx * w, fy * h, w, h)

            drawRead(c, readX * w, readY * h, readSize / 1000f * h, if (p == READ) 1f else 0.45f)
            drawFire(c, fireX * w, fireY * h, fireR / 1000f * h, if (p == FIRE) 1f else 0.45f)
            drawAim(c, aimX * w, aimY * h, hitSize / 1000f * h, if (p == AIM) 1f else 0.45f)
            drawDrag(c, dragX * w, dragY * h, if (p == DRAG) 1f else 0.45f)

            val title: String
            val sub: String
            when (p) {
                AIM -> {
                    title = "1 - Centro da mira do jogo"
                    sub = "O quadrado é onde o X de acerto aparece. − e + mudam o tamanho."
                }
                FIRE -> {
                    title = "2 - Botão de tiro"
                    sub = "O círculo deve ficar dentro do botão. − e + mudam o tamanho."
                }
                READ -> {
                    title = "3 - Área de giro"
                    sub = "Cenário sem HUD (sem botões). O app mede a câmera aqui."
                }
                else -> {
                    title = "4 - Onde o dedo gira a câmera"
                    sub = "Usado só na calibração de resposta."
                }
            }
            banner(c, w, title, sub + " Termine em OK.")
        }

        private fun guides(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * d
            paint.color = Color.WHITE
            paint.alpha = 90
            c.drawLine(0f, y, w, y, paint)
            c.drawLine(x, 0f, x, h, paint)
        }

        private fun outline(c: Canvas, col: Int, a: Float, draw: () -> Unit) {
            for (pass in 0..1) {
                paint.style = Paint.Style.STROKE
                if (pass == 0) {
                    paint.strokeWidth = 3.5f * d
                    paint.color = Color.BLACK
                    paint.alpha = (a * 0.7f * 255f).toInt()
                } else {
                    paint.strokeWidth = 1.8f * d
                    paint.color = col
                    paint.alpha = (a * 255f).toInt()
                }
                draw()
            }
        }

        /** Mira: anel com quatro marcas e o quadrado onde o X de acerto é procurado. */
        private fun drawAim(c: Canvas, x: Float, y: Float, half: Float, a: Float) {
            val r = 11f * d
            val inner = r - 5f * d
            val outer = r + 8f * d
            outline(c, Color.WHITE, a) {
                c.drawCircle(x, y, r, paint)
                c.drawLine(x - outer, y, x - inner, y, paint)
                c.drawLine(x + inner, y, x + outer, y, paint)
                c.drawLine(x, y - outer, x, y - inner, paint)
                c.drawLine(x, y + inner, x, y + outer, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f * d
            paint.color = green
            paint.alpha = (a * 255f).toInt()
            c.drawRect(x - half, y - half, x + half, y + half, paint)
            label(c, "MIRA", x + half + 6f * d, y - half + 14f * d, a)
        }

        private fun drawFire(c: Canvas, x: Float, y: Float, r: Float, a: Float) {
            outline(c, red, a) { c.drawCircle(x, y, r, paint) }
            paint.style = Paint.Style.FILL
            paint.color = red
            paint.alpha = (a * 255f).toInt()
            c.drawCircle(x, y, 3f * d, paint)
            label(c, "TIRO", x + r + 6f * d, y + 5f * d, a)
        }

        private fun drawRead(c: Canvas, x: Float, y: Float, size: Float, a: Float) {
            val half = size / 2f
            outline(c, cyan, a) { c.drawRect(x - half, y - half, x + half, y + half, paint) }
            label(c, "GIRO", x - half + 8f * d, y - half + 20f * d, a)
        }

        private fun drawDrag(c: Canvas, x: Float, y: Float, a: Float) {
            val r = 15f * d
            outline(c, amber, a) { c.drawCircle(x, y, r, paint) }
            paint.style = Paint.Style.FILL
            paint.color = amber
            paint.alpha = (a * 255f).toInt()
            c.drawCircle(x, y, 3.5f * d, paint)
            label(c, "DEDO", x + r + 6f * d, y - 8f * d, a)
        }

        private fun label(c: Canvas, s: String, x: Float, y: Float, a: Float) {
            text.alpha = (a * 255f).toInt()
            c.drawText(s, x, y, text)
            text.alpha = 255
        }

        private fun banner(c: Canvas, w: Float, title: String, sub: String) {
            val tw = maxOf(text.measureText(title), text.measureText(sub))
            val padX = 14f * d
            val padY = 10f * d
            val lineH = 18f * d
            val top = 12f * d
            val maxW = w - 24f * d
            val bw = minOf(tw + padX * 2, maxW)
            val rect = RectF((w - bw) / 2f, top, (w + bw) / 2f, top + padY * 2 + lineH * 2)
            c.drawRoundRect(rect, 10f * d, 10f * d, bannerBg)
            text.textAlign = Paint.Align.CENTER
            c.drawText(title, w / 2f, top + padY + lineH * 0.8f, text)
            c.drawText(sub, w / 2f, top + padY + lineH * 1.85f, text)
            text.textAlign = Paint.Align.LEFT
        }

        // ------------------------------------------------------------ toque (só ao posicionar)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = placing
            if (p == NONE) return false
            val real = ScreenInfo.realSize(ctx)
            if (real.first <= 0 || real.second <= 0) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    // rawX/rawY: posição na tela inteira.
                    val fx = (e.rawX / real.first).coerceIn(0f, 1f)
                    val fy = (e.rawY / real.second).coerceIn(0f, 1f)
                    when (p) {
                        AIM -> { aimX = fx; aimY = fy }
                        FIRE -> { fireX = fx; fireY = fy }
                        READ -> { readX = fx; readY = fy }
                        else -> { dragX = fx; dragY = fy }
                    }
                    if (e.actionMasked == MotionEvent.ACTION_UP) save()
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> save()
            }
            return true
        }
    }
}
