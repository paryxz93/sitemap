# Librerie Samsung: perché questa cartella è vuota

Il Pen Remote SDK si scarica solo accettando la licenza Samsung e non è
ridistribuibile. Questo repository è pubblico, quindi i jar non sono committati:
finirebbero nella storia di git, scaricabili da chiunque.

La compilazione li ottiene da un secret del repository.

## File necessari

| File | Origine |
|---|---|
| `spenremote-v1.0.1.jar` | zip del Pen Remote SDK, da developer.samsung.com |
| `sdk-v1.0.0.jar` | stesso zip, contiene `SsdkVendorCheck` |

## Compilare in locale

Copia i due jar in questa cartella. Il `fileTree` in `app/build.gradle.kts` li
raccoglie da solo, non c'è nulla da configurare. Il `.gitignore` impedisce che
finiscano per sbaglio in un commit.

## Compilare in CI

Il workflow ricostruisce la cartella dal secret `SPEN_SDK_LIBS_B64`, che
contiene uno zip dei due jar codificato in base64.

Per rigenerarlo:

```bash
cd app/libs
zip -X /tmp/spen-libs.zip spenremote-v1.0.1.jar sdk-v1.0.0.jar
base64 -w 0 /tmp/spen-libs.zip
```

Il valore va incollato in Settings, Secrets and variables, Actions, New
repository secret, con nome `SPEN_SDK_LIBS_B64`.

Senza il secret la compilazione si ferma con un messaggio esplicito. È una
scelta: fallire dicendo cosa manca è meglio di un APK che si installa e poi non
vede la penna.

## Due requisiti del manifest che non sono documentati

Valgono la pena di essere ricordati qui, perché senza di essi la penna non si
connette e l'errore accusa il telefono invece del manifest. Entrambi sono
verificabili nel bytecode di `SpenRemote`, e nessuno dei due compare nel
progetto di esempio ufficiale, che è fermo a targetSdk 28.

1. `<uses-permission android:name="com.samsung.android.sdk.penremote.BIND_SPEN_REMOTE" />`
   Senza, `bindService` restituisce false e l'SDK logga testualmente
   `Add com.samsung.android.sdk.penremote.BIND_SPEN_REMOTE permission`.

2. `<queries><package android:name="com.samsung.android.service.aircommand" /></queries>`
   L'SDK cerca quel pacchetto con il `PackageManager`. Da targetSdk 30 il filtro
   di visibilità dei pacchetti lo nasconde, e l'SDK conclude
   `This device does not support S Pen` su un dispositivo che la supporta.
