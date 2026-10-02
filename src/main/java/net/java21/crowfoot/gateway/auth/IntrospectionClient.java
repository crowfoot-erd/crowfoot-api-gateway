package net.java21.crowfoot.gateway.auth;

import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.gateway.common.GatewayError;
import net.java21.crowfoot.gateway.common.GatewayRejectedException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증 서버 Introspection 위임 클라이언트 (02-auth/api.md §4.1).
 *
 * <p>POST {auth-base-url}/internal/auth/introspect — form-urlencoded 본문 {@code token={Access JWT}}.
 * 무효(active=false)는 정상 응답이고, 전송 실패·타임아웃·5xx·계약 밖 본문은 전부
 * {@code GatewayRejectedException(AUTH_SERVICE_UNAVAILABLE)}(fail-closed 503)로 정규화한다.
 */
@Slf4j
@Component
public class IntrospectionClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public IntrospectionClient(WebClient introspectionWebClient, ObjectMapper objectMapper) {
        this.webClient = introspectionWebClient;
        this.objectMapper = objectMapper;
    }

    public Mono<IntrospectionResult> introspect(String token) {
        return webClient.post()
                .uri("/internal/auth/introspect")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("token", token))
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parse)
                .onErrorMap(ex -> {
                    log.warn("introspection unavailable: {}", ex.getMessage());
                    return new GatewayRejectedException(GatewayError.AUTH_SERVICE_UNAVAILABLE);
                });
    }

    /** 계약 밖 본문(파싱 실패·isSuccessful=false·active인데 sub 없음)도 장애 취급 — fail-closed */
    private IntrospectionResult parse(String body) {
        IntrospectionApiResponse decoded = objectMapper.readValue(body, IntrospectionApiResponse.class);
        if (decoded.header() == null || !decoded.header().isSuccessful() || decoded.response() == null) {
            throw new IllegalArgumentException("non-contract introspection response");
        }
        IntrospectionApiResponse.Response response = decoded.response();
        if (response.active() && response.sub() == null) {
            throw new IllegalArgumentException("active response without sub");
        }
        return new IntrospectionResult(response.active(), response.sub(), response.jti(),
                response.exp(), response.inactiveReason(), response.typ(), response.workspaceId(), response.tokenId());
    }

    /**
     * @param active         인증 서버 판정
     * @param sub            활성일 때의 사용자 식별자(BIGINT PK 문자열) — X-USER-ID 주입 원천
     * @param jti            검증 정보 참고용
     * @param exp            토큰 만료 시각(epoch seconds) — 캐시 만료 상한
     * @param inactiveReason 비활성 사유(EXPIRED/REVOKED/INVALID) — 401 코드 구분 근거
     * @param typ            토큰 종류(ACCESS 또는 WORKSPACE_TOKEN) — 부를 수 있는 경로를 가른다
     * @param workspaceId    워크스페이스 액세스 토큰이 묶인 워크스페이스 — X-TOKEN-WORKSPACE-ID 주입 원천
     * @param tokenId        워크스페이스 액세스 토큰 ID — X-ACCESS-TOKEN-ID 주입 원천
     */
    public record IntrospectionResult(boolean active, String sub, String jti, Long exp, String inactiveReason,
                                      String typ, String workspaceId, String tokenId) {

        /** 워크스페이스 액세스 토큰인가 — MCP 경로에서만 통한다 (03-gateway/requirements.md §2.3) */
        public boolean workspaceToken() {
            return "WORKSPACE_TOKEN".equals(typ);
        }
    }
}
