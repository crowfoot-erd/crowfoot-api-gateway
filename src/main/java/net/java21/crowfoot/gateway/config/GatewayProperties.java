package net.java21.crowfoot.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * crowfoot.gateway.* 게이트웨이 정책 설정 (tech-stack.md §1.7 — 정책 값은 프로필 yml의 crowfoot.* 키).
 *
 * @param authBaseUrl        인증 서버 내부 기점 (POST {auth-base-url}/internal/auth/introspect) —
 *                           로컬은 지정 포트, prod(쿠버네티스)는 Service DNS
 * @param coreBaseUrl        core 서버 기점
 * @param databaseManagerBaseUrl DB 매니저(crowfoot-database-manager) 기점 — 데이터 브라우저
 *                           (09-database-manager/00-data-browser.md Section 1.5)
 * @param mcpBaseUrl         MCP 서버(crowfoot-mcp) 기점 (10-mcp/00-mcp-server.md Section 2)
 * @param mcpHost            MCP 호스트 이름(crowfoot-mcp.java21.net) — 이 호스트에서는 /mcp/**만 받고, 다른 호스트의 /mcp는 받지 않는다.
 *                           비어 있으면 호스트를 보지 않고 경로만으로 가른다(local — 호스트가 하나뿐이다) (03-gateway/requirements.md §3)
 * @param mcpResponseTimeout MCP 라우트의 응답 타임아웃 — 응답이 스트리밍(SSE)일 수 있어 다른 라우트(10초)와 따로 길게 둔다. 기본 300초
 * @param validationCacheTtl 검증 캐시 TTL — 기본 30초, 상한 60초 (03-gateway/requirements.md §2)
 * @param rateLimit          IP 단위 고정창 rate limit (§4 — 수치는 프로퍼티로 관리)
 */
@ConfigurationProperties(prefix = "crowfoot.gateway")
public record GatewayProperties(
        String authBaseUrl,
        String coreBaseUrl,
        String databaseManagerBaseUrl,
        String mcpBaseUrl,
        String mcpHost,
        Duration mcpResponseTimeout,
        Duration validationCacheTtl,
        RateLimit rateLimit
) {

    private static final Duration TTL_CEILING = Duration.ofSeconds(60);

    public GatewayProperties {
        if (validationCacheTtl != null && validationCacheTtl.compareTo(TTL_CEILING) > 0) {
            validationCacheTtl = TTL_CEILING;
        }
        if (mcpResponseTimeout == null) {
            mcpResponseTimeout = Duration.ofSeconds(300);
        }
        if (mcpHost != null && mcpHost.isBlank()) {
            mcpHost = null;
        }
    }

    /**
     * @param requestsPerWindow 창당 허용 요청 수
     * @param windowSeconds     창 길이(초)
     * @param retryAfterSeconds 429 응답의 Retry-After 값(초) — 03-gateway/api.md §3.6
     */
    public record RateLimit(int requestsPerWindow, int windowSeconds, int retryAfterSeconds) {
    }
}
