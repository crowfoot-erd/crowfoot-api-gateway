package net.java21.crowfoot.gateway.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Introspection 전용 WebClient — reactor-netty 는 response timeout 기본값이 무한대라
 * 명시하지 않으면 인증 서버 장애 시 요청이 쌓이고 fail-closed 503 판정이 늦어진다.
 * connect 1s / response 2s — api-design.md §11 내부 호출 권장안 준용.
 */
@Configuration
public class IntrospectionWebClientConfig {

    @Bean
    public WebClient introspectionWebClient(GatewayProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1_000)
                .responseTimeout(Duration.ofSeconds(2));
        return WebClient.builder()
                .baseUrl(properties.authBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
