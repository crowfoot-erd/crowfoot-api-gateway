package net.java21.crowfoot.gateway.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway 메시지 번들 (api-design.md §5.7 — reactive 버전) —
 * 기동 시 적재(Map) 로케일 해석·Accept-Language 파싱·4벌 패리티를 검증한다.
 * 이벤트 루프에서 순수 조회임을 보장하려면 resolve가 블로킹 자원을 만지지 않아야 한다(구조 검증).
 */
class GatewayMessagesTest {

    private GatewayMessages messages;

    @BeforeEach
    void setUp() {
        messages = new GatewayMessages();
        messages.load();
    }

    @Test
    @DisplayName("Accept-Language별 문구 해석 — q값·zh 변체·미지원 언어 폴백")
    void resolvesPerAcceptLanguage() {
        assertThat(messages.resolve(GatewayError.ROUTE_NOT_FOUND, "ko"))
                .isEqualTo("존재하지 않는 API 경로입니다");
        assertThat(messages.resolve(GatewayError.ROUTE_NOT_FOUND, "en-US,en;q=0.9"))
                .isEqualTo("This API path does not exist");
        assertThat(messages.resolve(GatewayError.TOKEN_EXPIRED, "ja,en;q=0.5"))
                .isEqualTo("アクセストークンの有効期限が切れました");
        assertThat(messages.resolve(GatewayError.RATE_LIMITED, "zh-CN,zh;q=0.9,en;q=0.8"))
                .isEqualTo("请求超出限额，请稍后重试");
        assertThat(messages.resolve(GatewayError.TOKEN_MISSING, "zh-TW"))
                .isEqualTo("需要认证令牌");
    }

    @Test
    @DisplayName("헤더 없음·미지원 언어는 한국어 계약 문구로 폴백한다")
    void fallsBackToKorean() {
        assertThat(messages.resolve(GatewayError.DOWNSTREAM_UNAVAILABLE, null))
                .isEqualTo("서비스를 일시적으로 사용할 수 없습니다");
        assertThat(messages.resolve(GatewayError.DOWNSTREAM_UNAVAILABLE, "fr-CH,de"))
                .isEqualTo("서비스를 일시적으로 사용할 수 없습니다");
    }

    @Test
    @DisplayName("parseLanguage — 우선순위 첫 지원 언어, zh*는 간체 통일, 빈값·미매칭은 ko")
    void parsesAcceptLanguageHeader() {
        assertThat(GatewayMessages.parseLanguage(null)).isEqualTo("ko");
        assertThat(GatewayMessages.parseLanguage("")).isEqualTo("ko");
        assertThat(GatewayMessages.parseLanguage("ko-KR,ko;q=0.9")).isEqualTo("ko");
        assertThat(GatewayMessages.parseLanguage("fr,ja;q=0.8")).isEqualTo("ja");
        assertThat(GatewayMessages.parseLanguage("zh-Hans")).isEqualTo("zh");
        assertThat(GatewayMessages.parseLanguage("ZH")).isEqualTo("zh");
        assertThat(GatewayMessages.parseLanguage("fr-CH,de;q=0.9")).isEqualTo("ko");
    }

    @Test
    @DisplayName("4벌 키 집합은 동일하고 gateway.*는 GatewayError 8종과 정확히 1:1이다")
    void bundlesAreInParityWithEnum() {
        Map<String, Properties> bundles = loadAll();
        Set<Object> koKeys = bundles.get("ko").keySet();

        for (String lang : GatewayMessages.LANGS) {
            assertThat(bundles.get(lang).keySet())
                    .as("messages_%s 키 집합이 ko와 다릅니다", lang)
                    .isEqualTo(koKeys);
            for (Object key : bundles.get(lang).keySet()) {
                assertThat(((String) bundles.get(lang).get(key)).isBlank())
                        .as("messages_%s의 %s 값이 비었습니다", lang, key).isFalse();
            }
        }

        Set<String> expected = Arrays.stream(GatewayError.values())
                .map(GatewayError::messageKey)
                .collect(Collectors.toCollection(TreeSet::new));
        for (String lang : GatewayMessages.LANGS) {
            Set<String> actual = bundles.get(lang).keySet().stream()
                    .map(String.class::cast)
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(actual)
                    .as("messages_%s의 키가 GatewayError enum과 다릅니다", lang)
                    .isEqualTo(expected);
        }
    }

    private static Map<String, Properties> loadAll() {
        Map<String, Properties> bundles = new HashMap<>();
        for (String lang : GatewayMessages.LANGS) {
            String path = "i18n/messages_%s.properties".formatted(lang);
            Properties properties = new Properties();
            try (InputStream stream = GatewayMessagesTest.class.getClassLoader()
                    .getResourceAsStream(path)) {
                assertThat(stream).as("%s가 클래스패스에 없습니다", path).isNotNull();
                properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(path + " 읽기 실패", e);
            }
            bundles.put(lang, properties);
        }
        return bundles;
    }
}
