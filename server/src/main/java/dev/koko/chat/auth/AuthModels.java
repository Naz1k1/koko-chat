package dev.koko.chat.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDateTime;

/** HTTP 数据契约与持久化结果分离，避免把密码摘要或令牌摘要序列化给客户端。 */
public final class AuthModels {
    private AuthModels() {}
    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}") String account,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(max = 64) String nickname) {}
    public record LoginRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}") String account,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String deviceId) {}
    public record RefreshRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {}
    public record UserView(String id, String account, String nickname) {}
    public record TokenResponse(String accessToken, String refreshToken, Instant accessExpiresAt,
                                Instant refreshExpiresAt, String sessionId, UserView user) {}
    public record TicketResponse(String ticket, long expiresInSeconds) {}
    public record ApiError(String code, String message) {}
    public record UserRow(long id, String account, String passwordHash, String nickname, String status) {
        public UserView view() { return new UserView(Long.toString(id), account, nickname); }
    }
    public record SessionRow(String id, long userId, String deviceId, LocalDateTime expiresAt) {}
    /** Netty 身份来自服务端票据，客户端无法自行声明用户编号。 */
    public record Identity(String sessionId, long userId, String deviceId) {}
}
