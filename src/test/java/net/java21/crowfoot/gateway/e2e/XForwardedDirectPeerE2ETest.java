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
 * 소스 보존 직접 수신 토폴로지 E2E (운영 실측 2026-09-26) — 전단(호스트 Nginx → NodePort)이
 * 클라이언트 소스 IP 를 보존해 전달하는 체계에서는 게이트웨이의 직접 peer 가 실제 클라이언트다.
 * trusted-proxies 를 loopback 과 불일치하게 덮어써 비신뢰 직접 수신을 재현하고,
 * 위조 XFF 폐기(SCG) + 소켓 주소 materialize(자체 필터)를 실기동 라우팅으로 검증한다.
 * 하류(auth 세션 IP)는 이 XFF 첫 홉을 클라이언트 IP 로 읽는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class XForwardedDirectPeerE2ETest {

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
        registry.add("spring.cloud.gateway.server.webflux.trusted-proxies", () -> "10\\..*");
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
        // 127.0.0.1 리터럴 바인딩 — peer 를 127.0.0.1 로 고정해 trusted-proxies(10.*) 와 불일치시킨다
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    @DisplayName("직접 수신 클라이언트의 위조 XFF 는 폐기되고 소켓 주소가 XFF 로 내려간다")
    void directPeer_spoofedXffDropped_socketAddressForwarded() throws InterruptedException {
        // given
        coreServer.enqueue(MockResponses.downstreamOk());

        // when
        webTestClient.get().uri("/api/v1/core/providers")   // 화이트리스트 경로 — 토큰 불필요
                .header("X-Forwarded-For", "1.2.3.4, 203.0.113.9")   // 비신뢰 클라이언트가 심은 위조값
                .exchange()
                .expectStatus().isOk();

        // then
        RecordedRequest recorded = coreServer.takeRequest();
        assertThat(recorded.getHeader("X-Forwarded-For")).isEqualTo("127.0.0.1");   // TCP peer — 위조 불가
    }

    @Test
    @DisplayName("XFF 없이 온 직접 수신 요청도 소켓 주소가 XFF 로 내려간다")
    void directPeerWithoutXff_socketAddressForwarded() throws InterruptedException {
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
}
