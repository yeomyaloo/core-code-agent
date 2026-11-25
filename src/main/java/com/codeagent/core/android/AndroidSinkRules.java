package com.codeagent.core.android;

import com.codeagent.core.rules.SinkRule;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 안드로이드 전용 위험 지점 규칙 (받는 타입·메서드만 보고 판단하는 것). 인자나 주변 코드를 봐야 하는
 * 평문 매니페스트·인증 없는 소켓 핸드셰이크·안전하지 않은 토큰 저장은 {@link AndroidDetector} 가 따로 본다.
 * {@link com.codeagent.core.rules.SinkRules} 에 합쳐서 Kotlin 그래프를 만들 때 같이 쓴다.
 */
public final class AndroidSinkRules {

    private static final List<SinkRule> RULES = List.of(
            // TLS 없는 평문 소켓 서버: 같은 Wi-Fi 의 누구나 가로채거나 끼어들 수 있다 (SSLServerSocket 은 해당 없음)
            new SinkRule("java.net.ServerSocket", Set.of("<init>"), "CLEARTEXT", "CWE-319"),

            // WebView 에 자바스크립트 인터페이스를 붙이면 로드한 페이지가 네이티브 코드를 부를 수 있다
            new SinkRule("android.webkit.WebView", Set.of("addJavascriptInterface"), "WEBVIEW_JS", "CWE-749"));

    private AndroidSinkRules() {
    }

    public static Optional<SinkRule> match(String typeFqn, String method) {
        if (typeFqn == null) {
            return Optional.empty();
        }
        return RULES.stream().filter(r -> r.matches(typeFqn, method)).findFirst();
    }
}
