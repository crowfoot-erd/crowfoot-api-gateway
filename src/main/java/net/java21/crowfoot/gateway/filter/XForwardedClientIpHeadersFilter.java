package net.java21.crowfoot.gateway.filter;

import net.java21.crowfoot.gateway.ratelimit.ClientIpResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * 실제 클라이언트 IP 를 X-Forwarded-For 로 하류에 전달한다.
 *
 * <p>게이트웨이 앞의 전단은 두 가지 토폴로지가 있고, 이 필터는 둘 다 복원한다.
 * <ol>
 * <li>신뢰 프록시(인그레스) 경유 — SCG 5.0 의 {@link XForwardedHeadersFilter} 는 위조 방지를 위해
 *     X-Forwarded-For 를 "신뢰 프록시 목록에 등록된 항목 + 직접 연결 peer(인그레스 IP)"로 재구성한다.
 *     인그레스가 넣은 실제 클라이언트 IP 는 프록시가 아니므로 항상 탈락한다. 이 필터는 신뢰 소스
 *     요청의 원본 X-Forwarded-For 첫 홉을 맨 앞에 복원해 표준형 {@code 클라이언트IP, 인그레스IP} 로 만든다.
 * <li>소스 보존 직접 수신(운영 실측 2026-09-26) — 전단(호스트 Nginx → NodePort)이 클라이언트
 *     소스 IP 를 보존해 전달하는 체계에서는 게이트웨이의 직접 peer 가 곧 실제 클라이언트 공인 IP 다.
 *     이때는 신뢰 프록시가 아니므로 SCG 는 X-Forwarded-For 를 제거하는데, TCP 소켓 주소는 위조가
 *     불가능하므로 그 값을 그대로 하류 X-Forwarded-For 로 materialize 한다(비신뢰 소스가 보낸
 *     위조 XFF 는 이미 SCG 가 폐기했다).
 * </ol>
 * 두 경우 모두 {@link ClientIpResolver#resolve} 가 "신뢰 XFF 첫 홉, 없으면 소켓 주소"로
 * 하나로 수렴시키며, 하류(auth 세션 IP 등)는 첫 홉을 클라이언트 IP 로 읽는다.
 */
@Component
public class XForwardedClientIpHeadersFilter implements HttpHeadersFilter, Ordered {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final ObjectProvider<XForwardedHeadersFilter> xForwardedHeadersFilter;
    private final ClientIpResolver clientIpResolver;

    public XForwardedClientIpHeadersFilter(ObjectProvider<XForwardedHeadersFilter> xForwardedHeadersFilter,
                                           ClientIpResolver clientIpResolver) {
        this.xForwardedHeadersFilter = xForwardedHeadersFilter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public HttpHeaders filter(HttpHeaders headers, ServerWebExchange exchange) {
        String clientIp = clientIpResolver.resolve(exchange);   // 신뢰 XFF 첫 홉 — 없으면 소켓 주소(직접 수신 클라이언트)
        if (clientIp == null || clientIp.isBlank() || "unknown".equals(clientIp)) {
            return headers;   // 원격 주소를 알 수 없는 경우만 — SCG 가 제거·재구성한 결과를 그대로 둔다
        }
        String chain = headers.getFirst(X_FORWARDED_FOR);   // SCG 가 남긴 신뢰 peer 체인(예: 인그레스 IP)
        if (chain != null && (chain.equals(clientIp) || chain.startsWith(clientIp + ","))) {
            return headers;   // 체인 첫 홉이 이미 이 IP 다(신뢰 peer 본인 요청 등) — 중복 부착 방지
        }
        headers.set(X_FORWARDED_FOR, chain == null || chain.isBlank()
                ? clientIp
                : clientIp + ", " + chain);
        return headers;
    }

    @Override
    public boolean supports(Type type) {
        return type == Type.REQUEST;
    }

    @Override
    public int getOrder() {
        XForwardedHeadersFilter xForwarded = xForwardedHeadersFilter.getIfAvailable();
        return (xForwarded != null ? xForwarded.getOrder() : 0) + 1;   // XForwardedHeadersFilter 재구성 직후
    }
}
