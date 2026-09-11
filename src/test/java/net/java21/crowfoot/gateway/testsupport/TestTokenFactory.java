package net.java21.crowfoot.gateway.testsupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 테스트 전용 Access 토큰 공장 (testing.md §10.1).
 *
 * <p>테스트 전용 시크릿으로 HS256 서명을 만든다 — 운영 시크릿 재사용 금지.
 * Gateway 는 서명을 검증하지 않으므로(검증은 인증 서버 위임) 토큰 값은 계약 형식(3세그먼트)을
 * 갖추는 정도의 의미만 있다. 목 introspection 이 판정을 대신 내린다.
 */
public final class TestTokenFactory {

    /** 테스트 전용 시크릿 — 운영 재사용 금지 */
    private static final byte[] TEST_SECRET = "crowfoot-gateway-test-only-secret".getBytes(StandardCharsets.UTF_8);

    private TestTokenFactory() {
    }

    public static String access(String sub, String jti, long expEpochSeconds) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"" + sub + "\",\"jti\":\"" + jti
                + "\",\"exp\":" + expEpochSeconds + ",\"typ\":\"ACCESS\"}");
        return header + "." + payload + "." + sign(header + "." + payload);
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_SECRET, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("test token signing failed", e);
        }
    }
}
