package com.codeagent.core;

import com.codeagent.core.frontend.FrontendGraph;
import com.codeagent.core.java.JavaLanguage;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.python.PythonLanguage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 공용 코드 그래프 엔진의 입구. 저장소 폴더를 받아 저장 전 코드 그래프({@link CodeGraphDraft})를 만든다.
 * 저장소에 있는 언어를 확장자로 판별해 알맞은 파서만 돌린다.
 * be(DB 저장)와 mcp(메모리 색인)는 이 결과를 각자의 모델로 옮긴다.
 */
public final class CodeGraphEngine {

    private CodeGraphEngine() {
    }

    /**
     * 분석 결과.
     *
     * @param draft       코드 그래프 (노드·엣지)
     * @param parseErrors 파싱하지 못했거나 일부만 읽은 파일과 이유 ("경로: 메시지")
     * @param languages   저장소에서 찾아 분석한 언어 (java, python, javascript), 파서 순서대로
     */
    public record Result(CodeGraphDraft draft, List<String> parseErrors, Set<String> languages) {
    }

    /** 기본 파서. 순서대로 그래프에 더한다 (Java 저장소의 결과가 예전과 같도록 프론트엔드가 마지막) */
    public static List<LanguageParser> defaultParsers() {
        return List.of(new JavaLanguage(), new PythonLanguage(), new FrontendGraph());
    }

    /** 저장소 루트를 읽어 코드 그래프를 만든다. 분석 대상 코드는 읽기만 한다. */
    public static Result analyze(Path root) {
        return analyze(root, defaultParsers());
    }

    public static Result analyze(Path root, List<LanguageParser> parsers) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("저장소 경로가 디렉터리가 아님: " + root);
        }
        Path normalized = root.toAbsolutePath().normalize();
        CodeGraphDraft draft = new CodeGraphDraft();
        List<String> parseErrors = new ArrayList<>();
        Set<String> languages = new LinkedHashSet<>();
        for (LanguageParser parser : parsers) {
            if (parser.detect(normalized)) {
                languages.add(parser.language());
                parser.contribute(normalized, draft, parseErrors);
            }
        }
        return new Result(draft, List.copyOf(parseErrors), Collections.unmodifiableSet(languages));
    }
}
