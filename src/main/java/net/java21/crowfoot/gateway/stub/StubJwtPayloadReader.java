package net.java21.crowfoot.gateway.stub;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;

/**
 * JWT payload(두 번째 세그먼트) 읽기 — **auth-stub 전용**.
 *
 * <p>Gateway 검증 파이프라인은 JWT 를 해석하지 않는다(검증 전부 인증 서버 Introspection 위임 —
 * 2026-09-11 책임 분리 확정). payload 를 읽는 것은 introspection 응답을 만드는 auth 서버의 역할이며,
 * 이 리더는 그 역할을 흉내 내는 스텁이 쓴다. 스텁 빈과 같은 프로필로만 로드된다.
 */
@Component
public class StubJwtPayloadReader {

    private final ObjectMapper objectMapper;

    public StubJwtPayloadReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @return jti·sub·exp 클레임. 어느 하나라도 없으면 예외 — 정상 발급 토큰은 전부 갖는다(02-auth §1.4.2).
     * @throws IllegalArgumentException 세그먼트 수 불일치·깨진 base64·JSON 아님·클레임 결손
     */
    public JwtClaims read(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("not a three-segment JWT");
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode json = objectMapper.readTree(payload);

            String jti = text(json, "jti");
            String sub = text(json, "sub");
            Long exp = json.hasNonNull("exp") ? json.get("exp").asLong() : null;
            if (jti == null || exp == null) {
                throw new IllegalArgumentException("jti/exp claim missing");
            }
            return new JwtClaims(jti, sub, exp);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {   // base64·JSON 디코딩 실패 전부 정규화
            throw new IllegalArgumentException("unreadable JWT payload", e);
        }
    }

    private String text(JsonNode json, String field) {
        return json.hasNonNull(field) ? json.get(field).asText() : null;
    }

    /**
     * @param jti 토큰 고유 식별자
     * @param sub 사용자 식별자 — X-USER-ID 주입값의 원천이 되는 클레임
     * @param exp 만료 시각(epoch seconds)
     */
    public record JwtClaims(String jti, String sub, Long exp) {
    }
}
