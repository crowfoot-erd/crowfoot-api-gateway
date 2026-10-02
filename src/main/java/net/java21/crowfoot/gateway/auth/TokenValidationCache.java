package net.java21.crowfoot.gateway.auth;

import net.java21.crowfoot.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access 토큰 검증 결과 캐시 — 인메모리(프로세스 내), 키는 토큰 원문의 SHA-256 해시 (03-gateway/requirements.md §2).
 *
 * <p>Gateway 는 JWT 를 해석하지 않아 jti 를 알 수 없으므로 해시 키를 쓴다(2026-09-11 확정).
 * 만료 시각은 min(now + TTL, introspection 응답의 exp) — 폐기 반영 지연의 상한이
 * TTL(02-auth/requirements.md §1.4.4)이고 캐시가 토큰 수명보다 오래 사는 것도 막는다.
 * positive(active=true) 결과만 저장한다. 인스턴스 로컬 상태 — 수평 확장 시 Redis 이관 재검토.
 */
@Component
public class TokenValidationCache {

    private final ConcurrentHashMap<String, CachedValidation> cache = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public TokenValidationCache(Clock clock, GatewayProperties properties) {
        this.clock = clock;
        this.ttl = properties.validationCacheTtl();
    }

    /** @return 캐시에 유효한(active=true) 검증 결과가 있으면 그 결과 */
    public Optional<Validated> find(String key) {
        CachedValidation cached = cache.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.expiresAtMillis() <= clock.millis()) {   // 만료분 지연 제거
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(cached.validated());
    }

    /** exp가 없는 토큰(무기한 워크스페이스 액세스 토큰)은 now + TTL까지만 캐시한다 */
    public void put(String key, Validated validated, Long expEpochSeconds) {
        long expiresAt = clock.millis() + ttl.toMillis();
        if (expEpochSeconds != null) {
            expiresAt = Math.min(expiresAt, expEpochSeconds * 1000);
        }
        cache.put(key, new CachedValidation(validated, expiresAt));
    }

    /**
     * 검증된 토큰에서 헤더로 옮길 값 (03-gateway/requirements.md §2.1·2.3).
     *
     * @param sub         사용자 — X-USER-ID
     * @param workspaceId 워크스페이스 액세스 토큰이 묶인 워크스페이스 — 웹 로그인 토큰이면 null
     * @param tokenId     워크스페이스 액세스 토큰 ID — 웹 로그인 토큰이면 null
     */
    public record Validated(String sub, String workspaceId, String tokenId) {

        public boolean workspaceToken() {
            return workspaceId != null;
        }
    }

    private record CachedValidation(Validated validated, long expiresAtMillis) {
    }
}
