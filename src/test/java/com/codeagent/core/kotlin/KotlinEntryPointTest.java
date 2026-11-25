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

class KotlinEntryPointTest {

    @TempDir
    static Path root;

    private static CodeGraphEngine.Result result;

    @BeforeAll
    static void build() throws IOException {
        write("src/main/kotlin/com/ex/web/UserController.kt", """
                package com.ex.web

                import org.springframework.web.bind.annotation.*

                object Paths {
                    const val USERS = "/users"
                }

                @RestController
                @RequestMapping("/api")
                class UserController(private val service: UserService) {

                    @GetMapping("/users/{id}")
                    fun get(@PathVariable id: Long): String = service.find(id)

                    @RequestMapping(value = ["/a", "/b"], method = [RequestMethod.POST, RequestMethod.PUT])
                    fun multi() {}

                    @PostMapping(path = [Paths.USERS])
                    fun create() {}

                    @DeleteMapping
                    fun remove() {}

                    @GetMapping("/run")
                    fun run(@RequestParam cmd: String) {
                        CommandRunner().run(cmd)
                    }

                    fun notMapped() {}
                }

                class UserService {
                    fun find(id: Long): String = id.toString()
                }

                class Plain {
                    @GetMapping("/ignored")
                    fun ignored() {}
                }
                """);
        write("src/main/java/com/ex/web/CommandRunner.java", """
                package com.ex.web;

                public class CommandRunner {
                    public void run(String cmd) throws java.io.IOException {
                        Runtime.getRuntime().exec(cmd);
                    }
                }
                """);
        result = CodeGraphEngine.analyze(root);
    }

    @Test
    void Kotlin_컨트롤러의_매핑을_진입점으로_찾는다() {
        assertThat(result.parseErrors()).isEmpty();
        assertThat(result.draft().nodes()).filteredOn(n -> n.kind() == CodeNodeKind.ENTRY_POINT).extracting(NodeDraft::qualifiedName)
                .containsExactlyInAnyOrder(
                        "GET /api/users/{id} -> com.ex.web.UserController#get(Long)",
                        "POST /api/a -> com.ex.web.UserController#multi()",
                        "PUT /api/a -> com.ex.web.UserController#multi()",
                        "POST /api/b -> com.ex.web.UserController#multi()",
                        "PUT /api/b -> com.ex.web.UserController#multi()",
                        "POST /api/users -> com.ex.web.UserController#create()",
                        "DELETE /api -> com.ex.web.UserController#remove()",
                        "GET /api/run -> com.ex.web.UserController#run(String)");
        NodeDraft get = result.draft().nodes().stream()
                .filter(n -> n.qualifiedName().startsWith("GET /api/users/{id}")).findFirst().orElseThrow();
        assertThat(get.props()).containsEntry("httpMethod", "GET").containsEntry("path", "/api/users/{id}")
                .containsEntry("handler", "com.ex.web.UserController#get(Long)").containsEntry("language", "kotlin");
        assertThat(get.filePath()).isEqualTo("src/main/kotlin/com/ex/web/UserController.kt");
    }

    @Test
    void Kotlin_진입점에서_Java_위험_지점까지_잇는다() {
        CodeGraphDraft g = result.draft();
        assertThat(g.edges()).contains(
                new EdgeDraft(CodeGraphDraft.key(CodeNodeKind.ENTRY_POINT, "GET /api/run -> com.ex.web.UserController#run(String)"),
                        CodeGraphDraft.key(CodeNodeKind.METHOD, "com.ex.web.UserController#run(String)"), CodeEdgeKind.ROUTES_TO),
                new EdgeDraft(CodeGraphDraft.key(CodeNodeKind.METHOD, "com.ex.web.UserController#run(String)"),
                        CodeGraphDraft.key(CodeNodeKind.METHOD, "com.ex.web.CommandRunner#run(String)"), CodeEdgeKind.CALLS));
        assertThat(g.nodes()).filteredOn(n -> n.kind() == CodeNodeKind.SINK)
                .extracting(n -> n.props().get("method")).contains("com.ex.web.CommandRunner#run(String)");
    }

    private static void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
