package net.java21.crowfoot.gateway.auth;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * 선택 인증 경로(2026-09-27 — 공유 댓글, 03-gateway/requirements.md §2) — Authorization 헤더가
 * 있으면 검증해 X-USER-ID를 주입하고, 없으면 비회원(익명)으로 통과시킨다(08-core/02-model.md §1.10.7).
 *
 * <p>핵심은 "토큰이 있으면 반드시 검증" — 무효 토큰을 들고 익명 행세하는 구멍이 없다.
 * 통과/401 판정은 AuthenticationGlobalFilter가 하며 여기는 경로 원천만 담는다.
 *
 * <p>반응 토글(POST .../reactions)은 회원전용이라 이 목록이 아니다(§1.10.6 — 표준 인증).
 * 공개 DDL(GET .../ddl)은 신원 불필요라 순수 화이트리스트(AuthWhitelist) 소관이다(§1.10.8).
 * 남용 방어는 전 경로 글로벌 레이트리밋(FixedWindow)에 위임 — 다른 공개 경로와 같다.
 */
@Component
public class OptionalAuthMatcher {

    private static final List<Entry> ENTRIES = List.of(
            new Entry(HttpMethod.GET, "/api/v1/core/shares/*/comments"),      // 피드백 초기화 — 회원이면 reacted가 그 기준
            new Entry(HttpMethod.POST, "/api/v1/core/shares/*/comments"),     // 댓글 등록 — 회원(내용만)·비회원(별명+비밀번호) 양쪽
            new Entry(HttpMethod.PUT, "/api/v1/core/shares/*/comments/*"),    // 댓글 수정 — 회원 판정 또는 비밀번호
            new Entry(HttpMethod.DELETE, "/api/v1/core/shares/*/comments/*")  // 댓글 삭제 — 회원 판정 또는 비밀번호
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
