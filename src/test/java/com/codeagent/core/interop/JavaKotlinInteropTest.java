package com.codeagent.core.interop;

import com.codeagent.core.java.JavaGraphSupport;
import com.codeagent.core.java.JavaProject;
import com.codeagent.core.java.JavaProject.MethodInfo;
import com.codeagent.core.kotlin.KotlinGraphSupport;
import com.codeagent.core.kotlin.KotlinProject;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.EdgeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JavaKotlinInteropTest {

    @TempDir
    static Path root;

    private static CodeGraphDraft draft;

    @BeforeAll
    static void build() throws IOException {
        write("app/src/main/java/com/ex/LoginActivity.java", """
                package com.ex;

                public class LoginActivity {
                    private final ProfileStore store = new ProfileStore("p");

                    public void onCreate(String token) {
                        store.save(token);
                        TokensKt.wipe(token);
                        Session.INSTANCE.start(token);
                        Audit.Companion.log(token);
                        store.getOwner().greet();
                    }
                }
                """);
        write("app/src/main/java/com/ex/LegacyDb.java", """
                package com.ex;

                public class LegacyDb {
                    public LegacyDb(String name) {}
                    public void write(String token) {}
                }
                """);
        write("app/src/main/java/com/ex/Helper.java", """
                package com.ex;

                public class Helper {
                    public Runner build() { return new Runner(); }
                }
                """);
        write("app/src/main/java/com/ex/Runner.java", """
                package com.ex;

                public class Runner {
                    public void run(String token) {}
                }
                """);
        write("app/src/main/kotlin/com/ex/ProfileStore.kt", """
                package com.ex

                class ProfileStore(private val name: String) {
                    val owner: Owner = Owner()

                    fun save(token: String) {
                        val legacy = LegacyDb(name)
                        legacy.write(token)
                        Helper().build().run(token)
                    }
                }

                class Owner {
                    fun greet() {}
                }

                object Session {
                    fun start(token: String) {}
                }

                class Audit {
                    companion object {
                        fun log(token: String) {}
                    }
                }
                """);
        write("app/src/main/kotlin/com/ex/Tokens.kt", """
                package com.ex

                fun wipe(token: String) {}
                """);

        JavaProject java = JavaProject.load(root);
        List<String> errors = new ArrayList<>(java.parseErrors());
        KotlinProject kotlin = KotlinProject.load(root, errors);
        assertThat(errors).isEmpty();
        draft = new CodeGraphDraft();
        JavaGraphSupport javaSupport = new JavaGraphSupport(java, draft, Map.of());
        KotlinGraphSupport kotlinSupport = new KotlinGraphSupport(kotlin, draft, Map.of());
        new JavaKotlinInterop(java, kotlin).connect(javaSupport, kotlinSupport);
        java.types().forEach(javaSupport::addStructure);
        kotlin.types().forEach(kotlinSupport::addStructure);
        for (var type : java.types()) {
            for (MethodInfo method : type.methods()) {
                for (MethodCallExpr call : method.decl().findAll(MethodCallExpr.class)) {
                    String target = call.getScope().map(s -> javaSupport.typeOfScope(s, method)).orElse(null);
                    javaSupport.linkProjectCall(method, call, target);
                }
            }
        }
        kotlin.types().forEach(t -> t.functions().forEach(f -> kotlinSupport.addCalls(f, (a, b) -> Optional.empty(), Map.of())));
    }

    @Test
    void Java_에서_Kotlin_으로_잇는다() {
        String onCreate = "com.ex.LoginActivity#onCreate(String)";
        assertThat(draft.edges()).contains(
                edge(onCreate, "com.ex.ProfileStore#save(String)"),
                edge(onCreate, "com.ex.TokensKt#wipe(String)"),
                edge(onCreate, "com.ex.Session#start(String)"),
                edge(onCreate, "com.ex.Audit#log(String)"),
                edge(onCreate, "com.ex.Owner#greet()"));
    }

    @Test
    void Kotlin_에서_Java_로_잇는다() {
        String save = "com.ex.ProfileStore#save(String)";
        assertThat(draft.edges()).contains(
                edge(save, "com.ex.LegacyDb#<init>(String)"),
                edge(save, "com.ex.LegacyDb#write(String)"),
                edge(save, "com.ex.Runner#run(String)"));
    }

    @Test
    void 서버_엔진도_Java_컨트롤러에서_Kotlin_서비스의_위험_지점까지_잇는다(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("src/main/java/com/ex/web"));
        Files.createDirectories(repo.resolve("src/main/kotlin/com/ex/web"));
        Files.writeString(repo.resolve("src/main/java/com/ex/web/RunController.java"), """
                package com.ex.web;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class RunController {
                    private final RunService service;

                    public RunController(RunService service) {
                        this.service = service;
                    }

                    @GetMapping("/run")
                    public String run(@RequestParam String cmd) {
                        service.execute(cmd);
                        return "ok";
                    }
                }
                """);
        Files.writeString(repo.resolve("src/main/kotlin/com/ex/web/RunService.kt"), """
                package com.ex.web

                class RunService {
                    fun execute(cmd: String) {
                        Runtime.getRuntime().exec(cmd)
                    }
                }
                """);

        var result = com.codeagent.core.CodeGraphEngine.analyze(repo);

        assertThat(result.languages()).containsExactly("java", "kotlin");
        assertThat(result.draft().edges()).contains(
                edge("com.ex.web.RunController#run(String)", "com.ex.web.RunService#execute(String)"));
        // Kotlin 부분은 한 번만 만든다
        assertThat(result.draft().nodes()).filteredOn(n -> n.kind() == CodeNodeKind.SINK)
                .extracting(n -> n.props().get("method")).containsExactly("com.ex.web.RunService#execute(String)");
    }

    private static EdgeDraft edge(String from, String to) {
        return new EdgeDraft(CodeGraphDraft.key(CodeNodeKind.METHOD, from), CodeGraphDraft.key(CodeNodeKind.METHOD, to), CodeEdgeKind.CALLS);
    }

    private static void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
