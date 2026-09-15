package net.java21.crowfoot.gateway.auth;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * 무토큰 통과(인증 제외) 경로 — 원천 문서의 공개 그룹을 반영만 한다 (03-gateway/requirements.md §2,
 * 02-auth/api.md §2, 08-core/05-account.md §1.2). Gateway 가 스스로 정하지 않는다.
 *
 * <p>매칭은 (메서드, 경로) 쌍 단위 — 같은 경로라도 다른 메서드는 기본 거부 대상이다.
 * 판정은 rewrite 이전의 외부 경로(/api/v1/...) 기준 (requirements §3).
 */
@Component
public class AuthWhitelist {

    private static final List<Entry> ENTRIES = List.of(
            new Entry(HttpMethod.GET, "/api/v1/auth/oauth2/*"),        // 로그인 시작
            new Entry(HttpMethod.POST, "/api/v1/auth/oauth2/*/token"), // OAuth2 토큰 교환 — 프론트 콜백 구조(콜백은 프론트 /auth/callback, code 교환은 이 API)
            new Entry(HttpMethod.POST, "/api/v1/auth/refresh-token"),  // 재발급
            new Entry(HttpMethod.POST, "/api/v1/auth/logout"),         // 로그아웃
            new Entry(HttpMethod.GET, "/api/v1/core/providers"),       // core 공개 — 활성 제공자 목록
            new Entry(HttpMethod.GET, "/api/v1/core/shares/*")         // core 공개 — 공유 문서 조회(토큰이 자격, 08-core/02-model.md §1.10)
    );

    private final AntPathMatcher matcher = new AntPathMatcher();

    public boolean matches(HttpMethod method, String path) {
        return ENTRIES.stream().anyMatch(entry -> entry.matches(matcher, method, path));
    }

    private record Entry(HttpMethod method, String pattern) {

        boolean matches(AntPathMatcher matcher, HttpMethod method, String path) {
            return this.method.equals(method) && matcher.match(pattern, path);
        }
    }
}
