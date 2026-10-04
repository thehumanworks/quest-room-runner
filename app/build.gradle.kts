import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.meta.spatial.plugin)
}

android {
  namespace = "com.thehumanworks.roomrunner"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.thehumanworks.roomrunner"
    // HorizonOS is Android 14 (API level 34)
    minSdk = 34
    //noinspection OldTargetApi,ExpiredTargetSdkVersion
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"
    ndkVersion = "27.0.12077973"
  }

  // A project-local DEBUG keystore (password "android") is committed on purpose so that builds
  // from this box, from GitHub Actions and from your Mac all share one signature, which means
  // `adb install -r` can upgrade in place. It protects nothing; never use it for a store release.
  signingConfigs {
    getByName("debug") {
      // Stored as base64 text in git (keeps the repo text-only); decoded on first build.
      val jks = rootProject.file("keystore/roomrunner-debug.jks")
      val b64 = rootProject.file("keystore/roomrunner-debug.jks.b64")
      if (!jks.exists() && b64.exists()) {
        jks.writeBytes(Base64.getMimeDecoder().decode(b64.readText()))
      }
      storeFile = rootProject.file("keystore/roomrunner-debug.jks")
      storePassword = "android"
      keyAlias = "roomrunner"
      keyPassword = "android"
    }
  }

  packaging { resources.excludes.add("META-INF/LICENSE") }

  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // Sideload-friendly: release is signed with the debug key too.
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  buildFeatures { buildConfig = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
  implementation(libs.androidx.core.ktx)

  // Meta Spatial SDK
  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.meta.spatial.sdk.mruk)

  testImplementation(libs.junit)
}
