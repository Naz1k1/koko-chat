package dev.koko.chat.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.HexFormat;
import java.util.List;

/** 认证入口的固定窗口限流：原子设置计数和过期时间，Redis 不可用时拒绝认证。 */
@Component
@Profile("local")
public class AuthRateLimiter {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n", Long.class);
    public AuthRateLimiter(StringRedisTemplate redis) { this.redis = redis; }
    public void check(String dimension, String value, int limit) {
        String key = "koko:auth:rate:" + dimension + ":" + HexFormat.of().formatHex(AuthService.digest(value));
        Long count = redis.execute(INCREMENT, List.of(key));
        if (count == null || count > limit) throw new AuthException(429, "RATE_LIMITED", "操作过于频繁，请一分钟后重试");
    }
}
