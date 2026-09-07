package dev.koko.chat.attachment;

import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.Identity;
import dev.koko.chat.message.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import static dev.koko.chat.attachment.AttachmentModels.*;

/** 上传先登记后转存，再确认 READY；消息与附件绑定同事务，下载重新校验当前成员可见范围。 */
@Service @Profile("local")
public class AttachmentService {
    public static final int MAX_BYTES=10*1024*1024;
    private final AttachmentMapper mapper;private final ChatMapper chats;private final AuthService auth;
    private final RustFsStorage storage;private final TransactionTemplate tx;
    private final Semaphore transfers=new Semaphore(4);
    public AttachmentService(AttachmentMapper mapper,ChatMapper chats,AuthService auth,RustFsStorage storage,PlatformTransactionManager manager) {
        this.mapper=mapper;this.chats=chats;this.auth=auth;this.storage=storage;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public View create(Identity identity,String conversation,Create body) {
        long id=ChatService.number(conversation,false);validId(body.clientUploadId());
        if(body.name()==null || body.name().isBlank() || body.name().length()>180 || body.name().matches("(?s).*[\\p{Cntrl}/\\\\].*")
                || body.size()<1 || body.size()>MAX_BYTES || body.sha256()==null || !body.sha256().matches("[a-f0-9]{64}")
                || !Set.of("IMAGE","FILE").contains(Objects.toString(body.kind(),"")) || body.membershipEpoch()==null) invalid();
        try { return tx.execute(status -> {
            member(identity,id,body.membershipEpoch());
            var old=mapper.find(body.clientUploadId());
            if(old!=null) {
                if(old.ownerId()!=identity.userId() || old.conversationId()!=id || !old.membershipEpoch().equals(body.membershipEpoch())
                        || !old.name().equals(body.name()) || old.size()!=body.size() || !old.sha256().equals(body.sha256()) || !old.kind().equals(body.kind()))
                    throw new AuthException(409,"UPLOAD_CONFLICT","同一上传编号不能对应不同附件");
                return new View(reference(old),old.status());
            }
            var row=new Row(body.clientUploadId(),identity.userId(),id,body.membershipEpoch(),"attachments/"+body.clientUploadId(),
                    body.name(),body.size(),body.sha256(),body.kind(),"application/octet-stream","PENDING",null);
            mapper.insert(row);return new View(reference(row),row.status());
        }); } catch(DuplicateKeyException conflict) { throw new AuthException(409,"UPLOAD_CONFLICT","上传编号已被使用，请重试原请求"); }
    }
    private ChatModels.MemberRow member(Identity identity,long conversation,String epoch) {
        if(!auth.active(identity)) throw AuthException.unauthorized();
        var chat=chats.lockConversation(conversation);var member=chats.member(conversation,identity.userId());
        if(chat==null || !"ACTIVE".equals(chat.status()) || member==null || !"ACTIVE".equals(member.status()))
            throw new AuthException(403,"NOT_A_MEMBER","无权访问此会话附件");
        if(epoch!=null && !epoch.equals(member.membershipEpoch())) throw new AuthException(409,"MEMBERSHIP_CHANGED","成员周期已变化，请重新上传");
        return member;
    }
    private Row owned(Identity identity,String id) {
        validId(id);var row=mapper.find(id);
        if(row==null || row.ownerId()!=identity.userId()) throw new AuthException(404,"ATTACHMENT_NOT_FOUND","附件不存在");
        member(identity,row.conversationId(),row.membershipEpoch());return row;
    }
    public View upload(Identity identity,String id,InputStream input) throws IOException {
        Row row=tx.execute(status -> owned(identity,id));
        acquire();
        try {
            byte[] bytes=input.readNBytes(Math.toIntExact(row.size())+1);
            if(bytes.length!=row.size()) throw new AuthException(413,"FILE_SIZE_MISMATCH","文件大小与上传声明不一致");
            if(!HexFormat.of().formatHex(digest(bytes)).equals(row.sha256()))
                throw new AuthException(400,"FILE_HASH_MISMATCH","文件内容已变化，请重新选择文件");
            String type="FILE".equals(row.kind())?"application/octet-stream":imageType(bytes);
            if("PENDING".equals(row.status())) {
                try { storage.put(row.objectKey(),bytes,type); }
                catch(RuntimeException unavailable) { throw new AuthException(503,"STORAGE_UNAVAILABLE","对象存储暂不可用，请重试原上传"); }
            }
            return tx.execute(status -> {
                owned(identity,id);mapper.ready(id,type);var result=mapper.find(id);
                return new View(reference(result),result.status());
            });
        } finally { transfers.release(); }
    }
    private String imageType(byte[] bytes) throws IOException {
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) invalidImage();
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(!Set.of("png","jpeg","jpg").contains(format) || width<1 || height<1 || (long)width*height>4_194_304) invalidImage();
                if(reader.read(0)==null) invalidImage();
                return format.equals("png")?"image/png":"image/jpeg";
            } catch(IOException bad) { invalidImage();return ""; } finally { reader.dispose(); }
        }
    }
    /** 调用方已经持有会话锁，只能绑定本人当前周期、同一会话中已上传的附件。 */
    public Row forSend(Identity identity,long conversation,String epoch,String id) {
        validId(id);var row=mapper.find(id);
        if(row==null || row.ownerId()!=identity.userId() || row.conversationId()!=conversation || !row.membershipEpoch().equals(epoch)
                || "PENDING".equals(row.status())) throw new AuthException(409,"ATTACHMENT_NOT_READY","附件尚未上传或不属于当前会话");
        return row;
    }
    public void attach(String id,long message) {
        if(mapper.attach(id,message)!=1) throw new AuthException(409,"ATTACHMENT_USED","附件已绑定其他消息");
    }
    public record Download(Reference attachment,InputStream stream,Runnable release) implements AutoCloseable {
        @Override public void close() throws IOException { try { stream.close(); } finally { release.run(); } }
    }
    public Download download(Identity identity,String id) {
        validId(id);
        Row row=tx.execute(status -> {
            var file=mapper.find(id);if(file==null) throw new AuthException(404,"ATTACHMENT_NOT_FOUND","附件不存在");
            var member=member(identity,file.conversationId(),null);
            if(file.messageId()==null || !"ATTACHED".equals(file.status())) throw new AuthException(403,"ATTACHMENT_NOT_SENT","附件尚未发送");
            var message=chats.message(file.messageId());
            if(message==null || message.seq()<member.joinSeq()) throw new AuthException(403,"NOT_A_MEMBER","无权读取此成员周期之前的附件");
            return file;
        });
        acquire();
        try { return new Download(reference(row),storage.get(row.objectKey()),transfers::release); }
        catch(RuntimeException unavailable) { transfers.release();throw new AuthException(503,"STORAGE_UNAVAILABLE","附件暂时无法下载"); }
    }
    private static byte[] digest(byte[] bytes) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void acquire() { if(!transfers.tryAcquire()) throw new AuthException(429,"TRANSFER_BUSY","文件传输繁忙，请稍后重试"); }
    public static void validId(String id) { if(id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) invalid(); }
    private static void invalidImage() { throw new AuthException(400,"INVALID_IMAGE","仅支持有效 PNG/JPEG 图片，最多 419 万像素"); }
    private static void invalid() { throw new AuthException(400,"INVALID_ATTACHMENT","附件参数不合法，文件须为 1 字节至 10 MiB"); }
}
