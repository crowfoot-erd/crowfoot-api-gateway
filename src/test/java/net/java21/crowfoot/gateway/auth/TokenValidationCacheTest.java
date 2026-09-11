package net.java21.crowfoot.gateway.auth;

import net.java21.crowfoot.gateway.config.GatewayProperties;
import net.java21.crowfoot.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TokenValidationCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(NOW);
    private final TokenValidationCache cache = new TokenValidationCache(
            clock, new GatewayProperties(null, null, TTL, null));

    @Test
    @DisplayName("저장된 검증 결과는 TTL 내에 조회된다")
    void subFor_withinTtl_returnsSub() {
        // given
        cache.put("key-1", "1001", null);

        // when
        Optional<String> sub = cache.subFor("key-1");

        // then
        assertThat(sub).contains("1001");
    }

    @Test
    @DisplayName("TTL 경과 후에는 조회되지 않는다")
    void subFor_afterTtl_returnsEmpty() {
        // given
        cache.put("key-1", "1001", null);

        // when
        clock.advanceBy(TTL.plusSeconds(1));
        Optional<String> sub = cache.subFor("key-1");

        // then
        assertThat(sub).isEmpty();
    }

    @Test
    @DisplayName("만료 시각은 min(now+TTL, exp) — exp가 가까우면 exp에 만료된다")
    void subFor_expBeforeTtl_expiresAtExp() {
        // given
        long expEpochSeconds = NOW.plusSeconds(10).getEpochSecond();   // TTL(30s)보다 10s 후
        cache.put("key-1", "1001", expEpochSeconds);
        clock.advanceBy(Duration.ofSeconds(5));
        assertThat(cache.subFor("key-1")).isPresent();                  // exp 이전에는 유효

        // when
        clock.advanceBy(Duration.ofSeconds(6));                          // exp(10s) 경과
        Optional<String> sub = cache.subFor("key-1");

        // then
        assertThat(sub).isEmpty();
    }

    @Test
    @DisplayName("키마다 독립적으로 만료된다")
    void subFor_otherKey_isUnaffected() {
        // given
        cache.put("key-1", "1001", NOW.plusSeconds(10).getEpochSecond());
        cache.put("key-2", "1002", null);

        // when
        clock.advanceBy(Duration.ofSeconds(11));                         // key-1만 만료

        // then
        assertThat(cache.subFor("key-1")).isEmpty();
        assertThat(cache.subFor("key-2")).contains("1002");
    }
}
