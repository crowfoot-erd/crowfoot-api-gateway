package net.java21.crowfoot.gateway.filter;

import net.java21.crowfoot.gateway.ratelimit.ClientIpResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.Optional;

/**
 * 실제 클라이언트 IP 를 X-Forwarded-For 로 하류에 전달한다.
 *
 * <p>SCG 5.0 의 {@link XForwardedHeadersFilter} 는 위조 방지를 위해 X-Forwarded-For 를
 * "신뢰 프록시 목록에 등록된 항목 + 직접 연결 peer(인그레스 IP)"로 재구성한다 — 인그레스가 넣은
 * 실제 클라이언트 IP 는 프록시가 아니므로 항상 탈락한다. 이 필터는 그 다음 순서에 실행돼,
 * 신뢰 소스(인그레스)에서 온 요청의 원본 X-Forwarded-For 첫 홉을 맨 앞에 복원해
 * 표준형 {@code 클라이언트IP, 인그레스IP} 로 하류(auth 세션 IP 등)에 전달한다.
 *
 * <p>비신뢰 소스에서 온 요청은 {@link ClientIpResolver#forwardedClientIp} 가 비어 있으므로
 * SCG 가 제거한 상태를 그대로 둔다(스푸핑 차단 유지). trusted-proxies 미설정 시에도 동작하지 않는다.
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
        Optional<String> clientIp = clientIpResolver.forwardedClientIp(exchange);
        if (clientIp.isEmpty()) {
            return headers;   // 비신뢰 소스·원본 XFF 부재·미설정 — SCG 가 제거·재구성한 결과를 그대로 둔다
        }
        String chain = headers.getFirst(X_FORWARDED_FOR);   // SCG 가 남긴 신뢰 peer 체인(예: 인그레스 IP)
        headers.set(X_FORWARDED_FOR, chain == null || chain.isBlank()
                ? clientIp.get()
                : clientIp.get() + ", " + chain);
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
