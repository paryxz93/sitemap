package it.webfuturo.airmouse.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * Aggancia viste a finestre di sistema sul display giusto.
 *
 * SCELTA DEL TIPO DI FINESTRA
 * Si tenta per primo TYPE_ACCESSIBILITY_OVERLAY. Rispetto a
 * TYPE_APPLICATION_OVERLAY ha tre vantaggi concreti:
 *   - non richiede il permesso SYSTEM_ALERT_WINDOW, quindi nessun passaggio
 *     nelle impostazioni e nessun permesso speciale da spiegare all'utente;
 *   - sta piu' in alto nell'ordine delle finestre, quindi il cursore non
 *     sparisce sotto dialoghi e pannelli di sistema;
 *   - segue il ciclo di vita del servizio: se l'accessibilita' viene spenta,
 *     la finestra se ne va da sola invece di restare orfana a schermo.
 * TYPE_APPLICATION_OVERLAY resta come ripiego, perche' non tutte le
 * combinazioni di versione e display accettano il primo tipo su uno schermo
 * secondario.
 *
 * COORDINATE
 * Le finestre vengono agganciate al display bersaglio tramite un window
 * context. Usare il WindowManager predefinito del servizio metterebbe la vista
 * sullo schermo del telefono anche con DeX attivo.
 */
class OverlayHost(private val service: AccessibilityService) {

    private data class Attachment(
        val windowManager: WindowManager,
        val view: View,
        val params: WindowManager.LayoutParams
    )

    private val attachments = mutableMapOf<View, Attachment>()

    /**
     * @param touchable false per l'overlay del cursore, che deve lasciar
     *        passare i tocchi sottostanti, true per il touchpad.
     */
    fun attach(
        view: View,
        display: Display,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        touchable: Boolean
    ): Boolean {
        detach(view)

        for (type in candidateTypes()) {
            val windowManager = windowManagerFor(display, type) ?: continue
            val params = buildParams(type, width, height, x, y, touchable)
            try {
                windowManager.addView(view, params)
                attachments[view] = Attachment(windowManager, view, params)
                Log.i(TAG, "Vista agganciata al display ${display.displayId} con tipo $type")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "addView rifiutato con tipo $type", t)
            }
        }
        Log.e(TAG, "Impossibile agganciare la vista al display ${display.displayId}")
        return false
    }

    fun updateGeometry(view: View, width: Int, height: Int, x: Int, y: Int) {
        val attachment = attachments[view] ?: return
        attachment.params.width = width
        attachment.params.height = height
        attachment.params.x = x
        attachment.params.y = y
        try {
            attachment.windowManager.updateViewLayout(view, attachment.params)
        } catch (t: Throwable) {
            Log.w(TAG, "updateViewLayout fallito", t)
        }
    }

    fun detach(view: View) {
        val attachment = attachments.remove(view) ?: return
        try {
            attachment.windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView fallito", t)
        }
    }

    fun detachAll() {
        attachments.keys.toList().forEach { detach(it) }
    }

    fun isAttached(view: View): Boolean = attachments.containsKey(view)

    private fun candidateTypes(): List<Int> = listOf(
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    )

    private fun windowManagerFor(display: Display, type: Int): WindowManager? = try {
        val displayContext = service.createDisplayContext(display)
        // Il window context lega la finestra a questo display. Senza, la vista
        // finirebbe sullo schermo predefinito.
        val windowContext = displayContext.createWindowContext(type, null)
        windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    } catch (t: Throwable) {
        Log.w(TAG, "createWindowContext fallito per tipo $type", t)
        // Alcune combinazioni rifiutano il window context per
        // TYPE_ACCESSIBILITY_OVERLAY: si ripiega sul display context nudo.
        try {
            val displayContext = service.createDisplayContext(display)
            displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } catch (inner: Throwable) {
            Log.e(TAG, "Nessun WindowManager per il display ${display.displayId}", inner)
            null
        }
    }

    private fun buildParams(
        type: Int,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        touchable: Boolean
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT)
            .apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                this.x = x
                this.y = y
                // La finestra non deve mai spingere via il contenuto sotto.
                fitInsetsTypes = 0
            }
    }

    companion object {
        private const val TAG = "OverlayHost"
    }
}
