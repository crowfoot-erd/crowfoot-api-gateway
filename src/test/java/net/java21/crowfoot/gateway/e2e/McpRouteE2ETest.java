package net.java21.crowfoot.gateway.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import net.java21.crowfoot.gateway.testsupport.MockResponses;
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

/**
 * MCP 라우트 E2E (03-gateway/requirements.md §2.3·3).
 * MCP 호스트에서는 /mcp/**만 받고 API 호스트의 /mcp는 받지 않는다. 워크스페이스 액세스 토큰은 MCP 경로에서만,
 * 웹 로그인 토큰은 그 밖에서만 통한다. 인증 서버·core·MCP 서버는 MockWebServer 목.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpRouteE2ETest {

    private static final String MCP_HOST = "mcp.crowfoot.test";
    private static final MockWebServer authServer = start();
    private static final MockWebServer coreServer = start();
    private static final MockWebServer mcpServer = start();

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
        registry.add("crowfoot.gateway.mcp-base-url", () -> "http://localhost:" + mcpServer.getPort());
        registry.add("crowfoot.gateway.mcp-host", () -> MCP_HOST);
    }

    @AfterAll
    static void tearDown() throws IOException {
        authServer.shutdown();
        coreServer.shutdown();
        mcpServer.shutdown();
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;
    /** 목 서버의 요청 수는 클래스 전체에서 쌓인다 — 테스트 시작 시점의 값과 견준다 */
    private int mcpRequests;
    private int coreRequests;

    @BeforeEach
    void setUp() throws InterruptedException {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        for (MockWebServer server : new MockWebServer[] {authServer, coreServer, mcpServer}) {
            server.setDispatcher(new QueueDispatcher());
            while (server.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
                // 이전 테스트가 남긴 녹화 요청을 버린다
            }
        }
        mcpRequests = mcpServer.getRequestCount();
        coreRequests = coreServer.getRequestCount();
    }

    private static MockResponse workspaceToken() {
        return MockResponses.json(200, """
                {"header":{"isSuccessful":true,"resultCode":"SUCCESS","resultMessage":"SUCCESS"},
                 "response":{"active":true,"sub":"42","typ":"WORKSPACE_TOKEN","workspaceId":"77","tokenId":"12"}}
                """);
    }

    @Test
    @DisplayName("워크스페이스 액세스 토큰으로 MCP 경로를 부르면 헤더 세 개를 넣어 경로를 그대로 넘긴다")
    void workspaceTokenReachesMcp() throws InterruptedException {
        authServer.enqueue(workspaceToken());
        mcpServer.enqueue(MockResponses.downstreamOk());

        client.post().uri("/mcp")
                .header(HttpHeaders.HOST, MCP_HOST)
                .header(HttpHeaders.AUTHORIZATION, "Bearer cfw_unique_token_reaches_mcp")
                // 외부에서 넣은 값은 지우고 덮어쓴다
                .header("X-USER-ID", "1")
                .header("X-TOKEN-WORKSPACE-ID", "999")
                .header("X-ACCESS-TOKEN-ID", "999")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest forwarded = mcpServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/mcp");
        assertThat(forwarded.getHeader("X-USER-ID")).isEqualTo("42");
        assertThat(forwarded.getHeader("X-TOKEN-WORKSPACE-ID")).isEqualTo("77");
        assertThat(forwarded.getHeader("X-ACCESS-TOKEN-ID")).isEqualTo("12");
    }

    @Test
    @DisplayName("MCP 경로는 토큰이 없으면 401 — 공개 경로가 없다")
    void mcpRequiresToken() {
        client.post().uri("/mcp").header(HttpHeaders.HOST, MCP_HOST).bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
        assertThat(mcpServer.getRequestCount()).isEqualTo(mcpRequests);
    }

    @Test
    @DisplayName("웹 로그인 토큰으로는 MCP 경로를 부를 수 없다 — 403")
    void accessTokenCannotCallMcp() {
        authServer.enqueue(MockResponses.activeIntrospection("42", "jti-web-on-mcp"));

        client.post().uri("/mcp")
                .header(HttpHeaders.HOST, MCP_HOST)
                .header(HttpHeaders.AUTHORIZATION, "Bearer web-token-on-mcp")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.header.resultCode").isEqualTo("PERMISSION_DENIED");
    }

    @Test
    @DisplayName("워크스페이스 액세스 토큰으로는 core API를 직접 부를 수 없다 — 403")
    void workspaceTokenCannotCallCore() {
        authServer.enqueue(workspaceToken());

        client.get().uri("/api/v1/core/workspaces/77")
                .header(HttpHeaders.AUTHORIZATION, "Bearer cfw_unique_token_on_core")
                .exchange()
                .expectStatus().isForbidden();
        assertThat(coreServer.getRequestCount()).isEqualTo(coreRequests);
    }

    @Test
    @DisplayName("MCP 호스트의 다른 경로는 404 — MCP 호스트가 API의 또 다른 입구가 되지 않는다")
    void mcpHostServesOnlyMcp() {
        client.get().uri("/api/v1/core/providers").header(HttpHeaders.HOST, MCP_HOST)
                .exchange()
                .expectStatus().isNotFound();
        assertThat(coreServer.getRequestCount()).isEqualTo(coreRequests);
    }

    @Test
    @DisplayName("API 호스트의 /mcp는 404")
    void apiHostDoesNotServeMcp() {
        authServer.enqueue(workspaceToken());

        client.post().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer cfw_unique_token_api_host")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isNotFound();
        assertThat(mcpServer.getRequestCount()).isEqualTo(mcpRequests);
    }
}
