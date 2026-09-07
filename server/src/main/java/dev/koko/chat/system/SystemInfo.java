package dev.koko.chat.system;

/** 跨 Java/Kotlin 的系统探针 DTO；端口反映实际监听值，stage 表示功能阶段而非认证状态。 */
public record SystemInfo(String name, String version, String stage, int httpPort, int imPort, String imPath) {
}
