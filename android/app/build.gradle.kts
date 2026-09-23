plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Release signing. The keystore never lives in the repo: point at it with
// MIEFQUIRL_KEYSTORE and friends, either exported locally or injected by CI.
// When they are absent the release build is simply left unsigned, so a clone
// without the key still builds.
val keystorePath: String? = System.getenv("MIEFQUIRL_KEYSTORE")
    ?: providers.gradleProperty("miefquirl.keystore").orNull

android {
    namespace = "io.github.hamann.miefquirl"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hamann.miefquirl"
        // Karoo 3 is comfortably above this; 26 buys us native multidex and
        // java.nio.file, both of which the Clojure runtime wants.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        // Gives BuildConfig.DEBUG and BuildConfig.VERSION_NAME, the latter
        // used as the version the Karoo System shows for this extension.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MIEFQUIRL_KEYSTORE_PASSWORD")
                    ?: providers.gradleProperty("miefquirl.keystore.password").orNull
                keyAlias = System.getenv("MIEFQUIRL_KEY_ALIAS")
                    ?: providers.gradleProperty("miefquirl.key.alias").orNull
                    ?: "miefquirl"
                keyPassword = System.getenv("MIEFQUIRL_KEY_PASSWORD")
                    ?: providers.gradleProperty("miefquirl.key.password").orNull
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Off until a release build has actually been exercised on-device.
            // karoo-ext serialises its models with kotlinx.serialization, whose
            // serializers R8 cannot see; proguard-rules.pro keeps them, but that
            // is untested. Debug builds — what you sideload — never minify.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "about.html",
            )
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    // The protocol and the control logic — pure Kotlin, no Android.
    implementation(project(":headwind"))

    implementation(libs.karoo.ext)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.timber)
}
