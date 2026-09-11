package net.java21.crowfoot.gateway.e2e;

import net.java21.crowfoot.gateway.testsupport.MockResponses;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 라우팅 E2E (03-gateway/testing.md AUTH-13) — RouteLocator 빈(auth/core) + StripPrefix=2 검증.
 * 하류는 MockWebServer 목으로 대체한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayRoutingTest {

    private static final MockWebServer authServer = start();
    private static final MockWebServer coreServer = start();

    private static MockWebServer start() {
        try {
            MockWebServer server = new MockWebServer();
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("MockWebServer start failed", e);
        }
    }

    @DynamicPropertySource
    static void downstreamUrls(DynamicPropertyRegistry registry) {
        registry.add("crowfoot.gateway.auth-base-url", () -> "http://localhost:" + authServer.getPort());
        registry.add("crowfoot.gateway.core-base-url", () -> "http://localhost:" + coreServer.getPort());
    }

    @AfterAll
    static void tearDown() throws IOException {
        authServer.shutdown();
        coreServer.shutdown();
    }

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void bindWebTestClient() {
        // Boot 4 는 WebTestClient 자동구성 빈을 제공하지 않는다 — 기동 서버에 직접 바인딩
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    @DisplayName("core 라우트는 /api/v1/core/** 를 /core/** 로 재작성해 하류로 전달한다")
    void coreRoute_stripsTwoSegments_downstreamSeesCorePath() throws InterruptedException {
        // given
        coreServer.enqueue(MockResponses.downstreamOk());

        // when // then
        webTestClient.get().uri("/api/v1/core/providers")   // 화이트리스트 경로 — 토큰 불필요
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = coreServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/core/providers");
    }

    @Test
    @DisplayName("auth 라우트는 /api/v1/auth/** 를 /auth/** 로 재작성해 하류로 전달한다")
    void authRoute_stripsTwoSegments_downstreamSeesAuthPath() throws InterruptedException {
        // given
        authServer.enqueue(MockResponses.downstreamOk());

        // when // then
        webTestClient.post().uri("/api/v1/auth/refresh-token")   // 화이트리스트 경로
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = authServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/auth/refresh-token");
    }

    @Test
    @DisplayName("미정의 경로는 SCG 기본 본문 대신 공통 실패 포맷으로 404 를 내린다")
    void undefinedPath_returnsContracted404() {
        // given // when // then
        webTestClient.get().uri("/api/v1/nope")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody()
                .jsonPath("$.header.isSuccessful").isEqualTo(false)
                .jsonPath("$.header.resultCode").isEqualTo("RESOURCE_NOT_FOUND")
                .jsonPath("$.header.resultMessage").isEqualTo("존재하지 않는 API 경로입니다")
                .jsonPath("$.timestamp").doesNotExist()   // SCG 기본 에러 본문 미노출
                .jsonPath("$.path").doesNotExist()
                .jsonPath("$.response").doesNotExist();
    }

    @Test
    @DisplayName("내부 경로 /internal/** 는 외부에서 라우트 자체가 없다 — 404")
    void internalPath_notRoutedFromOutside() {
        // given // when // then
        webTestClient.get().uri("/internal/core/providers")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.header.resultCode").isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("요청 ID 는 하류로 전파되고 응답에 에코된다")
    void correlationId_propagatedAndEchoed() throws InterruptedException {
        // given
        coreServer.enqueue(MockResponses.downstreamOk());

        // when // then
        webTestClient.get().uri("/api/v1/core/providers")
                .header("X-REQUEST-ID", "req-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-REQUEST-ID", "req-123");

        RecordedRequest recorded = coreServer.takeRequest();
        assertThat(recorded.getHeader("X-REQUEST-ID")).isEqualTo("req-123");
    }

    @Test
    @DisplayName("요청 ID 가 없으면 생성해 전파·에코한다")
    void correlationId_generatedWhenAbsent() throws InterruptedException {
        // given
        coreServer.enqueue(MockResponses.downstreamOk());

        // when // then
        String generated = webTestClient.get().uri("/api/v1/core/providers")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-REQUEST-ID")
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst("X-REQUEST-ID");
        assertThat(generated).isNotBlank();

        RecordedRequest recorded = coreServer.takeRequest();
        assertThat(recorded.getHeader("X-REQUEST-ID")).isEqualTo(generated);
    }

    @Test
    @DisplayName("CORS preflight 는 허용 Origin 에만 자격 증명 허용 헤더를 내린다")
    void corsPreflight_allowedOrigin_grantedWithCredentials() {
        // given // when // then
        webTestClient.options().uri("/api/v1/auth/refresh-token")
                .header("Origin", "https://crowfoot.java21.net")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://crowfoot.java21.net")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
    }

    @Test
    @DisplayName("허용되지 않은 Origin 의 preflight 는 CORS 헤더 없이 거부된다")
    void corsPreflight_disallowedOrigin_rejected() {
        // given // when // then
        webTestClient.options().uri("/api/v1/auth/refresh-token")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }
}
