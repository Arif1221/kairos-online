plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.skarif.kairos"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.skarif.kairos"
        minSdk = 28 // DynamicsProcessing (10-band EQ) needs Android 9
        targetSdk = 37
        // Online: main's version + a 4th online number; versionCode = main's * 10 + that number.
        versionCode = 293
        versionName = "0.9.1.3"
    }

    // Release signing comes from the environment (GitHub secrets in CI), never from the repo.
    // Without it, local release builds fall back to the debug key so they still install.
    val keystorePath = System.getenv("KAIROS_KEYSTORE")
    val hasReleaseKey = keystorePath != null && file(keystorePath).exists()
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KAIROS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KAIROS_KEY_ALIAS")
                keyPassword = System.getenv("KAIROS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // "play": the offline build (same as Arif1221/kairos main). "online": adds YouTube Music search
    // and streaming; shipped on GitHub Releases only.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("online") {
            dimension = "distribution"
            applicationIdSuffix = ".online"
            versionNameSuffix = "-online"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.nio APIs that need desugaring below API 33.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.androidx.metrics.performance)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.palette.ktx)

    testImplementation(libs.junit)

    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    "onlineImplementation"(libs.newpipe.extractor)
    "onlineImplementation"(libs.okhttp)
    "onlineImplementation"(libs.media3.database)
}
