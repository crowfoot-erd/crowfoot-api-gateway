package net.java21.crowfoot.gateway.stub;

import net.java21.crowfoot.gateway.testsupport.TestTokenFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubJwtPayloadReaderTest {

    private final StubJwtPayloadReader reader = new StubJwtPayloadReader(JsonMapper.builder().build());

    @Test
    @DisplayName("정상 토큰에서 jti·sub·exp 클레임을 읽는다")
    void read_validToken_returnsClaims() {
        // given
        String token = TestTokenFactory.access("1001", "jti-1", 9999999999L);

        // when
        StubJwtPayloadReader.JwtClaims claims = reader.read(token);

        // then
        assertThat(claims.jti()).isEqualTo("jti-1");
        assertThat(claims.sub()).isEqualTo("1001");
        assertThat(claims.exp()).isEqualTo(9999999999L);
    }

    @Test
    @DisplayName("세그먼트 수가 3 이 아니면 거부한다")
    void read_notThreeSegments_throws() {
        // given // when // then
        assertThatThrownBy(() -> reader.read("two-segments-only"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("깨진 base64·JSON payload 는 거부한다")
    void read_brokenPayload_throws() {
        // given // when // then
        assertThatThrownBy(() -> reader.read("aaa.!!!not-base64!!!.bbb"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("jti/exp 클레임이 없으면 거부한다 — 스텁은 무효 판정(active=false)로 이어진다")
    void read_missingClaims_throws() {
        // given — exp 없는 payload 를 수동 조립
        String header = "eyJhbGciOiJIUzI1NiJ9";
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"1001\"}".getBytes());
        String token = header + "." + payload + ".sig";

        // when // then
        assertThatThrownBy(() -> reader.read(token))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
