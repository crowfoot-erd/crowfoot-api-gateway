package net.java21.crowfoot.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 라우트 조립 (03-gateway/requirements.md §3) — RouteLocator 빈으로 정의한다.
 *
 * <p>기점(auth·core)은 {@code crowfoot.gateway.*-base-url} 프로필 프로퍼티에서 주입받는다 —
 * 로컬은 지정 포트(localhost:8081·8082), prod(쿠버네티스)는 클러스터 내 Service DNS 기점.
 * rewrite(StripPrefix=2) — {@code /api/v1/core/workspaces/77 -> /core/workspaces/77}.
 * catch-all 라우트는 두지 않는다: 미정의 경로는 라우트 미매칭 → 404 RESOURCE_NOT_FOUND(§3).
 */
@Configuration
public class RouteLocatorConfig {

    @Bean
    public RouteLocator crowfootRouteLocator(RouteLocatorBuilder builder, GatewayProperties properties) {
        return builder.routes()
                .route("auth", spec -> spec.path("/api/v1/auth/**")
                        .filters(filters -> filters.stripPrefix(2))
                        .uri(properties.authBaseUrl()))
                .route("core", spec -> spec.path("/api/v1/core/**")
                        .filters(filters -> filters.stripPrefix(2))
                        .uri(properties.coreBaseUrl()))
                .build();
    }
}
