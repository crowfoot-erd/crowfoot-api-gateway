package net.java21.crowfoot.gateway.ratelimit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.headers.TrustedProxies;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * rate limit 키로 쓸 클라이언트 IP.
 *
 * <p>신뢰 경계: {@code X-Forwarded-For} 는 위조 가능하므로, 직접 수신(원격 주소 = 소스)인 경우
 * 일절 신뢰하지 않는다. 전단 프록시(인그레스)를 둔 운영에서는
 * {@code spring.cloud.gateway.server.webflux.trusted-proxies} 에 등록된 소스로부터 온
 * 요청에 한해 그 프록시가 남긴 {@code X-Forwarded-For} 첫 홉(= 실제 클라이언트 IP)을 키로 쓴다.
 * SCG 5.0 부터는 trusted-proxies 미설정 시 게이트웨이가 X-Forwarded-* 를 아예 제거하므로,
 * 이 등록이 없으면 rate limit 키가 프록시 주소로 수렴한다(전 사용자 공유 버킷).
 */
@Component
public class ClientIpResolver {

    private static final Log log = LogFactory.getLog(ClientIpResolver.class);

    /** X-Forwarded-For 항목 형태(IPv4/IPv6 리터럴) — IP가 아닌 값은 세션 IP·rate limit 키로 못 쓰게 한다 */
    private static final Pattern IP_LIKE = Pattern.compile("[0-9A-Fa-f:.]+");

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final TrustedProxies trustedProxies;   // null = trusted-proxies 미설정

    public ClientIpResolver(GatewayProperties gatewayProperties) {
        String pattern = gatewayProperties.getTrustedProxies();
        this.trustedProxies = StringUtils.hasText(pattern) ? TrustedProxies.from(pattern) : null;
    }

    public String resolve(ServerWebExchange exchange) {
        return forwardedClientIp(exchange)
                .orElseGet(() -> remoteAddress(exchange));
    }

    /**
     * 신뢰 프록시가 남긴 X-Forwarded-For 첫 홉 — 실제 클라이언트 IP.
     * 신뢰 프록시 미등록·비신뢰 소스 수신·원본 헤더 부재면 비어 있다(위조 방지).
     */
    public Optional<String> forwardedClientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String forwarded = exchange.getRequest().getHeaders().getFirst(X_FORWARDED_FOR);
        if (trustedProxies == null) {
            if (forwarded != null) {
                log.info("X-Forwarded-For 수신했으나 trusted-proxies 미설정으로 폐기한다 — 전단 프록시가 있다면 설정해야 클라이언트 IP가 전달된다");
            }
            return Optional.empty();
        }
        if (remote == null || remote.getAddress() == null) {
            return Optional.empty();
        }
        if (!trustedProxies.isTrusted(remote.getAddress().getHostAddress())) {
            if (forwarded != null) {
                log.info("비신뢰 소스(" + remote.getAddress().getHostAddress() + ")의 X-Forwarded-For 폐기 — 위조 방지");
            }
            return Optional.empty();
        }
        if (forwarded == null || forwarded.isBlank()) {
            return Optional.empty();
        }
        String first = forwarded.split(",")[0].trim();
        if (first.isEmpty() || !IP_LIKE.matcher(first).matches()) {
            return Optional.empty();
        }
        log.debug("신뢰 프록시 경유 클라이언트 IP=" + first);
        return Optional.of(first);
    }

    private String remoteAddress(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }
}
