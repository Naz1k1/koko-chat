package dev.koko.chat.ops;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 先鉴权再读取正文；运维入口禁止缓存，并限制分块请求大小。 */
@Component @Profile("local")
public class OpsFilter extends OncePerRequestFilter {
    private final OpsAccess access;
    private final java.util.concurrent.Semaphore requests=new java.util.concurrent.Semaphore(4);
    public OpsFilter(OpsAccess access) { this.access=access; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/internal/ops/"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        if(!access.enabled()) { response.sendError(404);return; }
        if(!access.accepts(request.getHeader("Authorization"))) { response.sendError(401);return; }
        if(request.getContentLengthLong()>4096) { response.sendError(413);return; }
        if(!requests.tryAcquire()) { response.sendError(429);return; }
        try { chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() throws IOException {
                var input=super.getInputStream();
                return new ServletInputStream() {
                    int count;
                    @Override public int read() throws IOException { int b=input.read();if(b!=-1 && ++count>4096) throw new IOException("Ops request too large");return b; }
                    @Override public boolean isFinished() { return input.isFinished(); }
                    @Override public boolean isReady() { return input.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
                };
            }
        },response); } finally { requests.release(); }
    }
}
