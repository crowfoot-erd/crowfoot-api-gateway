package net.java21.crowfoot.gateway.stub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * 간이 Introspection 스텁 — auth 서버(8081) 미구현 시 로컬 기동 검증용 (2026-09-11 확정).
 *
 * <p>같은 게이트웨이 프로세스에 {@code POST /stub/internal/auth/introspect} 를 노출하고,
 * auth-stub 프로필 yml이 {@code auth-base-url: http://localhost:8080/stub} 로 자기 자신을 향하게 한다.
 * 기동: {@code mvn spring-boot:run -Dspring-boot.run.profiles=local,auth-stub}
 *
 * <p>판정(인증 서버의 몫을 흉내): payload 파싱 실패 → INVALID, jti=="stub-revoked" → REVOKED,
 * exp 경과(스큐 60초) → EXPIRED, 그 외 active=true + payload 의 sub/jti/exp 응답.
 * 비활성 사유(inactiveReason)는 02-auth/api.md §4.1 확장 계약 그대로 내려준다.
 *
 * <p>운영 원천 차단 — @Profile 은 배열이 아니라 단일 표현식 AND 여야 한다
 * ({@code {"auth-stub", "!prod"}} 로 쓰면 OR 결합이라 prod 가 아니면 항상 활성화되는 버그).
 */
@Slf4j
@Configuration
@Profile("auth-stub & !prod")
public class AuthStubConfiguration {

    static final String REVOKED_JTI = "stub-revoked";
    private static final long CLOCK_SKEW_SECONDS = 60;

    @Bean
    public RouterFunction<ServerResponse> authIntrospectStub(StubJwtPayloadReader reader, Clock clock) {
        return RouterFunctions.route()
                .POST("/stub/internal/auth/introspect", request -> request.formData().flatMap(form -> {
                    Map<String, Object> body = responseBody(reader, clock, form.getFirst("token"));
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
                }))
                .build();
    }

    private Map<String, Object> responseBody(StubJwtPayloadReader reader, Clock clock, String token) {
        StubJwtPayloadReader.JwtClaims claims = null;
        if (token != null) {
            try {
                claims = reader.read(token);
            } catch (IllegalArgumentException e) {
                log.debug("stub: unreadable token");
            }
        }

        String inactiveReason = inactiveReason(reader, clock, claims);
        Map<String, Object> body = new HashMap<>();
        body.put("header", Map.of(
                "isSuccessful", true, "resultCode", "SUCCESS", "resultMessage", "SUCCESS"));
        if (inactiveReason == null) {
            body.put("response", Map.of(
                    "active", true,
                    "sub", claims.sub(),
                    "jti", claims.jti(),
                    "typ", "ACCESS",
                    "exp", claims.exp()));
        } else {
            body.put("response", Map.of("active", false, "inactiveReason", inactiveReason));
        }
        return body;
    }

    /** @return null 이면 활성, 아니면 비활성 사유(INVALID/REVOKED/EXPIRED) */
    private String inactiveReason(StubJwtPayloadReader reader, Clock clock, StubJwtPayloadReader.JwtClaims claims) {
        if (claims == null || claims.sub() == null) {
            return "INVALID";
        }
        if (REVOKED_JTI.equals(claims.jti())) {
            return "REVOKED";
        }
        if (claims.exp() + CLOCK_SKEW_SECONDS < clock.instant().getEpochSecond()) {
            return "EXPIRED";
        }
        return null;
    }
}
