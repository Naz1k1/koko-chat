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

// 编译工具链和目标字节码统一为 Java 21，避免 IDE、命令行与安装包使用不同版本。
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

// 从 .sq 文件生成类型化查询和建表代码，生成文件位于 build 目录，不手工维护。
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

// 按当前操作系统生成携带 Java 运行时的应用，打包与编译使用同一套 JDK。
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
            // 裁剪运行时时显式保留 JDBC 与网络/加密模块，供 SQLite 和 HTTPS 使用。
            modules("java.sql", "java.net.http", "jdk.crypto.ec", "jdk.unsupported")
            macOS {
                bundleID = "dev.koko.chat.desktop"
                // 本机 JDK 21.0.2 的 jpackage 要求 macOS 安装包版本首位大于零。
                packageVersion = "1.0.0"
            }
        }
    }
}
