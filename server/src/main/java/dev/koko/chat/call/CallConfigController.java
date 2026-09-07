package dev.koko.chat.call;

import dev.koko.chat.auth.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** TURN 凭证有效期有限，不向客户端提供 coturn 共享密钥；未配置时仅尝试直连。 */
@RestController @Profile("local")
public class CallConfigController {
    public record Ice(List<String> urls,String username,String credential) {}
    public record Config(List<Ice> iceServers) {}
    private final AuthService auth;private final String urls,secret;
    public CallConfigController(AuthService auth,@Value("${KOKO_TURN_URLS:}") String urls,@Value("${KOKO_TURN_SECRET:}") String secret) {this.auth=auth;this.urls=urls;this.secret=secret;}
    @GetMapping("/api/calls/config") public Config config(@RequestHeader(value="Authorization",required=false) String token) throws Exception {
        var identity=auth.authenticate(token);if(urls.isBlank()) return new Config(List.of());
        if(secret.isBlank()) throw new dev.koko.chat.auth.AuthException(503,"TURN_UNAVAILABLE","通话中继未配置");
        String user=(java.time.Instant.now().getEpochSecond()+3600)+":"+identity.userId();
        var mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
        return new Config(List.of(new Ice(Arrays.stream(urls.split(",")).map(String::trim).toList(),user,Base64.getEncoder().encodeToString(mac.doFinal(user.getBytes(StandardCharsets.UTF_8))))));
    }
}
