# Come attivare la sorgente S Pen

Questa cartella e' vuota di proposito.

L'app funziona senza SDK Samsung usando la sorgente Touchpad. Per abilitare
il controllo con la S Pen serve l'AAR ufficiale, che Samsung distribuisce solo
previa accettazione della sua licenza e non puo' essere ridistribuito qui.

## Procedura

1. Vai su `developer.samsung.com/galaxy-spen-remote` e scarica il Pen Remote SDK.
2. Estrai lo zip e copia il file `.aar` dentro questa cartella (`app/libs/`).
3. Ricompila. Non serve toccare nessun file di build: il `fileTree` in
   `app/build.gradle.kts` lo raccoglie da solo.
4. Avvia l'app: la schermata principale mostrera' la sorgente S Pen come
   Disponibile invece che Assente.

## Perche' reflection e non una dipendenza normale

`SPenInputSource.kt` risolve le classi Samsung con `Class.forName` e chiama i
metodi per nome. Cosi':

- il progetto compila e produce un APK installabile anche senza AAR;
- l'app non va in crash su dispositivi non Samsung o senza penna;
- l'AAR e' un innesto opzionale, non un requisito del build.

Il costo e' che un cambio di firma nell'SDK non viene rilevato a compile time
ma a runtime, dove viene loggato in modo esplicito (tag `SPenInputSource`).
