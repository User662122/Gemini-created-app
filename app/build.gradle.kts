plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.example"
  compileSdk { version = release(37) }

  defaultConfig {
    applicationId = "com.aistudio.chromebrowser.vktpnx"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
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
      storeFile = file("${rootDir}/keystore/dev-debug.p12")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      storeType = "PKCS12"
      enableV1Signing = true
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  splits {
    abi {
      isEnable = true
      reset()
      // Ship one 32-bit APK only; there are no arm64 or x86_64 builds.
      include("armeabi-v7a")
      isUniversalApk = false
    }
  }

  packaging {
    jniLibs {
      excludes.addAll(listOf("**/armeabi/*.so", "**/mips/*.so", "**/mips64/*.so", "**/x86/*.so"))
      useLegacyPackaging = true
    }
  }

  buildFeatures { compose = true }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xskip-metadata-version-check")
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(libs.geckoview)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)

  testImplementation(libs.junit)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
