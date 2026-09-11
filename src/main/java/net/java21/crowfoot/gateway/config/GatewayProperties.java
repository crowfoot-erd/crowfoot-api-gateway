package net.java21.crowfoot.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * crowfoot.gateway.* 게이트웨이 정책 설정 (tech-stack.md §1.7 — 정책 값은 프로필 yml의 crowfoot.* 키).
 *
 * @param authBaseUrl        인증 서버 내부 기점 (POST {auth-base-url}/internal/auth/introspect) —
 *                           로컬은 지정 포트, prod(쿠버네티스)는 Service DNS
 * @param coreBaseUrl        core 서버 기점
 * @param validationCacheTtl 검증 캐시 TTL — 기본 30초, 상한 60초 (03-gateway/requirements.md §2)
 * @param rateLimit          IP 단위 고정창 rate limit (§4 — 수치는 프로퍼티로 관리)
 */
@ConfigurationProperties(prefix = "crowfoot.gateway")
public record GatewayProperties(
        String authBaseUrl,
        String coreBaseUrl,
        Duration validationCacheTtl,
        RateLimit rateLimit
) {

    private static final Duration TTL_CEILING = Duration.ofSeconds(60);

    public GatewayProperties {
        if (validationCacheTtl != null && validationCacheTtl.compareTo(TTL_CEILING) > 0) {
            validationCacheTtl = TTL_CEILING;
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
