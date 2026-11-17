package com.sensi.observer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço de acessibilidade OPCIONAL. Só é usado na calibração de resposta: faz arrastos de teste
 * de tamanho conhecido para medir quanto a câmera do jogo gira por pixel de dedo.
 * Ele não lê toques, telas nem textos (onAccessibilityEvent não faz nada).
 */
class ObserverAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ObserverAccessibilityService? = null
    }

    /** true enquanto um arrasto está em andamento. */
    @Volatile
    var busy = false

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Arrasta o dedo em linha reta de (fromX, fromY) até (toX, toY) em [durationMs].
     * [onDone] recebe (concluiu, instante de início, instante de fim), em System.nanoTime().
     */
    fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
        onDone: (Boolean, Long, Long) -> Unit
    ) {
        if (busy) {
            onDone(false, 0L, 0L)
            return
        }
        busy = true
        main.post {
            var startNs = 0L
            try {
                val size = ScreenInfo.realSize(this)
                val maxX = (size.first - 1).toFloat()
                val maxY = (size.second - 1).toFloat()
                val path = Path().apply {
                    moveTo(fromX.coerceIn(0f, maxX), fromY.coerceIn(0f, maxY))
                    lineTo(toX.coerceIn(0f, maxX), toY.coerceIn(0f, maxY))
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                startNs = System.nanoTime()
                val started = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        busy = false
                        onDone(true, startNs, System.nanoTime())
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        busy = false
                        onDone(false, startNs, System.nanoTime())
                    }
                }, null)
                if (!started) {
                    busy = false
                    onDone(false, startNs, System.nanoTime())
                }
            } catch (e: Exception) {
                busy = false
                onDone(false, startNs, System.nanoTime())
            }
        }
    }
}
