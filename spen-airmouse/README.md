# S Pen Air Mouse

Cursore a schermo pilotato dalla S Pen del Galaxy S23 Ultra, con iniezione dei
tocchi tramite `AccessibilityService`. Funziona sullo schermo del telefono e sul
monitor esterno in Samsung DeX. Nessun root.

## Se l'interruttore di accessibilità è grigio

È il primo ostacolo che incontrerai, e non è un difetto dell'app.

Se in Accessibilità, App installate, la voce **S Pen Air Mouse** appare spenta e
non attivabile, con scritto **Controllato da impostazione con restrizioni**, si
tratta della protezione **Restricted Settings** introdotta con Android 13.
Android blocca i servizi di accessibilità delle app installate fuori dal Play
Store, perché sono la porta d'ingresso preferita dal malware.

Come sbloccarla:

1. Impostazioni, App, **S Pen Air Mouse**.
2. Tocca i **tre puntini in alto a destra**.
3. Scegli **Consenti impostazioni con restrizioni**.
4. Torna in Accessibilità, App installate, e attiva il servizio.

L'app ha un pulsante che apre direttamente la pagina al punto 1.

Se la voce nei tre puntini non compare, installa via ADB, che non attiva la
restrizione:

```bash
adb install -r spen-airmouse.apk
# oppure, se la restrizione è già scattata:
adb shell appops set it.webfuturo.airmouse ACCESS_RESTRICTED_SETTINGS allow
```

## Scaricare l'APK

Compilato da GitHub Actions a ogni push.

1. **Dal telefono**: apri la release `apk-latest` del repository e scarica
   l'asset `.apk`.
2. **Dal computer**: scheda Actions, ultima run di *Build APK*, artifact
   `spen-airmouse-debug-apk`.

APK di debug, firmato con la chiave di debug di Gradle: si installa, ma va
abilitata l'installazione da origini sconosciute per il browser o il gestore
file che usi.

## Prima configurazione della build

I jar del Pen Remote SDK non sono nel repository: non sono ridistribuibili e
questo repository è pubblico. La CI li ricostruisce da un secret.

Una volta sola, in Settings, Secrets and variables, Actions, New repository
secret:

| Campo | Valore |
|---|---|
| Nome | `SPEN_SDK_LIBS_B64` |
| Valore | base64 di uno zip contenente `spenremote-v1.0.1.jar` e `sdk-v1.0.0.jar` |

Dettagli e comando per rigenerarlo in [`app/libs/README.md`](app/libs/README.md).
Senza il secret la build si ferma con un messaggio esplicito.

## Mappatura del pulsante

L'SDK Samsung espone del pulsante soltanto `ACTION_DOWN` e `ACTION_UP`. Click
singolo, doppio click e pressione prolungata non esistono come eventi: sono
ricostruiti dai tempi in `core/ClickStateMachine.kt`.

| Gesto sul pulsante | Effetto |
|---|---|
| 1 tap | tocco, equivale al tasto sinistro |
| 2 tap | pressione prolungata, equivale al tasto destro |
| Tieni premuto | trascinamento, finché non rilasci |

Il tasto destro nativo non è replicabile e non lo sarà: `dispatchGesture`
inietta eventi **touch**, non eventi mouse. La pressione prolungata è il gesto
touch con la stessa semantica, cioè apre il menu contestuale. Per lo stesso
motivo non esistono hover né rotellina di scorrimento.

## Due requisiti del manifest che non sono documentati

Se la penna non si connette, quasi certamente è uno di questi. Nessuno dei due
compare nella documentazione Samsung né nel progetto di esempio ufficiale, che
è fermo a targetSdk 28 e quindi non li incontrava. Entrambi sono verificabili
nel bytecode di `SpenRemote`.

**1. Il permesso di binding.**

```xml
<uses-permission android:name="com.samsung.android.sdk.penremote.BIND_SPEN_REMOTE" />
```

Senza, `bindService` restituisce false e l'SDK logga testualmente
`Add com.samsung.android.sdk.penremote.BIND_SPEN_REMOTE permission`.

**2. La visibilità del pacchetto.**

```xml
<queries>
    <package android:name="com.samsung.android.service.aircommand" />
</queries>
```

L'SDK non parla direttamente con la penna: è un client AIDL che si lega al
servizio di sistema `com.samsung.android.service.aircommand`, che tiene lui la
connessione BLE. Prima di legarsi verifica che il pacchetto esista, con il
`PackageManager`. Da targetSdk 30 il filtro di visibilità dei pacchetti lo
nasconde se non è dichiarato, e l'SDK conclude `This device does not support
S Pen` su un dispositivo che la supporta benissimo.

## Taratura

Tutti i parametri sono nella schermata principale e si applicano a caldo.

**Stabilizzazione.** Il filtro è un One Euro, non una media mobile. Una media
mobile impone un compromesso fisso: tarata per togliere il tremore da fermo,
introduce ritardo in movimento. One Euro rende la frequenza di taglio funzione
della velocità, quindi è molto smorzato da fermo e molto reattivo in movimento.
Tara in quest'ordine:

1. Porta **Beta** a zero.
2. Abbassa **Taglio minimo** finché il cursore è immobile con la mano ferma.
3. Alza **Beta** finché sparisce il ritardo nei movimenti veloci.

**Dead zone.** Alzala finché il cursore sta fermo da fermo, poi fermati: oltre,
il movimento lento diventa a scatti.

**Doppio click.** Con il doppio click attivo, ogni click singolo attende 260 ms
per vedere se ne arriva un secondo. È un ritardo su *ogni* click.
Disattivandolo il tap parte immediatamente, ma si perde il click secondario.

## La domanda aperta, e come rispondere sul dispositivo

La documentazione Samsung indica che gli eventi della S Pen sono consegnati
all'activity in primo piano. Nel client dell'SDK, però, non c'è alcun controllo
del genere: è un semplice client AIDL, e chi decide se continuare a inviare
eventi è il servizio di sistema Samsung, il cui comportamento da qui non è
ispezionabile.

Quindi l'app lo misura. Il contatore **Eventi movimento** nella schermata di
stato conta i campioni ricevuti:

1. Apri un'altra app qualsiasi.
2. Muovi la S Pen in aria per qualche secondo.
3. Torna in S Pen Air Mouse e guarda il contatore.

Se è salito, l'air mouse funziona a livello di sistema. Se è fermo, la penna
funziona solo con l'app in primo piano, e altrove resta valido il touchpad.

## Architettura

```
input/                      sorgenti, stesso contratto per entrambe
  SPenInputSource           Samsung Pen Remote SDK, chiamate tipizzate
  TouchpadInputSource       superficie a schermo, nessuna dipendenza
core/
  PointerEngine             dead zone, ballistica, integrazione, clamp
  OneEuroFilter             stabilizzazione a taglio adattivo
  ClickStateMachine         da DOWN e UP a tap, doppio, trascinamento
service/
  AirMouseAccessibilityService   collega tutto
  DisplayTargetManager      sceglie telefono o monitor DeX
  OverlayHost               aggancia le finestre al display giusto
  CursorView                disegna la freccia
  GestureInjector           GestureDescription, con setDisplayId
```

Il touchpad a schermo non è un ripiego: usa la stessa identica catena della
penna e la stessa topologia, cioè superficie di movimento più un solo pulsante.
Serve a collaudare tutto anche senza penna, e in DeX resta utile per conto suo.

**La finestra del cursore è a tutto schermo e non si sposta mai.** La strada
ovvia sarebbe una finestrella grande quanto l'icona, riposizionata con
`updateViewLayout` a ogni campione: sarebbero decine di transazioni al secondo
verso `WindowManagerService`, con ritardo variabile. Qui il cursore si muove
dentro la finestra, ridisegnando, e il movimento non esce mai dal processo.
Effetto collaterale importante: le coordinate del disegno coincidono per
costruzione con quelle passate a `dispatchGesture`, perché entrambe sono
riferite all'origine dello stesso display. È così che si evita il difetto
classico di questi progetti, il click che arriva dove il cursore non è più.

## Compilare in locale

Servono JDK 17, Android SDK con piattaforma 34, e i due jar in `app/libs/`.

```bash
cd spen-airmouse
./gradlew assembleDebug
# APK in app/build/outputs/apk/debug/
```
