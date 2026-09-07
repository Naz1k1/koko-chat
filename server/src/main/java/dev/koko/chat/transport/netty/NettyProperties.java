package dev.koko.chat.transport.netty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Netty 接入配置；端口 0 用于测试随机监听，帧和聚合消息分别限制大小。 */
@Validated
@ConfigurationProperties("koko.netty")
public record NettyProperties(
        @DefaultValue("127.0.0.1") @NotBlank String host,
        @DefaultValue("8081") @Min(0) @Max(65535) int port,
        @DefaultValue("/im") @Pattern(regexp = "/[a-zA-Z0-9/_-]+") String path,
        @DefaultValue("16384") @Min(256) @Max(1048576) int maxFrameBytes,
        @DefaultValue("16384") @Min(256) @Max(1048576) int maxMessageBytes,
        @DefaultValue("75s") Duration idleTimeout,
        @DefaultValue("30s") Duration authenticationTimeout) {
    // 在绑定配置时拒绝无效超时，避免连接无期限占用资源。
    public NettyProperties {
        if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()
                || authenticationTimeout == null || authenticationTimeout.isNegative()
                || authenticationTimeout.isZero()) {
            throw new IllegalArgumentException("Netty timeouts must be positive");
        }
    }
}
