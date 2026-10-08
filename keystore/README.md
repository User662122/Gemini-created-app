# Development signing key

`dev-debug.p12` is the key that **debug** builds are signed with.

It is committed on purpose, and it contains no secret: it is a throwaway development key — the same
kind of key the Android SDK creates on every developer's machine (`androiddebugkey`, password
`android`, `CN=Android Debug`). Anyone can read it, and that is fine: a debug build is `debuggable`
and is not something to distribute. Publish a **release** build with your own upload key instead
(`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` in the build environment — see
`signingConfigs.release` in `app/build.gradle.kts`).

## Why it is here at all

Android will not install an app over one signed by a *different* key. It reports
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` and the only way forward is to uninstall the app first — which
throws away its cookies, history, bookmarks and settings.

That matters because these debug APKs are built on GitHub runners, and a runner's generated debug
keystore is not a file anyone guarantees to be the same from one run to the next. With the key pinned
in the repository:

* every build is signed by the same key, so a new APK installs **over** the previous one and keeps
  its data;
* the certificate fingerprint is a known value that CI publishes with each build, so this is
  checkable rather than assumed.

| | |
| --- | --- |
| Alias | `androiddebugkey` |
| Store password | `android` |
| Key password | `android` |
| Format | PKCS#12 (`storeType = "PKCS12"`) |
| Validity | until 2056 |
| Certificate SHA-256 | `BE:49:0F:F8:9F:48:5E:2D:54:07:59:E3:DA:CC:31:D8:17:9B:D9:AD:E5:41:90:3E:DA:B5:FD:B4:C1:AF:0E:05` |

Replace the file with `keytool -genkeypair -keystore keystore/dev-debug.p12 -storetype PKCS12 -alias
androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 -dname
"CN=Android Debug,O=Android,C=US"` if you ever want a different one — but note that changing it means
the next install needs an uninstall first.
