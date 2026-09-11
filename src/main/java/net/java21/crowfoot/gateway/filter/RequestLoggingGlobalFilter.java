package net.java21.crowfoot.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 접근 로그 — method·path·status·상관 ID·라우트 ID (03-gateway/requirements.md §4).
 * 토큰 원문은 절대 로그에 남기지 않는다. 응답 완료 시점(마지막 순서)에 기록한다.
 */
@Slf4j
@Component
public class RequestLoggingGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).doFinally(signal -> {
            String requestId = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.REQUEST_ID_HEADER);
            Object routeId = exchange.getAttributes().get(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            log.info("gateway request: {} {} -> {} (requestId={}, route={})",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(),
                    exchange.getResponse().getStatusCode(),
                    requestId,
                    routeId);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
