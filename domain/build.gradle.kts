import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin module: business rules (scheduling, fairness, allocation, streaks).
// Must never depend on Android, Room or Supabase types so it stays fast to test and portable.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
