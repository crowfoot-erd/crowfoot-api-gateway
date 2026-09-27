package net.java21.crowfoot.gateway.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선택 인증 경로 매칭 테스트 (03-gateway/requirements.md §2 — 공유 댓글 4쌍) —
 * 토큰 없으면 비회원 통과, 있으면 검증의 판정은 AuthenticationGlobalFilter가 한다(여기는 경로 원천).
 */
class OptionalAuthMatcherTest {

    private final OptionalAuthMatcher optionalAuth = new OptionalAuthMatcher();

    @Test
    @DisplayName("선택 인증 4쌍은 (메서드, 경로) 모두 일치할 때 매칭된다")
    void matches_optionallyAuthenticatedMethodAndPath_returnsTrue() {
        // given // when // then
        assertThat(optionalAuth.matches(HttpMethod.GET, "/api/v1/core/shares/Ab3xYz0123456789QrStUv/comments")).isTrue();
        assertThat(optionalAuth.matches(HttpMethod.POST, "/api/v1/core/shares/Ab3xYz0123456789QrStUv/comments")).isTrue();
        assertThat(optionalAuth.matches(HttpMethod.PUT, "/api/v1/core/shares/Ab3xYz0123456789QrStUv/comments/31")).isTrue();
        assertThat(optionalAuth.matches(HttpMethod.DELETE, "/api/v1/core/shares/Ab3xYz0123456789QrStUv/comments/31")).isTrue();
    }

    @Test
    @DisplayName("같은 경로라도 메서드가 다르면 매칭되지 않는다 — PATCH는 없고, 등록은 목록 경로까지만")
    void matches_samePathDifferentMethod_returnsFalse() {
        // given // when // then
        assertThat(optionalAuth.matches(HttpMethod.PATCH, "/api/v1/core/shares/tok/comments")).isFalse();
        assertThat(optionalAuth.matches(HttpMethod.PATCH, "/api/v1/core/shares/tok/comments/31")).isFalse();
        assertThat(optionalAuth.matches(HttpMethod.PUT, "/api/v1/core/shares/tok/comments")).isFalse();    // 수정은 {id}까지
        assertThat(optionalAuth.matches(HttpMethod.POST, "/api/v1/core/shares/tok/comments/31")).isFalse(); // 등록은 목록 경로까지만
        assertThat(optionalAuth.matches(HttpMethod.GET, "/api/v1/core/shares/tok/comments/31")).isFalse();  // 단건 조회 API는 없다
    }

    @Test
    @DisplayName("회원전용 반응 토글(POST .../reactions)은 선택 인증이 아니다 — 표준 인증 대상")
    void matches_memberOnlyReactions_returnsFalse() {
        // given // when // then
        assertThat(optionalAuth.matches(HttpMethod.POST, "/api/v1/core/shares/tok/reactions")).isFalse();
        assertThat(optionalAuth.matches(HttpMethod.GET, "/api/v1/core/shares/tok/reactions")).isFalse();
    }

    @Test
    @DisplayName("공개 조회·DDL·오너 관리 경로는 선택 인증 목록에 없다")
    void matches_otherSharePaths_returnsFalse() {
        // given // when // then
        assertThat(optionalAuth.matches(HttpMethod.GET, "/api/v1/core/shares/tok")).isFalse();   // 순수 공개(화이트리스트)
        assertThat(optionalAuth.matches(HttpMethod.GET, "/api/v1/core/shares/tok/ddl")).isFalse(); // 순수 공개(화이트리스트)
        assertThat(optionalAuth.matches(HttpMethod.GET, "/api/v1/core/shares")).isFalse();
        // 오너 답글·댓글 관리는 인증 컨텍스트 경로다
        assertThat(optionalAuth.matches(HttpMethod.POST, "/api/v1/core/workspaces/77/models/501/shares/9/comments")).isFalse();
        assertThat(optionalAuth.matches(HttpMethod.DELETE, "/api/v1/core/workspaces/77/models/501/shares/9/comments/31")).isFalse();
        assertThat(optionalAuth.matches(HttpMethod.GET, "/api/v1/core/workspaces")).isFalse();
    }

    @Test
    @DisplayName("유사 경로(패턴 초과 깊이·오타)는 매칭되지 않는다")
    void matches_lookalikePath_returnsFalse() {
        // given // when // then
        assertThat(optionalAuth.matches(HttpMethod.PUT, "/api/v1/core/shares/tok/comments/31/extra")).isFalse();
        assertThat(optionalAuth.matches(HttpMethod.GET, "/api/v1/core/shares/tok/commentss")).isFalse();
        assertThat(optionalAuth.matches(HttpMethod.GET, "/api/v1/core/sharess/tok/comments")).isFalse();
    }
}
