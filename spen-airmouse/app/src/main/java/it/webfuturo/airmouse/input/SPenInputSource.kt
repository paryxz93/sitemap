package it.webfuturo.airmouse.input

import android.content.Context
import android.util.Log
import com.samsung.android.sdk.SsdkVendorCheck
import com.samsung.android.sdk.penremote.AirMotionEvent
import com.samsung.android.sdk.penremote.ButtonEvent
import com.samsung.android.sdk.penremote.SpenEvent
import com.samsung.android.sdk.penremote.SpenEventListener
import com.samsung.android.sdk.penremote.SpenRemote
import com.samsung.android.sdk.penremote.SpenUnit
import com.samsung.android.sdk.penremote.SpenUnitManager
import java.util.concurrent.atomic.AtomicLong

/**
 * Sorgente basata sul Samsung Pen Remote SDK (spenremote-v1.0.1.jar).
 *
 * COME FUNZIONA DAVVERO
 * L'SDK non parla direttamente con la penna. E' un client AIDL che si lega al
 * servizio di sistema Samsung
 * `com.samsung.android.service.aircommand/...RemoteSpenBindingService`, il
 * quale a sua volta tiene la connessione BLE con la S Pen e inoltra gli eventi.
 * Da qui discendono due requisiti che NON sono nella documentazione ma sono
 * verificabili nel bytecode dell'SDK, e senza i quali la connessione fallisce
 * in silenzio. Entrambi stanno nel manifest:
 *
 *   1. <uses-permission android:name="com.samsung.android.sdk.penremote.BIND_SPEN_REMOTE" />
 *      Senza, bindService restituisce false e l'SDK logga esattamente
 *      "Add com.samsung.android.sdk.penremote.BIND_SPEN_REMOTE permission".
 *
 *   2. <queries><package android:name="com.samsung.android.service.aircommand" /></queries>
 *      L'SDK verifica l'esistenza del pacchetto con PackageManager. Da
 *      targetSdk 30 il filtro di visibilita' dei pacchetti lo nasconde se non
 *      viene dichiarato, e l'SDK conclude "This device does not support S Pen"
 *      su un dispositivo che la supporta benissimo.
 *
 * PERCHE' TIPIZZATO E NON PIU' PER REFLECTION
 * La prima versione usava la reflection per non dipendere da un AAR che non e'
 * ridistribuibile. Aveva un difetto grave: cercava una classe `SpenUnitTypes`
 * che NON esiste (le costanti stanno su [SpenUnit]), e la reflection lo
 * degradava a un log invece che a un errore di compilazione. Con il jar
 * presente il compilatore verifica ogni firma, che e' esattamente la garanzia
 * che serviva.
 */
class SPenInputSource : InputSource {

    override val displayName: String = "S Pen (Air Motion)"

    @Volatile
    private var listener: InputSource.Listener? = null

    private var unitManager: SpenUnitManager? = null
    private var airMotionUnit: SpenUnit? = null
    private var buttonUnit: SpenUnit? = null

    /** Campioni di movimento ricevuti da quando la sorgente e' attiva. */
    val eventCount = AtomicLong(0)

    // ------------------------------------------------------------------
    // Disponibilita'
    // ------------------------------------------------------------------

    override fun availability(context: Context): SourceAvailability {
        if (!isSamsungDevice()) return SourceAvailability.SDK_MISSING

        return try {
            // isFeatureEnabled e' sicuro prima di connect: internamente carica
            // le feature in modo pigro, non le legge da uno stato di connessione.
            val enabled = SpenRemote.getInstance()
                .isFeatureEnabled(SpenRemote.FEATURE_TYPE_AIR_MOTION)
            if (enabled) SourceAvailability.AVAILABLE else SourceAvailability.UNSUPPORTED
        } catch (t: Throwable) {
            // Su un dispositivo non Samsung l'SDK tocca classi del framework
            // Samsung (SemFloatingFeature) che li' non esistono.
            Log.w(TAG, "Controllo delle feature fallito", t)
            SourceAvailability.SDK_MISSING
        }
    }

    private fun isSamsungDevice(): Boolean = try {
        SsdkVendorCheck.isSamsungDevice()
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------
    // Ciclo di vita
    // ------------------------------------------------------------------

    override fun start(context: Context, listener: InputSource.Listener): Boolean {
        this.listener = listener
        eventCount.set(0)

        val remote = try {
            SpenRemote.getInstance()
        } catch (t: Throwable) {
            Log.e(TAG, "SpenRemote non disponibile", t)
            listener.onStatusChanged("SDK S Pen non disponibile su questo dispositivo")
            return false
        }

        // SpenRemote e' un singleton di processo, quindi la connessione
        // sopravvive allo spegnimento del servizio di accessibilita'. Tornare
        // qui dicendo "gia' connessa" sarebbe un errore: lo SpenUnitManager
        // arriva SOLO dentro onSuccess, e senza quello nessuna unita' viene mai
        // registrata. La penna resterebbe muta senza che nulla lo segnali.
        // Si chiude e si riapre, cosi' onSuccess viene garantito.
        if (remote.isConnected) {
            Log.i(TAG, "Connessione gia' aperta, la richiudo per riottenere lo SpenUnitManager")
            runCatching { remote.disconnect(context) }
                .onFailure { Log.w(TAG, "disconnect preventivo fallito", it) }
        }

        remote.setConnectionStateChangeListener { state -> onConnectionStateChanged(state) }

        return try {
            remote.connect(context, object : SpenRemote.ConnectionResultCallback {
                override fun onSuccess(manager: SpenUnitManager?) {
                    if (manager == null) {
                        this@SPenInputSource.listener
                            ?.onStatusChanged("Connessione riuscita ma gestore nullo")
                        return
                    }
                    unitManager = manager
                    attachUnits(manager)
                }

                override fun onFailure(error: Int) {
                    Log.e(TAG, "Connessione S Pen fallita: $error")
                    this@SPenInputSource.listener?.onStatusChanged(describeError(error))
                }
            })
            listener.onStatusChanged("Connessione alla S Pen in corso")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "connect ha sollevato eccezione", t)
            listener.onStatusChanged("Connessione fallita: ${t.message}")
            false
        }
    }

    override fun stop(context: Context) {
        val manager = unitManager
        if (manager != null) {
            airMotionUnit?.let { runCatching { manager.unregisterSpenEventListener(it) } }
            buttonUnit?.let { runCatching { manager.unregisterSpenEventListener(it) } }
        }
        runCatching {
            val remote = SpenRemote.getInstance()
            // Il listener di stato va tolto PRIMA di scollegare. La lambda
            // cattura questa sorgente, che a sua volta e' tenuta dal servizio:
            // lasciarla installata sul singleton significa tenere in vita un
            // servizio gia' spento e ricevere notifiche per conto di un
            // oggetto morto.
            remote.setConnectionStateChangeListener(null)
            remote.disconnect(context)
        }.onFailure { Log.w(TAG, "disconnect fallito", it) }

        listener = null
        unitManager = null
        airMotionUnit = null
        buttonUnit = null
    }

    // ------------------------------------------------------------------
    // Unita' ed eventi
    // ------------------------------------------------------------------

    private fun attachUnits(manager: SpenUnitManager) {
        var attached = 0

        // ATTENZIONE: le costanti stanno su SpenUnit, non su una classe
        // SpenUnitTypes. Quella classe non esiste nell'SDK.
        manager.getUnit(SpenUnit.TYPE_AIR_MOTION)?.let { unit ->
            airMotionUnit = unit
            manager.registerSpenEventListener(airMotionListener, unit)
            attached++
        }

        manager.getUnit(SpenUnit.TYPE_BUTTON)?.let { unit ->
            buttonUnit = unit
            manager.registerSpenEventListener(buttonListener, unit)
            attached++
        }

        val version = runCatching { SpenRemote.getInstance().versionName }.getOrNull()
        listener?.onStatusChanged(
            if (attached > 0) "S Pen connessa, $attached unità attive (SDK $version)"
            else "S Pen connessa ma nessuna unità registrata"
        )
    }

    private val airMotionListener = SpenEventListener { event: SpenEvent? ->
        val target = listener ?: return@SpenEventListener
        if (event == null) return@SpenEventListener
        try {
            val motion = AirMotionEvent(event)
            eventCount.incrementAndGet()
            // Il segno di Y viene invertito: l'SDK cresce verso l'alto, le
            // coordinate dello schermo crescono verso il basso.
            target.onMotionDelta(motion.deltaX, -motion.deltaY, System.nanoTime())
        } catch (t: Throwable) {
            Log.e(TAG, "Lettura AirMotionEvent fallita", t)
        }
    }

    private val buttonListener = SpenEventListener { event: SpenEvent? ->
        val target = listener ?: return@SpenEventListener
        if (event == null) return@SpenEventListener
        try {
            when (ButtonEvent(event).action) {
                ButtonEvent.ACTION_DOWN -> target.onButtonDown()
                ButtonEvent.ACTION_UP -> target.onButtonUp()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Lettura ButtonEvent fallita", t)
        }
    }

    private fun onConnectionStateChanged(state: Int) {
        val message = when (state) {
            SpenRemote.State.CONNECTED -> "S Pen connessa"
            SpenRemote.State.DISCONNECTED -> "S Pen disconnessa"
            SpenRemote.State.DISCONNECTED_BY_UNKNOWN_REASON ->
                "S Pen disconnessa per motivo sconosciuto"
            else -> "Stato connessione S Pen: $state"
        }
        Log.i(TAG, message)
        listener?.onStatusChanged(message)
    }

    /**
     * I codici di errore sono poco parlanti da soli, e il primo e' quello che
     * si incontra davvero quando manca il blocco <queries> nel manifest.
     */
    private fun describeError(error: Int): String = when (error) {
        SpenRemote.Error.UNSUPPORTED_DEVICE ->
            "Dispositivo non supportato. Se è un Galaxy con S Pen, controlla il blocco queries nel manifest"
        SpenRemote.Error.CONNECTION_FAILED ->
            "Connessione fallita. Controlla il permesso BIND_SPEN_REMOTE e che Air Actions sia attivo"
        SpenRemote.Error.UNKNOWN -> "Errore sconosciuto dell'SDK S Pen"
        else -> "Connessione S Pen fallita (codice $error)"
    }

    companion object {
        private const val TAG = "SPenInputSource"
    }
}
