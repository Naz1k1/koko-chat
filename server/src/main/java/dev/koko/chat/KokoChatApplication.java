package dev.koko.chat;

import dev.koko.chat.transport.netty.NettyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NettyProperties.class)
public class KokoChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(KokoChatApplication.class, args);
    }
}
