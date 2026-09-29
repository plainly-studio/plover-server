import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Sync and its encryption: the protocol the app and the server share, the phone's sync engine,
// and the vault's encryption. Kept apart from :core so the server builds on this alone, and so
// it can be published as open source with the server (docs/planned-features.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.animalsniffer)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Runs inside the Android app too: keep to APIs available on JDK 17 / Android.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

// Runs inside the Android app, whose minSdk is 26: check against Android API 26, as for :core.
animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}

dependencies {
    signature(libs.android.api.signature) { artifact { type = "signature" } }
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    // Argon2 for the vault's key derivation.
    implementation(libs.bouncycastle.prov)

    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
