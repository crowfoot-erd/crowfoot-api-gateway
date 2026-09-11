package net.java21.crowfoot.gateway.auth;

import net.java21.crowfoot.gateway.common.GatewayError;
import net.java21.crowfoot.gateway.common.GatewayRejectedException;
import net.java21.crowfoot.gateway.testsupport.TestTokenFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IntrospectionClientTest {

    private static final String TOKEN = TestTokenFactory.access("1001", "jti-1", 9999999999L);

    private static MockWebServer authServer;
    private static IntrospectionClient client;

    @BeforeAll
    static void setUp() throws IOException {
        authServer = new MockWebServer();
        authServer.start();
        client = new IntrospectionClient(
                WebClient.builder().baseUrl(authServer.url("/").toString()).build(),
                JsonMapper.builder().build());
    }

    @AfterAll
    static void tearDown() throws IOException {
        authServer.shutdown();
    }

    @Test
    @DisplayName("활성 토큰은 POST form-urlencoded token= 로 요청하고 active 결과를 반환한다")
    void introspect_activeResponse_returnsActiveResult() throws InterruptedException {
        // given
        authServer.enqueue(json("""
                {"header":{"isSuccessful":true,"resultCode":"SUCCESS","resultMessage":"성공"},
                 "response":{"active":true,"sub":"1001","jti":"jti-1","typ":"ACCESS","exp":9999999999}}
                """));

        // when
        StepVerifier.create(client.introspect(TOKEN))
                // then
                .expectNext(new IntrospectionClient.IntrospectionResult(
                        true, "1001", "jti-1", 9999999999L, null))
                .verifyComplete();

        RecordedRequest recorded = authServer.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/internal/auth/introspect");
        assertThat(recorded.getHeader("Content-Type")).contains("application/x-www-form-urlencoded");
        String body = recorded.getBody().readUtf8();
        assertThat(body).startsWith("token=");
        assertThat(URLDecoder.decode(body.substring("token=".length()), StandardCharsets.UTF_8))
                .isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("비활성 응답은 inactiveReason 과 함께 정상 반환된다 — 만료/무효 구분 원천")
    void introspect_inactiveResponse_returnsInactiveResultWithReason() {
        // given
        authServer.enqueue(json("""
                {"header":{"isSuccessful":true,"resultCode":"SUCCESS","resultMessage":"성공"},
                 "response":{"active":false,"inactiveReason":"EXPIRED"}}
                """));

        // when // then
        StepVerifier.create(client.introspect(TOKEN))
                .expectNext(new IntrospectionClient.IntrospectionResult(
                        false, null, null, null, "EXPIRED"))
                .verifyComplete();
    }

    @Test
    @DisplayName("인증 서버 5xx는 fail-closed 503(GateWayRejectedException)으로 정규화된다")
    void introspect_serverError_failsClosed() {
        // given
        authServer.enqueue(new MockResponse().setResponseCode(503).setBody("upstream down"));

        // when // then
        StepVerifier.create(client.introspect(TOKEN))
                .expectErrorSatisfies(throwable -> {
                    assertThat(throwable).isInstanceOf(GatewayRejectedException.class);
                    assertThat(((GatewayRejectedException) throwable).getError())
                            .isEqualTo(GatewayError.AUTH_SERVICE_UNAVAILABLE);
                })
                .verify();
    }

    @Test
    @DisplayName("계약 밖 본문(HTML)도 장애 취급한다")
    void introspect_nonJsonBody_failsClosed() {
        // given
        authServer.enqueue(new MockResponse().setBody("<html>502 Bad Gateway</html>"));

        // when // then
        StepVerifier.create(client.introspect(TOKEN))
                .expectErrorSatisfies(throwable -> assertThat(throwable)
                        .isInstanceOf(GatewayRejectedException.class)
                        .extracting(e -> ((GatewayRejectedException) e).getError())
                        .isEqualTo(GatewayError.AUTH_SERVICE_UNAVAILABLE))
                .verify();
    }

    @Test
    @DisplayName("isSuccessful=false 본문도 장애 취급한다")
    void introspect_unsuccessfulHeader_failsClosed() {
        // given
        authServer.enqueue(json("""
                {"header":{"isSuccessful":false,"resultCode":"INTERNAL_ERROR","resultMessage":"실패"},
                 "response":null}
                """));

        // when // then
        StepVerifier.create(client.introspect(TOKEN))
                .expectErrorSatisfies(throwable -> assertThat(throwable)
                        .isInstanceOf(GatewayRejectedException.class))
                .verify();
    }

    @Test
    @DisplayName("active=true 인데 sub 가 없는 계약 위반 응답도 장애 취급한다")
    void introspect_activeWithoutSub_failsClosed() {
        // given
        authServer.enqueue(json("""
                {"header":{"isSuccessful":true,"resultCode":"SUCCESS","resultMessage":"성공"},
                 "response":{"active":true,"sub":null}}
                """));

        // when // then
        StepVerifier.create(client.introspect(TOKEN))
                .expectErrorSatisfies(throwable -> assertThat(throwable)
                        .isInstanceOf(GatewayRejectedException.class))
                .verify();
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
