package dev.koko.chat.desktop.platform

import java.nio.file.Path

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
