package dev.koko.chat.auth;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static dev.koko.chat.auth.AuthModels.*;

/** 认证业务层：密码慢哈希、随机令牌摘要、事务内轮换。密码运算不占用数据库事务。 */
@Service
@Profile("local")
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AuthMapper mapper;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final PasswordEncoder passwords = new DelegatingPasswordEncoder("pbkdf2-v1",
            Map.of("pbkdf2-v1", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    private final String dummyHash = passwords.encode(token());
    public record SessionsRevoked(List<String> sessionIds) {}

    public AuthService(AuthMapper mapper, PlatformTransactionManager transactions, ApplicationEventPublisher events) {
        this.mapper = mapper; this.tx = new TransactionTemplate(transactions); this.events = events;
        // 空设备会话范围不需要间隙锁：同账号登录已由用户行锁串行化，唯一约束继续兜底。
        // 使用 RC 避免不同新用户同时撤销空范围后插入会话造成 RR 间隙锁死锁。
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public UserView register(RegisterRequest request) {
        String account = request.account().toLowerCase(Locale.ROOT);
        String hash = passwords.encode(request.password());
        // 正数随机编号无需分布式时钟；主键冲突由唯一约束兜底并重试，不能覆盖其他用户。
        for (int attempt = 0; attempt < 3; attempt++) {
            UserRow user = new UserRow(RANDOM.nextLong(1, Long.MAX_VALUE), account, hash, request.nickname().trim(), "ACTIVE");
            try { mapper.insertUser(user); return user.view(); }
            catch (DuplicateKeyException duplicate) {
                if (mapper.userByAccount(account) != null) throw new AuthException(409, "ACCOUNT_EXISTS", "账号已存在");
            }
        }
        throw new AuthException(503, "BUSY", "服务繁忙，请稍后重试");
    }

    public TokenResponse login(LoginRequest request) {
        UserRow user = mapper.userByAccount(request.account().toLowerCase(Locale.ROOT));
        // 未知账号也执行同类哈希，避免明显的账号存在性时序差异。
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.passwordHash());
        if (!matches || user == null || !"ACTIVE".equals(user.status()))
            throw new AuthException(401, "INVALID_CREDENTIALS", "账号或密码错误");
        return tx.execute(status -> {
            UserRow locked = mapper.lockUser(user.id());
            if (!"ACTIVE".equals(locked.status())) throw AuthException.unauthorized();
            List<String> old = mapper.deviceSessions(user.id(), request.deviceId());
            mapper.revokeDevice(user.id(), request.deviceId());
            String id = UUID.randomUUID().toString(), access = token(), refresh = token();
            Instant now = Instant.now(), expires = now.plus(Duration.ofDays(7)), accessExpires = now.plusSeconds(900);
            mapper.insertSession(id, user.id(), request.deviceId(), digest(refresh), utc(expires), digest(access), utc(accessExpires));
            events.publishEvent(new SessionsRevoked(old));
            return new TokenResponse(access, refresh, accessExpires, expires, id, locked.view());
        });
    }

    public TokenResponse refresh(String refreshToken) {
        return tx.execute(status -> {
            SessionRow session = mapper.lockRefresh(digest(refreshToken));
            if (session == null) throw AuthException.unauthorized();
            UserRow user = mapper.user(session.userId());
            if (!"ACTIVE".equals(user.status())) throw AuthException.unauthorized();
            Instant expires = session.expiresAt().toInstant(ZoneOffset.UTC);
            Instant accessExpires = Instant.now().plusSeconds(900);
            if (accessExpires.isAfter(expires)) accessExpires = expires;
            String access = token(), refresh = token();
            mapper.rotate(session.id(), digest(access), utc(accessExpires), digest(refresh));
            return new TokenResponse(access, refresh, accessExpires, expires, session.id(), user.view());
        });
    }

    /** 已知会话重复注销仍成功；轮换后未知的旧令牌返回 401，不能误报已撤销新会话。 */
    public void logout(String refreshToken) {
        tx.executeWithoutResult(status -> {
            SessionRow session = mapper.lockLogout(digest(refreshToken));
            if (session == null) throw AuthException.unauthorized();
            mapper.revoke(session.id());
            events.publishEvent(new SessionsRevoked(List.of(session.id())));
        });
    }

    public Identity authenticate(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) throw AuthException.unauthorized();
        SessionRow session = mapper.byAccess(digest(authorization.substring(7)));
        if (session == null) throw AuthException.unauthorized();
        return new Identity(session.id(), session.userId(), session.deviceId());
    }
    public UserView me(Identity identity) { return mapper.user(identity.userId()).view(); }
    public boolean active(Identity identity) { return mapper.active(identity) == 1; }
    public static String token() { byte[] value = new byte[32]; RANDOM.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    public static byte[] digest(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
