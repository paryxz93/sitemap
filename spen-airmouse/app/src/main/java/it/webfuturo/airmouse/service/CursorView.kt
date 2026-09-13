package it.webfuturo.airmouse.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * La freccia del cursore.
 *
 * PERCHE' UNA FINESTRA A TUTTO SCHERMO E NON UNA PICCOLA CHE SI SPOSTA
 * La strada ovvia sarebbe una finestra grande quanto l'icona, riposizionata con
 * updateViewLayout a ogni campione. E' la scelta sbagliata: ogni chiamata e' un
 * viaggio di andata e ritorno fino a WindowManagerService, quindi decine di
 * transazioni al secondo tra processi, con ritardo variabile e scatti.
 *
 * Qui la finestra e' agganciata una volta sola a tutto il display e il cursore
 * si muove DENTRO di essa, ridisegnando. Il movimento non esce mai dal
 * processo dell'app, e si invalida solo il rettangolo che e' cambiato davvero.
 *
 * Effetto collaterale utile: le coordinate del disegno coincidono per
 * costruzione con quelle passate a dispatchGesture, perche' entrambe sono
 * riferite all'origine dello stesso display. E' cosi' che si evita il difetto
 * classico di questi progetti, il click che arriva dove il cursore non e' piu'.
 */
class CursorView(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 12, 16, 22)
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        strokeJoin = Paint.Join.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 77, 163, 255)
        style = Paint.Style.FILL
    }

    private val arrow = Path()

    private var cursorX = 0f
    private var cursorY = 0f

    /** Vero durante un trascinamento: il cursore mostra un alone. */
    private var dragging = false

    init {
        // La vista non e' toccabile e non ha sfondo: deve solo disegnare.
        setWillNotDraw(false)
        buildArrowPath()
    }

    private fun buildArrowPath() {
        // Freccia classica, punta nell'origine (0,0) cosi' la posizione del
        // cursore coincide con il punto che verra' cliccato.
        arrow.reset()
        arrow.moveTo(0f, 0f)
        arrow.lineTo(0f, ARROW_HEIGHT)
        arrow.lineTo(ARROW_HEIGHT * 0.28f, ARROW_HEIGHT * 0.74f)
        arrow.lineTo(ARROW_HEIGHT * 0.46f, ARROW_HEIGHT * 1.12f)
        arrow.lineTo(ARROW_HEIGHT * 0.64f, ARROW_HEIGHT * 1.04f)
        arrow.lineTo(ARROW_HEIGHT * 0.46f, ARROW_HEIGHT * 0.67f)
        arrow.lineTo(ARROW_HEIGHT * 0.76f, ARROW_HEIGHT * 0.63f)
        arrow.close()
    }

    fun moveTo(x: Float, y: Float) {
        if (x == cursorX && y == cursorY) return
        cursorX = x
        cursorY = y
        invalidateCursorArea()
    }

    fun setDragging(value: Boolean) {
        if (dragging == value) return
        dragging = value
        invalidateCursorArea()
    }

    fun currentX(): Float = cursorX

    fun currentY(): Float = cursorY

    /**
     * Richiede il ridisegno.
     *
     * Nota su un'ottimizzazione che NON viene fatta: invalidare solo il
     * rettangolo cambiato, con invalidate(Rect), sarebbe inutile. Con
     * l'accelerazione hardware attiva il dirty rect viene ignorato e la vista
     * e' comunque ridisegnata per intero, tanto che quei metodi sono deprecati
     * dalla API 28. Il guadagno vero e' un altro ed e' gia' in essere: la
     * finestra non si muove mai, quindi il movimento del cursore non genera
     * nessuna transazione verso WindowManagerService.
     *
     * Il disegno in se' e' un solo Path su una vista senza sfondo, quindi
     * ridisegnarla tutta costa una frazione di frame.
     */
    private fun invalidateCursorArea() {
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(cursorX, cursorY)
        if (dragging) {
            canvas.drawCircle(0f, 0f, ARROW_HEIGHT * 0.55f, haloPaint)
        }
        canvas.drawPath(arrow, fillPaint)
        canvas.drawPath(arrow, strokePaint)
        canvas.restoreToCount(saved)
    }

    companion object {
        private const val ARROW_HEIGHT = 34f
    }
}
