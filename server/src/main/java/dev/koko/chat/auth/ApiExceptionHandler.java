package dev.koko.chat.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import static dev.koko.chat.auth.AuthModels.*;

/** 错误响应不回显输入值，防止校验异常把密码或令牌泄露到响应及日志。 */
@RestControllerAdvice(basePackages = "dev.koko.chat.auth")
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(AuthException.class)
    ResponseEntity<ApiError> business(AuthException error) {
        return ResponseEntity.status(error.status()).body(new ApiError(error.code(), error.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "请检查账号、密码、昵称和设备编号格式"));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> unavailable(DataAccessException error) {
        log.warn("认证存储暂不可用：{}", error.getClass().getSimpleName());
        return ResponseEntity.status(503).body(new ApiError("SERVICE_UNAVAILABLE", "认证服务暂不可用，请稍后重试"));
    }
}
