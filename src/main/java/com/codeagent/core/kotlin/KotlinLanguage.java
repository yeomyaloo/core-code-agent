package com.codeagent.core.kotlin;

import com.codeagent.core.LanguageParser;
import com.codeagent.core.SourceFiles;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.rules.SinkRules;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Kotlin 파서 (tree-sitter). 구조·호출 관계와 서버 위험 지점 규칙(SinkRules)으로 그래프를 더한다.
 * Kotlin 진입점(Spring 애너테이션 등)은 아직 찾지 않는다. 안드로이드처럼 진입점·규칙이 다른 제품은
 * {@link KotlinProject} 와 {@link KotlinGraphSupport} 를 직접 쓴다.
 */
public final class KotlinLanguage implements LanguageParser {

    /** 라이브러리 jar 없이 반환 타입을 아는 JDK 메서드 */
    private static final Map<String, String> JDK_RETURN_TYPES = Map.of("getRuntime", "java.lang.Runtime");

    @Override
    public String language() {
        return "kotlin";
    }

    @Override
    public boolean detect(Path root) {
        return SourceFiles.any(root, KotlinProject.SKIP_DIRS, KotlinProject::isSource);
    }

    @Override
    public void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors) {
        KotlinProject project = KotlinProject.load(root, parseErrors);
        KotlinGraphSupport support = new KotlinGraphSupport(project, draft, JDK_RETURN_TYPES);
        project.types().forEach(support::addStructure);
        project.types().forEach(support::addOverrides);
        project.types().forEach(t -> t.functions().forEach(f -> support.addCalls(f, SinkRules::match, Map.of())));
    }
}
