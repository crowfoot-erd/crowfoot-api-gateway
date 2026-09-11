package net.java21.crowfoot.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 상관 ID 발급·전파 (03-gateway/requirements.md §4) — 헤더명 X-REQUEST-ID 로 확정.
 *
 * <p>WebFilter 로 라우트 밖(미정의 경로 404·actuator)까지 커버한다.
 * 요청 헤더에 있으면 유지(하류 전파), 없으면 UUID 발급. 응답 헤더로도 에코한다.
 */
@Component
public class CorrelationIdFilter implements WebFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "X-REQUEST-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        String requestId = incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, requestId))
                .build();
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);   // 응답 에코

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
