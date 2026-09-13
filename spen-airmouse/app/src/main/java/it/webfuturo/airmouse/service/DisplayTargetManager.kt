package it.webfuturo.airmouse.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.util.Log
import android.view.Display
import android.view.WindowManager

/** Display su cui vivono cursore e iniezione, con le sue dimensioni reali. */
data class DisplayTarget(
    val display: Display,
    val displayId: Int,
    val width: Int,
    val height: Int
) {
    val isExternal: Boolean get() = displayId != Display.DEFAULT_DISPLAY
}

/**
 * Decide su quale schermo lavorare e segnala i cambi.
 *
 * Con DeX attivo il monitor esterno e' un Display distinto, non un'estensione
 * di quello del telefono: ha id, dimensioni e densita' proprie. Cursore e
 * iniezione devono seguirlo insieme, altrimenti si disallineano e il click
 * finisce dove il cursore non e'.
 *
 * La scelta e' volutamente semplice: se esiste un display esterno acceso, e'
 * lui il bersaglio. E' il comportamento che l'utente si aspetta, perche' se ha
 * collegato un monitor e' li' che sta guardando.
 */
class DisplayTargetManager(
    private val context: Context,
    handler: Handler,
    private val onTargetChanged: (DisplayTarget) -> Unit
) {

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var current: DisplayTarget? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reevaluate()
        override fun onDisplayRemoved(displayId: Int) = reevaluate()
        override fun onDisplayChanged(displayId: Int) = reevaluate()
    }

    init {
        displayManager.registerDisplayListener(listener, handler)
    }

    fun release() {
        displayManager.unregisterDisplayListener(listener)
    }

    fun currentTarget(): DisplayTarget? = current

    /** Forza un ricalcolo, ad esempio all'avvio del servizio o a una rotazione. */
    fun reevaluate() {
        val target = resolveTarget() ?: return
        val previous = current
        // Anche a display invariato le dimensioni cambiano con la rotazione,
        // quindi il confronto include la geometria.
        if (previous != null &&
            previous.displayId == target.displayId &&
            previous.width == target.width &&
            previous.height == target.height
        ) {
            return
        }
        current = target
        Log.i(
            TAG,
            "Display bersaglio: id=${target.displayId} ${target.width}x${target.height} " +
                "esterno=${target.isExternal}"
        )
        onTargetChanged(target)
    }

    private fun resolveTarget(): DisplayTarget? {
        val displays = displayManager.displays ?: return null

        val external = displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY && it.state == Display.STATE_ON
        }
        val chosen = external
            ?: displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
            ?: displays.firstOrNull()
            ?: return null

        val size = measure(chosen) ?: return null
        return DisplayTarget(chosen, chosen.displayId, size.first, size.second)
    }

    /**
     * Dimensioni reali del display, incluse le zone sotto le barre di sistema.
     * Sono le coordinate in cui ragiona dispatchGesture, quindi devono essere
     * le stesse in cui posizioniamo il cursore.
     */
    private fun measure(display: Display): Pair<Int, Int>? = try {
        val displayContext = context.createDisplayContext(display)
        val windowManager =
            displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = windowManager.maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } catch (t: Throwable) {
        Log.e(TAG, "Impossibile misurare il display ${display.displayId}", t)
        null
    }

    companion object {
        private const val TAG = "DisplayTargetManager"
    }
}
