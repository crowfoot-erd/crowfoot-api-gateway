package net.java21.crowfoot.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 게이트웨이 공통 빈 — 만료 판정·캐시 TTL·rate limit 창 전부 이 Clock 기준으로 움직인다.
 * 테스트는 가짜 시계로 이 빈을 교체해 시간 경계를 제어한다 (testing.md §10.1).
 */
@Configuration
public class GatewayConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
