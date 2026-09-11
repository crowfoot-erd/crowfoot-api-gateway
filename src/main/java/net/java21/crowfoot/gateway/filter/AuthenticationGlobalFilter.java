package net.java21.crowfoot.gateway.filter;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.gateway.auth.AuthWhitelist;
import net.java21.crowfoot.gateway.auth.IntrospectionClient;
import net.java21.crowfoot.gateway.auth.TokenValidationCache;
import net.java21.crowfoot.gateway.common.GatewayError;
import net.java21.crowfoot.gateway.common.GatewayRejectedException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Access 토큰 검증 게이트 (03-gateway/requirements.md §2) — 검증 자체는 전부 인증 서버 Introspection 에 위임.
 *
 * <p>Gateway 는 JWT 를 해석(파싱·클레임 판정)하지 않는다 — Bearer 스킴 확인과 토큰 전달만 담당한다
 * (2026-09-11 확정: 검증 책임의 명확한 분리). 만료/무효 구분도 인증 서버가 내려주는
 * {@code inactiveReason}(EXPIRED/REVOKED/INVALID)으로 판정한다.
 *
 * <p>순서: ① 화이트리스트 판정(외부 경로 기준) ② Bearer 스킴 확인 ③ 검증 캐시 조회(토큰 SHA-256 해시 키)
 * ④ Introspection 위임 — 활성이면 캐시 저장 후 sub 주입, 비활성이면 inactiveReason 에 따라 401,
 * 장애면 fail-closed 503.
 *
 * <p>신뢰 경계: 모든 요청(화이트리스트 포함)에서 외부 유입 {@code X-USER-ID}를 먼저 제거하고,
 * 검증 성공 시에만 introspection 결과의 sub 로 주입한다 (testing.md AUTH-16).
 * 헤더 조작은 전부 {@link HttpHeaders} API로만 — HTTP/2 소문자 정규화 대응 (api-design.md §5.6).
 */
@Component
@RequiredArgsConstructor
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-USER-ID";
    private static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String REASON_EXPIRED = "EXPIRED";

    private final AuthWhitelist whitelist;
    private final TokenValidationCache validationCache;
    private final IntrospectionClient introspectionClient;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpRequest.Builder mutated = request.mutate();
        mutated.headers(headers -> headers.remove(USER_ID_HEADER));   // 위조 방지 — 항상 제거 먼저

        if (whitelist.matches(request.getMethod(), request.getPath().value())) {
            return chain.filter(with(exchange, mutated));
        }

        String token = bearerToken(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            return reject(GatewayError.TOKEN_MISSING, "Bearer");
        }

        String cacheKey = tokenHash(token);
        Optional<String> cached = validationCache.subFor(cacheKey);
        if (cached.isPresent()) {   // 캐시 히트 — 인증 서버 장애와 무관하게 통과 (api.md §3.4)
            mutated.headers(headers -> headers.set(USER_ID_HEADER, cached.get()));
            return chain.filter(with(exchange, mutated));
        }

        return introspectionClient.introspect(token)
                .flatMap(result -> {
                    if (result.active()) {
                        validationCache.put(cacheKey, result.sub(), result.exp());
                        mutated.headers(headers -> headers.set(USER_ID_HEADER, result.sub()));
                        return chain.filter(with(exchange, mutated));
                    }
                    if (REASON_EXPIRED.equals(result.inactiveReason())) {
                        return reject(GatewayError.TOKEN_EXPIRED, INVALID_TOKEN_CHALLENGE);
                    }
                    return reject(GatewayError.TOKEN_INVALID, INVALID_TOKEN_CHALLENGE);   // 사유 미노출
                });
    }

    /** 검증 캐시 키 — 토큰 원문의 SHA-256 hex (Gateway 는 JWT 를 파싱하지 않으므로 jti 를 쓰지 않는다) */
    private String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {   // SHA-256 은 모든 JVM 에 상주
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Authorization 헤더에서 Bearer 토큰 추출 — 스킴 대소문자 무시, 비Bearer 는 null */
    private String bearerToken(String authorization) {
        if (authorization == null || authorization.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length());
    }

    private Mono<Void> reject(GatewayError error, String wwwAuthenticate) {
        return Mono.error(new GatewayRejectedException(error, wwwAuthenticate, null));
    }

    private ServerWebExchange with(ServerWebExchange exchange, ServerHttpRequest.Builder mutated) {
        return exchange.mutate().request(mutated.build()).build();
    }

    @Override
    public int getOrder() {
        return -100;   // RateLimit(-200) 뒤, CookiePolicy(-50) 앞
    }
}
