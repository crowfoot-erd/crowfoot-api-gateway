package net.java21.crowfoot.gateway.common;

import lombok.Getter;

/**
 * 필터 파이프라인이 요청을 거절할 때 Mono.error 로 던지는 신호 예외.
 * {@code GatewayErrorWebExceptionHandler}가 상태코드·본문·부가 헤더로 렌더링한다.
 */
@Getter
public class GatewayRejectedException extends RuntimeException {

    private final GatewayError error;

    /** 무토큰 401 = {@code Bearer}, 만료·무효 401 = {@code Bearer error="invalid_token"} (RFC 6750) */
    private final String wwwAuthenticate;

    /** 429 의 Retry-After 값(초) — 그 외엔 null */
    private final Long retryAfterSeconds;

    public GatewayRejectedException(GatewayError error) {
        this(error, null, null);
    }

    public GatewayRejectedException(GatewayError error, String wwwAuthenticate, Long retryAfterSeconds) {
        super(error.resultCode() + ": " + error.resultMessage());
        this.error = error;
        this.wwwAuthenticate = wwwAuthenticate;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
