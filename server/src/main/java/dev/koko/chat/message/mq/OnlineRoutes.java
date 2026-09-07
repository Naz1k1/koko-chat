package dev.koko.chat.message.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.koko.chat.auth.AuthModels.Identity;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;

/** 按用户分桶的在线路由租约；查询异常必须向上传递，不能把 Redis 故障误判为离线。 */
@Component @Profile("local")
public class OnlineRoutes {
    public record Route(String sessionId,long userId,String deviceId,String gateway) {}
    private final StringRedisTemplate redis;private final ObjectMapper json;private final MessagingTopology topology;
    private static final DefaultRedisScript<List> READ=new DefaultRedisScript<>("""
        local expired=redis.call('ZRANGEBYSCORE',KEYS[1],'-inf',ARGV[1]);
        for _,id in ipairs(expired) do redis.call('ZREM',KEYS[1],id); redis.call('HDEL',KEYS[2],id); end;
        return redis.call('HVALS',KEYS[2]);
        """,List.class);
    private static final DefaultRedisScript<Long> TOUCH=new DefaultRedisScript<>("""
        redis.call('ZADD',KEYS[1],ARGV[2],ARGV[1]); redis.call('HSET',KEYS[2],ARGV[1],ARGV[3]);
        redis.call('EXPIRE',KEYS[1],90); redis.call('EXPIRE',KEYS[2],90); return 1;
        """,Long.class);
    public OnlineRoutes(StringRedisTemplate redis,ObjectMapper json,MessagingTopology topology) { this.redis=redis;this.json=json;this.topology=topology; }
    private List<String> keys(long user) { String base=topology.name("online:{"+user+"}");return List.of(base+":expiry",base+":routes"); }
    public void touch(Identity identity) {
        try { redis.execute(TOUCH,keys(identity.userId()),identity.sessionId(),Long.toString(System.currentTimeMillis()+15000),
                json.writeValueAsString(new Route(identity.sessionId(),identity.userId(),identity.deviceId(),topology.route()))); }
        catch(com.fasterxml.jackson.core.JsonProcessingException bad) { throw new IllegalStateException(bad); }
    }
    public List<Route> routes(long user) {
        List<?> values=redis.execute(READ,keys(user),Long.toString(System.currentTimeMillis()));
        if(values==null) throw new IllegalStateException("Cannot read online routes");
        return values.stream().map(value -> {
            try { return json.readValue(value.toString(),Route.class); }
            catch(Exception bad) { throw new IllegalStateException("Invalid online route",bad); }
        }).toList();
    }
}
