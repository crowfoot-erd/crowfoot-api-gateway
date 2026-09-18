package net.java21.crowfoot.gateway.e2e;

import net.java21.crowfoot.gateway.testsupport.MockResponses;
import net.java21.crowfoot.gateway.testsupport.TestTokenFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토큰 검증 E2E (03-gateway/testing.md AUTH-15/16/17/20/22).
 *
 * <p>검증 책임은 전부 인증 서버 introspection 에 있다 — 만료/무효 구분도 목 응답의
 * inactiveReason 으로 검증한다 (2026-09-11 책임 분리 확정). 인증 서버는 MockWebServer 목.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TokenValidationE2ETest {

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
    void isolateMockServers() {
        // Boot 4 는 WebTestClient 자동구성 빈을 제공하지 않는다 — 기동 서버에 직접 바인딩
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        // MockWebServer 는 클래스 전체가 큐를 공유한다 — 이전 테스트가 남긴
        // 미소비 응답(캐시 히트 검증의 500 함정 등)과 미회수 녹화 요청을 버리고 시작한다
        authServer.setDispatcher(new QueueDispatcher());
        coreServer.setDispatcher(new QueueDispatcher());
        drain(authServer);
        drain(coreServer);
    }

    private static void drain(MockWebServer server) {
        try {
            while (server.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("화이트리스트 경로는 토큰 없이 통과한다")
    void whitelistPath_withoutToken_passes() {
        // given
        authServer.enqueue(MockResponses.downstreamOk());

        // when // then
        webTestClient.post().uri("/api/v1/auth/refresh-token")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("릴리스 노트 공개 조회는 토큰 없이 core로 라우팅된다 — 인증 서버 미호출")
    void releaseNotesWhitelistPath_withoutToken_routesToCore() throws Exception {
        // given
        coreServer.enqueue(MockResponses.downstreamOk());
        int authCallsBefore = authServer.getRequestCount();

        // when // then
        webTestClient.get().uri("/api/v1/core/community/release-notes/recent")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest forwarded = coreServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getMethod()).isEqualTo("GET");
        assertThat(forwarded.getPath()).isEqualTo("/core/community/release-notes/recent"); // StripPrefix=2
        assertThat(forwarded.getHeader("X-USER-ID")).isNull();                              // 무토큰 → 미주입
        assertThat(authServer.getRequestCount()).isEqualTo(authCallsBefore);               // introspection 미호출
    }

    @Test
    @DisplayName("보호 경로 무토큰은 401 AUTH_TOKEN_INVALID + WWW-Authenticate: Bearer — 인증 서버 미호출")
    void protectedPath_withoutToken_rejected401WithoutIntrospection() {
        // given
        int authCallsBefore = authServer.getRequestCount();

        // when // then
        webTestClient.get().uri("/api/v1/core/workspaces")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody()
                .jsonPath("$.header.resultCode").isEqualTo("AUTH_TOKEN_INVALID");

        assertThat(authServer.getRequestCount()).isEqualTo(authCallsBefore);   // 스킴 확인은 Gateway 몫
    }

    @Test
    @DisplayName("Authorization 이 Bearer 스킴이 아니면 401 — 인증 서버 미호출")
    void nonBearerScheme_rejected401() {
        // given
        int authCallsBefore = authServer.getRequestCount();

        // when // then
        webTestClient.get().uri("/api/v1/core/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.header.resultCode").isEqualTo("AUTH_TOKEN_INVALID");

        assertThat(authServer.getRequestCount()).isEqualTo(authCallsBefore);
    }

    @Test
    @DisplayName("인증 서버 판정 만료(inactiveReason=EXPIRED)는 401 AUTH_TOKEN_EXPIRED 로 구분된다")
    void expiredToken_byIntrospectionReason_rejected401Expired() {
        // given
        authServer.enqueue(MockResponses.inactiveIntrospection("EXPIRED"));
        String expiredToken = TestTokenFactory.access("1001", "jti-expired", 1000L);

        // when // then
        webTestClient.get().uri("/api/v1/core/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody()
                .jsonPath("$.header.resultCode").isEqualTo("AUTH_TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("폐기 토큰(inactiveReason=REVOKED)은 401 AUTH_TOKEN_INVALID — 사유는 미노출")
    void revokedToken_rejected401Invalid() {
        // given
        authServer.enqueue(MockResponses.inactiveIntrospection("REVOKED"));
        String revokedToken = TestTokenFactory.access("1001", "jti-revoked", 9999999999L);

        // when // then
        webTestClient.get().uri("/api/v1/core/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokedToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.header.resultCode").isEqualTo("AUTH_TOKEN_INVALID");
    }

    @Test
    @DisplayName("활성 토큰은 통과하고 하류에 sub 가 X-USER-ID 로 주입된다")
    void activeToken_passesWithUserIdInjection() throws InterruptedException {
        // given
        authServer.enqueue(MockResponses.activeIntrospection("1001", "jti-active"));
        coreServer.enqueue(MockResponses.downstreamOk());

        // when // then
        webTestClient.get().uri("/api/v1/core/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokenFactory.access("1001", "jti-active", 9999999999L))
                .exchange()
                .expectStatus().isOk();

        RecordedRequest coreRecorded = coreServer.takeRequest();
        assertThat(coreRecorded.getHeader("X-USER-ID")).isEqualTo("1001");
    }

    @Test
    @DisplayName("외부에서 위조한 X-USER-ID 는 제거되고 introspection sub 만 주입된다 (AUTH-16)")
    void forgedUserIdHeader_removedAndReplacedWithIntrospectedSub() throws InterruptedException {
        // given
        authServer.enqueue(MockResponses.activeIntrospection("1001", "jti-forged"));
        coreServer.enqueue(MockResponses.downstreamOk());

        // when // then
        webTestClient.get().uri("/api/v1/core/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokenFactory.access("1001", "jti-forged", 9999999999L))
                .header("X-USER-ID", "9999")   // 위조 시도
                .exchange()
                .expectStatus().isOk();

        RecordedRequest coreRecorded = coreServer.takeRequest();
        assertThat(coreRecorded.getHeader("X-USER-ID")).isEqualTo("1001");   // 위조값 9999 아님
    }

    @Test
    @DisplayName("검증 성공은 캐시된다 — 이후 인증 서버 장애와 무관하게 동일 토큰은 통과한다")
    void cachedToken_bypassesSubsequentIntrospectionEvenWhenAuthDown() {
        // given
        String token = TestTokenFactory.access("1001", "jti-cache", 9999999999L);
        authServer.enqueue(MockResponses.activeIntrospection("1001", "jti-cache"));
        coreServer.enqueue(MockResponses.downstreamOk());
        int authCallsBefore = authServer.getRequestCount();

        webTestClient.get().uri("/api/v1/core/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
        assertThat(authServer.getRequestCount()).isEqualTo(authCallsBefore + 1);

        // when — 인증 서버가 죽어도(500)
        authServer.enqueue(new MockResponse().setResponseCode(500));
        coreServer.enqueue(MockResponses.downstreamOk());

        // then — 캐시 히트로 통과, introspection 추가 호출 없음
        webTestClient.get().uri("/api/v1/core/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
        assertThat(authServer.getRequestCount()).isEqualTo(authCallsBefore + 1);   // 미호출
    }

    @Test
    @DisplayName("캐시 없는 토큰은 인증 서버 장애 시 fail-closed 503 SERVICE_UNAVAILABLE")
    void uncachedToken_authDown_failsClosed503() {
        // given — 새 토큰(캐시 미스 보장)
        authServer.enqueue(new MockResponse().setResponseCode(503));

        // when // then
        webTestClient.get().uri("/api/v1/core/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokenFactory.access("1001", "jti-down", 9999999999L))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.header.resultCode").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.header.resultMessage").isEqualTo("인증 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요");
    }

    @Test
    @DisplayName("core 라우트는 crowfoot_refresh 쿠키만 제거하고 다른 쿠키는 보존한다")
    void coreRoute_stripsRefreshCookieOnly() throws InterruptedException {
        // given
        authServer.enqueue(MockResponses.activeIntrospection("1001", "jti-cookie-core"));
        coreServer.enqueue(MockResponses.downstreamOk());

        // when // then
        webTestClient.get().uri("/api/v1/core/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokenFactory.access("1001", "jti-cookie-core", 9999999999L))
                .header(HttpHeaders.COOKIE, "crowfoot_refresh=secret; other=1")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest coreRecorded = coreServer.takeRequest();
        assertThat(coreRecorded.getHeader("Cookie")).isEqualTo("other=1");
    }

    @Test
    @DisplayName("auth 라우트는 crowfoot_refresh 쿠키를 유지한다")
    void authRoute_keepsRefreshCookie() throws InterruptedException {
        // given — refresh-token 은 화이트리스트라 토큰 불필요
        authServer.enqueue(MockResponses.downstreamOk());

        // when // then
        webTestClient.post().uri("/api/v1/auth/refresh-token")
                .header(HttpHeaders.COOKIE, "crowfoot_refresh=secret; other=1")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest authRecorded = authServer.takeRequest();
        assertThat(authRecorded.getHeader("Cookie")).contains("crowfoot_refresh=secret");
    }
}
