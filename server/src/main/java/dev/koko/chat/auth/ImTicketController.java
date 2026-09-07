package dev.koko.chat.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static dev.koko.chat.auth.AuthModels.*;

/** 访问令牌走 HTTP Authorization，避免令牌通过 WebSocket URL 进入代理访问日志。 */
@RestController
@Profile("local")
public class ImTicketController {
    private final AuthService auth;
    private final ImTicketService tickets;
    private final AuthRateLimiter limiter;
    public ImTicketController(AuthService auth, ImTicketService tickets, AuthRateLimiter limiter) {
        this.auth=auth;this.tickets=tickets;this.limiter=limiter;
    }
    @PostMapping("/api/im/tickets")
    public TicketResponse issue(@RequestHeader(value="Authorization",required=false) String authorization) {
        Identity identity=auth.authenticate(authorization);
        limiter.check("ticket",identity.sessionId(),30);
        return tickets.issue(identity);
    }
}
