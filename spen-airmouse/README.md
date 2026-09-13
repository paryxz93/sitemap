# S Pen Air Mouse

Cursore a schermo pilotato dalla S Pen del Galaxy S23 Ultra, con iniezione dei
tocchi tramite `AccessibilityService`. Funziona sullo schermo del telefono e sul
monitor esterno in Samsung DeX. Nessun root.

## Scaricare l'APK

L'APK viene compilato da GitHub Actions a ogni push. Due strade:

1. **Dal telefono**, la piu' comoda: apri la release `apk-latest` del
   repository e scarica l'asset `.apk`.
2. **Dal computer**: scheda Actions, apri l'ultima run di *Build APK*, scarica
   l'artifact `spen-airmouse-debug-apk`.

E' un APK di debug, firmato con la chiave di debug di Gradle: si installa senza
problemi, ma va abilitata l'installazione da origini sconosciute per il browser
o il gestore file che usi.

## Primo avvio

1. Installa l'APK e apri **S Pen Air Mouse**.
2. Tocca **Apri impostazioni accessibilita'** e attiva il servizio nell'elenco.
   Senza questo permesso l'app non puo' generare tocchi: senza root non esiste
   altra strada.
3. Compaiono il cursore e, in basso, il touchpad.

## Cosa funziona subito e cosa richiede l'SDK Samsung

| Funzione | Senza AAR Samsung | Con AAR Samsung |
|---|---|---|
| Cursore a schermo | si' | si' |
| Iniezione tap, pressione lunga, trascinamento | si' | si' |
| Cursore su monitor esterno in DeX | si' | si' |
| Movimento da touchpad a schermo | si' | si' |
| Movimento con la S Pen in aria | no | si' |
| Pulsante della S Pen | no | si' |

L'AAR Samsung non e' nel repository perche' si scarica solo accettando la
licenza Samsung e non e' ridistribuibile. Istruzioni in
[`app/libs/README.md`](app/libs/README.md): e' un file da copiare in una
cartella, nient'altro da configurare.

Il touchpad non e' un segnaposto: usa la stessa identica catena della penna
(dead zone, ballistica, filtro, overlay, iniezione), quindi tutto cio' che
funziona con il touchpad funziona con la penna appena aggiungi l'AAR.

## Mappatura del pulsante

L'SDK Samsung espone del pulsante soltanto `ACTION_DOWN` e `ACTION_UP`. Click
singolo, doppio click e pressione prolungata non esistono come eventi: sono
ricostruiti dai tempi in `core/ClickStateMachine.kt`.

| Gesto sul pulsante | Effetto |
|---|---|
| 1 tap | tocco, equivale al tasto sinistro |
| 2 tap | pressione prolungata, equivale al tasto destro |
| Tieni premuto | trascinamento, finche' non rilasci |

Il tasto destro nativo non e' replicabile e non lo sara': `dispatchGesture`
inietta eventi **touch**, non eventi mouse. La pressione prolungata e' il gesto
touch con la stessa semantica, cioe' apre il menu contestuale. Per lo stesso
motivo non esistono hover ne' rotellina di scorrimento.

## Taratura

Tutti i parametri sono nella schermata principale e si applicano a caldo.

**Stabilizzazione.** Il filtro e' un One Euro, non una media mobile. Una media
mobile impone un compromesso fisso: tarata per togliere il tremore da fermo,
introduce ritardo in movimento. One Euro rende la frequenza di taglio funzione
della velocita', quindi e' molto smorzato da fermo e molto reattivo in
movimento. Tara in quest'ordine:

1. Porta **Beta** a zero.
2. Abbassa **Taglio minimo** finche' il cursore e' immobile con la mano ferma.
3. Alza **Beta** finche' sparisce il ritardo nei movimenti veloci.

**Dead zone.** Alzala finche' il cursore sta fermo da fermo, poi fermati: oltre,
il movimento lento diventa a scatti.

**Doppio click.** Con il doppio click attivo, ogni click singolo attende 260 ms
per vedere se ne arriva un secondo. E' un ritardo su *ogni* click.
Disattivandolo il tap parte immediatamente, ma si perde il click secondario.

## La domanda aperta, e come rispondere sul dispositivo

La documentazione Samsung indica che gli eventi della S Pen sono consegnati
all'activity in primo piano, e che i listener vanno deregistrati in `onPause`.
Se il vincolo valesse anche per un `AccessibilityService`, la penna piloterebbe
il cursore solo con questa app aperta, che e' l'esatto contrario di un air
mouse di sistema.

Non e' una domanda a cui si possa rispondere leggendo la documentazione, quindi
l'app la misura. Il contatore **Eventi movimento** nella schermata di stato
conta i campioni ricevuti:

1. Apri un'altra app qualsiasi.
2. Muovi la S Pen in aria per qualche secondo.
3. Torna in S Pen Air Mouse e guarda il contatore.

Se e' salito, l'air mouse funziona a livello di sistema. Se e' fermo, la penna
funziona solo con l'app in primo piano e altrove resta valido il touchpad.

## Architettura

```
input/                      sorgenti, stesso contratto per entrambe
  SPenInputSource           SDK Samsung via reflection
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

Due scelte che vale la pena conoscere.

**La finestra del cursore e' a tutto schermo e non si sposta mai.** La strada
ovvia sarebbe una finestrella grande quanto l'icona, riposizionata con
`updateViewLayout` a ogni campione: sarebbero decine di transazioni al secondo
verso `WindowManagerService`, con ritardo variabile. Qui il cursore si muove
dentro la finestra, ridisegnando, e il movimento non esce mai dal processo.
Effetto collaterale importante: le coordinate del disegno coincidono per
costruzione con quelle passate a `dispatchGesture`, perche' entrambe sono
riferite all'origine dello stesso display. E' cosi' che si evita il difetto
classico di questi progetti, il click che arriva dove il cursore non e' piu'.

**L'SDK Samsung e' raggiunto per reflection, non come dipendenza.** Cosi' il
progetto compila sempre, l'APK e' sempre installabile e l'AAR resta un innesto
opzionale. Il costo e' che un cambio di firma nell'SDK emerge a runtime invece
che in compilazione: per questo ogni lookup e' difensivo e logga il nome esatto
di cio' che manca, sotto il tag `SPenInputSource`.

## Compilare in locale

Servono JDK 17 e Android SDK con piattaforma 34.

```bash
cd spen-airmouse
./gradlew assembleDebug
# APK in app/build/outputs/apk/debug/
```
