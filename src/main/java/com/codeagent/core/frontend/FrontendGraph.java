package com.codeagent.core.frontend;

import com.codeagent.core.LanguageParser;
import com.codeagent.core.SourceFiles;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프론트엔드(JS/TS/Vue/HTML) 위험 지점을 그래프에 더한다: 파일 → 위험 지점. 진입점에서 이어지는 경로는 없다.
 */
public final class FrontendGraph implements LanguageParser {

    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "bower_components", "dist", "build", "out");
    /** Node 백엔드 파서가 호출로 직접 보는 규칙 (백엔드 파일에서는 줄 검사를 하지 않는다) */
    private static final Set<String> NODE_HANDLED_APIS = Set.of("eval", "new Function", "setTimeout/setInterval (문자열)");

    /** 같은 저장소의 Node 백엔드 파서 (없으면 모든 JS·TS 파일을 프론트엔드로 본다) */
    private final com.codeagent.core.node.NodeLanguage node;

    public FrontendGraph() {
        this(null);
    }

    public FrontendGraph(com.codeagent.core.node.NodeLanguage node) {
        this.node = node;
    }

    @Override
    public String language() {
        return "javascript";
    }

    @Override
    public boolean detect(Path root) {
        return SourceFiles.any(root, SKIP_DIRS, p -> FrontendScanner.isFrontendFile(p.getFileName().toString()));
    }

    @Override
    public void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors) {
        contribute(root, draft, node);
    }

    public static void contribute(Path root, CodeGraphDraft draft) {
        contribute(root, draft, (com.codeagent.core.node.NodeLanguage) null);
    }

    /**
     * Node 백엔드 파일이면 eval·new Function 은 Node 파서가 호출로 보므로 건너뛰고, 나머지(비밀값 등)는
     * 감싼 Node 함수에서 잇는다 (진입점 경로가 생긴다).
     */
    private static void contribute(Path root, CodeGraphDraft draft, com.codeagent.core.node.NodeLanguage node) {
        Set<String> backend = node == null ? Set.of() : node.backendFiles(root);
        for (FrontendScanner.FrontendSink sink : FrontendScanner.scan(root)) {
            boolean backendFile = backend.contains(sink.filePath());
            if (backendFile && NODE_HANDLED_APIS.contains(sink.api())) {
                continue;
            }
            String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, sink.filePath(), sink.filePath(), null, null,
                    Map.of("language", "javascript")));
            String fileName = sink.filePath().substring(sink.filePath().lastIndexOf('/') + 1);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("category", sink.category());
            props.put("cwe", sink.cwe());
            props.put("api", sink.api());
            // 함수 단위로 나누지 않으므로 파일 이름(확장자 제외)을 위치 이름으로 쓴다
            props.put("method", fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName);
            props.put("snippet", sink.snippet());
            java.util.Optional<String> enclosing = backendFile ? node.enclosingMethodKey(root, sink.filePath(), sink.line())
                    : java.util.Optional.empty();
            props.put("frontend", !backendFile);
            if (backendFile) {
                props.put("language", "node");
            }
            String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK,
                    sink.filePath() + "@L" + sink.line() + ":" + sink.column() + " " + sink.api(),
                    sink.filePath(), sink.line(), sink.line(), props));
            draft.addEdge(fileKey, sinkKey, CodeEdgeKind.CONTAINS);
            enclosing.ifPresent(methodKey -> draft.addEdge(methodKey, sinkKey, CodeEdgeKind.CALLS));
        }
    }
}
