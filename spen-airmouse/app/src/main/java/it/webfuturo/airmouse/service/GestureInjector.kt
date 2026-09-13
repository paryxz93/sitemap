package it.webfuturo.airmouse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Display

/**
 * Unico punto in cui l'app tocca il sistema al posto dell'utente.
 *
 * LIMITE STRUTTURALE, NON AGGIRABILE SENZA ROOT
 * dispatchGesture inietta eventi TOUCH, non eventi mouse. Ne discende che:
 *   - non esiste hover, quindi niente evidenziazione al passaggio;
 *   - non esiste tasto destro nativo: il click secondario viene reso come
 *     pressione prolungata, che e' il gesto touch con la stessa semantica
 *     (menu contestuale) nella quasi totalita' delle app;
 *   - non esiste rotellina: lo scroll e' un trascinamento.
 * Questi non sono difetti dell'implementazione ma della sola strada praticabile
 * senza root.
 */
class GestureInjector(
    private val service: AccessibilityService,
    private val handler: Handler
) {

    /** Display su cui iniettare. Cambia quando DeX viene attivato. */
    var targetDisplayId: Int = Display.DEFAULT_DISPLAY

    var tapDurationMillis: Long = 60L
    var longPressDurationMillis: Long = 620L

    /** Estremo raggiunto dal trascinamento in corso. */
    private var dragX = 0f
    private var dragY = 0f
    private var dragActive = false

    /** Tratto ancora in volo: serve a non sovrapporre due segmenti. */
    private var segmentInFlight = false

    /** Ultimo bersaglio richiesto mentre un segmento era in volo. */
    private var pendingDragX = 0f
    private var pendingDragY = 0f
    private var hasPendingDrag = false

    // ------------------------------------------------------------------
    // Gesti discreti
    // ------------------------------------------------------------------

    fun tap(x: Float, y: Float) {
        dispatch(buildStrokeGesture(x, y, tapDurationMillis, willContinue = false), "tap")
    }

    fun longPress(x: Float, y: Float) {
        dispatch(
            buildStrokeGesture(x, y, longPressDurationMillis, willContinue = false),
            "longPress"
        )
    }

    // ------------------------------------------------------------------
    // Trascinamento continuo
    // ------------------------------------------------------------------

    /**
     * Avvia un trascinamento. Il primo tratto dura quanto una pressione
     * prolungata perche' molte app richiedono di superare la soglia di long
     * press prima di accettare il trascinamento di un elemento.
     */
    fun dragBegin(x: Float, y: Float) {
        if (dragActive) dragEnd(x, y)
        dragX = x
        dragY = y
        dragActive = true
        hasPendingDrag = false
        dispatch(
            buildStrokeGesture(x, y, DRAG_HOLD_MILLIS, willContinue = true),
            "dragBegin"
        )
    }

    /**
     * Prolunga il trascinamento fino al punto indicato.
     *
     * I tratti non si possono accavallare: finche' uno e' in volo, il bersaglio
     * successivo viene solo memorizzato, e alla conclusione si parte
     * direttamente verso l'ULTIMO punto richiesto. Accodare ogni campione
     * darebbe un cursore che insegue con ritardo crescente.
     */
    fun dragTo(x: Float, y: Float) {
        if (!dragActive) return
        if (segmentInFlight) {
            pendingDragX = x
            pendingDragY = y
            hasPendingDrag = true
            return
        }
        emitDragSegment(x, y, willContinue = true)
    }

    fun dragEnd(x: Float, y: Float) {
        if (!dragActive) return
        hasPendingDrag = false
        emitDragSegment(x, y, willContinue = false)
        dragActive = false
    }

    fun isDragging(): Boolean = dragActive

    fun cancelDrag() {
        dragActive = false
        hasPendingDrag = false
    }

    private fun emitDragSegment(x: Float, y: Float, willContinue: Boolean) {
        val path = Path().apply {
            moveTo(dragX, dragY)
            lineTo(x, y)
        }
        // Un tratto di lunghezza nulla viene rifiutato dal framework.
        if (dragX == x && dragY == y) path.lineTo(x + MIN_SEGMENT_PX, y)

        val stroke = GestureDescription.StrokeDescription(
            path, 0L, DRAG_SEGMENT_MILLIS, willContinue
        )
        dragX = x
        dragY = y
        dispatch(buildGesture(stroke), if (willContinue) "dragTo" else "dragEnd")
    }

    // ------------------------------------------------------------------
    // Costruzione e invio
    // ------------------------------------------------------------------

    private fun buildStrokeGesture(
        x: Float,
        y: Float,
        durationMillis: Long,
        willContinue: Boolean
    ): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
            // Segmento minimo: un percorso composto dal solo moveTo e' vuoto e
            // viene rifiutato. Un pixel resta ampiamente dentro la soglia di
            // tolleranza del tocco, quindi il sistema lo legge come tap fermo.
            lineTo(x + MIN_SEGMENT_PX, y)
        }
        return buildGesture(
            GestureDescription.StrokeDescription(path, 0L, durationMillis, willContinue)
        )
    }

    private fun buildGesture(stroke: GestureDescription.StrokeDescription): GestureDescription {
        val builder = GestureDescription.Builder().addStroke(stroke)
        // setDisplayId esiste da API 33. Sotto quella soglia il gesto finisce
        // per forza sul display predefinito: in DeX il click arriverebbe sul
        // telefono invece che sul monitor.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setDisplayId(targetDisplayId)
        }
        return builder.build()
    }

    private fun dispatch(gesture: GestureDescription, label: String) {
        segmentInFlight = true
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                segmentInFlight = false
                flushPendingDrag()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                segmentInFlight = false
                Log.w(TAG, "Gesto annullato: $label")
                // Un trascinamento annullato non si puo' proseguire: ogni
                // continueStroke successivo verrebbe rifiutato.
                if (label.startsWith("drag")) dragActive = false
                hasPendingDrag = false
            }
        }

        val accepted = try {
            service.dispatchGesture(gesture, callback, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "dispatchGesture ha sollevato eccezione per $label", t)
            false
        }

        if (!accepted) {
            segmentInFlight = false
            Log.w(TAG, "dispatchGesture rifiutato per $label")
            if (label.startsWith("drag")) dragActive = false
        }
    }

    private fun flushPendingDrag() {
        if (!hasPendingDrag || !dragActive) return
        hasPendingDrag = false
        emitDragSegment(pendingDragX, pendingDragY, willContinue = true)
    }

    companion object {
        private const val TAG = "GestureInjector"
        private const val MIN_SEGMENT_PX = 1f
        private const val DRAG_HOLD_MILLIS = 520L
        private const val DRAG_SEGMENT_MILLIS = 40L
    }
}
