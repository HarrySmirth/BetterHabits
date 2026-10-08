import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Database tests: applies supabase/migrations to an embedded Postgres (same major version as the
// Supabase project) and exercises RLS policies and RPCs as different users. No Docker required.
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
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.postgresql)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    val migrations = layout.projectDirectory.dir("../migrations")
    inputs.dir(migrations).withPropertyName("migrations")
    systemProperty("migrationsDir", migrations.asFile.absolutePath)
}
