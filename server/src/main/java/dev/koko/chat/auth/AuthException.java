package dev.koko.chat.auth;

/** 可公开的业务错误，不携带密码、令牌或底层数据库异常内容。 */
public class AuthException extends RuntimeException {
    private final int status;
    private final String code;
    public AuthException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static AuthException unauthorized() { return new AuthException(401, "UNAUTHENTICATED", "登录已失效，请重新登录"); }
}
