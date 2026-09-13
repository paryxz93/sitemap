package it.webfuturo.airmouse.service

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import it.webfuturo.airmouse.core.ClickGesture
import it.webfuturo.airmouse.core.ClickStateMachine
import it.webfuturo.airmouse.core.PointerEngine
import it.webfuturo.airmouse.core.Settings
import it.webfuturo.airmouse.input.InputSource
import it.webfuturo.airmouse.input.SPenInputSource
import it.webfuturo.airmouse.input.SourceAvailability
import it.webfuturo.airmouse.input.TouchpadInputSource

/**
 * Il servizio e' l'unico componente che puo' fare due cose indispensabili senza
 * root: disegnare sopra tutte le app e generare tocchi al posto dell'utente.
 * Per questo tiene lui l'intera catena, invece di delegarla a un service
 * normale che non avrebbe nessuno dei due poteri.
 *
 * PERCORSO DI UN CAMPIONE
 *   sorgente (thread BLE o UI)
 *     -> accumulo dei delta, senza allocazioni
 *     -> un solo drain per frame sul thread principale
 *       -> PointerEngine: dead zone, ballistica, integrazione, One Euro, clamp
 *         -> CursorView.moveTo    (disegno, resta nel processo)
 *         -> GestureInjector      (solo se un trascinamento e' in corso)
 *
 * Perche' l'accumulo e non una post per evento: gli eventi BLE arrivano su un
 * thread di sistema, mentre disegno e iniezione vanno fatti sul thread
 * principale. Inoltrare ogni campione creerebbe una coda che cresce quando il
 * thread principale e' occupato, e il cursore inseguirebbe con ritardo
 * crescente. Accumulando e drenando una volta per frame, il ritardo resta
 * limitato a un frame qualunque cosa accada a monte.
 */
class AirMouseAccessibilityService : AccessibilityService() {

    private lateinit var handler: Handler
    private lateinit var settings: Settings
    private lateinit var overlayHost: OverlayHost
    private lateinit var cursorView: CursorView
    private lateinit var injector: GestureInjector
    private lateinit var displayTargets: DisplayTargetManager
    private lateinit var clickMachine: ClickStateMachine

    private val engine = PointerEngine()

    private val spenSource = SPenInputSource()
    private val touchpadSource = TouchpadInputSource()
    private var spenActive = false
    private var touchpadActive = false

    // Accumulo dei delta fra un frame e l'altro.
    private val deltaLock = Any()
    private var pendingDeltaX = 0f
    private var pendingDeltaY = 0f
    private var pendingSamples = 0
    private var drainScheduled = false

    private var motionEventCount = 0L

    private val settingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySettings() }

    private val drainRunnable = Runnable { drainPendingDeltas() }

    // ------------------------------------------------------------------
    // Ciclo di vita
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler = Handler(Looper.getMainLooper())
        settings = Settings(this)
        overlayHost = OverlayHost(this)
        cursorView = CursorView(this)
        injector = GestureInjector(this, handler)

        clickMachine = ClickStateMachine(handler, ::onClickGesture)

        displayTargets = DisplayTargetManager(this, handler, ::onDisplayTargetChanged)

        settings.registerListener(settingsListener)
        applySettings()

        displayTargets.reevaluate()
        startSources()

        AirMouseBus.update {
            it.copy(serviceRunning = true, sourceStatus = "servizio avviato")
        }
        Log.i(TAG, "Servizio avviato")
    }

    override fun onDestroy() {
        stopSources()
        if (this::settings.isInitialized) settings.unregisterListener(settingsListener)
        if (this::displayTargets.isInitialized) displayTargets.release()
        if (this::overlayHost.isInitialized) overlayHost.detachAll()
        AirMouseBus.reset()
        Log.i(TAG, "Servizio terminato")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Non serve leggere il contenuto delle finestre: il servizio usa
        // l'accessibilita' solo per iniettare gesti e disegnare sopra le app.
        // L'evento viene comunque sfruttato per riconciliare il display, che e'
        // il momento in cui DeX puo' essere appena entrato o uscito.
        if (this::displayTargets.isInitialized) displayTargets.reevaluate()
    }

    override fun onInterrupt() {
        if (this::clickMachine.isInitialized) clickMachine.reset()
    }

    // ------------------------------------------------------------------
    // Sorgenti di input
    // ------------------------------------------------------------------

    private val inputListener = object : InputSource.Listener {
        override fun onMotionDelta(deltaX: Float, deltaY: Float, eventNanos: Long) {
            synchronized(deltaLock) {
                pendingDeltaX += deltaX
                pendingDeltaY += deltaY
                pendingSamples++
                if (drainScheduled) return
                drainScheduled = true
            }
            handler.post(drainRunnable)
        }

        override fun onButtonDown() {
            handler.post { clickMachine.onButtonDown() }
        }

        override fun onButtonUp() {
            handler.post { clickMachine.onButtonUp() }
        }

        override fun onStatusChanged(status: String) {
            AirMouseBus.update { it.copy(sourceStatus = status) }
        }
    }

    private fun startSources() {
        val availability = spenSource.availability(this)
        spenActive = when (availability) {
            SourceAvailability.AVAILABLE -> spenSource.start(this, inputListener)
            SourceAvailability.UNSUPPORTED -> {
                AirMouseBus.update {
                    it.copy(sourceStatus = "S Pen presente ma Air Motion non disponibile")
                }
                false
            }
            SourceAvailability.SDK_MISSING -> {
                AirMouseBus.update {
                    it.copy(sourceStatus = "SDK Samsung assente: vedi app/libs/README.md")
                }
                false
            }
        }

        if (settings.touchpadEnabled) {
            touchpadActive = touchpadSource.start(this, inputListener)
            attachTouchpad()
        }

        AirMouseBus.update {
            it.copy(
                activeSource = buildString {
                    if (spenActive) append(spenSource.displayName)
                    if (spenActive && touchpadActive) append(" + ")
                    if (touchpadActive) append(touchpadSource.displayName)
                    if (isEmpty()) append("nessuna")
                }
            )
        }
    }

    private fun stopSources() {
        if (spenActive) {
            spenSource.stop(this)
            spenActive = false
        }
        if (touchpadActive) {
            touchpadSource.currentView()?.let { overlayHost.detach(it) }
            touchpadSource.stop(this)
            touchpadActive = false
        }
    }

    /**
     * Il touchpad sta SEMPRE sullo schermo del telefono, anche quando il
     * cursore vive sul monitor esterno: e' il telefono che l'utente tocca.
     * Cursore e touchpad possono quindi trovarsi su display diversi.
     */
    private fun attachTouchpad() {
        val view = touchpadSource.currentView() ?: return
        val phoneDisplay = phoneDisplay() ?: return
        val metrics = measurePhoneDisplay(phoneDisplay) ?: return

        val width = (metrics.first * 0.92f).toInt()
        val height = (metrics.second * 0.34f).toInt()
        val x = (metrics.first - width) / 2
        val y = metrics.second - height - (metrics.second * 0.06f).toInt()

        overlayHost.attach(view, phoneDisplay, width, height, x, y, touchable = true)
    }

    private fun phoneDisplay(): Display? {
        val displayManager =
            getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun measurePhoneDisplay(display: Display): Pair<Int, Int>? = try {
        val context = createDisplayContext(display)
        val windowManager =
            context.getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val bounds = windowManager.maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } catch (t: Throwable) {
        Log.e(TAG, "Misura del display del telefono fallita", t)
        null
    }

    // ------------------------------------------------------------------
    // Display bersaglio
    // ------------------------------------------------------------------

    private fun onDisplayTargetChanged(target: DisplayTarget) {
        engine.setBounds(target.width, target.height)
        injector.targetDisplayId = target.displayId

        overlayHost.detach(cursorView)
        val attached = overlayHost.attach(
            view = cursorView,
            display = target.display,
            width = target.width,
            height = target.height,
            x = 0,
            y = 0,
            touchable = false
        )

        if (attached) engine.center()
        val position = engine.currentPosition()
        cursorView.moveTo(position.x, position.y)

        AirMouseBus.update {
            it.copy(
                displayDescription = "id ${target.displayId}, ${target.width}x${target.height}" +
                    if (target.isExternal) " (esterno, DeX)" else " (telefono)",
                sourceStatus = if (attached) it.sourceStatus else "overlay del cursore non agganciabile"
            )
        }

        // Il touchpad va riagganciato: cambiando display puo' essere cambiata
        // anche la geometria dello schermo del telefono.
        if (touchpadActive) {
            touchpadSource.currentView()?.let { overlayHost.detach(it) }
            attachTouchpad()
        }
    }

    // ------------------------------------------------------------------
    // Elaborazione
    // ------------------------------------------------------------------

    private fun drainPendingDeltas() {
        val dx: Float
        val dy: Float
        val samples: Int
        synchronized(deltaLock) {
            dx = pendingDeltaX
            dy = pendingDeltaY
            samples = pendingSamples
            pendingDeltaX = 0f
            pendingDeltaY = 0f
            pendingSamples = 0
            drainScheduled = false
        }
        if (samples == 0) return

        motionEventCount += samples

        val position = engine.onDelta(dx, dy, System.nanoTime())
        cursorView.moveTo(position.x, position.y)

        // Durante un trascinamento il tratto deve seguire il cursore, altrimenti
        // l'elemento trascinato resta fermo mentre la freccia si muove.
        if (injector.isDragging()) {
            injector.dragTo(position.x, position.y)
        }

        AirMouseBus.update {
            it.copy(
                motionEvents = motionEventCount,
                cursorX = position.x.toInt(),
                cursorY = position.y.toInt()
            )
        }
    }

    private fun onClickGesture(gesture: ClickGesture) {
        val position = engine.currentPosition()
        when (gesture) {
            ClickGesture.PRIMARY_TAP -> injector.tap(position.x, position.y)
            ClickGesture.SECONDARY_LONG_PRESS -> injector.longPress(position.x, position.y)
            ClickGesture.DRAG_BEGIN -> {
                cursorView.setDragging(true)
                injector.dragBegin(position.x, position.y)
            }
            ClickGesture.DRAG_END -> {
                cursorView.setDragging(false)
                injector.dragEnd(position.x, position.y)
            }
        }
    }

    private fun applySettings() {
        engine.sensitivity = settings.sensitivity
        engine.deadZone = settings.deadZone
        engine.accelerationGain = settings.accelerationGain
        engine.setFilterParameters(settings.filterMinCutoff, settings.filterBeta)
        if (this::clickMachine.isInitialized) {
            clickMachine.doubleClickEnabled = settings.doubleClickEnabled
        }
    }

    companion object {
        private const val TAG = "AirMouseService"
    }
}
