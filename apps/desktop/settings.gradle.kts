// 插件仓库与应用依赖仓库分开声明，固定使用官方发行源。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "koko-chat-desktop"
