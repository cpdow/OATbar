# OATbar

Shows outside air temperature (OBD-II Mode 01, PID 0x46) from a Bluetooth ELM327-style dongle
in a small always-on-top pill, positioned next to your head unit's clock.

## Build
1. Open this folder in Android Studio (Koala or newer), let it sync, create the Gradle wrapper if prompted.
2. Build > Build APK(s). Copy `app/build/outputs/apk/debug/app-debug.apk` to the head unit and install.

## First run on the head unit
1. Plug the dongle into the truck's OBD port, ignition on.
2. Open OATbar > "Bluetooth permissions", then "Overlay permission" (allow OATbar).
   If the unit has no overlay settings screen, from a PC run:
   `adb shell appops set com.example.oatbar SYSTEM_ALERT_WINDOW allow`
3. Pick the dongle: "Paired devices" (classic dongles, pair in system Bluetooth settings first)
   or "Scan for BLE" (Vgate iCar Pro 2S, OBDLink CX, etc.).
4. Tap Start. Use the arrows to move the pill beside the clock; Text +/- to resize.
5. The service restarts at boot once you've started it.

## Testing without a dongle (emulator)
Turn on "Demo mode" on the setup screen, grant the overlay permission, and tap Start. The pill
sweeps a fake temperature from -10°C to 30°C so you can test position, size and units.
Remember to turn Demo mode off before using it in the truck.

## Updating the app
`app/debug.keystore` is a fixed debug signing key, committed on purpose, so every build (local or
GitHub) is signed the same way and a newer APK installs over an older one. If you installed a build
made before this key was added, uninstall it once first:
`adb uninstall com.example.oatbar`
Do not reuse this key for anything you publish.

## Notes
- If the status says "ECU gave no data for PID 0146", first confirm the truck reports the PID with
  a phone app (Car Scanner ELM OBD2) before debugging this app.
- The ECU's ambient value can differ from the dash reading and may lag when the truck is heat-soaked.
- Untested build: written without an Android SDK available. Expect to fix small compile errors.
