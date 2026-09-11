package net.java21.crowfoot.gateway.error;

import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.gateway.common.GatewayError;
import net.java21.crowfoot.gateway.common.GatewayErrorResponse;
import net.java21.crowfoot.gateway.common.GatewayRejectedException;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * Gateway 에러 응답 렌더링의 단일 소스 (03-gateway/api.md §1).
 *
 * <p>SCG 기본 에러 본문(timestamp/path/error)이 노출되지 않도록 Boot 기본 핸들러(@Order(-1))보다
 * 앞서(@Order(-2)) 모든 에러를 공통 실패 포맷(header 3값)으로 바꿔 친다.
 */
@Slf4j
@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;

    public GatewayErrorWebExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        Rejection rejection = resolve(ex);
        logRejection(exchange, ex, rejection);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(rejection.error().status());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (rejection.wwwAuthenticate() != null) {
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, rejection.wwwAuthenticate());
        }
        if (rejection.retryAfterSeconds() != null) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(rejection.retryAfterSeconds()));
        }

        byte[] body = objectMapper.writeValueAsBytes(GatewayErrorResponse.of(rejection.error()));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private Rejection resolve(Throwable ex) {
        // 필터 파이프라인이 거절한 요청 — 계약 응답(본문 + WWW-Authenticate/Retry-After) 그대로
        if (ex instanceof GatewayRejectedException rejected) {
            return new Rejection(rejected.getError(), rejected.getWwwAuthenticate(), rejected.getRetryAfterSeconds());
        }
        // 라우트 미매칭(미정의 경로) — SCG 는 NotFoundException 을 던진다 (api.md §3.5)
        if (ex instanceof NotFoundException
                || ex instanceof ResponseStatusException status && status.getStatusCode() == HttpStatus.NOT_FOUND) {
            return new Rejection(GatewayError.ROUTE_NOT_FOUND, null, null);
        }
        // 다운스트림 연결 실패·타임아웃 — ConnectTimeoutException 은 ConnectException 의 하위 클래스
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof ConnectException || cause instanceof TimeoutException) {
            return new Rejection(GatewayError.DOWNSTREAM_UNAVAILABLE, null, null);
        }
        return new Rejection(GatewayError.INTERNAL_ERROR, null, null);
    }

    private void logRejection(ServerWebExchange exchange, Throwable ex, Rejection rejection) {
        String method = String.valueOf(exchange.getRequest().getMethod());
        String path = exchange.getRequest().getPath().value();
        if (rejection.error() == GatewayError.INTERNAL_ERROR) {
            log.error("gateway unhandled error: {} {}", method, path, ex);
        } else {
            log.warn("gateway rejected: {} {} -> {} {} ({})", method, path,
                    rejection.error().status(), rejection.error().resultCode(), ex.getMessage());
        }
    }

    private record Rejection(GatewayError error, String wwwAuthenticate, Long retryAfterSeconds) {
    }
}
