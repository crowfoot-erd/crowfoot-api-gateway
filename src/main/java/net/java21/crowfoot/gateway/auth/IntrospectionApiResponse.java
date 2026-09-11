package net.java21.crowfoot.gateway.auth;

/**
 * Introspection 응답 — RFC 7662 표준이 아니라 공통 포맷으로 래핑된다 (02-auth/api.md §4.1).
 * 이 때문에 Spring Security 기본 introspection 클라이언트를 쓸 수 없고 전용 파서가 필요하다.
 */
public record IntrospectionApiResponse(Header header, Response response) {

    public record Header(boolean isSuccessful, String resultCode, String resultMessage) {
    }

    /**
     * active=false(무효)도 API 실패가 아닌 200 + isSuccessful=true + active=false 로 온다.
     *
     * @param inactiveReason 비활성 사유(EXPIRED/REVOKED/INVALID) — Gateway 는 JWT 를 해석하지 않으므로
     *                       만료/무효 구분을 인증 서버가 이 필드로 내려준다 (2026-09-11 계약 확장).
     *                       활성 토큰에서는 null.
     */
    public record Response(
            boolean active,
            String sub,
            String jti,
            String sid,
            String typ,
            String iss,
            String aud,
            Long iat,
            Long exp,
            String inactiveReason
    ) {
    }
}
