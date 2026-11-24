package com.codeagent.core.kotlin;

import com.codeagent.core.CodeGraphEngine;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.EdgeDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KotlinGraphTest {

    @TempDir
    static Path root;

    private static CodeGraphEngine.Result result;

    @BeforeAll
    static void build() throws IOException {
        write("src/main/kotlin/com/example/k/Runner.kt", """
                package com.example.k

                import java.io.File

                class Runner(private val base: String) {
                    private val helper = Helper()

                    fun run(cmd: String) {
                        Runtime.getRuntime().exec(cmd)
                        helper.write(cmd)
                    }

                    companion object {
                        fun make(): Runner = Runner("x")
                    }
                }

                class Helper {
                    fun write(name: String) {
                        val f = File("/tmp", name)
                        f.delete()
                    }
                }

                fun top(x: String) = Runner(x).run(x)
                """);
        write("src/main/kotlin/com/example/k/Svc.kt", """
                package com.example.k

                interface Svc {
                    fun go(a: Int)
                }

                class SvcImpl : Svc {
                    override fun go(a: Int) {
                        println(a)
                    }
                }
                """);
        write("src/test/kotlin/com/example/k/RunnerTest.kt", """
                package com.example.k
                class RunnerTest { fun t() { Runtime.getRuntime().exec("rm") } }
                """);
        result = CodeGraphEngine.analyze(root);
    }

    @Test
    void Kotlin_을_판별해_구조를_만든다() {
        CodeGraphDraft g = result.draft();
        assertThat(result.languages()).contains("kotlin");
        assertThat(result.parseErrors()).isEmpty();
        assertThat(g.nodes()).filteredOn(n -> n.kind() == CodeNodeKind.CLASS).extracting(NodeDraft::qualifiedName)
                .containsExactlyInAnyOrder("com.example.k.Runner", "com.example.k.Helper", "com.example.k.RunnerKt",
                        "com.example.k.Svc", "com.example.k.SvcImpl");
        NodeDraft svc = g.nodes().stream().filter(n -> n.qualifiedName().equals("com.example.k.Svc")).findFirst().orElseThrow();
        assertThat(svc.props()).containsEntry("declaration", "interface").containsEntry("language", "kotlin");
        assertThat(g.nodes()).filteredOn(n -> n.kind() == CodeNodeKind.METHOD).extracting(NodeDraft::qualifiedName)
                .contains("com.example.k.Runner#run(String)", "com.example.k.Runner#make()", "com.example.k.RunnerKt#top(String)");
    }

    @Test
    void 프로퍼티_생성자_호출로_타입을_알아내_호출을_잇는다() {
        assertThat(result.draft().edges()).contains(
                edge("com.example.k.Runner#run(String)", "com.example.k.Helper#write(String)", CodeEdgeKind.CALLS),
                edge("com.example.k.RunnerKt#top(String)", "com.example.k.Runner#run(String)", CodeEdgeKind.CALLS),
                edge("com.example.k.Svc#go(Int)", "com.example.k.SvcImpl#go(Int)", CodeEdgeKind.OVERRIDDEN_BY));
    }

    @Test
    void 서버_규칙으로_위험_지점을_찾고_테스트_코드는_건너뛴다() {
        assertThat(result.draft().nodes()).filteredOn(n -> n.kind() == CodeNodeKind.SINK)
                .extracting(n -> n.filePath() + ":" + n.startLine() + " " + n.props().get("api") + " " + n.props().get("language"))
                .containsExactlyInAnyOrder(
                        "src/main/kotlin/com/example/k/Runner.kt:9 java.lang.Runtime.exec kotlin",
                        "src/main/kotlin/com/example/k/Runner.kt:20 java.io.File.<init> kotlin");
    }

    private static EdgeDraft edge(String from, String to, CodeEdgeKind kind) {
        return new EdgeDraft(CodeGraphDraft.key(CodeNodeKind.METHOD, from), CodeGraphDraft.key(CodeNodeKind.METHOD, to), kind);
    }

    private static void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
