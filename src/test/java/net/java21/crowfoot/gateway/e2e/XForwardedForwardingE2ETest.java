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
 * X-Forwarded-* 하류 전달 E2E (SCG 5.0 신뢰 프록시 모델) — 인그레스가 남긴 클라이언트 IP 가
 * 표준형 체인(클라이언트IP, 게이트웨이 peer)으로 core 에 도달하는지 실기동 라우팅으로 검증한다.
 * test 프로필의 trusted-proxies=127\.0\.0\.1 과 WebTestClient(127.0.0.1 바인딩)가 신뢰 소스를 구성한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class XForwardedForwardingE2ETest {

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
        registry.add("crowfoot.gateway.core-base-url", () -> "http://localhost:" + coreServer.getPort());
    }

    @AfterAll
    static void tearDown() throws IOException {
        coreServer.shutdown();
    }

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void bindWebTestClient() {
        // 127.0.0.1 리터럴로 바인딩 — localhost 해석이 ::1 로 넘어가 신뢰 판정이 갈리지 않게 한다
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    @DisplayName("인그레스가 남긴 클라이언트 IP 가 표준형 체인으로 하류에 전달된다")
    void trustedSource_clientIpForwardedInStandardChain() throws InterruptedException {
        // given
        coreServer.enqueue(MockResponses.downstreamOk());

        // when
        webTestClient.get().uri("/api/v1/core/providers")   // 화이트리스트 경로 — 토큰 불필요
                .header("X-Forwarded-For", "203.0.113.9")   // 인그레스(신뢰 소스 127.0.0.1)가 넣은 클라이언트 IP
                .exchange()
                .expectStatus().isOk();

        // then
        RecordedRequest recorded = coreServer.takeRequest();
        assertThat(recorded.getHeader("X-Forwarded-For")).isEqualTo("203.0.113.9, 127.0.0.1");
    }

    @Test
    @DisplayName("XFF 없이 오면 하류 XFF 는 게이트웨이 peer(신뢰 소스) 단독이다")
    void noIncomingXff_peerOnlyChain() throws InterruptedException {
        // given
        coreServer.enqueue(MockResponses.downstreamOk());

        // when
        webTestClient.get().uri("/api/v1/core/providers")
                .exchange()
                .expectStatus().isOk();

        // then
        RecordedRequest recorded = coreServer.takeRequest();
        assertThat(recorded.getHeader("X-Forwarded-For")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("X-Forwarded-Proto·Host·Port·Prefix 도 표준 세트로 함께 생성된다")
    void standardForwardedSetGenerated() throws InterruptedException {
        // given
        coreServer.enqueue(MockResponses.downstreamOk());

        // when
        webTestClient.get().uri("/api/v1/core/providers")
                .header("X-Forwarded-Proto", "https")
                .exchange()
                .expectStatus().isOk();

        // then
        RecordedRequest recorded = coreServer.takeRequest();
        assertThat(recorded.getHeader("X-Forwarded-Proto")).isEqualTo("https,http");   // 원본 + 게이트웨이 관측(SCG 조인 형식)
        assertThat(recorded.getHeader("X-Forwarded-Host")).isEqualTo("127.0.0.1:" + port);
        assertThat(recorded.getHeader("X-Forwarded-Port")).isEqualTo(String.valueOf(port));
        assertThat(recorded.getHeader("X-Forwarded-Prefix")).isEqualTo("/api/v1");
    }
}
