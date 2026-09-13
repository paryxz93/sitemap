package it.webfuturo.airmouse.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Touchpad a schermo: sorgente di input che non richiede alcun SDK esterno.
 *
 * Non e' un ripiego di comodo. Serve a tre cose concrete:
 *
 *  1. Rende l'app collaudabile end to end senza l'AAR Samsung, che non e'
 *     ridistribuibile. Tutta la catena a valle (filtro, ballistica, overlay,
 *     iniezione del gesto) e' identica, quindi cio' che funziona qui funziona
 *     con la penna.
 *  2. Riproduce esattamente la topologia della S Pen: una superficie di
 *     movimento piu' UN SOLO pulsante. Cosi' la macchina a stati dei click
 *     riceve gli stessi eventi grezzi in entrambi i casi.
 *  3. In DeX resta utile per conto suo: il telefono diventa il trackpad del
 *     monitor esterno.
 *
 * La vista viene creata qui ma e' il servizio ad agganciarla a una finestra,
 * perche' solo lui sa su quale display va messa.
 */
class TouchpadInputSource : InputSource {

    override val displayName: String = "Touchpad a schermo"

    private var listener: InputSource.Listener? = null
    private var view: TouchpadView? = null

    override fun availability(context: Context): SourceAvailability = SourceAvailability.AVAILABLE

    override fun start(context: Context, listener: InputSource.Listener): Boolean {
        this.listener = listener
        view = TouchpadView(context, listener)
        listener.onStatusChanged("Touchpad attivo")
        return true
    }

    override fun stop(context: Context) {
        listener = null
        view = null
    }

    /** Vista da agganciare a una finestra. Null se la sorgente non e' partita. */
    fun currentView(): View? = view

    /**
     * Superficie di input.
     *
     * Zona alta: movimento. Trascinando si muove il cursore, senza premere
     * nulla, esattamente come muovere la penna in aria.
     *
     * Barra bassa: il pulsante. Inoltra ACTION_DOWN e ACTION_UP grezzi alla
     * macchina a stati, che ne ricava tap, doppio click e trascinamento. Qui non
     * si interpreta nulla, per non avere due implementazioni della stessa logica.
     */
    @SuppressLint("ViewConstructor")
    private class TouchpadView(
        context: Context,
        private val listener: InputSource.Listener
    ) : View(context) {

        private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 22, 26, 32)
        }
        private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 38, 46, 58)
        }
        private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 77, 163, 255)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 235, 240, 248)
            textAlign = Paint.Align.CENTER
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 235, 240, 248)
            textAlign = Paint.Align.CENTER
        }

        private val padRect = RectF()
        private val buttonRect = RectF()

        private var buttonPressed = false

        /** Puntatore che sta muovendo il cursore, per identificativo. */
        private var movePointerId = MotionEvent.INVALID_POINTER_ID
        private var lastMoveX = 0f
        private var lastMoveY = 0f

        /** Puntatore che tiene premuto il pulsante. */
        private var buttonPointerId = MotionEvent.INVALID_POINTER_ID

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val buttonHeight = h * BUTTON_HEIGHT_FRACTION
            padRect.set(0f, 0f, w.toFloat(), h - buttonHeight)
            buttonRect.set(0f, h - buttonHeight, w.toFloat(), h.toFloat())
            labelPaint.textSize = buttonHeight * 0.34f
            hintPaint.textSize = buttonHeight * 0.28f
        }

        override fun onDraw(canvas: Canvas) {
            val radius = width * 0.04f
            canvas.drawRoundRect(padRect, radius, radius, padPaint)
            canvas.drawRoundRect(
                buttonRect, radius, radius,
                if (buttonPressed) buttonPressedPaint else buttonPaint
            )

            canvas.drawText(
                "Trascina per muovere il cursore",
                padRect.centerX(),
                padRect.centerY() + hintPaint.textSize / 3f,
                hintPaint
            )
            canvas.drawText(
                "TAP click   |   2 TAP lungo   |   TIENI trascina",
                buttonRect.centerX(),
                buttonRect.centerY() + labelPaint.textSize / 3f,
                labelPaint
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val index = event.actionIndex
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val id = event.getPointerId(index)

                    if (buttonRect.contains(x, y)) {
                        if (buttonPointerId == MotionEvent.INVALID_POINTER_ID) {
                            buttonPointerId = id
                            buttonPressed = true
                            invalidate()
                            listener.onButtonDown()
                        }
                    } else if (movePointerId == MotionEvent.INVALID_POINTER_ID) {
                        movePointerId = id
                        lastMoveX = x
                        lastMoveY = y
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (movePointerId != MotionEvent.INVALID_POINTER_ID) {
                        val index = event.findPointerIndex(movePointerId)
                        if (index >= 0) {
                            val x = event.getX(index)
                            val y = event.getY(index)
                            val dx = (x - lastMoveX) / NORMALIZER
                            val dy = (y - lastMoveY) / NORMALIZER
                            lastMoveX = x
                            lastMoveY = y
                            // Stesso contratto della S Pen: delta normalizzati.
                            listener.onMotionDelta(dx, dy, System.nanoTime())
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val id = event.getPointerId(event.actionIndex)
                    releasePointer(id)
                }

                MotionEvent.ACTION_CANCEL -> {
                    releasePointer(buttonPointerId)
                    releasePointer(movePointerId)
                }
            }
            return true
        }

        private fun releasePointer(id: Int) {
            if (id == MotionEvent.INVALID_POINTER_ID) return
            if (id == buttonPointerId) {
                buttonPointerId = MotionEvent.INVALID_POINTER_ID
                buttonPressed = false
                invalidate()
                listener.onButtonUp()
            }
            if (id == movePointerId) {
                movePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }

        companion object {
            private const val BUTTON_HEIGHT_FRACTION = 0.30f

            /**
             * Converte i pixel del dito in unita' normalizzate. Il valore
             * coincide con la sensibilita' di default del PointerEngine, cosi'
             * a impostazioni di fabbrica il rapporto dito/cursore e' circa 1:1
             * e l'accelerazione fa il resto.
             */
            private const val NORMALIZER = 2600f
        }
    }
}
