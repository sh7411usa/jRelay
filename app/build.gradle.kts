plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sh7411usa.jrelay"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.sh7411usa.jrelay"
        minSdk = 24
        targetSdk = 36
        versionCode = 35
        versionName = "6.66"
    }

    buildTypes {
        debug {
            // The debug APK is the artifact the owner actually sideloads (assembleDebug output),
            // and the relay phone is plugged into a PC regularly to grant WRITE_SECURE_SETTINGS.
            // Without this override, AGP's default android:debuggable="true" would let any
            // ADB-authorised machine pull databases/jrelay.db (real member phone numbers plus the
            // full message log) via `adb shell run-as`, no root required.
            // logcat still works without android:debuggable, so the owner's device checklist is
            // unaffected.
            isDebuggable = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
}
