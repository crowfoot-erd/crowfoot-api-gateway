package net.java21.crowfoot.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.route.builder.BooleanSpec;
import org.springframework.cloud.gateway.route.builder.PredicateSpec;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 라우트 조립 (03-gateway/requirements.md §3) — RouteLocator 빈으로 정의한다.
 *
 * <p>기점(auth·core·DB 매니저)은 {@code crowfoot.gateway.*-base-url} 프로필 프로퍼티에서 주입받는다 —
 * 로컬은 지정 포트(localhost:8081·8082·8084), prod(쿠버네티스)는 클러스터 내 Service DNS 기점.
 * rewrite(StripPrefix=2) — {@code /api/v1/core/workspaces/77 -> /core/workspaces/77}.
 * DB 매니저도 같은 규칙이다 — {@code /api/v1/database-manager/** -> /database-manager/**}
 * (09-database-manager/00-data-browser.md Section 1.5). 공개 경로가 없어 화이트리스트 변경은 없다.
 * MCP 라우트는 호스트로 가른다 — MCP 호스트에서는 /mcp/**만 받고, API 호스트의 /mcp는 받지 않는다(§3).
 * catch-all 라우트는 두지 않는다: 미정의 경로는 라우트 미매칭 → 404 RESOURCE_NOT_FOUND(§3).
 */
@Configuration
public class RouteLocatorConfig {

    /**
     * 사이트 등록·다시 가져오기 응답 타임아웃 — core가 캡처 서비스를 기다린다(최악 약 35초, core 읽기 제한 40초).
     * 다른 core 경로의 10초로는 끊긴다(08-core/19-site-showcase.md Section 4)
     */
    static final long SITE_CAPTURE_TIMEOUT_MILLIS = 45_000;

    @Bean
    public RouteLocator crowfootRouteLocator(RouteLocatorBuilder builder, GatewayProperties properties) {
        String mcpHost = properties.mcpHost();
        return builder.routes()
                .route("auth", spec -> apiPath(spec, "/api/v1/auth/**", mcpHost)
                        .filters(filters -> filters.stripPrefix(2))
                        .uri(properties.authBaseUrl()))
                // 문서의 사이트 — core 라우트보다 먼저 둔다(같은 기점, 타임아웃만 길다)
                .route("core-site", spec -> apiPath(spec, "/api/v1/core/workspaces/*/models/*/site",
                                "/api/v1/core/workspaces/*/models/*/site/capture", mcpHost)
                        .filters(filters -> filters.stripPrefix(2))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, SITE_CAPTURE_TIMEOUT_MILLIS)
                        .uri(properties.coreBaseUrl()))
                .route("core", spec -> apiPath(spec, "/api/v1/core/**", mcpHost)
                        .filters(filters -> filters.stripPrefix(2))
                        .uri(properties.coreBaseUrl()))
                .route("database-manager", spec -> apiPath(spec, "/api/v1/database-manager/**", mcpHost)
                        .filters(filters -> filters.stripPrefix(2))
                        .uri(properties.databaseManagerBaseUrl()))
                // MCP — 접두를 떼지 않는다(/mcp 그대로). 응답이 스트리밍일 수 있어 타임아웃을 따로 둔다
                .route("mcp", spec -> mcpPath(spec, mcpHost)
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, properties.mcpResponseTimeout().toMillis())
                        .uri(properties.mcpBaseUrl()))
                .build();
    }

    /** API 라우트 — MCP 호스트로 들어온 요청은 받지 않는다. MCP 호스트가 API 전체의 또 다른 입구가 되지 않게 한다 */
    private static BooleanSpec apiPath(PredicateSpec spec, String pattern, String mcpHost) {
        return apiPath(spec, pattern, null, mcpHost);
    }

    private static BooleanSpec apiPath(PredicateSpec spec, String pattern, String second, String mcpHost) {
        BooleanSpec path = second == null ? spec.path(pattern) : spec.path(pattern, second);
        return mcpHost == null ? path : path.and().not(other -> other.host(mcpHost));
    }

    /** MCP 라우트 — MCP 호스트의 /mcp와 /mcp/**만. 호스트가 설정되지 않았으면(local) 경로만 본다 */
    private static BooleanSpec mcpPath(PredicateSpec spec, String mcpHost) {
        BooleanSpec path = spec.path("/mcp", "/mcp/**");
        return mcpHost == null ? path : path.and().host(mcpHost);
    }
}
