package it.webfuturo.airmouse.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Filtro passa basso del primo ordine a coefficiente variabile.
 *
 *   y[i] = a * x[i] + (1 - a) * y[i-1]
 *
 * con "a" ricavato dalla frequenza di taglio e dal passo temporale reale,
 * non da un campionamento costante. Serve perche' gli eventi BLE della S Pen
 * non arrivano a cadenza fissa: usare un alpha costante darebbe uno
 * smorzamento diverso a ogni variazione di frequenza.
 */
private class LowPass {
    private var initialized = false
    private var previous = 0f

    fun reset() {
        initialized = false
        previous = 0f
    }

    fun filter(value: Float, alpha: Float): Float {
        val out = if (!initialized) {
            initialized = true
            value
        } else {
            alpha * value + (1f - alpha) * previous
        }
        previous = out
        return out
    }

    fun lastValue(): Float = previous
    fun isInitialized(): Boolean = initialized
}

/**
 * Filtro One Euro (Casiez, Roussel, Vogel, CHI 2012) su un singolo asse.
 *
 * Perche' questo e non una media mobile esponenziale semplice: la media mobile
 * impone un compromesso fisso. Se la tari per togliere il tremolio quando la
 * penna e' ferma, introduce un ritardo percepibile quando la muovi in fretta;
 * se la tari per essere reattiva, il cursore trema da fermo.
 *
 * One Euro rompe il compromesso rendendo la frequenza di taglio funzione
 * della velocita' del segnale:
 *
 *   cutoff = minCutoff + beta * |velocita' filtrata|
 *
 * Da fermo la velocita' e' circa zero, il taglio scende a minCutoff e il
 * segnale viene smorzato molto: niente tremolio. In movimento rapido il taglio
 * sale, il filtro lascia passare e il ritardo crolla.
 *
 * Taratura dei parametri, nell'ordine:
 *  1. beta = 0, abbassa minCutoff finche' il cursore e' fermo da fermo.
 *  2. alza beta finche' il ritardo nei movimenti veloci sparisce.
 */
class OneEuroFilter(
    /** Frequenza di taglio a velocita' nulla, in Hz. Piu' bassa, piu' stabile. */
    var minCutoff: Float = 1.0f,
    /** Guadagno sulla velocita'. Piu' alto, meno ritardo ma piu' tremolio. */
    var beta: Float = 0.007f,
    /** Taglio del filtro applicato alla derivata. Raramente va toccato. */
    var derivativeCutoff: Float = 1.0f
) {
    private val valueFilter = LowPass()
    private val derivativeFilter = LowPass()
    private var lastRawValue = 0f
    private var hasLastRaw = false

    fun reset() {
        valueFilter.reset()
        derivativeFilter.reset()
        hasLastRaw = false
        lastRawValue = 0f
    }

    /**
     * @param value valore grezzo
     * @param dtSeconds tempo trascorso dal campione precedente, in secondi
     */
    fun filter(value: Float, dtSeconds: Float): Float {
        // Protezione contro dt degenere: eventi duplicati o clock che torna
        // indietro produrrebbero alpha fuori scala.
        val dt = dtSeconds.coerceIn(MIN_DT, MAX_DT)

        // Derivata grezza del segnale, poi filtrata per non far dipendere il
        // taglio adattivo dal rumore stesso che stiamo cercando di togliere.
        val rawDerivative = if (hasLastRaw) (value - lastRawValue) / dt else 0f
        lastRawValue = value
        hasLastRaw = true

        val filteredDerivative =
            derivativeFilter.filter(rawDerivative, alphaFor(derivativeCutoff, dt))

        val adaptiveCutoff = minCutoff + beta * abs(filteredDerivative)
        return valueFilter.filter(value, alphaFor(adaptiveCutoff, dt))
    }

    private fun alphaFor(cutoffHz: Float, dt: Float): Float {
        // tau = 1 / (2 * pi * fc);  alpha = 1 / (1 + tau / dt)
        val tau = 1.0f / (TWO_PI * cutoffHz.coerceAtLeast(MIN_CUTOFF))
        return (1.0f / (1.0f + tau / dt)).coerceIn(0f, 1f)
    }

    companion object {
        private const val TWO_PI = 6.2831855f
        private const val MIN_CUTOFF = 0.0001f
        private const val MIN_DT = 0.001f      // 1 ms
        private const val MAX_DT = 0.100f      // 100 ms
    }
}

/** Coppia di filtri One Euro con parametri condivisi, per le due coordinate. */
class OneEuroFilter2D(
    minCutoff: Float = 1.0f,
    beta: Float = 0.007f
) {
    private val fx = OneEuroFilter(minCutoff, beta)
    private val fy = OneEuroFilter(minCutoff, beta)

    fun setParameters(minCutoff: Float, beta: Float) {
        fx.minCutoff = minCutoff
        fx.beta = beta
        fy.minCutoff = minCutoff
        fy.beta = beta
    }

    fun reset() {
        fx.reset()
        fy.reset()
    }

    fun filterX(x: Float, dt: Float): Float = fx.filter(x, dt)
    fun filterY(y: Float, dt: Float): Float = fy.filter(y, dt)
}

/** Modulo di un vettore, estratto per leggibilita' nei call site. */
fun magnitude(x: Float, y: Float): Float = sqrt(x * x + y * y)
