package it.webfuturo.airmouse.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import it.webfuturo.airmouse.core.Settings
import it.webfuturo.airmouse.service.AirMouseBus

/**
 * Schermata di stato e taratura.
 *
 * Non e' una vetrina: e' lo strumento diagnostico del progetto. Il contatore
 * degli eventi di movimento serve a rispondere sul dispositivo reale alla
 * domanda che la documentazione Samsung lascia aperta, cioe' se gli eventi
 * della penna continuino ad arrivare quando l'app NON e' in primo piano.
 *
 * Procedura: apri un'altra app, muovi la penna, torna qui. Se il contatore e'
 * salito, l'air mouse funziona a livello di sistema. Se e' fermo, la penna
 * pilota il cursore solo con questa app aperta, e il touchpad resta la sola
 * sorgente valida altrove.
 */
class MainActivity : Activity() {

    private lateinit var settings: Settings

    private lateinit var statusService: TextView
    private lateinit var statusSource: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusDisplay: TextView
    private lateinit var statusCounter: TextView

    private val observer: (AirMouseBus.Status) -> Unit = { status -> renderStatus(status) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(buildLayout())
        AirMouseBus.observe(observer)
    }

    override fun onDestroy() {
        AirMouseBus.removeObserver(observer)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        renderStatus(AirMouseBus.current())
    }

    // ------------------------------------------------------------------
    // Interfaccia, costruita in codice
    // ------------------------------------------------------------------

    private fun buildLayout(): ViewGroup {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(28), pad, dp(40))
        }

        root.addView(title("S Pen Air Mouse"))
        root.addView(
            caption(
                "Cursore a schermo pilotato dalla S Pen o dal touchpad, con " +
                    "iniezione dei tocchi tramite accessibilita'. Funziona anche in DeX."
            )
        )

        root.addView(sectionHeader("Stato"))
        statusService = statusLine()
        statusSource = statusLine()
        statusDetail = statusLine()
        statusDisplay = statusLine()
        statusCounter = statusLine()
        root.addView(statusService)
        root.addView(statusSource)
        root.addView(statusDetail)
        root.addView(statusDisplay)
        root.addView(statusCounter)

        root.addView(
            primaryButton("Apri impostazioni accessibilita'") {
                startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        root.addView(
            caption(
                "Attiva \"S Pen Air Mouse\" nell'elenco. Senza questo permesso " +
                    "l'app non puo' generare tocchi: senza root non esiste altra strada."
            )
        )

        root.addView(sectionHeader("Se l'interruttore e' grigio"))
        root.addView(
            caption(
                "Se nell'elenco accessibilita' la voce appare spenta e non " +
                    "attivabile, con scritto \"Controllato da impostazione con " +
                    "restrizioni\", non e' un problema dell'app. E' la protezione " +
                    "Restricted Settings di Android 13 e successivi: blocca i " +
                    "servizi di accessibilita' delle app installate fuori dal Play " +
                    "Store, perche' sono il bersaglio preferito del malware."
            )
        )
        root.addView(
            primaryButton("Apri i dettagli di questa app") {
                startActivity(
                    Intent(
                        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
        )
        root.addView(
            caption(
                "Nella pagina che si apre tocca i tre puntini in alto a destra e " +
                    "scegli \"Consenti impostazioni con restrizioni\". Poi torna " +
                    "nelle impostazioni accessibilita' e attiva il servizio."
            )
        )

        root.addView(sectionHeader("Movimento"))
        root.addView(
            slider(
                label = "Sensibilita'",
                min = 600f, max = 6000f, value = settings.sensitivity,
                format = { "%.0f px per unita'".format(it) }
            ) { settings.sensitivity = it }
        )
        root.addView(
            slider(
                label = "Accelerazione",
                min = 0f, max = 5f, value = settings.accelerationGain,
                format = { "guadagno %.2f".format(it) }
            ) { settings.accelerationGain = it }
        )
        root.addView(
            slider(
                label = "Dead zone",
                min = 0f, max = 0.03f, value = settings.deadZone,
                format = { "%.4f".format(it) }
            ) { settings.deadZone = it }
        )
        root.addView(
            caption(
                "La dead zone toglie il micro tremore della mano. Alzala finche' " +
                    "il cursore sta fermo da fermo, poi fermati: oltre, il movimento " +
                    "lento diventa a scatti."
            )
        )

        root.addView(sectionHeader("Stabilizzazione (filtro One Euro)"))
        root.addView(
            slider(
                label = "Taglio minimo",
                min = 0.1f, max = 6f, value = settings.filterMinCutoff,
                format = { "%.2f Hz".format(it) }
            ) { settings.filterMinCutoff = it }
        )
        root.addView(
            slider(
                label = "Beta (reattivita')",
                min = 0f, max = 0.08f, value = settings.filterBeta,
                format = { "%.4f".format(it) }
            ) { settings.filterBeta = it }
        )
        root.addView(
            caption(
                "Tara in quest'ordine: porta beta a zero e abbassa il taglio minimo " +
                    "finche' il cursore e' immobile da fermo; poi alza beta finche' " +
                    "sparisce il ritardo nei movimenti veloci."
            )
        )

        root.addView(sectionHeader("Pulsante"))
        root.addView(
            toggle("Doppio click attivo", settings.doubleClickEnabled) {
                settings.doubleClickEnabled = it
            }
        )
        root.addView(
            caption(
                "Con il doppio click attivo, ogni click singolo aspetta 260 ms per " +
                    "vedere se ne arriva un secondo. Disattivandolo il tap parte subito, " +
                    "ma si perde la pressione prolungata da doppio click."
            )
        )
        root.addView(
            toggle("Touchpad a schermo", settings.touchpadEnabled) {
                settings.touchpadEnabled = it
            }
        )
        root.addView(
            caption(
                "Il touchpad usa la stessa catena della penna, quindi serve a " +
                    "collaudare tutto anche senza SDK Samsung. Le modifiche a questa " +
                    "voce si applicano riavviando il servizio di accessibilita'."
            )
        )

        root.addView(sectionHeader("Cosa non e' replicabile senza root"))
        root.addView(
            caption(
                "L'accessibilita' inietta eventi TOUCH, non eventi mouse. Non " +
                    "esistono quindi hover, tasto destro nativo e rotellina. Il click " +
                    "secondario viene reso come pressione prolungata, che nelle app " +
                    "Android apre lo stesso menu contestuale."
            )
        )

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0C1014"))
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun renderStatus(status: AirMouseBus.Status) {
        statusService.text = "Servizio: " +
            if (status.serviceRunning) "attivo" else "non attivo"
        statusService.setTextColor(
            if (status.serviceRunning) ACCENT_OK else ACCENT_WARN
        )
        statusSource.text = "Sorgente: ${status.activeSource}"
        statusDetail.text = "Dettaglio: ${status.sourceStatus}"
        statusDisplay.text = "Display: ${status.displayDescription}"
        statusCounter.text =
            "Eventi movimento: ${status.motionEvents}   cursore ${status.cursorX}, ${status.cursorY}"
    }

    // ------------------------------------------------------------------
    // Piccoli costruttori di viste
    // ------------------------------------------------------------------

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 26f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(Color.WHITE)
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        letterSpacing = 0.12f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(ACCENT)
        setPadding(0, dp(26), 0, dp(8))
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#93A1B0"))
        setPadding(0, dp(6), 0, dp(4))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun statusLine() = TextView(this).apply {
        textSize = 14f
        setTextColor(Color.parseColor("#D6DEE8"))
        setPadding(0, dp(3), 0, dp(3))
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 3
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(16)
        layoutParams = params
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#D6DEE8"))
            isChecked = initial
            setPadding(0, dp(10), 0, dp(4))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    /**
     * SeekBar lavora su interi, i parametri del filtro su float. La conversione
     * usa [SLIDER_STEPS] passi fissi, cosi' la risoluzione della barra non
     * dipende dall'intervallo del singolo parametro.
     */
    private fun slider(
        label: String,
        min: Float,
        max: Float,
        value: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ): ViewGroup {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }

        val readout = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#D6DEE8"))
            text = "$label:  ${format(value)}"
        }

        val bar = SeekBar(this).apply {
            this.max = SLIDER_STEPS
            progress = (((value - min) / (max - min)) * SLIDER_STEPS)
                .toInt().coerceIn(0, SLIDER_STEPS)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val mapped = min + (max - min) * (progress.toFloat() / SLIDER_STEPS)
                    readout.text = "$label:  ${format(mapped)}"
                    if (fromUser) onChange(mapped)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }

        container.addView(readout)
        container.addView(bar)
        return container
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SLIDER_STEPS = 1000
        private val ACCENT = Color.parseColor("#4DA3FF")
        private val ACCENT_OK = Color.parseColor("#5AD18A")
        private val ACCENT_WARN = Color.parseColor("#FFB454")
    }
}
