package net.java21.crowfoot.gateway.common;

/**
 * Gateway 공통 실패 포맷 (api-design.md §5.3) — header 3값만 담는다.
 *
 * <p>Gateway 가 다운스트림 도달 전 스스로 내보내는 응답에는 데이터가 없으므로
 * response/responses 필드를 아예 선언하지 않는다(null 노출 금지 — 03-gateway/api.md §1).
 */
public record GatewayErrorResponse(Header header) {

    public record Header(boolean isSuccessful, String resultCode, String resultMessage) {
    }

    public static GatewayErrorResponse of(GatewayError error) {
        return new GatewayErrorResponse(new Header(false, error.resultCode(), error.resultMessage()));
    }
}
