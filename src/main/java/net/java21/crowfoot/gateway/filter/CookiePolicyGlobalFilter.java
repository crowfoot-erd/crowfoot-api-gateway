package net.java21.crowfoot.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Refresh 쿠키 전달 정책 (03-gateway/requirements.md §2) —
 * core 라우트로 가는 요청에서 {@code crowfoot_refresh} 쿠키만 선택 제거하고(다른 쿠키 보존),
 * auth 라우트로 가는 요청의 쿠키는 그대로 유지 전달한다(testing.md AUTH-22).
 *
 * <p>Refresh 쿠키는 {@code Path=/api/v1/auth} 스코핑이라 브라우저는 core 경로에 보내지 않지만,
 * 클라이언트가 수동으로 실어 보내는 경우를 서버 측에서 이중 방어한다.
 */
@Component
public class CookiePolicyGlobalFilter implements GlobalFilter, Ordered {

    static final String REFRESH_COOKIE = "crowfoot_refresh";
    private static final String CORE_ROUTE_PREFIX = "/api/v1/core/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(CORE_ROUTE_PREFIX)) {
            return chain.filter(exchange);   // auth 등 — 쿠키 유지
        }

        List<String> cookieHeaders = exchange.getRequest().getHeaders().get(HttpHeaders.COOKIE);
        if (cookieHeaders == null || cookieHeaders.stream().noneMatch(this::containsRefreshCookie)) {
            return chain.filter(exchange);
        }

        List<String> preserved = cookieHeaders.stream()
                .map(this::withoutRefreshCookie)
                .filter(value -> !value.isEmpty())
                .toList();
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HttpHeaders.COOKIE);
                    if (!preserved.isEmpty()) {
                        headers.put(HttpHeaders.COOKIE, preserved);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean containsRefreshCookie(String cookieHeader) {
        return cookieName(cookieHeader) != null;
    }

    private String withoutRefreshCookie(String cookieHeader) {
        return Arrays.stream(cookieHeader.split(";"))
                .map(String::trim)
                .filter(pair -> !pair.isEmpty())
                .filter(pair -> !pair.startsWith(REFRESH_COOKIE + "="))
                .collect(Collectors.joining("; "));
    }

    private String cookieName(String cookieHeader) {
        return Arrays.stream(cookieHeader.split(";"))
                .map(String::trim)
                .filter(pair -> pair.startsWith(REFRESH_COOKIE + "="))
                .findFirst()
                .orElse(null);
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
