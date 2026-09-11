package net.java21.crowfoot.gateway.testsupport;

import okhttp3.mockwebserver.MockResponse;

/**
 * E2E 목(MockWebServer) 응답 팩토리 — 인증 서버 introspection·하류 서비스 공통 포맷 (02-auth/api.md §4.1).
 */
public final class MockResponses {

    private MockResponses() {
    }

    /** introspection 활성 응답 — sub 를 내려주면 Gateway 가 X-USER-ID 로 주입한다 */
    public static MockResponse activeIntrospection(String sub, String jti) {
        return json(200, """
                {"header":{"isSuccessful":true,"resultCode":"SUCCESS","resultMessage":"성공"},
                 "response":{"active":true,"sub":"%s","jti":"%s","typ":"ACCESS","exp":9999999999}}
                """.formatted(sub, jti));
    }

    /** introspection 비활성 응답 — inactiveReason(EXPIRED/REVOKED/INVALID)로 401 코드가 갈린다 */
    public static MockResponse inactiveIntrospection(String inactiveReason) {
        return json(200, """
                {"header":{"isSuccessful":true,"resultCode":"SUCCESS","resultMessage":"성공"},
                 "response":{"active":false,"inactiveReason":"%s"}}
                """.formatted(inactiveReason));
    }

    /** 하류(auth/core) 정상 응답 */
    public static MockResponse downstreamOk() {
        return json(200, """
                {"header":{"isSuccessful":true,"resultCode":"SUCCESS","resultMessage":"성공"},
                 "response":{}}
                """);
    }

    public static MockResponse json(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
