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
        contribute(root, draft);
    }

    public static void contribute(Path root, CodeGraphDraft draft) {
        for (FrontendScanner.FrontendSink sink : FrontendScanner.scan(root)) {
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
            props.put("frontend", true);
            String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK,
                    sink.filePath() + "@L" + sink.line() + ":" + sink.column() + " " + sink.api(),
                    sink.filePath(), sink.line(), sink.line(), props));
            draft.addEdge(fileKey, sinkKey, CodeEdgeKind.CONTAINS);
        }
    }
}
