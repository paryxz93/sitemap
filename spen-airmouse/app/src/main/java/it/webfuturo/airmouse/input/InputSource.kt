package it.webfuturo.airmouse.input

import android.content.Context

/** Disponibilita' di una sorgente di input sul dispositivo corrente. */
enum class SourceAvailability {
    /** Utilizzabile subito. */
    AVAILABLE,

    /** Le classi ci sono ma il dispositivo o la penna non supportano la funzione. */
    UNSUPPORTED,

    /** L'SDK non e' presente nell'APK. */
    SDK_MISSING
}

/**
 * Una sorgente qualsiasi capace di produrre delta di movimento e pressioni di
 * un pulsante.
 *
 * L'astrazione esiste per una ragione precisa: la catena a valle
 * (filtro, ballistica, overlay, iniezione del gesto) e' la parte difficile e
 * non deve sapere da dove arrivano i numeri. La S Pen e il touchpad a schermo
 * entrano dallo stesso imbuto, quindi il resto dell'app si puo' collaudare
 * anche su un dispositivo senza penna.
 */
interface InputSource {

    /** Nome leggibile, mostrato nella schermata di stato. */
    val displayName: String

    fun availability(context: Context): SourceAvailability

    /** @return true se la sorgente e' partita. */
    fun start(context: Context, listener: Listener): Boolean

    fun stop(context: Context)

    interface Listener {
        /**
         * @param deltaX spostamento normalizzato, tipicamente in [-1, 1]
         * @param deltaY come sopra
         * @param eventNanos istante dell'evento da System.nanoTime()
         */
        fun onMotionDelta(deltaX: Float, deltaY: Float, eventNanos: Long)

        fun onButtonDown()

        fun onButtonUp()

        /** Cambio di stato da mostrare in interfaccia, ad esempio errori di connessione. */
        fun onStatusChanged(status: String)
    }
}
