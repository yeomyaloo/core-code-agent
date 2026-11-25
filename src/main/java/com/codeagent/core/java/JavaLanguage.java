package com.codeagent.core.java;

import com.codeagent.core.interop.JavaKotlinInterop;
import com.codeagent.core.kotlin.KotlinGraphSupport;
import com.codeagent.core.kotlin.KotlinLanguage;
import com.codeagent.core.kotlin.KotlinProject;
import com.codeagent.core.LanguageParser;
import com.codeagent.core.SourceFiles;
import com.codeagent.core.model.CodeGraphDraft;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Java/Spring 파서. 기존 {@link JavaProject} + {@link CodeGraphBuilder} 를 그대로 감싼다.
 */
public final class JavaLanguage implements LanguageParser {

    /** 같은 저장소의 Kotlin 을 같이 만들고 호출을 이을 짝 (없으면 Java 만) */
    private final KotlinLanguage kotlin;

    public JavaLanguage() {
        this(null);
    }

    public JavaLanguage(KotlinLanguage kotlin) {
        this.kotlin = kotlin;
    }

    private static final Set<String> SKIP_DIRS = Set.of(".git", ".gradle", ".idea", "build", "target", "out", "node_modules");

    @Override
    public String language() {
        return "java";
    }

    @Override
    public boolean detect(Path root) {
        return SourceFiles.any(root, SKIP_DIRS, p -> p.getFileName().toString().endsWith(".java"));
    }

    @Override
    public void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors) {
        JavaProject project = JavaProject.load(root);
        CodeGraphBuilder builder = new CodeGraphBuilder(project, draft);
        parseErrors.addAll(project.parseErrors());
        if (kotlin == null || !kotlin.detect(root)) {
            builder.buildJava();
            return;
        }
        // Java ↔ Kotlin: 서로의 타입 이름을 알려 주고 호출을 이은 다음 둘 다 만든다. Kotlin 파서는 이 저장소를 다시 만들지 않는다
        KotlinProject kotlinProject = KotlinProject.load(root, parseErrors);
        KotlinGraphSupport kotlinSupport = kotlin.support(kotlinProject, draft);
        new JavaKotlinInterop(project, kotlinProject).connect(builder.support(), kotlinSupport);
        builder.buildJava();
        kotlin.build(kotlinProject, kotlinSupport);
        kotlin.builtWithJava(root);
    }
}
