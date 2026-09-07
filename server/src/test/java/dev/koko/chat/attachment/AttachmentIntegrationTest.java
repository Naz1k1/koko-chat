package dev.koko.chat.attachment;

import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.*;
import dev.koko.chat.contact.*;
import dev.koko.chat.group.*;
import dev.koko.chat.message.*;
import dev.koko.chat.message.ChatModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.util.*;
import static dev.koko.chat.attachment.AttachmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 HTTP、MySQL 与 RustFS 验证附件生命周期、访问权限及跨存储失败后的重试。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"koko.netty.port=0","koko.messaging.outbox-enabled=false","spring.rabbitmq.listener.simple.auto-startup=false"})
@ActiveProfiles("local") @DirtiesContext @EnabledIfEnvironmentVariable(named="KOKO_CHAT_AUTH_TEST",matches="true")
class AttachmentIntegrationTest {
    @LocalServerPort int port;
    @Autowired AuthService auth; @Autowired AuthMapper users; @Autowired ChatService chat; @Autowired ChatMapper chats;
    @Autowired AttachmentService files; @Autowired AttachmentMapper mapper; @Autowired RustFsStorage storage;
    @Autowired GroupService groups; @Autowired ContactService contacts; @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions; @Autowired ObjectMapper json;
    private final List<Long> people=new ArrayList<>();private final Set<String> conversations=new HashSet<>();
    private final List<String> objects=new ArrayList<>();
    record Person(Identity identity,String account,String token) { String id(){return Long.toString(identity.userId());} }
    private Person person() {
        String account="it_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        var user=auth.register(new RegisterRequest(account,"Attachment-test-password!","附件测试"));people.add(Long.parseLong(user.id()));
        var token=auth.login(new LoginRequest(account,"Attachment-test-password!","attachment-test"));
        return new Person(auth.authenticate("Bearer "+token.accessToken()),account,token.accessToken());
    }
    private ConversationView direct(Person a,Person b) {
        var info=chat.createDirect(a.identity(),b.account());conversations.add(info.id());return info;
    }
    private Create create(Person owner,ConversationView info,byte[] bytes,String kind) throws Exception {
        String id=UUID.randomUUID().toString();objects.add("attachments/"+id);
        var body=new Create(id,info.membershipEpoch(),"测试附件."+(kind.equals("IMAGE")?"png":"bin"),bytes.length,
                HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),kind);
        var response=http(owner,"POST","/api/conversations/"+info.id()+"/attachments",json.writeValueAsBytes(body),"application/json");
        assertThat(response.statusCode()).isEqualTo(200);assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("PENDING");
        return body;
    }
    private HttpResponse<byte[]> http(Person person,String method,String path,byte[] bytes,String type) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(person!=null) request.header("Authorization","Bearer "+person.token());
        if(type!=null) request.header("Content-Type",type);
        return HttpClient.newHttpClient().send(request.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private HttpResponse<byte[]> put(Person p,Create file,byte[] bytes) throws Exception {return http(p,"PUT","/api/attachments/"+file.clientUploadId()+"/content",bytes,"application/octet-stream");}
    private HttpResponse<byte[]> get(Person p,Create file) throws Exception {return http(p,"GET","/api/attachments/"+file.clientUploadId()+"/content",null,null);}
    private SendCommand send(ConversationView info,Create file,String command) {return new SendCommand(info.id(),info.membershipEpoch(),command,null,file.clientUploadId());}

    @Test void uploadRetryMessageAtomicityAndAuthenticatedDownload() throws Exception {
        var a=person();var b=person();var stranger=person();var info=direct(a,b);
        byte[] bytes=new byte[32768];new Random(17).nextBytes(bytes);var file=create(a,info,bytes,"FILE");
        var command=send(info,file,UUID.randomUUID().toString());
        assertThatThrownBy(()->chat.send(a.identity(),command)).isInstanceOf(AuthException.class).extracting("code").isEqualTo("ATTACHMENT_NOT_READY");
        assertThat(put(a,file,Arrays.copyOf(bytes,bytes.length-1)).statusCode()).isEqualTo(413);
        byte[] wrong=bytes.clone();wrong[0]++;
        assertThat(put(a,file,wrong).statusCode()).isEqualTo(400);
        assertThat(put(a,file,bytes).statusCode()).isEqualTo(200);
        assertThat(files.create(a.identity(),info.id(),file).status()).isEqualTo("READY");
        assertThat(put(a,file,bytes).statusCode()).isEqualTo(200);
        assertThat(get(a,file).statusCode()).isEqualTo(403);
        // Outbox 插入失败必须一并回滚附件绑定与会话序号。
        var failing=spy(chats);doThrow(new IllegalStateException("injected outbox failure")).when(failing).insertOutbox(anyString(),anyLong(),anyString());
        var broken=new ChatService(failing,users,auth,json,transactions,mock(org.springframework.context.ApplicationEventPublisher.class),files);
        assertThatThrownBy(()->broken.send(a.identity(),command)).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.find(file.clientUploadId()).status()).isEqualTo("READY");
        assertThat(chats.conversation(Long.parseLong(info.id())).latestSeq()).isZero();
        var sent=chat.send(a.identity(),command);assertThat(sent.type()).isEqualTo("FILE");assertThat(sent.attachment().sha256()).isEqualTo(file.sha256());
        assertThat(chat.send(a.identity(),command).id()).isEqualTo(sent.id());
        String canonical=json.writeValueAsString(new TreeMap<>(Map.of("text",sent.text(),"attachment",sent.attachment())));
        assertThat(chats.message(Long.parseLong(sent.id())).bodyHash()).isEqualTo(AuthService.digest(canonical));
        assertThat(put(a,file,bytes).statusCode()).isEqualTo(200);
        assertThatThrownBy(()->chat.send(a.identity(),send(info,file,UUID.randomUUID().toString())))
                .isInstanceOf(AuthException.class).extracting("code").isEqualTo("ATTACHMENT_USED");
        assertThat(chats.conversation(Long.parseLong(info.id())).latestSeq()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE message_id=?",Integer.class,sent.id())).isEqualTo(1);
        var downloaded=get(b,file);assertThat(downloaded.statusCode()).isEqualTo(200);assertThat(downloaded.body()).isEqualTo(bytes);
        assertThat(downloaded.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(downloaded.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;");
        assertThat(get(null,file).statusCode()).isEqualTo(401);assertThat(get(stranger,file).statusCode()).isEqualTo(403);
        var anonymous=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(System.getenv().getOrDefault("RUSTFS_ENDPOINT","http://127.0.0.1:9000")+"/"+System.getenv().getOrDefault("RUSTFS_BUCKET","koko-chat")+"/attachments/"+file.clientUploadId())).GET().build(),HttpResponse.BodyHandlers.discarding());
        assertThat(anonymous.statusCode()).isEqualTo(403);
    }
    @Test void imageValidationStorageFailureAndGroupRejoinCannotReadOldAttachment() throws Exception {
        var a=person();var b=person();var request=contacts.create(a.identity(),new ContactModels.CreateRequest(b.account(),"测试"));contacts.handle(b.identity(),request.id(),true);
        String id=groups.create(a.identity(),new GroupModels.Create(UUID.randomUUID().toString(),"附件群",List.of(b.id()))).groupId();conversations.add(id);
        var info=chat.summary(a.identity(),id);String oldEpoch=chat.summary(b.identity(),id).membershipEpoch();
        var image=new java.awt.image.BufferedImage(32,24,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);byte[] bytes=output.toByteArray();
        var file=create(a,info,bytes,"IMAGE");
        var offline=mock(RustFsStorage.class);doThrow(new IllegalStateException("injected unavailable")).when(offline).put(anyString(),any(byte[].class),anyString());
        var broken=new AttachmentService(mapper,chats,auth,offline,transactions);
        assertThatThrownBy(()->broken.upload(a.identity(),file.clientUploadId(),new ByteArrayInputStream(bytes)))
                .isInstanceOf(AuthException.class).extracting("code").isEqualTo("STORAGE_UNAVAILABLE");
        assertThat(mapper.find(file.clientUploadId()).status()).isEqualTo("PENDING");
        assertThat(put(a,file,bytes).statusCode()).isEqualTo(200);
        chat.send(a.identity(),send(info,file,UUID.randomUUID().toString()));
        assertThat(get(b,file).body()).isEqualTo(bytes);assertThat(mapper.find(file.clientUploadId()).contentType()).isEqualTo("image/png");
        groups.remove(a.identity(),id,b.id(),new GroupModels.Change(UUID.randomUUID().toString(),info.membershipEpoch(),oldEpoch));
        assertThat(get(b,file).statusCode()).isEqualTo(403);
        groups.invite(a.identity(),id,new GroupModels.Invite(UUID.randomUUID().toString(),info.membershipEpoch(),List.of(b.id())));
        assertThat(get(b,file).statusCode()).isEqualTo(403);
        byte[] fake="<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var invalid=create(a,info,fake,"IMAGE");var rejected=put(a,invalid,fake);
        assertThat(rejected.statusCode()).isEqualTo(400);assertThat(json.readTree(rejected.body()).path("code").asText()).isEqualTo("INVALID_IMAGE");
    }
    @AfterEach void cleanup() {
        for(String key:objects) storage.delete(key);
        for(String id:conversations) {
            jdbc.update("DELETE FROM device_cursor WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM message_outbox WHERE message_id IN (SELECT id FROM message WHERE conversation_id=?)",id);
            jdbc.update("DELETE FROM message WHERE conversation_id=?",id);jdbc.update("DELETE FROM attachment WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM conversation_member WHERE conversation_id=?",id);jdbc.update("DELETE FROM group_command WHERE conversation_id=?",id);jdbc.update("DELETE FROM conversation WHERE id=?",id);
        }
        for(long id:people) {jdbc.update("DELETE FROM friendship WHERE user_id=? OR friend_id=?",id,id);jdbc.update("DELETE FROM friend_request WHERE sender_id=? OR receiver_id=?",id,id);jdbc.update("DELETE FROM auth_session WHERE user_id=?",id);}
        for(long id:people) jdbc.update("DELETE FROM app_user WHERE id=?",id);
    }
}
