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
    @DisplayName("화이트리스트 8쌍은 (메서드, 경로) 모두 일치할 때 통과한다")
    void matches_whitelistedMethodAndPath_returnsTrue() {
        // given // when // then
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/auth/oauth2/google")).isTrue();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/auth/oauth2/github/token")).isTrue();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/auth/refresh-token")).isTrue();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/auth/logout")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/providers")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/shares/Ab3xYz0123456789QrStUv")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/shares")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/release-notes/recent")).isTrue();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/release-notes/9")).isTrue();
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
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/shares")).isFalse();
        assertThat(whitelist.matches(HttpMethod.POST, "/api/v1/core/community/release-notes/9")).isFalse(); // 쓰기는 보호
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
        // 릴리스 노트 공개 경계 — bare 경로(*는 0세그먼트 미매칭)·깊은 하위 경로·오타·인증 커뮤니티 조회
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/release-notes")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/release-notes/9/extra")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/release-notess/9")).isFalse();
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/core/community/posts/recent")).isFalse();
    }

    @Test
    @DisplayName("폐기된 서버 콜백 경로는 더 이상 화이트리스트에 없다")
    void matches_removedServerCallbackPath_returnsFalse() {
        // given // when // then
        assertThat(whitelist.matches(HttpMethod.GET, "/api/v1/auth/oauth2/code/github")).isFalse();
    }
}
