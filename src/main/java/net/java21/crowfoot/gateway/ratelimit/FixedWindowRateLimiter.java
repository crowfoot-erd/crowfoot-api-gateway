package net.java21.crowfoot.gateway.ratelimit;

import net.java21.crowfoot.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 단위 고정창(fixed window) 카운터 — Phase 1 확정안 (03-gateway/requirements.md §4).
 *
 * <p>창 인덱스(epoch / windowSeconds)가 바뀌면 카운트를 리셋한다.
 * {@link ConcurrentHashMap#compute}로 증가가 원자적이라 동시 다발 요청에서도 정확하다.
 * 인스턴스 로컬 상태 — 수평 확장 시 이관 재검토. 수치는 {@code crowfoot.gateway.rate-limit.*} 프로퍼티.
 */
@Component
public class FixedWindowRateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;   // 만료 엔트리 지연 청소 임계

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final GatewayProperties.RateLimit config;
    private final Clock clock;

    public FixedWindowRateLimiter(Clock clock, GatewayProperties properties) {
        this.clock = clock;
        this.config = properties.rateLimit();
    }

    /** @return 이번 창에서 아직 허용 남았으면 true */
    public boolean tryAcquire(String clientIp) {
        long windowMillis = config.windowSeconds() * 1000L;
        long windowIndex = clock.millis() / windowMillis;

        Window window = windows.compute(clientIp, (ip, current) ->
                current == null || current.index() < windowIndex
                        ? new Window(windowIndex, 1)
                        : new Window(windowIndex, current.count() + 1));

        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(entry -> entry.getValue().index() < windowIndex);
        }
        return window.count() <= config.requestsPerWindow();
    }

    /** 429 응답의 Retry-After 값(초) — api.md §3.6 계약 값 */
    public long retryAfterSeconds() {
        return config.retryAfterSeconds();
    }

    private record Window(long index, int count) {
    }
}
