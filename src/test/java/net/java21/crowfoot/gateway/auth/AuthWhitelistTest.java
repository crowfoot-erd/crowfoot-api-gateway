package net.java21.crowfoot.gateway.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AuthWhitelistTest {

    private final AuthWhitelist whitelist = new AuthWhitelist();

    @Test
    @DisplayName("화이트리스트 11쌍은 (메서드, 경로) 모두 일치할 때 통과한다")
    void matches_whitelistedMethodAndPath_returnsTrue() {
        // given // when // then
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/auth/oauth2/google")).isTrue();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/auth/oauth2/github/token")).isTrue();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/auth/refresh-token")).isTrue();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/auth/logout")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/providers")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/shares/Ab3xYz0123456789QrStUv")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/shares/Ab3xYz0123456789QrStUv/ddl")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/shares")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/showcase/sites")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/showcase/sites/12/thumbnail")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/release-notes/recent")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/release-notes/9")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/templates")).isTrue();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/metrics/visit")).isTrue();
    }

    @ParameterizedTest
    @MethodSource("protectedPathMethods")
    @DisplayName("보호 경로는 메서드와 무관하게 화이트리스트에 걸리지 않는다")
    void matches_protectedPath_returnsFalse(HttpMethod method) {
        // given // when // then
        assertThat(whitelist.matches(method, "/api/v1/core/workspaces")).isFalse();
    }

    private static Stream<HttpMethod> protectedPathMethods() {
        return Stream.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    }

    @Test
    @DisplayName("같은 경로라도 메서드가 다르면 매칭되지 않는다")
    void matches_samePathDifferentMethod_returnsFalse() {
        // given // when // then
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/auth/logout")).isFalse();
        assertThat(whitelist.matches(HttpMethod.PUT, "/api/v1/auth/refresh-token")).isFalse();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/providers")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/auth/oauth2/github/token")).isFalse();
        assertThat(whitelist.matches(HttpMethod.DELETE, "/api/v1/core/shares/Ab3xYz0123456789QrStUv")).isFalse();
        assertThat(whitelist.matches(HttpMethod.PATCH, "/api/v1/core/shares/Ab3xYz0123456789QrStUv/comments")).isFalse(); // PATCH 댓글 API는 없다
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/shares/Ab3xYz0123456789QrStUv/reactions")).isFalse(); // 반응 상태 조회형 오남용 차단(초기화는 GET comments에 동봉)
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/shares/Ab3xYz0123456789QrStUv/reactions")).isFalse(); // 회원전용(§1.10.6) — 선택 인증 목록(OptionalAuthMatcher) 소관
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/shares/Ab3xYz0123456789QrStUv/ddl")).isFalse(); // DDL은 조회(GET) 전용
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/showcase/sites/12/reports")).isFalse(); // 신고는 로그인(19-site-showcase §3.7)
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/admin/showcase/sites")).isFalse();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/shares")).isFalse();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/community/release-notes/9")).isFalse(); // 쓰기는 보호
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/templates")).isFalse(); // 복제 등 쓰기는 보호
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/metrics/visit")).isFalse(); // 조회형 오남용 차단
    }

    @Test
    @DisplayName("유사 경로(오타·하위 경로 추가)는 매칭되지 않는다")
    void matches_lookalikePath_returnsFalse() {
        // given // when // then
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/providerss")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/providers/extra")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/auth/oauth2")).isFalse();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v2/auth/refresh-token")).isFalse();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/auth/oauth2/github/tokens")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/shares/tok/extra")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/sharess/tok")).isFalse();
        // 피드백 공개 경계 — 와일드카드 수 불일치·패턴 초과 깊이 (comments 4쌍은 선택 인증 목록이라 여기 없다)
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/shares/tok/comments/31")).isFalse(); // 댓글 등록은 목록 경로까지만
        assertThat(whitelist.matches(HttpMethod.DELETE, "/api/v1/core/shares/tok/comments/31/extra")).isFalse();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/shares/tok/reactions/extra")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/shares/tok/ddl/extra")).isFalse(); // DDL은 1세그먼트 토큰까지만
        // 릴리스 노트 공개 경계 — bare 경로(*는 0세그먼트 미매칭)·깊은 하위 경로·오타·인증 커뮤니티 조회
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/release-notes")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/release-notes/9/extra")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/release-notess/9")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/posts/recent")).isFalse();
        // 템플릿 공개 경계 — 하위 경로·오타는 미매칭(원천은 bare 경로뿐)
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/templates/extra")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/templatess")).isFalse();
        // 비콘 공개 경계 — 하위 경로·bare 경로·관리자 조회는 미매칭
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/metrics/visit/extra")).isFalse();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/metrics")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/admin/metrics/summary")).isFalse(); // 관리자 통계는 보호
    }

    @Test
    @DisplayName("폐기된 서버 콜백 경로는 더 이상 화이트리스트에 없다")
    void matches_removedServerCallbackPath_returnsFalse() {
        // given // when // then
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/auth/oauth2/code/github")).isFalse();
    }
}
