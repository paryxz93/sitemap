package it.webfuturo.airmouse.input

import android.content.Context
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * Sorgente basata sul Samsung Pen Remote SDK, raggiunto interamente per
 * reflection.
 *
 * PERCHE' REFLECTION
 * L'AAR Samsung si scarica solo accettando la licenza Samsung e non e'
 * ridistribuibile, quindi non puo' stare nel repository. Con una dipendenza di
 * compilazione normale, chiunque clonasse il progetto senza AAR non riuscirebbe
 * nemmeno a compilare. Per reflection il progetto compila sempre, l'APK e'
 * sempre installabile, e la sorgente S Pen si accende da sola quando l'AAR
 * viene messo in app/libs/.
 *
 * COSA SI PERDE
 * Le firme non sono verificate a compile time. Se Samsung cambia l'API, il
 * guasto si manifesta a runtime. Per questo ogni lookup e' difensivo e ogni
 * fallimento viene loggato con il nome esatto di cio' che mancava, invece di
 * propagare una NoSuchMethodException opaca.
 *
 * SUPERFICIE API USATA (da documentazione ufficiale Samsung)
 *   SpenRemote.getInstance()
 *   SpenRemote.isFeatureEnabled(int)            FEATURE_TYPE_AIR_MOTION, FEATURE_TYPE_BUTTON
 *   SpenRemote.connect(Context, ConnectionResultCallback)
 *   SpenRemote.disconnect(Context)
 *   SpenUnitManager.getUnit(int)                SpenUnitTypes.AIR_MOTION, BUTTON
 *   SpenUnitManager.registerSpenEventListener(SpenEventListener, SpenUnit)
 *   SpenUnitManager.unregisterSpenEventListener(SpenUnit)
 *   AirMotionEvent(SpenEvent).getDeltaX() / getDeltaY()   normalizzati in [-1, 1]
 *   ButtonEvent(SpenEvent).getAction()          ACTION_DOWN, ACTION_UP
 *
 * LIMITE NOTO E NON AGGIRABILE DA QUI
 * La documentazione Samsung indica che gli eventi della penna sono consegnati
 * all'activity in primo piano. Se cosi' fosse anche per un AccessibilityService,
 * il cursore si muoverebbe solo con l'app aperta. Il contatore [eventCount] serve
 * proprio a misurarlo sul dispositivo reale: apri un'altra app, muovi la penna,
 * torna qui e guarda se il contatore e' salito.
 */
class SPenInputSource : InputSource {

    override val displayName: String = "S Pen (Air Motion)"

    @Volatile
    private var listener: InputSource.Listener? = null

    private var spenRemoteInstance: Any? = null
    private var unitManager: Any? = null
    private var airMotionUnit: Any? = null
    private var buttonUnit: Any? = null
    private var airMotionListenerProxy: Any? = null
    private var buttonListenerProxy: Any? = null

    /** Numero di eventi di movimento ricevuti da quando la sorgente e' attiva. */
    val eventCount = AtomicLong(0)

    // ------------------------------------------------------------------
    // Disponibilita'
    // ------------------------------------------------------------------

    override fun availability(context: Context): SourceAvailability {
        val remoteClass = loadClass(CLASS_SPEN_REMOTE) ?: return SourceAvailability.SDK_MISSING

        val instance = try {
            remoteClass.getMethod("getInstance").invoke(null)
        } catch (t: Throwable) {
            Log.w(TAG, "SpenRemote.getInstance() non disponibile", t)
            return SourceAvailability.SDK_MISSING
        } ?: return SourceAvailability.SDK_MISSING

        val featureAirMotion = staticInt(remoteClass, "FEATURE_TYPE_AIR_MOTION")
            ?: return SourceAvailability.SDK_MISSING

        return try {
            val enabled = remoteClass
                .getMethod("isFeatureEnabled", Int::class.javaPrimitiveType)
                .invoke(instance, featureAirMotion) as? Boolean ?: false
            if (enabled) SourceAvailability.AVAILABLE else SourceAvailability.UNSUPPORTED
        } catch (t: Throwable) {
            Log.w(TAG, "isFeatureEnabled ha fallito", t)
            SourceAvailability.UNSUPPORTED
        }
    }

    // ------------------------------------------------------------------
    // Ciclo di vita
    // ------------------------------------------------------------------

    override fun start(context: Context, listener: InputSource.Listener): Boolean {
        this.listener = listener
        eventCount.set(0)

        val remoteClass = loadClass(CLASS_SPEN_REMOTE)
        if (remoteClass == null) {
            listener.onStatusChanged("SDK Samsung assente nell'APK")
            return false
        }

        val instance = try {
            remoteClass.getMethod("getInstance").invoke(null)
        } catch (t: Throwable) {
            Log.e(TAG, "getInstance fallito", t)
            listener.onStatusChanged("SpenRemote.getInstance() non disponibile")
            return false
        }
        if (instance == null) {
            listener.onStatusChanged("SpenRemote.getInstance() ha restituito null")
            return false
        }
        spenRemoteInstance = instance

        val callbackInterface = loadClass(CLASS_CONNECTION_CALLBACK)
        if (callbackInterface == null) {
            listener.onStatusChanged("ConnectionResultCallback non trovata")
            return false
        }

        val callbackProxy = Proxy.newProxyInstance(
            callbackInterface.classLoader,
            arrayOf(callbackInterface),
            ConnectionCallbackHandler(context)
        )

        val connect = findMethod(remoteClass, "connect", 2)
        if (connect == null) {
            listener.onStatusChanged("SpenRemote.connect(Context, callback) non trovata")
            return false
        }

        return try {
            connect.invoke(instance, context, callbackProxy)
            listener.onStatusChanged("Connessione alla S Pen in corso")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "connect fallito", t)
            listener.onStatusChanged("Connessione fallita: ${t.cause?.message ?: t.message}")
            false
        }
    }

    override fun stop(context: Context) {
        val manager = unitManager
        if (manager != null) {
            unregisterUnit(manager, airMotionUnit)
            unregisterUnit(manager, buttonUnit)
        }

        val instance = spenRemoteInstance
        if (instance != null) {
            try {
                findMethod(instance.javaClass, "disconnect", 1)?.invoke(instance, context)
            } catch (t: Throwable) {
                Log.w(TAG, "disconnect fallito", t)
            }
        }

        listener = null
        unitManager = null
        airMotionUnit = null
        buttonUnit = null
        airMotionListenerProxy = null
        buttonListenerProxy = null
        spenRemoteInstance = null
    }

    private fun unregisterUnit(manager: Any, unit: Any?) {
        if (unit == null) return
        try {
            findMethod(manager.javaClass, "unregisterSpenEventListener", 1)
                ?.invoke(manager, unit)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterSpenEventListener fallito", t)
        }
    }

    // ------------------------------------------------------------------
    // Callback di connessione
    // ------------------------------------------------------------------

    private inner class ConnectionCallbackHandler(
        private val context: Context
    ) : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            objectMethodResult(method, args)?.let { return it }

            when (method.name) {
                "onSuccess" -> {
                    val manager = args?.getOrNull(0)
                    if (manager == null) {
                        listener?.onStatusChanged("Connessione riuscita ma SpenUnitManager nullo")
                    } else {
                        unitManager = manager
                        attachUnits(manager)
                    }
                }

                "onFailure" -> {
                    val code = args?.getOrNull(0)
                    Log.e(TAG, "Connessione S Pen fallita, codice $code")
                    listener?.onStatusChanged("Connessione S Pen fallita (codice $code)")
                }
            }
            return null
        }

        private fun attachUnits(manager: Any) {
            val unitTypes = loadClass(CLASS_SPEN_UNIT_TYPES)
            if (unitTypes == null) {
                listener?.onStatusChanged("SpenUnitTypes non trovata")
                return
            }

            val getUnit = findMethod(manager.javaClass, "getUnit", 1)
            val register = findMethod(manager.javaClass, "registerSpenEventListener", 2)
            val eventListenerInterface = loadClass(CLASS_SPEN_EVENT_LISTENER)

            if (getUnit == null || register == null || eventListenerInterface == null) {
                listener?.onStatusChanged("API SpenUnitManager non corrispondenti")
                return
            }

            var attached = 0

            staticInt(unitTypes, "AIR_MOTION")?.let { type ->
                val unit = runCatching { getUnit.invoke(manager, type) }.getOrNull()
                if (unit != null) {
                    airMotionUnit = unit
                    val proxy = Proxy.newProxyInstance(
                        eventListenerInterface.classLoader,
                        arrayOf(eventListenerInterface),
                        SpenEventHandler(isButton = false)
                    )
                    airMotionListenerProxy = proxy
                    if (invokeRegister(register, manager, proxy, unit)) attached++
                }
            }

            staticInt(unitTypes, "BUTTON")?.let { type ->
                val unit = runCatching { getUnit.invoke(manager, type) }.getOrNull()
                if (unit != null) {
                    buttonUnit = unit
                    val proxy = Proxy.newProxyInstance(
                        eventListenerInterface.classLoader,
                        arrayOf(eventListenerInterface),
                        SpenEventHandler(isButton = true)
                    )
                    buttonListenerProxy = proxy
                    if (invokeRegister(register, manager, proxy, unit)) attached++
                }
            }

            listener?.onStatusChanged(
                if (attached > 0) "S Pen connessa, $attached unita' attive"
                else "S Pen connessa ma nessuna unita' registrata"
            )
        }

        /**
         * L'ordine degli argomenti di registerSpenEventListener non e' identico
         * in tutte le versioni dell'SDK. Si prova prima quello documentato
         * (listener, unit) e, se i tipi non combaciano, quello invertito.
         */
        private fun invokeRegister(
            register: Method,
            manager: Any,
            proxy: Any,
            unit: Any
        ): Boolean {
            try {
                register.invoke(manager, proxy, unit)
                return true
            } catch (t: IllegalArgumentException) {
                Log.w(TAG, "registerSpenEventListener(listener, unit) rifiutato, provo invertito")
            } catch (t: Throwable) {
                Log.e(TAG, "registerSpenEventListener fallito", t)
                return false
            }
            return try {
                register.invoke(manager, unit, proxy)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "registerSpenEventListener fallito in entrambi gli ordini", t)
                false
            }
        }
    }

    // ------------------------------------------------------------------
    // Eventi
    // ------------------------------------------------------------------

    private inner class SpenEventHandler(private val isButton: Boolean) : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            objectMethodResult(method, args)?.let { return it }
            if (method.name != "onEvent") return null

            val event = args?.getOrNull(0) ?: return null
            if (isButton) handleButton(event) else handleMotion(event)
            return null
        }

        private fun handleMotion(event: Any) {
            val target = listener ?: return
            val ctor = airMotionConstructor(event.javaClass) ?: return
            try {
                val motion = ctor.newInstance(event)
                val dx = (motion.javaClass.getMethod("getDeltaX").invoke(motion) as? Float) ?: 0f
                val dy = (motion.javaClass.getMethod("getDeltaY").invoke(motion) as? Float) ?: 0f
                eventCount.incrementAndGet()
                // Il segno di Y viene invertito: l'SDK usa Y crescente verso
                // l'alto, il sistema di coordinate dello schermo Y crescente
                // verso il basso.
                target.onMotionDelta(dx, -dy, System.nanoTime())
            } catch (t: Throwable) {
                Log.e(TAG, "Lettura AirMotionEvent fallita", t)
            }
        }

        private fun handleButton(event: Any) {
            val target = listener ?: return
            val ctor = buttonConstructor(event.javaClass) ?: return
            try {
                val button = ctor.newInstance(event)
                val action = (button.javaClass.getMethod("getAction").invoke(button) as? Int)
                    ?: return
                val buttonClass = button.javaClass
                val down = staticInt(buttonClass, "ACTION_DOWN") ?: 0
                val up = staticInt(buttonClass, "ACTION_UP") ?: 1
                when (action) {
                    down -> target.onButtonDown()
                    up -> target.onButtonUp()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Lettura ButtonEvent fallita", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilita' di reflection
    // ------------------------------------------------------------------

    private var cachedAirMotionCtor: Constructor<*>? = null
    private var cachedButtonCtor: Constructor<*>? = null

    private fun airMotionConstructor(eventClass: Class<*>): Constructor<*>? {
        cachedAirMotionCtor?.let { return it }
        val found = singleArgConstructor(CLASS_AIR_MOTION_EVENT, eventClass)
        cachedAirMotionCtor = found
        return found
    }

    private fun buttonConstructor(eventClass: Class<*>): Constructor<*>? {
        cachedButtonCtor?.let { return it }
        val found = singleArgConstructor(CLASS_BUTTON_EVENT, eventClass)
        cachedButtonCtor = found
        return found
    }

    private fun singleArgConstructor(className: String, argClass: Class<*>): Constructor<*>? {
        val cls = loadClass(className) ?: return null
        // Si cerca per arieta' e non per tipo esatto: il parametro dichiarato e'
        // SpenEvent, ma l'oggetto passato potrebbe essere una sottoclasse.
        val ctor = cls.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(argClass)
        }
        if (ctor == null) {
            Log.e(TAG, "Nessun costruttore a un argomento in $className")
            return null
        }
        ctor.isAccessible = true
        return ctor
    }

    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (t: Throwable) {
        Log.i(TAG, "Classe assente: $name")
        null
    }

    private fun findMethod(cls: Class<*>, name: String, argCount: Int): Method? {
        val method = cls.methods.firstOrNull { it.name == name && it.parameterTypes.size == argCount }
        if (method == null) Log.e(TAG, "Metodo assente: ${cls.name}.$name con $argCount argomenti")
        return method
    }

    private fun staticInt(cls: Class<*>, name: String): Int? = try {
        cls.getField(name).getInt(null)
    } catch (t: Throwable) {
        Log.e(TAG, "Costante assente: ${cls.name}.$name")
        null
    }

    /**
     * I proxy dinamici ricevono anche equals, hashCode e toString. Senza questa
     * gestione, un semplice log dell'oggetto proxy causerebbe una ricorsione.
     */
    private fun objectMethodResult(method: Method, args: Array<out Any?>?): Any? =
        when (method.name) {
            "equals" -> args?.getOrNull(0) === this
            "hashCode" -> System.identityHashCode(this)
            "toString" -> "SPenInputSourceProxy"
            else -> null
        }

    companion object {
        private const val TAG = "SPenInputSource"
        private const val PKG = "com.samsung.android.sdk.penremote"
        private const val CLASS_SPEN_REMOTE = "$PKG.SpenRemote"
        private const val CLASS_CONNECTION_CALLBACK = "$PKG.SpenRemote\$ConnectionResultCallback"
        private const val CLASS_SPEN_UNIT_TYPES = "$PKG.SpenUnitTypes"
        private const val CLASS_SPEN_EVENT_LISTENER = "$PKG.SpenEventListener"
        private const val CLASS_AIR_MOTION_EVENT = "$PKG.AirMotionEvent"
        private const val CLASS_BUTTON_EVENT = "$PKG.ButtonEvent"
    }
}
