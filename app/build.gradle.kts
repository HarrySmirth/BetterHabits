import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no kotlin-android plugin is applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** Reads a git-ignored local properties file, or returns empty properties when it doesn't exist. */
fun localProperties(name: String): Properties = Properties().apply {
    val file = rootProject.file(name)
    if (file.exists()) file.inputStream().use(::load)
}

val secrets = localProperties("secrets.properties")
val keystoreProps = localProperties("keystore.properties")

/** Local file first, then environment variable (CI). Never committed. */
fun config(props: Properties, key: String, env: String): String? =
    props.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }

// Single source of truth for the version: `betterhabits.version` in gradle.properties (semver).
// versionCode = MAJOR*10000 + MINOR*100 + PATCH, so it rises monotonically with the semver.
val appVersion = providers.gradleProperty("betterhabits.version").get()
val appVersionCode = appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "minor/patch must be < 100 to keep versionCode monotonic" }
    major * 10_000 + minor * 100 + patch
}

android {
    namespace = "app.betterhabits"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.betterhabits"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion

        // Starts the app with in-memory fakes so UI tests never touch a real backend.
        testInstrumentationRunner = "app.betterhabits.BetterHabitsTestRunner"

        // Client-safe values only (project URL + publishable key). Security relies on RLS, never on
        // hiding these. Empty values make the app show a "not configured" state instead of crashing.
        buildConfigField("String", "SUPABASE_URL", "\"${config(secrets, "supabase.url", "SUPABASE_URL").orEmpty()}\"")
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${config(secrets, "supabase.publishableKey", "SUPABASE_PUBLISHABLE_KEY").orEmpty()}\"",
        )
        buildConfigField("String", "GITHUB_REPOSITORY", "\"${providers.gradleProperty("betterhabits.githubRepository").get()}\"")
        // OAuth *web* client ID (public). Empty hides the Google sign-in button.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${config(secrets, "google.webClientId", "GOOGLE_WEB_CLIENT_ID").orEmpty()}\"",
        )
        // Must match CHILD_EMAIL_DOMAIN used by the child-accounts Edge Function.
        buildConfigField("String", "CHILD_EMAIL_DOMAIN", "\"${providers.gradleProperty("betterhabits.childEmailDomain").get()}\"")
    }

    // Release signing: keystore.properties locally, or environment variables in GitHub Actions.
    // When neither is present the release build is left unsigned and the release workflow refuses to publish it.
    val storeFilePath = config(keystoreProps, "storeFile", "ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = config(keystoreProps, "storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = config(keystoreProps, "keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = config(keystoreProps, "keyPassword", "ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Fakes shared by JVM unit tests and instrumented UI tests.
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    }
}

// Room schemas are exported and committed so database migrations can be reviewed and tested.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Offline-first: Room is the UI's source of truth; WorkManager retries sync when back online.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)

    // Backend: Supabase auth, PostgREST and Edge Functions over Ktor/OkHttp.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.functions)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)

    // Google sign-in via Android Credential Manager (ID token handed to Supabase).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
