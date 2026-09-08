package dev.koko.chat.ops;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 两个独立后端 JVM，共享临时 MySQL 库和唯一 MQ 命名空间；真实杀进程后跨节点补拉。 */
@EnabledIfEnvironmentVariable(named="KOKO_CHAT_MULTI_NODE_TEST",matches="true")
class MultiNodeRecoveryTest {
    private final ObjectMapper json=new ObjectMapper();
    private final String scope="it-multi-"+UUID.randomUUID().toString().substring(0,8);
    private final String schema="koko_chat_test_"+UUID.randomUUID().toString().replace("-","");
    private final String opsToken=UUID.randomUUID().toString()+UUID.randomUUID();
    private final List<Node> nodes=new ArrayList<>();
    private record Node(Process process,int http,int im,Path log) {}
    @Test void crossNodeDeliverySurvivesAbruptGatewayExitAndExpiredPublisherLease() throws Exception {
        String url=System.getenv("KOKO_CHAT_MYSQL_TEST_URL"),password=System.getenv("KOKO_CHAT_MYSQL_TEST_PASSWORD");
        assertThat(url).isNotBlank();assertThat(password).isNotBlank();
        try(var database=DriverManager.getConnection(url,"root",password);var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            execute(database,"CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                Node a=start(password),b=start(password);ready(client,a);ready(client,b);database.setCatalog(schema);verifyOperationsCli(a);
                var alice=account(client,a,"multi_alice");var bob=account(client,b,"multi_bob");
                String ta=alice.path("accessToken").asText(),tb=bob.path("accessToken").asText();
                var conversation=http(client,a,"POST","/api/conversations/direct",ta,Map.of("account","multi_bob"));
                String cid=conversation.path("id").asText(),epoch=conversation.path("membershipEpoch").asText();
                try(var left=connect(client,a,ta);var right=connect(client,b,tb)) {
                    var first=left.command(Map.of("type","SEND","conversationId",cid,"membershipEpoch",epoch,"clientMsgId",UUID.randomUUID().toString(),"text","跨节点消息"),"SEND_ACK");
                    String firstId=first.path("message").path("id").asText();assertThat(firstId).isNotBlank();
                    assertThat(right.await("MESSAGE",firstId).path("message").path("text").asText()).isEqualTo("跨节点消息");
                    // 模拟发布者成功发到 broker 后未写回确认便退出，租约到期允许另一个节点重发。
                    execute(database,"UPDATE message_outbox SET status='PUBLISHING',published_at=NULL,lease_token='00000000-0000-0000-0000-000000000001',lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE message_id="+Long.parseLong(firstId));
                    assertThat(right.await("MESSAGE",firstId).path("message").path("id").asText()).isEqualTo(firstId);
                    assertThat(count(database,"SELECT COUNT(*) FROM message WHERE id="+Long.parseLong(firstId))).isEqualTo(1);
                    b.process().destroyForcibly();assertThat(b.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
                    var second=left.command(Map.of("type","SEND","conversationId",cid,"membershipEpoch",epoch,"clientMsgId",UUID.randomUUID().toString(),"text","节点退出期间消息"),"SEND_ACK");
                    String secondId=second.path("message").path("id").asText();
                    // 同一登录会话切换到存活节点，历史补拉不依赖旧网关队列。
                    try(var recovered=connect(client,a,tb)) {
                        var history=http(client,a,"GET","/api/conversations/"+cid+"/messages?afterSeq=1&toSeq=2",tb,null);
                        assertThat(history.path("messages").size()).isEqualTo(1);assertThat(history.path("messages").get(0).path("id").asText()).isEqualTo(secondId);
                        var third=left.command(Map.of("type","SEND","conversationId",cid,"membershipEpoch",epoch,"clientMsgId",UUID.randomUUID().toString(),"text","跨节点重连后推送"),"SEND_ACK");
                        recovered.await("MESSAGE",third.path("message").path("id").asText());
                        assertThat(count(database,"SELECT COUNT(*) FROM message WHERE conversation_id="+Long.parseLong(cid))).isEqualTo(3);
                    }
                }
            } finally {
                for(var node:nodes) {node.process().destroyForcibly();node.process().waitFor(10,TimeUnit.SECONDS);}
                try { cleanupQueues(); } finally { execute(database,"DROP DATABASE `"+schema+"`"); }
            }
        }
    }
    private Node start(String password) throws Exception {
        int http=port(),im=port();while(im==http) im=port();Path log=Files.createTempFile("koko-multi-node-",".log");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx256m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),"dev.koko.chat.KokoChatApplication","--spring.profiles.active=local","--server.port="+http,"--koko.netty.port="+im,"--koko.messaging.prefix="+scope,"--koko.ops.monitor-delay-ms=500");
        builder.environment().put("MYSQL_DATABASE",schema);builder.environment().put("MYSQL_USERNAME","root");builder.environment().put("MYSQL_PASSWORD",password);
        builder.environment().put("KOKO_OPS_TOKEN",opsToken);builder.redirectErrorStream(true).redirectOutput(log.toFile());var node=new Node(builder.start(),http,im,log);nodes.add(node);return node;
    }
    private void ready(HttpClient client,Node node) throws Exception {
        for(int i=0;i<200;i++) {
            if(!node.process().isAlive()) throw new IllegalStateException("子节点启动失败，日志："+node.log());
            try {if(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+node.http()+"/actuator/health")).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200) return;}catch(Exception ignored) { }
            Thread.sleep(200);
        }
        throw new IllegalStateException("子节点未就绪，日志："+node.log());
    }
    private JsonNode account(HttpClient client,Node node,String account) throws Exception {
        http(client,node,"POST","/api/auth/register",null,Map.of("account",account,"password","Multi-node-test-password!","nickname",account));
        return http(client,node,"POST","/api/auth/login",null,Map.of("account",account,"password","Multi-node-test-password!","deviceId",UUID.randomUUID().toString()));
    }
    private JsonNode http(HttpClient client,Node node,String method,String path,String token,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+node.http()+path)).timeout(Duration.ofSeconds(5));
        if(token!=null) request.header("Authorization","Bearer "+token);
        request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isBetween(200,299);return json.readTree(response.body());
    }
    private Probe connect(HttpClient client,Node node,String token) throws Exception {
        String ticket=http(client,node,"POST","/api/im/tickets",token,null).path("ticket").asText();var probe=new Probe();
        probe.socket=client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+node.im()+"/im"),probe).get(5,TimeUnit.SECONDS);
        probe.command(Map.of("type","AUTH","ticket",ticket),"AUTH_OK");return probe;
    }
    private class Probe implements WebSocket.Listener,AutoCloseable {
        WebSocket socket;final BlockingQueue<String> messages=new LinkedBlockingQueue<>();final StringBuilder partial=new StringBuilder();
        @Override public void onOpen(WebSocket socket) {socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last) {partial.append(text);if(last) {messages.add(partial.toString());partial.setLength(0);}socket.request(1);return null;}
        JsonNode command(Map<String,String> body,String response) throws Exception {var value=json.valueToTree(body);((com.fasterxml.jackson.databind.node.ObjectNode)value).put("v",1).put("requestId",UUID.randomUUID().toString());socket.sendText(value.toString(),true).get(5,TimeUnit.SECONDS);return await(response,null);}
        JsonNode await(String type,String message) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end) {String next=messages.poll(200,TimeUnit.MILLISECONDS);if(next==null) continue;var value=json.readTree(next);if(type.equals(value.path("type").asText()) && (message==null || message.equals(value.path("message").path("id").asText()))) return value;}throw new AssertionError("未收到预期的 "+type);}
        @Override public void close() {if(socket!=null) socket.abort();}
    }
    /** 用真实运维 CLI 验证启用状态下的定时快照；凭据只放子进程环境。 */
    private void verifyOperationsCli(Node node) throws Exception {
        Path output=Files.createTempFile("koko-ops-cli-",".json");
        Path script=Path.of(System.getProperty("user.dir"),"../scripts/ops.py").normalize();
        for(int i=0;i<10;i++) {
            var builder=new ProcessBuilder("python3",script.toString(),"--url","http://127.0.0.1:"+node.http(),"status");
            builder.environment().put("KOKO_OPS_TOKEN",opsToken);builder.redirectOutput(output.toFile());builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            var process=builder.start();
            if(!process.waitFor(12,TimeUnit.SECONDS)) {process.destroyForcibly();throw new AssertionError("运维 CLI 超时");}
            if(process.exitValue()==0) {var status=json.readTree(Files.readString(output));assertThat(status.path("metrics").path("mysql_up").asDouble()).isEqualTo(1);Files.delete(output);return;}
            Thread.sleep(500);
        }
        throw new AssertionError("定时运维快照未就绪");
    }
    private void cleanupQueues() {
        var factory=new CachingConnectionFactory(System.getenv().getOrDefault("RABBITMQ_HOST","127.0.0.1"),Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT","5672")));
        factory.setUsername(System.getenv().getOrDefault("RABBITMQ_USERNAME","koko"));factory.setPassword(System.getenv("RABBITMQ_PASSWORD"));
        try {var admin=new RabbitAdmin(factory);for(String suffix:List.of("dispatch.q","dispatch.dlq","dispatch.retry.5s.q","dispatch.retry.30s.q","dispatch.retry.120s.q")) admin.deleteQueue(scope+"."+suffix);for(String suffix:List.of("message.x","gateway.x","retry.x","dead.x")) admin.deleteExchange(scope+"."+suffix);}finally {factory.destroy();}
    }
    private static int port() throws Exception {try(var socket=new java.net.ServerSocket(0)) {return socket.getLocalPort();}}
    private static void execute(Connection db,String sql) throws Exception {try(var statement=db.createStatement()) {statement.execute(sql);}}
    private static long count(Connection db,String sql) throws Exception {try(var statement=db.createStatement();var result=statement.executeQuery(sql)) {result.next();return result.getLong(1);}}
}
