package dev.koko.chat.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 禁止缓存认证数据；大请求在 JSON 反序列化之前被限制，避免认证入口无限读取。 */
@Component
public class AuthResponseFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/auth/") && !request.getRequestURI().equals("/api/im/tickets"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        if (request.getContentLengthLong() > 8192) { response.sendError(413); return; }
        // 未知 Content-Length（如分块请求）也限制累计读取字节数。
        HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() throws IOException {
                ServletInputStream input = super.getInputStream();
                return new ServletInputStream() {
                    private int count;
                    @Override public int read() throws IOException { int b=input.read(); if (b!=-1 && ++count>8192) throw new IOException("Request too large"); return b; }
                    @Override public boolean isFinished() { return input.isFinished(); }
                    @Override public boolean isReady() { return input.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
                };
            }
        };
        chain.doFilter(wrapped, response);
    }
}
