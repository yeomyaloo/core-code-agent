package com.codeagent.core.java;

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
        new CodeGraphBuilder(project, draft).buildJava();
        parseErrors.addAll(project.parseErrors());
    }
}
