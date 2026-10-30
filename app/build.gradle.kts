plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.sp3wro.hamradiotoolkit"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.sp3wro.hamradiotoolkit"
        minSdk = 24
        targetSdk = 36
        // Wersja: RR.MM.betaN; versionCode = RRMM·100 + N (musi rosnąć przy każdym wydaniu)
        versionCode = 261010
        versionName = "26.10.beta10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8: minifikacja i usuwanie nieużywanego kodu. Retrofit, OkHttp i kotlinx.serialization
            // dostarczają własne reguły keep, więc projekt nie potrzebuje dodatkowych
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME – wersja w logowaniu APRS-IS i nagłówku User-Agent
        buildConfig = true
    }
    // Język wybiera się w aplikacji – w paczce AAB muszą być wszystkie języki, a nie tylko język telefonu
    bundle {
        language {
            enableSplit = false
        }
    }
    testOptions {
        unitTests.all {
            // Pełny zestaw nagrań FT8 z ft8_lib: ./gradlew test -Pft8.testDir=<katalog test/wav>
            providers.gradleProperty("ft8.testDir").orNull?.let { dir -> it.systemProperty("ft8.testDir", dir) }
            providers.gradleProperty("ft8.verbose").orNull?.let { v -> it.systemProperty("ft8.verbose", v) }
            // Pobrane kalendarze zawodów (sp9cxn.html, pkukf.html, qrzru.ics) do sprawdzenia parserów: -Pcontests.dir=<katalog>
            providers.gradleProperty("contests.dir").orNull?.let { d -> it.systemProperty("contests.dir", d) }
            // Pobrane dane radiodyplom.pl (radiodyplom.ics, ses_<id>.html) do sprawdzenia parserów: -Pawards.dir=<katalog>
            providers.gradleProperty("awards.dir").orNull?.let { d -> it.systemProperty("awards.dir", d) }
            // Pełne pobrane dane przemienników (hearham.json, repeatermap_*.json): -Pintl.dataDir=<katalog>
            providers.gradleProperty("intl.dataDir").orNull?.let { d -> it.systemProperty("intl.dataDir", d) }
            // Nagrania DSSTV (HamDRM) z QSSTV: -Pdsstv.wavDir=<katalog z wav> (i katalogi out_* z plikami wzorcowymi)
            providers.gradleProperty("dsstv.wavDir").orNull?.let { d -> it.systemProperty("dsstv.wavDir", d) }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.play.services.location)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Retrofit & Serialization
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // Coil (Image loading)
    implementation(libs.coil.compose)

    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Satellite SGP4 prediction
    implementation(libs.predict4java)

    // Zadania w tle: powiadomienia (satelity, DX, pogoda kosmiczna) i odświeżanie widżetu
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}