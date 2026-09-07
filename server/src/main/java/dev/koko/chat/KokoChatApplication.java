package dev.koko.chat;

import dev.koko.chat.transport.netty.NettyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 后端启动入口：同时装配 HTTP 服务和由 Spring 管理生命周期的 Netty 接入层。 */
@SpringBootApplication
@EnableConfigurationProperties(NettyProperties.class)
public class KokoChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(KokoChatApplication.class, args);
    }
}
