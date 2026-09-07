package dev.koko.chat.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.HexFormat;
import static dev.koko.chat.auth.AuthModels.*;

/** 票据只用于一次 WebSocket 认证；Redis GETDEL 将读取和删除合为原子操作。 */
@Service
@Profile("local")
public class ImTicketService {
    private final StringRedisTemplate redis;
    private final AuthService auth;
    public ImTicketService(StringRedisTemplate redis, AuthService auth) { this.redis=redis; this.auth=auth; }
    public TicketResponse issue(Identity identity) {
        String ticket=AuthService.token();
        String value=identity.sessionId()+":"+identity.userId()+":"+identity.deviceId();
        redis.opsForValue().set(key(ticket),value,Duration.ofSeconds(30));
        return new TicketResponse(ticket,30);
    }
    public Identity consume(String ticket) {
        if (ticket==null || !ticket.matches("[A-Za-z0-9_-]{43}")) throw AuthException.unauthorized();
        String value=redis.opsForValue().getAndDelete(key(ticket));
        if (value==null) throw AuthException.unauthorized();
        String[] parts=value.split(":",3);
        Identity identity=new Identity(parts[0],Long.parseLong(parts[1]),parts[2]);
        // 即使票据尚未到期，也必须再次检查登录是否已被注销或同设备登录替换。
        if (!auth.active(identity)) throw AuthException.unauthorized();
        return identity;
    }
    private String key(String ticket) { return "koko:im:ticket:"+HexFormat.of().formatHex(AuthService.digest(ticket)); }
}
