package com.codeagent.core.python;

import com.codeagent.core.CodeGraphEngine;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.EdgeDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PythonGraphTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/fastapi-app");

    private static CodeGraphEngine.Result result;
    private static CodeGraphDraft draft;

    @BeforeAll
    static void build() {
        result = CodeGraphEngine.analyze(FIXTURE);
        draft = result.draft();
    }

    @Test
    void 언어를_판별한다() {
        assertThat(result.languages()).containsExactly("python");
        assertThat(PythonLanguage.moduleName("app/routers/__init__.py")).isEqualTo("app.routers");
        assertThat(PythonLanguage.moduleName("app/routers/scan.py")).isEqualTo("app.routers.scan");
    }

    @Test
    void 구조_노드를_만들고_테스트_코드는_건너뛴다() {
        List<String> files = nodes(CodeNodeKind.FILE).stream().map(NodeDraft::qualifiedName).toList();
        assertThat(files).contains("app/main.py", "app/routers/scan.py", "app/services/scanner.py")
                .noneMatch(f -> f.startsWith("tests/"));
        assertThat(node(CodeNodeKind.CLASS, "app.routers.scan.ScanRequest").props()).containsEntry("language", "python");
        assertThat(node(CodeNodeKind.METHOD, "app.repo.UserRepo.delete")).isNotNull();
        assertThat(draft.edges()).contains(new EdgeDraft(
                CodeGraphDraft.key(CodeNodeKind.CLASS, "app.repo.UserRepo"),
                CodeGraphDraft.key(CodeNodeKind.METHOD, "app.repo.UserRepo.delete"), CodeEdgeKind.CONTAINS));
    }

    @Test
    void FastAPI_진입점을_prefix_까지_합쳐서_찾는다() {
        Set<String> routes = nodes(CodeNodeKind.ENTRY_POINT).stream()
                .map(n -> n.props().get("httpMethod") + " " + n.props().get("path"))
                .collect(Collectors.toSet());
        assertThat(routes).containsExactlyInAnyOrder(
                "GET /health",
                "POST /api/scan/nmap", "POST /api/scan/deep", "GET /api/scan/fetch", "POST /api/scan/ping",
                "GET /files/{name}", "GET /notes/{name}", "GET /about",
                "POST /admin/import", "POST /admin/config", "GET /admin/users", "GET /admin/report");

        NodeDraft nmap = entry("POST /api/scan/nmap");
        assertThat(nmap.props()).containsEntry("handler", "app.routers.scan.run_nmap").containsEntry("framework", "fastapi");
        assertThat(nmap.filePath()).isEqualTo("app/routers/scan.py");
        assertThat(nmap.startLine()).isEqualTo(18);
    }

    @Test
    void Depends_를_기록하고_의존_함수를_호출로_잇는다() {
        assertThat((List<Object>) entry("POST /api/scan/nmap").props().get("dependencies")).containsExactly("app.deps.get_current_user");
        assertThat((List<Object>) entry("POST /api/scan/deep").props().get("dependencies")).containsExactly("app.deps.get_current_user");
        // 라우터 전체에 건 dependencies=[Depends(require_admin)] 와 파라미터의 Depends(get_db)
        assertThat((List<Object>) entry("GET /admin/users").props().get("dependencies"))
                .containsExactly("app.deps.require_admin", "app.deps.get_db");
        assertThat((List<Object>) entry("GET /about").props().get("dependencies")).isEmpty();
        assertThat(draft.edges()).contains(call("app.routers.scan.run_nmap", "app.deps.get_current_user"));
    }

    @Test
    void 위험_지점을_분류한다() {
        Map<String, String> sinks = nodes(CodeNodeKind.SINK).stream()
                .collect(Collectors.toMap(n -> n.props().get("api") + "@" + n.props().get("method"),
                        n -> (String) n.props().get("category"), (a, b) -> a));
        assertThat(sinks).containsOnly(
                Map.entry("asyncio.create_subprocess_exec@app.services.scanner.run_nmap", "COMMAND"),
                Map.entry("nmap.PortScanner.scan@app.services.scanner.scan_with_lib", "COMMAND"),
                Map.entry("subprocess.run@app.routers.scan.ping", "COMMAND"),
                Map.entry("httpx.Client.get@app.routers.scan.fetch", "SSRF"),
                Map.entry("fastapi.responses.FileResponse@app.routers.files.download", "PATH"),
                Map.entry("builtins.open@app.routers.files.read_note", "PATH"),
                Map.entry("pickle.loads@app.routers.admin.import_backup", "DESERIALIZATION"),
                Map.entry("yaml.load@app.routers.admin.load_config", "DESERIALIZATION"),
                Map.entry("sqlalchemy.text@app.routers.admin.report", "SQL"),
                Map.entry("cursor.execute@app.repo.find_user", "SQL"),
                Map.entry("self.conn.execute@app.repo.UserRepo.delete", "SQL"),
                Map.entry("os.system@app.legacy.cleanup", "COMMAND"));

        // 고정 값만 넘기는 호출은 위험 지점이 아니다
        assertThat(nodes(CodeNodeKind.SINK)).noneMatch(n -> String.valueOf(n.props().get("snippet")).contains("uptime"));
        assertThat(nodes(CodeNodeKind.SINK)).noneMatch(n -> String.valueOf(n.props().get("snippet")).contains("ABOUT.md"));
        assertThat(nodes(CodeNodeKind.SINK).stream().filter(n -> n.props().get("api").equals("yaml.load"))).hasSize(1);
        assertThat(nodes(CodeNodeKind.SINK).stream().filter(n -> n.props().get("api").equals("nmap.PortScanner.scan")))
                .allMatch(n -> n.props().get("cwe").equals("CWE-88"));
    }

    @Test
    void 진입점에서_다른_모듈의_위험_지점까지_경로가_이어진다() {
        assertThat(reachableSinks("POST /api/scan/nmap")).contains("asyncio.create_subprocess_exec");
        assertThat(reachableSinks("POST /api/scan/deep")).contains("nmap.PortScanner.scan");
        assertThat(reachableSinks("GET /admin/users")).contains("cursor.execute");
        assertThat(reachableSinks("GET /api/scan/fetch")).containsExactly("httpx.Client.get");
        assertThat(reachableSinks("GET /about")).isEmpty();
    }

    @Test
    void 문법_오류가_있는_파일도_나머지는_분석한다() {
        assertThat(result.parseErrors()).anyMatch(e -> e.startsWith("app/legacy.py: "));
        assertThat(node(CodeNodeKind.METHOD, "app.legacy.cleanup")).isNotNull();
    }

    // ---- 도우미 ----

    private static List<NodeDraft> nodes(CodeNodeKind kind) {
        return draft.nodes().stream().filter(n -> n.kind() == kind).toList();
    }

    private static NodeDraft node(CodeNodeKind kind, String name) {
        return draft.node(CodeGraphDraft.key(kind, name));
    }

    private static NodeDraft entry(String route) {
        return nodes(CodeNodeKind.ENTRY_POINT).stream()
                .filter(n -> (n.props().get("httpMethod") + " " + n.props().get("path")).equals(route))
                .findFirst().orElseThrow();
    }

    private static EdgeDraft call(String from, String to) {
        return new EdgeDraft(CodeGraphDraft.key(CodeNodeKind.METHOD, from), CodeGraphDraft.key(CodeNodeKind.METHOD, to), CodeEdgeKind.CALLS);
    }

    private static Set<String> reachableSinks(String route) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(entry(route).key()));
        Set<String> sinks = new HashSet<>();
        while (!queue.isEmpty()) {
            String key = queue.poll();
            if (!seen.add(key)) {
                continue;
            }
            NodeDraft n = draft.node(key);
            if (n != null && n.kind() == CodeNodeKind.SINK) {
                sinks.add((String) n.props().get("api"));
            }
            for (EdgeDraft e : draft.edges()) {
                if (e.srcKey().equals(key) && e.kind() != CodeEdgeKind.CONTAINS) {
                    queue.add(e.dstKey());
                }
            }
        }
        return sinks;
    }
}
