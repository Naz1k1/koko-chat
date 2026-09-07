package dev.koko.chat.system;

public record SystemInfo(String name, String version, String stage, int httpPort, int imPort, String imPath) {
}
