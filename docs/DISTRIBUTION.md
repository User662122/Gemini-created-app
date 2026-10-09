# Builds, downloads and upgrades

## What CI publishes

Every push to a tracked branch builds the debug APK and replaces the assets on a single release:

**https://github.com/User662122/Gemini-created-app/releases/tag/debug-latest**

| APK | Size | Device |
| --- | --- | --- |
| `app-arm64-v8a-debug.apk` | ~117 MB | Phones made in the last several years — **this is almost certainly yours** |
| `app-armeabi-v7a-debug.apk` | ~113 MB | Older 32-bit arm devices |
| `app-x86_64-debug.apk` | ~124 MB | Emulators |

Not sure which one? `adb shell getprop ro.product.cpu.abi` prints it. A device that installs the wrong
one fails with `INSTALL_FAILED_NO_MATCHING_ABIS`.

The URLs never change, so they can be used directly:

```bash
# Download, resuming if interrupted (a phone connection will be)
curl -L -C - -o chrome-browser.apk \
  https://github.com/User662122/Gemini-created-app/releases/download/debug-latest/app-arm64-v8a-debug.apk

# Upgrade in place, keeping tabs, history, bookmarks and cookies
adb install -r chrome-browser.apk
```

Or just open the release page on the phone and tap the file.

## Why the APK is this large

The app's own code is a few megabytes. The rest is the browsing engine, and it is large the way any
browser engine is: Gecko ships a full copy of itself **per CPU architecture** — roughly 70–90 MB of
native library each, plus its JavaScript and resource bundle. Firefox for Android's APK is in the same
range.

That is exactly why the build now produces one APK per architecture instead of one universal APK:
a phone can use exactly one of those copies, and the universal APK made every download carry all of
them. Same app, 2.3× less to download.

## Upgrades

**Always check the commit in the release notes before re-testing a fix.** The release notes print the
exact commit each APK was built from (`Build: commit …`). A device that downloaded the previous APK
keeps running the previous code even after a newer release exists — the classic "I installed the
update and nothing changed" that is really "I installed the update from before the update". When a
fix is not behaving, compare the device's APK commit with the fix's commit first; if they differ,
download again.

Two things decide whether a new build installs over the old one:

1. **The signing key.** Android refuses to install an app signed by a different key than the installed
   one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Debug builds are signed with the committed development
   key in `keystore/`, which never changes, so this cannot happen — see `keystore/README.md`. Every
   build publishes the certificate's SHA-256 fingerprint, and CI reads it back from the APK itself, so
   the claim is checkable rather than assumed:

   `BE:49:0F:F8:9F:48:5E:2D:54:07:59:E3:DA:CC:31:D8:17:9B:D9:AD:E5:41:90:3E:DA:B5:FD:B4:C1:AF:0E:05`

2. **The version code.** The app's `versionCode` stays the same between builds, which Android accepts
   for a reinstall. If a build ever refuses to install because of a downgrade, uninstall first.

Upgrading keeps the app's data. What it does **not** keep is the engine's runtime state — pages,
tabs and in-memory sessions are gone, the way they would be after any app update.

## What cannot be avoided, and what can

**Any code change means a new APK.** Android has no mechanism for patch-updating a sideloaded app: it
replaces the whole package, and since the package contains the engine, that is ~117 MB each time. (Play
Store does incremental delivery, but that requires publishing through Play, and this app is installed
from a file.)

If you are iterating on code, the honest options are:

* **Download the arm64 APK and install over the old one** — 117 MB, resumable, keeps your data. This is
  the normal path.
* **Build locally and install over USB.** Once the phone has the app, `./gradlew :app:installDebug`
  (or `adb install -r`) pushes only the changed APK over the cable — no internet download at all. That
  is the fastest loop, and it needs a machine with the Android SDK. The engine is downloaded from
  Maven once and then stays in the local Gradle cache.
* **Ask for the change in a way that batches.** Several fixes in one push means one download.

Two build-side levers are deliberately *not* used, and can be if you want them:

* **A minified release build** (R8) would shrink the app's own code, not the engine — realistically
  15–25 MB less, at the cost of the in-app Network Inspector and Gecko's remote debugging, both of
  which are debug-only by design. It also needs your own signing key
  (`KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD`), not the development one.
* **Shipping fewer architectures**: dropping `armeabi-v7a` and `x86_64` would shorten CI, not any
  individual download, because each APK already contains one architecture. They are kept so an old
  phone or an emulator still works.

## Artifacts vs releases

GitHub *Actions artifacts* require a GitHub login to download and cannot be resumed; they are also
deleted after their retention period (14 days here). The *release assets* above are ordinary public
files: no login, resumable, stable URL, nothing to expire. Both exist — the artifacts are per-ABI too,
and are handy when you want the file attached to the specific run that built it.
