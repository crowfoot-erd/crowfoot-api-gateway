package net.java21.crowfoot.gateway.filter;

import net.java21.crowfoot.gateway.ratelimit.ClientIpResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * X-Forwarded-For 클라이언트 IP 복원 — SCG 5.0 이 peer 체인으로 재구성한 헤더 뒤에
 * 신뢰 소스 요청의 원본 첫 홉을 맨 앞에 붙인다 (비신뢰 소스는 SCG 제거 상태 유지).
 */
class XForwardedClientIpHeadersFilterTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<XForwardedHeadersFilter> xForwarded = mock(ObjectProvider.class);

    private XForwardedClientIpHeadersFilter filterWith(String trustedProxiesPattern) {
        when(xForwarded.getIfAvailable()).thenReturn(null);   // 순서 계산용 — 빈이 없어도 동작
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedProxies(trustedProxiesPattern);
        return new XForwardedClientIpHeadersFilter(xForwarded, new ClientIpResolver(properties));
    }

    private ServerWebExchange exchangeFrom(String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/v1/core/providers");
        try {
            builder.remoteAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 50000));
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private HttpHeaders rewrittenByScg(String chain) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", chain);   // SCG XForwardedHeadersFilter 가 남긴 신뢰 peer 체인
        return headers;
    }

    @Test
    @DisplayName("신뢰 소스면 원본 첫 홉을 앞에 붙여 표준형 체인으로 만든다")
    void trustedSource_prependsOriginalClientIp() {
        HttpHeaders result = filterWith("127\\.0\\.0\\.1")
                .filter(rewrittenByScg("127.0.0.1"), exchangeFrom("203.0.113.9"));

        assertThat(result.getFirst("X-Forwarded-For")).isEqualTo("203.0.113.9, 127.0.0.1");
    }

    @Test
    @DisplayName("SCG 가 남긴 체인이 없으면 클라이언트 IP 단독으로 설정한다")
    void noChainFromScg_setsClientIpAlone() {
        HttpHeaders result = filterWith("127\\.0\\.0\\.1")
                .filter(new HttpHeaders(), exchangeFrom("203.0.113.9"));

        assertThat(result.getFirst("X-Forwarded-For")).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("비신뢰 소스는 SCG 가 제거한 상태를 그대로 둔다")
    void untrustedSource_keepsStrippedState() {
        HttpHeaders headers = rewrittenByScg("10.42.1.5");
        HttpHeaders result = filterWith("10\\..*")
                .filter(headers, exchangeFrom("203.0.113.9"));

        assertThat(result).isSameAs(headers);
        assertThat(result.getFirst("X-Forwarded-For")).isEqualTo("10.42.1.5");
    }

    @Test
    @DisplayName("신뢰 소스라도 원본 XFF 가 없으면 그대로 둔다")
    void trustedSourceWithoutOriginalXff_unchanged() {
        HttpHeaders headers = rewrittenByScg("127.0.0.1");
        HttpHeaders result = filterWith("127\\.0\\.0\\.1")
                .filter(headers, exchangeFrom(null));

        assertThat(result).isSameAs(headers);
    }

    @Test
    @DisplayName("trusted-proxies 미설정이면 동작하지 않는다")
    void noTrustedProxies_inactive() {
        HttpHeaders headers = rewrittenByScg("127.0.0.1");
        HttpHeaders result = filterWith(null)
                .filter(headers, exchangeFrom("203.0.113.9"));

        assertThat(result).isSameAs(headers);
    }

    @Test
    @DisplayName("요청 방향에만 적용한다")
    void supportsRequestOnly() {
        XForwardedClientIpHeadersFilter filter = filterWith("127\\.0\\.0\\.1");
        assertThat(filter.supports(HttpHeadersFilter.Type.REQUEST)).isTrue();
        assertThat(filter.supports(HttpHeadersFilter.Type.RESPONSE)).isFalse();
    }
}
