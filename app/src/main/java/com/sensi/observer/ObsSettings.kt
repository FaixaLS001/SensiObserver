package com.sensi.observer

import android.content.Context
import android.content.SharedPreferences
import com.sensi.observer.engine.Scope

/** Configurações salvas no aparelho, compartilhadas entre as telas e o serviço. */
object ObsSettings {
    private const val PREFS = "observer_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private val defaultSens = intArrayOf(130, 116, 104, 90, 80)

    // ------------------------------------------------------------------ jogo

    /** Sensibilidade que está no Free Fire agora (escala 0 a 200), por mira. */
    fun sens(scope: Int): Int = sp.getInt("sens$scope", defaultSens[scope.coerceIn(0, Scope.COUNT - 1)])

    fun setSens(scope: Int, v: Int) {
        sp.edit().putInt("sens$scope", v.coerceIn(0, 200)).apply()
    }

    /** Quantos dedos o jogador usa (2 = dois polegares, 3 e 4 = garras). */
    var fingers: Int
        get() = sp.getInt("fingers", 2)
        set(v) = sp.edit().putInt("fingers", v.coerceIn(2, 4)).apply()

    /** Escala da captura em % da tela (25 a 60). Menor = mais leve, maior = mais preciso. */
    var scalePct: Int
        get() = sp.getInt("scale", 40)
        set(v) = sp.edit().putInt("scale", v.coerceIn(25, 60)).apply()

    // ------------------------------------------------------------------ marcadores (milésimos)
    // Posições em milésimos da LARGURA (x) e da ALTURA (y) da tela deitada (o jogo é na horizontal).
    // Tamanhos em milésimos da ALTURA.

    var aimXPm: Int
        get() = sp.getInt("aimX", 500)
        set(v) = sp.edit().putInt("aimX", v.coerceIn(0, 1000)).apply()

    var aimYPm: Int
        get() = sp.getInt("aimY", 500)
        set(v) = sp.edit().putInt("aimY", v.coerceIn(0, 1000)).apply()

    /** Metade do lado da região onde o marcador de acerto (X) aparece, ao redor da mira. */
    var hitSizePm: Int
        get() = sp.getInt("hitSize", 50)
        set(v) = sp.edit().putInt("hitSize", v.coerceIn(20, 140)).apply()

    var fireXPm: Int
        get() = sp.getInt("fireX", 880)
        set(v) = sp.edit().putInt("fireX", v.coerceIn(0, 1000)).apply()

    var fireYPm: Int
        get() = sp.getInt("fireY", 680)
        set(v) = sp.edit().putInt("fireY", v.coerceIn(0, 1000)).apply()

    var fireRadiusPm: Int
        get() = sp.getInt("fireR", 70)
        set(v) = sp.edit().putInt("fireR", v.coerceIn(20, 200)).apply()

    var readXPm: Int
        get() = sp.getInt("readX", 500)
        set(v) = sp.edit().putInt("readX", v.coerceIn(0, 1000)).apply()

    var readYPm: Int
        get() = sp.getInt("readY", 420)
        set(v) = sp.edit().putInt("readY", v.coerceIn(0, 1000)).apply()

    /** Lado da área de leitura do movimento (quadrado), em milésimos da altura da tela. */
    var readSizePm: Int
        get() = sp.getInt("readSize", 480)
        set(v) = sp.edit().putInt("readSize", v.coerceIn(200, 900)).apply()

    /** Ponto onde o dedo pressiona para girar a câmera (usado só na calibração de resposta). */
    var dragXPm: Int
        get() = sp.getInt("dragX", 760)
        set(v) = sp.edit().putInt("dragX", v.coerceIn(0, 1000)).apply()

    var dragYPm: Int
        get() = sp.getInt("dragY", 520)
        set(v) = sp.edit().putInt("dragY", v.coerceIn(0, 1000)).apply()

    // ------------------------------------------------------------------ calibrações guardadas

    var fireCalOk: Boolean
        get() = sp.getBoolean("fireCalOk", false)
        private set(v) = sp.edit().putBoolean("fireCalOk", v).apply()

    val fireIdle: FloatArray
        get() = floatArrayOf(sp.getFloat("fireIdleR", 0f), sp.getFloat("fireIdleG", 0f), sp.getFloat("fireIdleB", 0f))

    var fireThreshold: Float
        get() = sp.getFloat("fireThr", 18f)
        private set(v) = sp.edit().putFloat("fireThr", v).apply()

    fun saveFireCalibration(idle: FloatArray, threshold: Float) {
        sp.edit()
            .putFloat("fireIdleR", idle[0]).putFloat("fireIdleG", idle[1]).putFloat("fireIdleB", idle[2])
            .putFloat("fireThr", threshold)
            .putBoolean("fireCalOk", true)
            .apply()
    }

    var thrHead: Int
        get() = sp.getInt("thrHead", 6)
        set(v) = sp.edit().putInt("thrHead", v.coerceIn(2, 400)).apply()

    var thrBody: Int
        get() = sp.getInt("thrBody", 10)
        set(v) = sp.edit().putInt("thrBody", v.coerceIn(2, 400)).apply()

    var hitHeadCalibrated: Boolean
        get() = sp.getBoolean("hitHeadCal", false)
        set(v) = sp.edit().putBoolean("hitHeadCal", v).apply()

    var hitBodyCalibrated: Boolean
        get() = sp.getBoolean("hitBodyCal", false)
        set(v) = sp.edit().putBoolean("hitBodyCal", v).apply()

    /** Esquece as calibrações do botão de tiro e do acerto (as de resposta ficam no [SessionStore]). */
    fun clearCalibrations() {
        sp.edit()
            .remove("fireCalOk").remove("fireIdleR").remove("fireIdleG").remove("fireIdleB").remove("fireThr")
            .remove("thrHead").remove("thrBody").remove("hitHeadCal").remove("hitBodyCal")
            .apply()
    }

    // ------------------------------------------------------------------ opções

    var showBubble: Boolean
        get() = sp.getBoolean("showBubble", true)
        set(v) = sp.edit().putBoolean("showBubble", v).apply()

    /** Mostra os números ao vivo (FPS, leitura, tiro, acertos) ao lado da bolinha. */
    var showLive: Boolean
        get() = sp.getBoolean("showLive", true)
        set(v) = sp.edit().putBoolean("showLive", v).apply()

    /** Salva a linha do tempo quadro a quadro (ms a ms) em um arquivo CSV ao finalizar. */
    var recordCsv: Boolean
        get() = sp.getBoolean("recordCsv", false)
        set(v) = sp.edit().putBoolean("recordCsv", v).apply()

    /** Esconde da leitura de movimento o que é HUD parado. */
    var useStaticMask: Boolean
        get() = sp.getBoolean("staticMask", true)
        set(v) = sp.edit().putBoolean("staticMask", v).apply()

    /** Procura o marcador de acerto só nas diagonais (X). Desligado = anel inteiro. */
    var useDiagonalHit: Boolean
        get() = sp.getBoolean("diagHit", true)
        set(v) = sp.edit().putBoolean("diagHit", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubbleX", -1)
        set(v) = sp.edit().putInt("bubbleX", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()
}
