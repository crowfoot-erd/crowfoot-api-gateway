package net.java21.crowfoot.gateway.common;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Gateway resultMessage 다국어 해석 (api-design.md §5.7 — reactive 버전) —
 * 기동 시 properties 4벌을 메모리 Map에 적재해 요청 경로(이벤트 루프)에서는 순수 조회만 한다.
 * Accept-Language는 헤더를 직접 파싱한다(LocaleContextHolder·ResourceBundle 등 블로킹 자원 미사용).
 *
 * <p>미지원 언어·헤더 없음·키 없음은 {@link GatewayError#resultMessage()}(한국어)로 폴백한다.
 * zh* 태그(zh-Hans·zh-CN·zh-TW 등)는 간체(zh)로 통일한다 — 웹 감지 정책과 동일.
 */
@Slf4j
@Component
public class GatewayMessages {

    static final List<String> LANGS = List.of("ko", "en", "ja", "zh");
    private static final String BUNDLE = "i18n/messages_%s.properties";

    private final Map<String, Properties> bundles = new HashMap<>();

    @PostConstruct
    void load() {
        for (String lang : LANGS) {
            String path = BUNDLE.formatted(lang);
            Properties properties = new Properties();
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
                if (stream == null) {
                    throw new IllegalStateException(path + " 이 클래스패스에 없습니다 — 4벌 동시 갱신 필요");
                }
                properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(path + " 읽기 실패", e);
            }
            bundles.put(lang, properties);
        }
        log.info("gateway 메시지 번들 적재 완료 — {}개 언어, {}키", bundles.size(),
                bundles.get("ko").size());
    }

    /** 에러 문구 해석 — Accept-Language 헤더 문자열(없으면 null)을 받아 로케일 문구를 돌려준다 */
    public String resolve(GatewayError error, String acceptLanguage) {
        Properties properties = bundles.get(parseLanguage(acceptLanguage));
        String resolved = properties == null ? null : properties.getProperty(error.messageKey());
        return resolved != null ? resolved : error.resultMessage();
    }

    /** Accept-Language 파싱 — q값 무시 우선순위 순, 첫 지원 언어. 미매칭·빈값은 ko */
    static String parseLanguage(String header) {
        if (header == null || header.isBlank()) {
            return "ko";
        }
        for (String part : header.split(",")) {
            String tag = part.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (tag.startsWith("zh")) {
                return "zh";
            }
            switch (tag.split("-")[0]) {
                case "ko" -> {
                    return "ko";
                }
                case "en" -> {
                    return "en";
                }
                case "ja" -> {
                    return "ja";
                }
                default -> {
                    // 다음 후보로
                }
            }
        }
        return "ko";
    }
}
