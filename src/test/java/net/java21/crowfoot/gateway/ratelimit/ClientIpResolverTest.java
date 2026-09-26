package net.java21.crowfoot.gateway.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 클라이언트 IP 판별 — 신뢰 프록시 등록 여부·소스 신뢰·XFF 형태에 따른 분기 (SCG 5.0 신뢰 모델).
 * 원격 주소는 테스트 전체에서 127.0.0.1 로 고정하고 trusted-proxies 정규식으로 신뢰/비신뢰를 갈라본다.
 */
class ClientIpResolverTest {

    private ClientIpResolver resolverWith(String trustedProxiesPattern) {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedProxies(trustedProxiesPattern);
        return new ClientIpResolver(properties);
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

    @Test
    @DisplayName("신뢰 프록시 미등록이면 원본 XFF 가 있어도 원격 주소를 쓴다")
    void noTrustedProxies_fallsBackToRemoteAddress() {
        assertThat(resolverWith(null).forwardedClientIp(exchangeFrom("203.0.113.9"))).isEmpty();
        assertThat(resolverWith(null).resolve(exchangeFrom("203.0.113.9"))).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("비신뢰 소스(정규식 불일치)면 원본 XFF 를 신뢰하지 않는다")
    void untrustedSource_ignoresForwardedFor() {
        ClientIpResolver resolver = resolverWith("10\\..*");   // 127.0.0.1 은 불일치

        assertThat(resolver.forwardedClientIp(exchangeFrom("203.0.113.9"))).isEmpty();
        assertThat(resolver.resolve(exchangeFrom("203.0.113.9"))).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("신뢰 소스면 원본 XFF 첫 홉을 클라이언트 IP 로 내놓는다")
    void trustedSource_returnsFirstForwardedEntry() {
        ClientIpResolver resolver = resolverWith("127\\.0\\.0\\.1");

        assertThat(resolver.forwardedClientIp(exchangeFrom("203.0.113.9, 10.42.1.5"))).contains("203.0.113.9");
        assertThat(resolver.resolve(exchangeFrom("203.0.113.9, 10.42.1.5"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("첫 홉이 IP 형태가 아니면 버리고 원격 주소로 돌아간다")
    void nonIpFirstEntry_rejected() {
        ClientIpResolver resolver = resolverWith("127\\.0\\.0\\.1");

        assertThat(resolver.forwardedClientIp(exchangeFrom("evil.example.com"))).isEmpty();
        assertThat(resolver.resolve(exchangeFrom("evil.example.com"))).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("XFF 첫 홉이 빈 값(콤마 선행)이면 비어 있다")
    void blankFirstEntry_empty() {
        assertThat(resolverWith("127\\.0\\.0\\.1").forwardedClientIp(exchangeFrom(" , 203.0.113.9"))).isEmpty();
    }

    @Test
    @DisplayName("원본 XFF 가 없으면 신뢰 소스여도 원격 주소를 쓴다")
    void trustedSourceWithoutXff_fallsBackToRemoteAddress() {
        ClientIpResolver resolver = resolverWith("127\\.0\\.0\\.1");

        assertThat(resolver.forwardedClientIp(exchangeFrom(null))).isEmpty();
        assertThat(resolver.resolve(exchangeFrom(null))).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("IPv6 리터럴 첫 홉도 받아들인다")
    void ipv6FirstEntry_accepted() {
        assertThat(resolverWith("127\\.0\\.0\\.1").forwardedClientIp(exchangeFrom("2001:db8::1"))).contains("2001:db8::1");
    }
}
