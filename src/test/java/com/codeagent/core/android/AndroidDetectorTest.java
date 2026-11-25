package com.codeagent.core.android;

import com.codeagent.core.CodeGraphEngine;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 안드로이드 앱의 외부 공격면(소켓 진입점·매니페스트 exported·평문·인증 없는 핸드셰이크·토큰 저장)과
 * WebSocket 핸드셰이크용 SHA-1 오탐 억제를 확인한다. LLM 없이 코드 그래프만 본다.
 */
class AndroidDetectorTest {

    private static final Path APP = Path.of("src/test/resources/fixtures/android-app");

    private static CodeGraphDraft draft;

    @BeforeAll
    static void build() {
        CodeGraphEngine.Result result = CodeGraphEngine.analyze(APP);
        assertThat(result.parseErrors()).isEmpty();
        assertThat(result.languages()).contains("kotlin");
        draft = result.draft();
    }

    private static List<NodeDraft> nodes(CodeNodeKind kind) {
        return draft.nodes().stream().filter(n -> n.kind() == kind).toList();
    }

    private static List<NodeDraft> sinks(String category) {
        return nodes(CodeNodeKind.SINK).stream().filter(n -> category.equals(n.props().get("category"))).toList();
    }

    @Test
    void ServerSocket_수락_루프는_소켓_진입점이다() {
        List<NodeDraft> socket = nodes(CodeNodeKind.ENTRY_POINT).stream()
                .filter(n -> "socket".equals(n.props().get("kind"))).toList();
        assertThat(socket).hasSize(2);
        assertThat(socket).allSatisfy(n -> assertThat(n.props().get("transport")).isEqualTo("tcp"));
        assertThat(socket).anySatisfy(n -> assertThat((String) n.props().get("handler")).contains("StreamServer#acceptLoop"));
        assertThat(socket).anySatisfy(n -> assertThat((String) n.props().get("handler")).contains("SecureServer#acceptLoop"));
    }

    @Test
    void 매니페스트의_exported_컴포넌트만_진입점이고_런처_액티비티와_내부_서비스는_아니다() {
        List<NodeDraft> components = nodes(CodeNodeKind.ENTRY_POINT).stream()
                .filter(n -> "android-component".equals(n.props().get("kind"))).toList();
        assertThat(components).hasSize(1);
        assertThat(components.get(0).props().get("component")).isEqualTo("receiver");
        assertThat((String) components.get(0).props().get("name")).contains("CommandReceiver");
    }

    @Test
    void 평문_전송은_매니페스트와_TLS없는_소켓에서_잡힌다() {
        List<NodeDraft> cleartext = sinks("CLEARTEXT");
        // usesCleartextTraffic 1 + ServerSocket.<init> 2 (StreamServer, SecureServer)
        assertThat(cleartext).hasSize(3);
        assertThat(cleartext).anySatisfy(n -> assertThat(n.filePath()).endsWith("AndroidManifest.xml"));
        assertThat(cleartext).filteredOn(n -> "java.net.ServerSocket.<init>".equals(n.props().get("api"))).hasSize(2);
    }

    @Test
    void 인증없는_소켓_핸드셰이크만_인증부재로_잡힌다() {
        List<NodeDraft> missing = sinks("MISSING_AUTH");
        assertThat(missing).hasSize(1);
        assertThat((String) missing.get(0).props().get("method")).contains("StreamServer#upgrade");
    }

    @Test
    void WebSocket_핸드셰이크용_SHA1은_약한암호화로_잡지_않고_그외_약한해시는_잡는다() {
        List<NodeDraft> weak = sinks("WEAK_CRYPTO");
        // SHA-1 두 번(StreamServer·SecureServer 핸드셰이크)은 억제, 핸드셰이크와 무관한 MD5 만 남는다
        assertThat(weak).hasSize(1);
        assertThat(weak.get(0).props().get("algorithm")).isEqualTo("MD5");
        assertThat((String) weak.get(0).props().get("method")).contains("Hasher");
    }

    @Test
    void WebView_자바스크립트_인터페이스와_토큰_저장이_잡힌다() {
        assertThat(sinks("WEBVIEW_JS")).hasSize(1);
        assertThat(sinks("TOKEN_STORAGE")).hasSize(1);
    }
}
