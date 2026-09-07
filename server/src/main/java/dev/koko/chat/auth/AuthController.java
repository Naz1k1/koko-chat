package dev.koko.chat.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Locale;
import static dev.koko.chat.auth.AuthModels.*;

/** HTTP 认证入口；仅 local 配置启用真实持久化认证，骨架模式不会模拟登录成功。 */
@RestController
@RequestMapping("/api/auth")
@Profile("local")
public class AuthController {
    private final AuthService service;
    private final AuthRateLimiter limiter;
    public AuthController(AuthService service, AuthRateLimiter limiter) { this.service = service; this.limiter = limiter; }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        limit(request, body.account()); return service.register(body);
    }
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        limit(request, body.account()); return service.login(body);
    }
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        limiter.check("ip", request.getRemoteAddr(), 60); return service.refresh(body.refreshToken());
    }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest body) { service.logout(body.refreshToken()); }
    @GetMapping("/me")
    public UserView me(@RequestHeader(value="Authorization", required=false) String authorization) {
        return service.me(service.authenticate(authorization));
    }
    private void limit(HttpServletRequest request, String account) {
        // 未配置可信代理前只使用实际 TCP 对端，不信任任意 X-Forwarded-For。
        limiter.check("ip", request.getRemoteAddr(), 60);
        limiter.check("account", account.toLowerCase(Locale.ROOT), 12);
    }
}
