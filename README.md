# Driving Test Lite (Android TV, 3D)
Tiny OpenGL ES 2.0 driving-test game. No libraries, 128x128 textures, 30 fps cap, low RAM.
Works fully offline: graphics and sounds are generated in code unless you provide downloads.

## Remote
UP = accelerator | DOWN = brake | LEFT/RIGHT = steer | OK = horn (restart after finish)

## Test course (left-hand traffic, start score 100, pass >= 70)
1. Cone slalom (-5 per cone)  2. 30 km/h zone  3. Full stop at STOP sign (-20)
4. Traffic light (-20 on red)  5. Park straight in yellow box. Speeding / off-road lose points.

## Graphics & sound (downloadable)
Textures (road, grass, building) and sounds (engine loop, crash, beep, horn, success, fail) are listed in
`app/src/main/assets/assets.conf` as `key=filename` or `key=https://full-url`.
They are fetched in two ways, and the game works even if both fail (built-in generated textures/sounds are used):

1. **At APK build time** (GitHub Actions runs `tools/fetch_assets.sh` and bundles the files in the APK).
   Easiest: host all files in one folder and set the repo variable
   Settings -> Secrets and variables -> Actions -> Variables -> `ASSET_BASE_URL` = `https://your-host/folder`
   (files named as in assets.conf), or put full URLs in assets.conf.
2. **At install / first launch** (the app downloads anything still missing over HTTPS, then reloads automatically).
   For this, set `@base=` (or full URLs) in assets.conf before building.

Limits: HTTPS only, max 2 MB per file. Textures are auto-resized to 128x128 (low RAM).
Use CC0 or your own files and check their licences.

## Build APK on GitHub
1. Create repo, push this folder to `main`.
2. Actions tab -> "Build APK" -> run -> download artifact `DrivingTestLite-apk`
   (or push a tag like `v1.0` to get it in Releases).
3. Install on TV: `adb connect <tv-ip>` then `adb install DrivingTestLite.apk`, or copy via USB / "Send Files to TV".
