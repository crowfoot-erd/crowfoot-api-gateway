package net.java21.crowfoot.gateway.filter;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.gateway.common.GatewayError;
import net.java21.crowfoot.gateway.common.GatewayRejectedException;
import net.java21.crowfoot.gateway.ratelimit.ClientIpResolver;
import net.java21.crowfoot.gateway.ratelimit.FixedWindowRateLimiter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * IP 단위 rate limit (03-gateway/requirements.md §4) — 인증 검증보다 먼저 실행돼
 * 무토큰 요청 폭주도 카운트된다. 초과 시 429 RATE_LIMITED + Retry-After + 공통 실패 포맷(api.md §3.6).
 */
@Component
@RequiredArgsConstructor
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private final FixedWindowRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = clientIpResolver.resolve(exchange);
        if (!rateLimiter.tryAcquire(clientIp)) {
            return Mono.error(new GatewayRejectedException(
                    GatewayError.RATE_LIMITED, null, rateLimiter.retryAfterSeconds()));
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
