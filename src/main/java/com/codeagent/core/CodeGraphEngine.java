package com.codeagent.core;

import com.codeagent.core.java.CodeGraphBuilder;
import com.codeagent.core.java.JavaProject;
import com.codeagent.core.model.CodeGraphDraft;

import java.nio.file.Path;
import java.util.List;

/**
 * 공용 코드 그래프 엔진의 입구. 저장소 폴더를 받아 저장 전 코드 그래프({@link CodeGraphDraft})를 만든다.
 * be(DB 저장)와 mcp(메모리 색인)는 이 결과를 각자의 모델로 옮긴다.
 */
public final class CodeGraphEngine {

    private CodeGraphEngine() {
    }

    /**
     * 분석 결과.
     *
     * @param draft       코드 그래프 (노드·엣지)
     * @param parseErrors 파싱하지 못한 파일과 이유 ("경로: 메시지")
     */
    public record Result(CodeGraphDraft draft, List<String> parseErrors) {
    }

    /** 저장소 루트를 읽어 코드 그래프를 만든다. 분석 대상 코드는 읽기만 한다. */
    public static Result analyze(Path root) {
        JavaProject project = JavaProject.load(root);
        CodeGraphDraft draft = new CodeGraphBuilder(project).build();
        return new Result(draft, List.copyOf(project.parseErrors()));
    }
}
