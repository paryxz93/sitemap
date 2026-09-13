package it.webfuturo.airmouse.core

import android.os.Handler

/** Azioni riconosciute a partire dai soli eventi grezzi del pulsante. */
enum class ClickGesture {
    /** Click singolo. Viene iniettato come tocco. */
    PRIMARY_TAP,

    /**
     * Doppio click. Viene iniettato come pressione prolungata, non come tasto
     * destro: senza root un AccessibilityService puo' emettere solo eventi
     * touch, e il tasto destro del mouse non e' un evento touch.
     */
    SECONDARY_LONG_PRESS,

    /** Pulsante tenuto premuto: inizio trascinamento. */
    DRAG_BEGIN,

    /** Pulsante rilasciato durante un trascinamento. */
    DRAG_END
}

/**
 * Ricostruisce click singolo, doppio click e pressione prolungata partendo da
 * ACTION_DOWN e ACTION_UP, che sono gli unici due eventi che l'SDK Samsung
 * espone per il pulsante della S Pen. Nessuno dei tre gesti esiste come evento
 * nativo: vanno tutti dedotti dai tempi.
 *
 * Compromesso da conoscere: con [doubleClickEnabled] attivo, il click singolo
 * non puo' essere emesso prima che sia scaduta la finestra del doppio click,
 * perche' fino ad allora non si sa ancora se il secondo click arrivera'.
 * Sono circa 260 ms di ritardo su OGNI click. Disattivando il doppio click il
 * tap parte immediatamente. La scelta e' esposta all'utente nelle impostazioni.
 */
class ClickStateMachine(
    private val handler: Handler,
    private val onGesture: (ClickGesture) -> Unit
) {

    var longPressMillis: Long = 480L
    var doubleClickWindowMillis: Long = 260L

    /** Se falso, il tap viene emesso subito e il doppio click non si attiva. */
    var doubleClickEnabled: Boolean = true

    private enum class State {
        IDLE,
        /** Primo pulsante premuto, non si sa ancora se sara' tap o trascinamento. */
        FIRST_DOWN,
        /** Primo click concluso, in attesa di un eventuale secondo. */
        WAIT_SECOND_CLICK,
        /** Secondo pulsante premuto dentro la finestra del doppio click. */
        SECOND_DOWN,
        /** Pressione prolungata in corso: trascinamento attivo. */
        DRAGGING
    }

    private var state = State.IDLE

    private val longPressRunnable = Runnable { onLongPressElapsed() }
    private val singleClickRunnable = Runnable { onDoubleClickWindowElapsed() }

    /** Da chiamare su ButtonEvent.ACTION_DOWN. */
    fun onButtonDown() {
        handler.removeCallbacks(singleClickRunnable)
        handler.removeCallbacks(longPressRunnable)

        state = if (state == State.WAIT_SECOND_CLICK) State.SECOND_DOWN else State.FIRST_DOWN

        // La pressione prolungata va armata anche sul secondo click: tenere
        // premuto dopo un doppio click e' un gesto che l'utente fa
        // istintivamente per trascinare.
        handler.postDelayed(longPressRunnable, longPressMillis)
    }

    /** Da chiamare su ButtonEvent.ACTION_UP. */
    fun onButtonUp() {
        handler.removeCallbacks(longPressRunnable)

        when (state) {
            State.DRAGGING -> {
                state = State.IDLE
                onGesture(ClickGesture.DRAG_END)
            }

            State.SECOND_DOWN -> {
                state = State.IDLE
                onGesture(ClickGesture.SECONDARY_LONG_PRESS)
            }

            State.FIRST_DOWN -> {
                if (doubleClickEnabled) {
                    state = State.WAIT_SECOND_CLICK
                    handler.postDelayed(singleClickRunnable, doubleClickWindowMillis)
                } else {
                    state = State.IDLE
                    onGesture(ClickGesture.PRIMARY_TAP)
                }
            }

            else -> state = State.IDLE
        }
    }

    /** Annulla ogni gesto in corso, ad esempio alla perdita della connessione. */
    fun reset() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(singleClickRunnable)
        if (state == State.DRAGGING) onGesture(ClickGesture.DRAG_END)
        state = State.IDLE
    }

    fun isDragging(): Boolean = state == State.DRAGGING

    private fun onLongPressElapsed() {
        if (state == State.FIRST_DOWN || state == State.SECOND_DOWN) {
            state = State.DRAGGING
            onGesture(ClickGesture.DRAG_BEGIN)
        }
    }

    private fun onDoubleClickWindowElapsed() {
        if (state == State.WAIT_SECOND_CLICK) {
            state = State.IDLE
            onGesture(ClickGesture.PRIMARY_TAP)
        }
    }
}
