import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  // API 37, not 36.1: GeckoView 157's AAR metadata requires it (and so do the androidx libraries
  // this project already resolves). Compiling against a newer API says nothing about which devices
  // can install the app — that is minSdk, below.
  compileSdk { version = release(37) }

  defaultConfig {
    applicationId = "com.aistudio.chromebrowser.vktpnx"
    // GeckoView requires Android 8.0 (API 26). The previous WebView-based engine ran on API 24;
    // see docs/ENGINE_MIGRATION.md ("Compatibility") for why the floor moved.
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    getByName("debug") {
      // A committed development key instead of whatever the build machine happens to have.
      //
      // Android refuses to install an app over one signed by a different key, and a CI runner's
      // generated debug keystore is not guaranteed to be the same file from one run to the next.
      // With the key pinned here, every build installs over the previous one and keeps its data —
      // tabs, history, bookmarks, cookies — instead of requiring an uninstall. See keystore/README.md;
      // the key is not a secret (it is the throwaway `androiddebugkey` every Android SDK creates).
      storeFile = file("${rootDir}/keystore/dev-debug.p12")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      storeType = "PKCS12"
      // v1 in addition to the default v2/v3 schemes: a JAR-style signature is what tools that only
      // understand v1 (keytool among them) can read, and nothing this app supports needs it dropped.
      enableV1Signing = true
    }
  }

  // Keep debug signing on Android Gradle Plugin's generated default key. A clean CI checkout
  // does not contain the project-local debug.keystore ignored by Git.
  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")

      // The developer Network Inspector is hard-disabled for release builds. Together with
      // BuildConfig.DEBUG (false here) and ApplicationInfo.FLAG_DEBUGGABLE (false for a release
      // certificate) this makes the inspector unreachable in production, and R8 can strip it.
      buildConfigField("boolean", "NETWORK_INSPECTOR_ENABLED", "false")
    }
    debug {
      // Debug builds carry the inspector, plus the debug-only Application subclass in
      // app/src/debug/ that installs it. Set this to "false" to switch the feature off entirely.
      buildConfigField("boolean", "NETWORK_INSPECTOR_ENABLED", "true")
    }
  }
  compileOptions {
    // GeckoView requires Java 17 source/target compatibility; it uses Java 17 APIs internally.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  splits {
    // One APK per CPU architecture.
    //
    // Gecko's native library *is* the app's size: it ships a full copy of Gecko per architecture, and
    // a universal APK carries all of them while a phone can use exactly one. Splitting means each
    // download is a third of the universal APK, and the universal APK is switched off for the same
    // reason — it is the artifact nobody's device needs in full.
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "armeabi-v7a", "x86_64")
      isUniversalApk = false
    }
  }
  packaging {
    jniLibs {
      // How Firefox for Android itself ships Gecko: the engine's native libraries are compressed
      // into the APK (legacy packaging) rather than stored page-aligned, and the architectures no
      // current device uses are left out — GeckoView carries a library per ABI, and every one of
      // them costs roughly a hundred megabytes.
      excludes.addAll(
        listOf("**/armeabi/*.so", "**/mips/*.so", "**/mips64/*.so", "**/x86/*.so")
      )
      useLegacyPackaging = true
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// The Kotlin language level and the Kotlin stdlib version on the classpath are decided by two
// different things: this project pins the compiler (2.2.10), while Gradle resolves the *newest* stdlib
// any dependency asks for — and GeckoView 157 asks for 2.4.20. A compiler can read stdlib metadata up
// to one minor version ahead of itself, so the mismatch is reported as an error rather than silently
// producing something odd.
//
// Pinning the stdlib below what the engine declares would risk a NoSuchMethodError inside the engine
// at runtime, which is worse than a compiler flag: the engine gets exactly the stdlib it was built
// against, and the compiler is told to accept metadata from a newer release. Remove this the next time
// the Kotlin plugin is upgraded to 2.4.x.
kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xskip-metadata-version-check")
    // Must match compileOptions above: AGP fails the build when the Java and Kotlin targets differ.
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  // The browsing engine. GeckoView packages Mozilla's Gecko (rendering/layout), SpiderMonkey
  // (JavaScript) and Gecko's own networking/storage stack as an Android library. It replaces
  // android.webkit.WebView as the engine while the browser UI stays app-owned. See
  // docs/ENGINE_MIGRATION.md.
  implementation(libs.geckoview)
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
