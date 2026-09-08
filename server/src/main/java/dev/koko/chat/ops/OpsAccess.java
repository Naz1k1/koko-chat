package dev.koko.chat.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 独立运维凭据，默认关闭；普通聊天令牌不能访问运维接口。 */
@Component @Profile("local")
public class OpsAccess {
    private final byte[] credential;
    public OpsAccess(@Value("${KOKO_OPS_TOKEN:}") String token) {
        if(!token.isEmpty() && token.length()<32) throw new IllegalArgumentException("KOKO_OPS_TOKEN 至少需要 32 个字符");
        credential=token.getBytes(StandardCharsets.UTF_8);
    }
    public boolean enabled() { return credential.length>0; }
    public boolean accepts(String header) {
        return enabled() && header!=null && header.startsWith("Bearer ") && MessageDigest.isEqual(credential,header.substring(7).getBytes(StandardCharsets.UTF_8));
    }
}
