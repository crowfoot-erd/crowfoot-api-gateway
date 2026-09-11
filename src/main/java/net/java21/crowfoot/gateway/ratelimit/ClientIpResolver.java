package net.java21.crowfoot.gateway.ratelimit;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * rate limit 키로 쓸 클라이언트 IP — 원격 주소 기준.
 *
 * <p>{@code X-Forwarded-For} 는 위조 가능하므로 신뢰하지 않는다(Phase 1 — Gateway 직접 수신,
 * 전단 프록시 없음). 프록시 도입 시 신뢰 프록시 체인 재검토.
 */
@Component
public class ClientIpResolver {

    public String resolve(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }
}
