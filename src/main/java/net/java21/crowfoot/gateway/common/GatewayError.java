package net.java21.crowfoot.gateway.common;

import org.springframework.http.HttpStatus;

/**
 * Gateway 자체 에러 매핑 (03-gateway/api.md §2 시나리오 표 그대로).
 *
 * <p>resultCode·resultMessage 는 기존 에러 코드 체계(api-design.md §4·§5.4)와 각 문서의 계약 문구를
 * 재사용한다 — Gateway 가 새 에러 코드를 발명하지 않는다는 설계 원칙(api.md §1).
 * 상수명(TOKEN_MISSING 등)은 Gateway 내부 표기일 뿐 응답에 노출되지 않는다.
 * 401 두 가지(TOKEN_MISSING/TOKEN_INVALID)는 resultCode 가 같지만 WWW-Authenticate 챌린지 값이 다르다(RFC 6750).
 */
public enum GatewayError {

    /** 무토큰·비Bearer 스킴 — api.md §3.1 */
    TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "인증 토큰이 필요합니다"),

    /** 변조·typ 불일치·폐기·payload 파싱 실패 등 — api.md §3.3 (사유는 미노출) */
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "유효하지 않은 인증 토큰입니다"),

    /** 로컬 exp 사전 판정으로 만료 확정 — api.md §3.2 (인증 서버 무호출) */
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_EXPIRED", "Access Token이 만료되었습니다"),

    /** 인증 서버 응답 불가 — fail-closed — api.md §3.4 */
    AUTH_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
            "인증 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요"),

    /** 다운스트림(core 등) 연결 실패·타임아웃 — 일반 503 (인증 서버 아님) */
    DOWNSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
            "서비스를 일시적으로 사용할 수 없습니다"),

    /** 미정의 경로 — api.md §3.5 */
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "존재하지 않는 API 경로입니다"),

    /** Rate limit 초과 — api.md §3.6 (+ Retry-After 헤더) */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요"),

    /** 미처리 예외 — SCG 기본 에러 본문 대신 공통 실패 포맷으로 렌더링 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다");

    private final HttpStatus status;
    private final String resultCode;
    private final String resultMessage;

    GatewayError(HttpStatus status, String resultCode, String resultMessage) {
        this.status = status;
        this.resultCode = resultCode;
        this.resultMessage = resultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String resultCode() {
        return resultCode;
    }

    public String resultMessage() {
        return resultMessage;
    }
}
