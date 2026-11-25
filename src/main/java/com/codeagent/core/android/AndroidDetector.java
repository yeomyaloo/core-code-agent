package com.codeagent.core.android;

import com.codeagent.core.kotlin.KotlinGraphSupport;
import com.codeagent.core.kotlin.KotlinProject;
import com.codeagent.core.kotlin.KotlinProject.KotlinFunction;
import com.codeagent.core.kotlin.KotlinProject.KotlinType;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.rules.SinkRule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 안드로이드 앱의 외부 공격면을 코드 그래프에 더한다. HTTP 라우트가 아니라서 서버용 진입점·규칙으로는 안 잡히는 것들:
 * <ul>
 *   <li>raw {@link java.net.ServerSocket#accept()} 수락 루프 → 소켓 진입점</li>
 *   <li>매니페스트의 exported 컴포넌트·딥링크 → 외부 진입점</li>
 *   <li>평문 전송(usesCleartextTraffic) → 위험 지점</li>
 *   <li>토큰/PIN 검사 없이 올리는 소켓(WebSocket) 핸드셰이크 → 인증 부재 위험 지점</li>
 *   <li>SharedPreferences 에 토큰·비밀번호를 평문 저장 → 위험 지점</li>
 * </ul>
 * Kotlin 그래프({@link KotlinGraphSupport})를 이미 만든 뒤에 돌린다.
 */
public final class AndroidDetector {

    /** WebSocket 업그레이드 응답을 쓰는 함수의 표시 (핸드셰이크 처리기) */
    private static final Pattern HANDSHAKE = Pattern.compile("Switching Protocols|Sec-WebSocket-Accept", Pattern.CASE_INSENSITIVE);

    /** 핸드셰이크 경로에 접근 제어가 있는지 보는 표시. sec-websocket-key 의 "key" 처럼 흔한 말은 일부러 뺐다 */
    private static final Pattern AUTH_SIGNAL = Pattern.compile(
            "token|passcode|password|secret|bearer|authorization|credential|api[_-]?key|\\bpin\\b|authenticate",
            Pattern.CASE_INSENSITIVE);

    /** ServerSocket.accept() 수락 루프 */
    private static final Pattern ACCEPT_CALL = Pattern.compile("\\.accept\\s*\\(");

    /** SharedPreferences 에 민감한 값을 넣는 putString("token", ...) */
    private static final Pattern SENSITIVE_PUT = Pattern.compile(
            "put(?:String|StringSet)\\s*\\(\\s*\"[^\"]*(?:token|password|passwd|pwd|secret|jwt|credential|api[_-]?key|session|refresh)[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private final KotlinProject project;
    private final KotlinGraphSupport support;
    private final CodeGraphDraft draft;

    public AndroidDetector(KotlinProject project, KotlinGraphSupport support) {
        this.project = project;
        this.support = support;
        this.draft = support.draft();
    }

    /** 매니페스트 정보와 Kotlin 코드에서 안드로이드 진입점·위험 지점을 더한다. */
    public void contribute(AndroidManifest manifest) {
        contributeManifest(manifest);
        contributeCode();
    }

    // ---- 매니페스트 ----

    private void contributeManifest(AndroidManifest manifest) {
        if (manifest.cleartextTraffic()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("category", "CLEARTEXT");
            props.put("cwe", "CWE-319");
            props.put("api", "android:usesCleartextTraffic=\"true\"");
            props.put("method", "AndroidManifest");
            props.put("snippet", "usesCleartextTraffic=\"true\" — 앱 전체가 평문(HTTP/TCP) 전송을 허용한다");
            props.put("mobile", true);
            String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, manifest.filePath(), manifest.filePath(),
                    null, null, Map.of("language", "android")));
            String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK,
                    manifest.filePath() + "@L" + manifest.cleartextLine() + " usesCleartextTraffic",
                    manifest.filePath(), manifest.cleartextLine(), manifest.cleartextLine(), props));
            draft.addEdge(fileKey, sinkKey, CodeEdgeKind.CONTAINS);
        }
        for (AndroidManifest.Component c : manifest.externalComponents()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("kind", "android-component");
            props.put("component", c.kind());
            props.put("name", c.name());
            props.put("actions", c.actions());
            props.put("deepLinks", c.deepLinks());
            props.put("language", "android");
            draft.addNode(new NodeDraft(CodeNodeKind.ENTRY_POINT,
                    "EXPORTED " + c.kind() + " " + c.name(),
                    manifest.filePath(), c.line(), c.line(), props));
        }
    }

    // ---- 코드 ----

    private void contributeCode() {
        for (KotlinType type : project.types()) {
            boolean typeHasAuth = AUTH_SIGNAL.matcher(typeText(type)).find();
            for (KotlinFunction function : type.functions()) {
                String text = function.sourceText();
                socketEntryPoint(function, text);
                missingAuthHandshake(function, text, typeHasAuth);
                insecureTokenStorage(function, text);
            }
        }
    }

    /** ServerSocket.accept() 수락 루프를 소켓 진입점으로 본다. */
    private void socketEntryPoint(KotlinFunction function, String text) {
        if (!ACCEPT_CALL.matcher(text).find() || !text.contains("ServerSocket")) {
            return;
        }
        boolean tls = text.contains("SSLServerSocket");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", "socket");
        props.put("transport", tls ? "tls" : "tcp");
        props.put("handler", function.key());
        props.put("language", "kotlin");
        String entryKey = draft.addNode(new NodeDraft(CodeNodeKind.ENTRY_POINT,
                "SOCKET accept -> " + function.key(),
                function.owner().filePath(), function.startLine(), function.endLine(), props));
        draft.addEdge(entryKey, KotlinGraphSupport.methodKey(function), CodeEdgeKind.ROUTES_TO);
    }

    /** 소켓(WebSocket) 핸드셰이크를 올리는데 그 클래스 어디에도 토큰·PIN 검사가 없으면 인증 부재 위험 지점. */
    private void missingAuthHandshake(KotlinFunction function, String text, boolean typeHasAuth) {
        if (typeHasAuth || !HANDSHAKE.matcher(text).find()) {
            return;
        }
        SinkRule rule = new SinkRule("socket", Set.of("handshake"), "MISSING_AUTH", "CWE-306");
        support.addSink(function, function.node(), rule, "소켓 핸드셰이크 (인증 검사 없음)", Map.<String, Object>of("mobile", true));
    }

    /** SharedPreferences 에 토큰·비밀번호를 평문으로 저장. */
    private void insecureTokenStorage(KotlinFunction function, String text) {
        if (!SENSITIVE_PUT.matcher(text).find()) {
            return;
        }
        SinkRule rule = new SinkRule("android.content.SharedPreferences.Editor", Set.of("putString"), "TOKEN_STORAGE", "CWE-312");
        support.addSink(function, function.node(), rule, "SharedPreferences 평문 토큰 저장", Map.<String, Object>of("mobile", true));
    }

    private static String typeText(KotlinType type) {
        StringBuilder sb = new StringBuilder();
        for (KotlinFunction f : type.functions()) {
            sb.append(f.sourceText()).append('\n');
        }
        return sb.toString();
    }
}
