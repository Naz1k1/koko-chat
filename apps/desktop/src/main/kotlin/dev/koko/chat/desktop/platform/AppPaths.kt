package dev.koko.chat.desktop.platform

import java.nio.file.Path

/** 封装各平台应用数据目录；测试可用环境变量隔离存储，避免污染真实用户设置。 */
object AppPaths {
    fun preferencesFile(): Path {
        val override = System.getenv("KOKO_CHAT_DATA_DIR")
        if (!override.isNullOrBlank()) return Path.of(override, "preferences.db")
        val home = System.getProperty("user.home")
        val directory = when {
            System.getProperty("os.name").startsWith("Mac") -> Path.of(home, "Library", "Application Support", "koko-chat")
            System.getProperty("os.name").startsWith("Windows") -> Path.of(System.getenv("APPDATA") ?: home, "koko-chat")
            else -> Path.of(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "koko-chat")
        }
        return directory.resolve("preferences.db")
    }
}
