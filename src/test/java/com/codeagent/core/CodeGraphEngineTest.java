package com.codeagent.core;

import com.codeagent.core.java.CodeGraphBuilder;
import com.codeagent.core.java.JavaProject;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeNodeKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphEngineTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/owasp-app");

    @Test
    void 엔진_결과는_빌더를_직접_부른_결과와_같다() {
        CodeGraphEngine.Result result = CodeGraphEngine.analyze(FIXTURE);

        JavaProject project = JavaProject.load(FIXTURE);
        CodeGraphDraft direct = new CodeGraphBuilder(project).build();

        assertThat(result.parseErrors()).isEqualTo(project.parseErrors());
        assertThat(result.draft().nodes()).containsExactlyElementsOf(direct.nodes());
        assertThat(result.draft().edges()).containsExactlyElementsOf(direct.edges());
        assertThat(result.draft().unresolvedCalls()).isEqualTo(direct.unresolvedCalls());
        assertThat(result.draft().count(CodeNodeKind.SINK)).isPositive();
    }
}
