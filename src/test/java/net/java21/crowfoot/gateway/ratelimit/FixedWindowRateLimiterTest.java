package net.java21.crowfoot.gateway.ratelimit;

import net.java21.crowfoot.gateway.config.GatewayProperties;
import net.java21.crowfoot.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final FixedWindowRateLimiter rateLimiter = new FixedWindowRateLimiter(
            clock, new GatewayProperties(null, null, null,
            new GatewayProperties.RateLimit(3, 60, 30)));

    @Test
    @DisplayName("창당 한도까지 허용하고 초과분은 거부한다")
    void tryAcquire_beyondLimit_returnsFalse() {
        // given // when // then
        assertThat(rateLimiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(rateLimiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(rateLimiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(rateLimiter.tryAcquire("1.1.1.1")).isFalse();
    }

    @Test
    @DisplayName("창이 지나면 카운트가 리셋된다")
    void tryAcquire_nextWindow_resetsCount() {
        // given
        IntStream.range(0, 3).forEach(i -> rateLimiter.tryAcquire("1.1.1.1"));
        assertThat(rateLimiter.tryAcquire("1.1.1.1")).isFalse();

        // when
        clock.advanceBy(java.time.Duration.ofSeconds(61));

        // then
        assertThat(rateLimiter.tryAcquire("1.1.1.1")).isTrue();
    }

    @Test
    @DisplayName("IP 단위로 카운트가 격리된다")
    void tryAcquire_differentIp_isolated() {
        // given
        IntStream.range(0, 3).forEach(i -> rateLimiter.tryAcquire("1.1.1.1"));

        // when // then
        assertThat(rateLimiter.tryAcquire("1.1.1.1")).isFalse();
        assertThat(rateLimiter.tryAcquire("2.2.2.2")).isTrue();
    }

    @Test
    @DisplayName("동시 다발에도 정확히 한도만큼만 허용한다")
    void tryAcquire_concurrentAttempts_allowsExactlyLimit() {
        // given
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
                clock, new GatewayProperties(null, null, null,
                new GatewayProperties.RateLimit(50, 60, 30)));
        AtomicInteger allowed = new AtomicInteger();

        // when
        IntStream.range(0, 100).parallel().forEach(i -> {
            if (limiter.tryAcquire("1.1.1.1")) {
                allowed.incrementAndGet();
            }
        });

        // then
        assertThat(allowed.get()).isEqualTo(50);
    }

    @Test
    @DisplayName("Retry-After 값은 프로퍼티 설정값을 그대로 노출한다")
    void retryAfterSeconds_returnsConfiguredValue() {
        // given // when // then
        assertThat(rateLimiter.retryAfterSeconds()).isEqualTo(30);
    }
}
