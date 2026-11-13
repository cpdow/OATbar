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
5. Auto-start: once you've tapped Start (and not Stop), OATbar starts itself when the head unit
   powers up, connects to the saved dongle, and shows the pill at the saved position. Tapping
   Stop turns auto-start off until you tap Start again.
   If it doesn't come up after a power cycle, look in the head unit's settings for an
   "auto-start" / "boot apps" / "app manager" list and allow OATbar there, and turn off any
   battery optimization for it. You can also add OATbar to the unit's "auto-run apps" list,
   since opening the app starts the pill too.

## Testing without a dongle (emulator)
Turn on "Demo mode" on the setup screen, grant the overlay permission, and tap Start. The pill
sweeps a fake temperature from -10°C to 30°C so you can test position, size and units.
Remember to turn Demo mode off before using it in the truck.

## Checking that auto-start works
The setup screen has an "Auto-start log" at the bottom. After a power cycle of the head unit, open
OATbar and read it (newest first):
- `boot signal BOOT_COMPLETED: service start requested` then `service started`: the unit sends the
  normal boot signal and it works.
- `wake signal: SCREEN_ON` (or USER_PRESENT / bluetooth ON) with no boot line: the unit wakes from
  standby rather than rebooting. That is fine as long as the pill is on screen.
- `NOT started, ...`: the reason is printed (Start not pressed, no dongle, overlay permission).
- Nothing new at all: the unit is not letting OATbar run in the background after power-up. Use the
  unit's "application starts automatically" setting (some units: System settings > User) and pick
  "OATbar Auto-start". It is an invisible entry: it starts the pill and closes itself, so the setup
  screen never opens. It also appears as a second icon in the app list; tapping it does nothing
  visible. Make sure OATbar is excluded from any memory cleaner. Swiping OATbar away in the
  running-apps list or using "Clear All" can force-stop it, and a force-stopped app gets no boot signal
  until it is opened again.

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
