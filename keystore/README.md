# Debug signing key

The debug APK uses `dev-debug.p12` so local builds and CI builds share a signing identity. The key is a standard development key, not a production credential. Release signing remains configured through the `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` environment variables.
