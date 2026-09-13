package it.webfuturo.airmouse.service

import android.os.Handler
import android.os.Looper

/**
 * Ponte fra l'AccessibilityService e la schermata di stato.
 *
 * Vivono nello stesso processo ma hanno cicli di vita indipendenti: il servizio
 * puo' essere attivo con l'activity chiusa, e l'activity puo' aprirsi con il
 * servizio spento. Un singleton osservabile e' la forma piu' semplice che
 * regge entrambi i casi senza tenere in vita nessuno dei due.
 */
object AirMouseBus {

    data class Status(
        val serviceRunning: Boolean = false,
        val activeSource: String = "nessuna",
        val sourceStatus: String = "servizio non attivo",
        val displayDescription: String = "sconosciuto",
        val motionEvents: Long = 0L,
        val cursorX: Int = 0,
        val cursorY: Int = 0
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<(Status) -> Unit>()

    @Volatile
    private var current = Status()

    fun current(): Status = current

    fun observe(observer: (Status) -> Unit) {
        observers.add(observer)
        observer(current)
    }

    fun removeObserver(observer: (Status) -> Unit) {
        observers.remove(observer)
    }

    fun update(transform: (Status) -> Status) {
        val next = transform(current)
        if (next == current) return
        current = next
        mainHandler.post {
            // Copia difensiva: un osservatore puo' disiscriversi dalla callback.
            observers.toList().forEach { it(next) }
        }
    }

    fun reset() = update { Status() }
}
