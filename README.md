# RoamWifi

An Android app that keeps you on the best saved Wi-Fi while you walk around a building with many separate
networks (a hostel, campus, office). It watches signal trends, checks that each network really has internet,
and hands over to a better one *before* the current one drops.

Native Kotlin + Jetpack Compose. A web app cannot scan or switch Wi-Fi, so this is an installable APK.

## How it works

1. **Scan** in the background while the app is open (every 2.5 s while moving or weak, down to 12 s when you sit still on a strong link).
2. **Direction of movement** is inferred from each network's signal *trend* (least-squares slope over the last few scans),
   projected a few seconds ahead. A network that is getting stronger gets a bonus; one that is fading gets a penalty.
   This is more reliable than guessing a heading from the compass.
3. **Internet check** on the connected network (HTTP 204 probe bound to the Wi-Fi network, plus an occasional ~250 KB speed test).
   Captive portals and dead routers are detected and avoided for 10 minutes.
4. **Decide** (`Scoring.decide`): switch only if the candidate is clearly better (hysteresis), has been quiet long enough since the
   last switch, is above -78 dBm, has working internet and has not just failed. If you are already on a network with no internet, it leaves within seconds.
5. **Credentials** are stored AES-256-GCM encrypted with a non-exportable Android Keystore key. Backups are disabled.

Scanning stops when you close the app (back button, swipe from recents, or the notification's Stop button).
Pressing Home keeps it running so it works with the screen off in your pocket.

## Android version behaviour (important)

| Android | How the switch happens |
|---|---|
| 8 - 9 | Classic `enableNetwork()`: direct, fastest |
| 10 - 11 | Network Suggestions: the app removes the suggestion for the network you are on, and the OS joins the best remaining one |
| 12+ | Network Suggestions with **priorities**: the app raises the target's priority; the OS performs the hand-over |

Android 10+ does not let any app force a connection. First use prompts you to allow RoamWifi to suggest networks.
For best results, "Forget" the hostel networks in Android's own Wi-Fi settings and let RoamWifi manage them,
otherwise the OS may prefer its own saved copy. The OS also throttles scans (about 4 per 2 minutes); on Android 10+ you can
switch off *Developer options > Wi-Fi scan throttling* for faster reaction.

Switching between *different* SSIDs always has a short gap (typically 1-3 s) because the radio must re-associate. RoamWifi
minimises it by switching early and by checking quality in advance; it cannot make it zero.

## Build and deploy for free

The repo contains a GitHub Actions workflow, so you do not need Android Studio:

1. Create a GitHub repo and push this folder to `main`.
2. Open **Actions > Build APK**; when it finishes, download the `roamwifi-debug-apk` artifact and install the APK
   (allow "install unknown apps").
3. To publish a release: `git tag v1.0.0 && git push --tags`. The APK is attached to the GitHub Release automatically.

Local build (needs JDK 17 and the Android SDK): `./gradlew testDebugUnitTest assembleDebug`.
Open the folder in Android Studio for the emulator/profiler.

Optional signed release build: set `ROAMWIFI_KEYSTORE_PATH`, `ROAMWIFI_KEYSTORE_PASSWORD`, `ROAMWIFI_KEY_ALIAS`,
`ROAMWIFI_KEY_PASSWORD` and run `./gradlew assembleRelease`.

## Layout

```
app/src/main/java/com/roamwifi/app/
  core/     Models, Scoring (pure logic, unit-tested), WifiScanner, InternetProber, WifiConnector, RoamEngine, EngineBus
  data/     CryptoBox (Keystore AES-GCM), NetworkStore
  service/  RoamService (foreground service, START_NOT_STICKY)
  ui/       MainActivity, RoamScreen, Theme
app/src/test/  ScoringTest (17 tests)
```

All tunable thresholds live in `Tuning` in `Scoring.kt`.

## Known limits

- Hidden SSIDs are not supported (cannot be matched by name).
- Enterprise (802.1X) networks are not supported; only open, WPA2 and WPA3 personal.
- iOS cannot do this at all (Apple does not allow scanning or switching).
