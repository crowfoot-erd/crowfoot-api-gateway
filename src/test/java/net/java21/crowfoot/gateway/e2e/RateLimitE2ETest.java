package net.java21.crowfoot.gateway.e2e;

import net.java21.crowfoot.gateway.testsupport.MockResponses;
import okhttp3.mockwebserver.MockWebServer;
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
 * IP 고정창 rate limit E2E — 프로퍼티로 창을 좁혀 429 계약(RateLimit + Retry-After)을 검증한다 (03-gateway/api.md §3.6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "crowfoot.gateway.rate-limit.requests-per-window=3",
                "crowfoot.gateway.rate-limit.window-seconds=2",
                "crowfoot.gateway.rate-limit.retry-after-seconds=5"
        })
@ActiveProfiles("test")
class RateLimitE2ETest {

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
        // Boot 4 는 WebTestClient 자동구성 빈을 제공하지 않는다 — 기동 서버에 직접 바인딩
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    @DisplayName("창당 한도 초과 요청은 429 RATE_LIMITED + Retry-After — 하류 미도달")
    void requestsBeyondLimit_rejected429WithRetryAfter() {
        // given — 화이트리스트 경로(토큰 불필요), 하류 목은 허용분 3개만
        coreServer.enqueue(MockResponses.downstreamOk());
        coreServer.enqueue(MockResponses.downstreamOk());
        coreServer.enqueue(MockResponses.downstreamOk());

        // when // then
        for (int i = 0; i < 3; i++) {
            webTestClient.get().uri("/api/v1/core/providers")
                    .exchange()
                    .expectStatus().isOk();
        }
        webTestClient.get().uri("/api/v1/core/providers")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "5")
                .expectBody()
                .jsonPath("$.header.resultCode").isEqualTo("RATE_LIMITED");

        assertThat(coreServer.getRequestCount()).isEqualTo(3);   // 거부분은 하류 미도달
    }
}
