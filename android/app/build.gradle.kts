import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

/**
 * Clave de subida a Google Play: android/key.properties (fuera de git, ver key.properties.example) o, en CI,
 * variables de entorno GOLAB_KEYSTORE_FILE, GOLAB_KEYSTORE_PASSWORD, GOLAB_KEY_ALIAS y GOLAB_KEY_PASSWORD.
 */
val keyProperties = Properties().apply {
    rootProject.file("key.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signingValue(property: String, env: String): String? =
    keyProperties.getProperty(property)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }
val releaseKeystore = signingValue("storeFile", "GOLAB_KEYSTORE_FILE")

android {
    namespace = "co.golab.deals"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "co.golab.deals"
        minSdk = 24
        targetSdk = 37
        // Subirlo en cada APK que se reparta: Android solo instala encima uno con versionCode mayor, y el
        // aviso de actualización (config/update.latestVersionCode) se compara contra este número.
        versionCode = 5
        versionName = "1.0.0-beta05"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = rootProject.file(releaseKeystore) // relativa a android/, o absoluta
                storePassword = signingValue("storePassword", "GOLAB_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "GOLAB_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "GOLAB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Sin clave de subida, los APK de release se firman con la clave debug (~/.android/debug.keystore) para
            // poder probarlos en el teléfono. El .aab es solo para Play, que rechaza la firma debug: ahí se exige la clave.
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                if (gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }) {
                    throw GradleException("Falta la clave de subida: crea android/key.properties (ver key.properties.example)")
                }
                logger.warn("Release firmado con la clave debug: no sirve para Google Play (falta android/key.properties)")
                signingConfigs.getByName("debug")
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.VERSION_CODE: lo compara el aviso de actualización (AGP 9 lo trae desactivado).
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    // Splash nativa de Android 12+ con retrocompatibilidad hasta minSdk.
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // Firebase arrastra fragment 1.1.0; la Activity Result API (permiso de notificaciones) exige >= 1.3.0.
    implementation(libs.androidx.fragment)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.auth)
    // Eventos de interés (DealAnalytics): van a Google Analytics, no a Firestore.
    implementation(libs.firebase.analytics)
    // Login con Google vía Credential Manager: reemplaza al GoogleSignIn obsoleto de play-services-auth;
    // credentials-play-services-auth es el puente con Play Services en Android 13 y anteriores.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}