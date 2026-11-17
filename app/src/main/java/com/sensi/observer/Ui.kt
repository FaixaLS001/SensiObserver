package com.sensi.observer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.sensi.observer.engine.DeviceInfo

/**
 * Android 12+ bloqueia os toques que passam por camadas de outro app quando a opacidade somada
 * delas passa de 0,8 (proteção contra "tapjacking"). Só conta a opacidade da JANELA
 * (WindowManager.LayoutParams.alpha), não o que está desenhado nela. A camada de marcadores
 * (tela cheia) usa o valor abaixo e reforça o desenho na mesma proporção.
 */
object TouchSafe {
    const val MARKER_WINDOW_ALPHA = 0.35f
}

/** Tamanho real da tela (muda quando o aparelho gira) e dados do aparelho. */
object ScreenInfo {
    fun realSize(ctx: Context): Pair<Int, Int> {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return Pair(m.widthPixels, m.heightPixels)
    }

    /** Dados do aparelho sempre com a tela deitada (largura maior que a altura), como o jogo. */
    fun deviceInfo(ctx: Context): DeviceInfo {
        val size = realSize(ctx)
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = ctx.resources.displayMetrics
        val hz = display?.refreshRate ?: 60f
        return DeviceInfo(
            widthPx = maxOf(size.first, size.second),
            heightPx = minOf(size.first, size.second),
            densityDpi = metrics.densityDpi,
            xdpi = metrics.xdpi,
            ydpi = metrics.ydpi,
            refreshHz = if (hz > 20f) hz else 60f
        )
    }
}

/** Peças de interface no mesmo visual do Vision Paint Macro (cartões numerados, roxo/ciano). */
class UiKit(private val ctx: Context) {

    enum class Kind { PRIMARY, TONAL, DANGER, STOP }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun dpf(v: Float): Float = v * ctx.resources.displayMetrics.density

    fun color(id: Int): Int = ContextCompat.getColor(ctx, id)

    fun lp(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(radiusDp)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    fun oval(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    // ------------------------------------------------------------------ blocos

    fun header(title: String, subtitle: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_logo)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#4C35CC"), Color.parseColor("#271A6B"))
            ).apply { cornerRadius = dpf(14f) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })
        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) }
        }
        titles.addView(TextView(ctx).apply {
            text = title
            textSize = 20f
            setTextColor(color(R.color.vp_text))
            setTypeface(typeface, Typeface.BOLD)
        })
        titles.addView(TextView(ctx).apply {
            text = subtitle
            textSize = 13f
            setTextColor(color(R.color.vp_text_secondary))
        })
        row.addView(titles)
        return row
    }

    /** Cartão de seção: número (opcional), título e o conteúdo logo abaixo. */
    fun card(n: Int?, title: String): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(color(R.color.vp_surface), 18f, color(R.color.vp_stroke))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        if (n != null) {
            head.addView(TextView(ctx).apply {
                text = n.toString()
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.vp_on_accent))
                setTypeface(typeface, Typeface.BOLD)
                background = oval(color(R.color.vp_accent))
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) }
            })
        }
        head.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(color(R.color.vp_text))
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(head)
        return box
    }

    /** Texto de apoio (cinza, menor). */
    fun note(t: String): TextView = TextView(ctx).apply {
        text = t
        textSize = 13f
        setTextColor(color(R.color.vp_text_secondary))
        setLineSpacing(0f, 1.1f)
        setPadding(0, dp(4), 0, dp(4))
    }

    /** Linha de texto normal do relatório (com marcador). */
    fun line(t: String, tint: Int? = null): TextView = TextView(ctx).apply {
        text = "•  $t"
        textSize = 14f
        setTextColor(tint ?: color(R.color.vp_text))
        setLineSpacing(0f, 1.12f)
        setPadding(0, dp(4), 0, dp(4))
    }

    fun chip(): TextView = TextView(ctx).apply {
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    fun setChip(tv: TextView, ok: Boolean, okText: String, badText: String) {
        val tint = color(if (ok) R.color.vp_success else R.color.vp_warning)
        tv.text = if (ok) "✔ $okText" else "✘ $badText"
        tv.setTextColor(tint)
        tv.background = rounded(ColorUtils.setAlphaComponent(tint, 0x26), 12f)
    }

    /** Linha com o nome à esquerda e o estado (chip) à direita. */
    fun statusRow(title: String, chip: TextView): View {
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        top.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTextColor(color(R.color.vp_text))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        top.addView(chip)
        return top
    }

    /** Nome da permissão, estado e o botão que leva à configuração. */
    fun permRow(title: String, chip: TextView, action: String, onClick: () -> Unit): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(statusRow(title, chip))
        box.addView(button(action, Kind.TONAL, onClick), lp(2))
        return box
    }

    // ------------------------------------------------------------------ botões

    fun styleButton(b: Button, kind: Kind) {
        val accentText = color(R.color.vp_accent_text)
        val danger = color(R.color.vp_danger_fill)
        val white = color(R.color.vp_on_accent)
        val (fill, fg, ripple) = when (kind) {
            Kind.PRIMARY -> Triple(color(R.color.vp_accent), white, 0x33FFFFFF)
            Kind.TONAL -> Triple(
                color(R.color.vp_surface_variant),
                accentText,
                ColorUtils.setAlphaComponent(accentText, 0x33)
            )
            Kind.DANGER -> Triple(
                ColorUtils.setAlphaComponent(danger, 0x1F),
                danger,
                ColorUtils.setAlphaComponent(danger, 0x33)
            )
            Kind.STOP -> Triple(danger, white, 0x33FFFFFF)
        }
        b.setTextColor(fg)
        b.background = RippleDrawable(
            ColorStateList.valueOf(ripple),
            rounded(fill, 12f),
            rounded(Color.WHITE, 12f)
        )
    }

    fun button(label: String, kind: Kind, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = 0
            minimumHeight = dp(48)
            stateListAnimator = null
            styleButton(this, kind)
            setOnClickListener { onClick() }
        }

    fun switch(label: String, start: Boolean, onChange: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(ctx).apply {
            text = label
            textSize = 15f
            setTextColor(color(R.color.vp_text))
            isChecked = start
            setPadding(0, dp(8), 0, dp(8))
            val on = color(R.color.vp_accent)
            val off = color(R.color.vp_text_secondary)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, off))
            trackTintList = ColorStateList(states, intArrayOf(on, ColorUtils.setAlphaComponent(off, 0x66)))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    /** Nome à esquerda, valor à direita e a barra embaixo. */
    fun slider(
        label: String,
        lo: Int,
        hi: Int,
        start: Int,
        unit: String,
        tint: Int? = null,
        onChange: (Int) -> Unit
    ): View = sliderPair(label, lo, hi, start, unit, tint, onChange).first

    fun sliderPair(
        label: String,
        lo: Int,
        hi: Int,
        start: Int,
        unit: String,
        tint: Int?,
        onChange: (Int) -> Unit
    ): Pair<View, SeekBar> {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(2))
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val valueTv = TextView(ctx).apply {
            text = "$start$unit"
            textSize = 13f
            setTextColor(color(R.color.vp_accent_text))
            setTypeface(typeface, Typeface.BOLD)
        }
        head.addView(TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(color(R.color.vp_text))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        head.addView(valueTv)

        val barColor = tint ?: color(R.color.vp_accent)
        val sb = SeekBar(ctx).apply {
            max = hi - lo
            progress = (start - lo).coerceIn(0, hi - lo)
            progressTintList = ColorStateList.valueOf(barColor)
            thumbTintList = ColorStateList.valueOf(barColor)
            progressBackgroundTintList =
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(barColor, 0x44))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + lo
                    valueTv.text = "$v$unit"
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(head)
        box.addView(sb)
        return Pair(box, sb)
    }
}
