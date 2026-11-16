import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// --- Lettura di local.properties -> BuildConfig ------------------------------------------
// Le chiavi restano fuori dal VCS; in CI si possono passare come variabili d'ambiente
// (ASTRO_DARKSKY_APIKEY ecc.) che hanno la precedenza.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cfg(key: String, default: String): String {
    val envKey = "ASTRO_" + key.uppercase().replace('.', '_')
    return System.getenv(envKey) ?: localProps.getProperty(key) ?: default
}

/** Quota la stringa per un campo BuildConfig, escapando backslash e virgolette. */
fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.astroscout"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.astroscout"
        minSdk = 26 // java.time nativo senza desugaring
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DARKSKY_BASE_URL", cfg("darksky.baseUrl", "https://www.darkskysites.com/").asBuildConfigString())
        buildConfigField("String", "DARKSKY_PATH", cfg("darksky.path", "api/v1/sky-quality").asBuildConfigString())
        buildConfigField("String", "DARKSKY_API_KEY", cfg("darksky.apiKey", "").asBuildConfigString())
        buildConfigField("boolean", "DARKSKY_USE_SIMULATED", cfg("darksky.useSimulated", "false").toBoolean().toString())
        buildConfigField("String", "SEVENTIMER_BASE_URL", cfg("seventimer.baseUrl", "https://www.7timer.info/").asBuildConfigString())
        buildConfigField("String", "HTTP_USER_AGENT", cfg("http.userAgent", "AstroScout/0.1 (Android)").asBuildConfigString())
        buildConfigField("String", "MAP_TILE_URL", cfg("map.tileUrl", "https://tile.openstreetmap.org/{z}/{x}/{y}.png").asBuildConfigString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    // Esporta lo schema Room per poter scrivere migrazioni verificabili.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.maplibre.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
