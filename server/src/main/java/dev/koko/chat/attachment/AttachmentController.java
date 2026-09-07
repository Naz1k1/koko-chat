package dev.koko.chat.attachment;

import dev.koko.chat.auth.AuthService;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static dev.koko.chat.attachment.AttachmentModels.*;

/** 二进制传输使用 HTTP，不进入 Netty 消息帧；读取正文之前完成身份与附件权限验证。 */
@RestController @Profile("local")
public class AttachmentController {
    private final AuthService auth;private final AttachmentService attachments;
    public AttachmentController(AuthService auth,AttachmentService attachments) { this.auth=auth;this.attachments=attachments; }
    @PostMapping("/api/conversations/{id}/attachments")
    public View create(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,@RequestBody Create body) {
        return attachments.create(auth.authenticate(token),id,body);
    }
    @PutMapping(value="/api/attachments/{id}/content",consumes="application/octet-stream")
    public View upload(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,HttpServletRequest request) throws IOException {
        return attachments.upload(auth.authenticate(token),id,request.getInputStream());
    }
    @GetMapping("/api/attachments/{id}/content")
    public void download(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,HttpServletResponse response) throws IOException {
        try(var download=attachments.download(auth.authenticate(token),id)) {
            response.setContentType(download.attachment().contentType());response.setContentLengthLong(download.attachment().size());
            response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(download.attachment().name(),StandardCharsets.UTF_8).build().toString());
            download.stream().transferTo(response.getOutputStream());
        }
    }
    @GetMapping("/api/attachments/{id}/thumbnail")
    public void thumbnail(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,HttpServletResponse response) throws IOException {
        try(var download=attachments.thumbnail(auth.authenticate(token),id)) {
            response.setContentType("image/jpeg");response.setContentLengthLong(download.attachment().size());
            response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Content-SHA256",download.attachment().sha256());
            download.stream().transferTo(response.getOutputStream());
        }
    }
}
