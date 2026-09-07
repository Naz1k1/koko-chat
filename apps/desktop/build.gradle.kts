import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.sqldelight)
}

group = "dev.koko.chat"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.json)
    implementation(libs.sqldelight.sqlite)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

sqldelight {
    databases {
        create("DesktopDatabase") {
            packageName.set("dev.koko.chat.desktop.data.generated")
        }
    }
}

tasks.test { useJUnit() }

val desktopJava = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

compose.desktop {
    application {
        mainClass = "dev.koko.chat.desktop.app.MainKt"
        javaHome = desktopJava.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "koko-chat"
            packageVersion = "0.1.0"
            description = "koko-chat Kotlin 桌面客户端"
            vendor = "koko-chat"
            modules("java.sql", "java.net.http", "jdk.crypto.ec", "jdk.unsupported")
            macOS {
                bundleID = "dev.koko.chat.desktop"
                // JDK 21.0.2 jpackage requires a positive first component on macOS.
                packageVersion = "1.0.0"
            }
        }
    }
}
