package it.webfuturo.airmouse.core

/**
 * Posizione del cursore in pixel, sul display attualmente bersaglio.
 * Classe mutabile riusata a ogni evento: la catena gira anche a 100 Hz e
 * allocare un oggetto per campione darebbe pressione inutile sul GC.
 */
class PointerPosition {
    var x: Float = 0f
    var y: Float = 0f
}

/**
 * Trasforma i delta della sorgente di input in una posizione assoluta,
 * stabile, dentro i limiti del display bersaglio.
 *
 * Catena, nell'ordine:
 *
 *   delta normalizzato
 *     -> dead zone radiale        (toglie il micro tremore della mano)
 *     -> ballistica non lineare   (precisione da fermi, velocita' nei tragitti lunghi)
 *     -> scala in pixel           (normalizzata sulla diagonale del display)
 *     -> integrazione             (da delta a posizione assoluta)
 *     -> One Euro                 (toglie il rumore residuo senza aggiungere ritardo)
 *     -> clamp ai bordi
 *
 * La dead zone sta PRIMA dell'integrazione di proposito: applicata dopo
 * lascerebbe accumulare la deriva del rumore, e il cursore scivolerebbe da solo.
 */
class PointerEngine {

    // --- Parametri regolabili dall'utente -----------------------------------

    /**
     * Pixel percorsi per un delta normalizzato pieno (valore 1.0), riferiti a
     * un display con diagonale di RIFERIMENTO_DIAGONALE_PX pixel.
     */
    var sensitivity: Float = 2600f

    /**
     * Soglia sotto la quale il movimento viene considerato tremore e scartato,
     * in unita' normalizzate. Troppo alta e il cursore diventa a scatti.
     */
    var deadZone: Float = 0.004f

    /**
     * Quanto la velocita' amplifica il guadagno. A 0 la risposta e' lineare:
     * preciso ma servono molti gesti per attraversare lo schermo.
     */
    var accelerationGain: Float = 1.9f

    /** Velocita' normalizzata, in unita' al secondo, a cui il guadagno raddoppia. */
    var accelerationReference: Float = 1.4f

    private val filter = OneEuroFilter2D()

    fun setFilterParameters(minCutoff: Float, beta: Float) =
        filter.setParameters(minCutoff, beta)

    // --- Stato --------------------------------------------------------------

    private var boundsWidth: Int = 0
    private var boundsHeight: Int = 0
    private var displayScale: Float = 1f

    /** Posizione integrata non filtrata. */
    private var rawX = 0f
    private var rawY = 0f

    private var lastEventNanos: Long = 0L

    private val output = PointerPosition()

    /**
     * Aggiorna le dimensioni del display bersaglio. Va richiamato quando DeX
     * viene attivato o spento, o quando il display cambia rotazione.
     * La posizione corrente viene riproporzionata invece che azzerata, cosi'
     * il cursore non salta in un angolo al cambio di schermo.
     */
    fun setBounds(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == boundsWidth && height == boundsHeight) return

        val ratioX = if (boundsWidth > 0) rawX / boundsWidth else 0.5f
        val ratioY = if (boundsHeight > 0) rawY / boundsHeight else 0.5f

        boundsWidth = width
        boundsHeight = height

        // La sensibilita' viene normalizzata sulla diagonale, cosi' lo stesso
        // gesto della penna copre la stessa frazione di schermo sul telefono
        // e sul monitor esterno in DeX.
        val diagonal = magnitude(width.toFloat(), height.toFloat())
        displayScale = diagonal / REFERENCE_DIAGONAL_PX

        rawX = ratioX * width
        rawY = ratioY * height
        filter.reset()
    }

    fun boundsWidth(): Int = boundsWidth

    fun boundsHeight(): Int = boundsHeight

    /** Riporta il cursore al centro del display bersaglio. */
    fun center() {
        rawX = boundsWidth / 2f
        rawY = boundsHeight / 2f
        filter.reset()
        lastEventNanos = 0L
        output.x = rawX
        output.y = rawY
    }

    /** Ultima posizione calcolata, senza rielaborare nulla. */
    fun currentPosition(): PointerPosition = output

    /**
     * Consuma un campione della sorgente di input.
     *
     * @param deltaX spostamento normalizzato sull'asse X, tipicamente in [-1, 1]
     * @param deltaY spostamento normalizzato sull'asse Y
     * @param eventNanos istante dell'evento, da System.nanoTime()
     * @return la posizione filtrata e limitata ai bordi
     */
    fun onDelta(deltaX: Float, deltaY: Float, eventNanos: Long): PointerPosition {
        if (boundsWidth == 0 || boundsHeight == 0) return output

        val dt = computeDeltaSeconds(eventNanos)

        // 1. Dead zone radiale. Radiale e non per asse: una soglia per asse
        //    squadrerebbe i movimenti diagonali lenti.
        val rawMagnitude = magnitude(deltaX, deltaY)
        if (rawMagnitude < deadZone) {
            // Il filtro continua a girare sull'ultima posizione, cosi' quando
            // la penna riparte non c'e' uno scalino dovuto allo stato fermo.
            return emit(dt)
        }

        // Sottrae la soglia invece di azzerare sotto soglia: evita il salto
        // discontinuo nell'istante in cui il movimento supera la dead zone.
        val scale = (rawMagnitude - deadZone) / rawMagnitude
        val dzX = deltaX * scale
        val dzY = deltaY * scale

        // 2. Ballistica: guadagno crescente con la velocita'.
        val speed = magnitude(dzX, dzY) / dt
        val gain = 1f + accelerationGain * (speed / accelerationReference)

        // 3. In pixel, riscalato sulla diagonale del display bersaglio.
        val pixelsPerUnit = sensitivity * displayScale * gain

        rawX += dzX * pixelsPerUnit
        rawY += dzY * pixelsPerUnit

        // Il clamp sull'accumulatore impedisce che tenendo la penna contro un
        // bordo si accumuli un debito da smaltire prima di rientrare.
        rawX = rawX.coerceIn(0f, boundsWidth.toFloat())
        rawY = rawY.coerceIn(0f, boundsHeight.toFloat())

        return emit(dt)
    }

    private fun emit(dt: Float): PointerPosition {
        output.x = filter.filterX(rawX, dt).coerceIn(0f, boundsWidth.toFloat())
        output.y = filter.filterY(rawY, dt).coerceIn(0f, boundsHeight.toFloat())
        return output
    }

    private fun computeDeltaSeconds(eventNanos: Long): Float {
        val previous = lastEventNanos
        lastEventNanos = eventNanos
        if (previous == 0L) return DEFAULT_DT
        val dt = (eventNanos - previous) / 1_000_000_000f
        return if (dt <= 0f) DEFAULT_DT else dt
    }

    companion object {
        /** Diagonale di un pannello 1440x3088, quello dell'S23 Ultra. */
        private const val REFERENCE_DIAGONAL_PX = 3407f
        private const val DEFAULT_DT = 0.016f
    }
}
