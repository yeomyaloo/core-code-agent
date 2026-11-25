package com.codeagent.core;

import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 경로를 상수로 쓴 진입점: 프로젝트 상수면 값으로 풀고, 모르면 ${식} 으로 남긴다 */
class ConstantPathTest {

    @TempDir
    static Path root;

    private static CodeGraphEngine.Result result;

    @BeforeAll
    static void build() throws IOException {
        write("src/main/java/com/ex/c/ApiPaths.java", """
                package com.ex.c;

                public final class ApiPaths {
                    public static final String BASE = "/api";
                    public static final String ORDERS = BASE + "/orders";
                    public static final String RUN = "/run";
                }
                """);
        write("src/main/java/com/ex/c/OrderController.java", """
                package com.ex.c;

                import org.springframework.web.bind.annotation.*;

                import static com.ex.c.ApiPaths.RUN;

                @RestController
                @RequestMapping(ApiPaths.ORDERS)
                public class OrderController {
                    private static final String ONE = "/{id}";

                    @GetMapping(ONE)
                    public String get(@PathVariable Long id) { return ""; }

                    @PostMapping(RUN)
                    public String run() { return ""; }

                    @DeleteMapping(Unknown.PATH)
                    public String remove() { return ""; }
                }
                """);
        write("src/main/java/com/ex/c/JavaRoutes.java", """
                package com.ex.c;

                import org.springframework.web.reactive.function.server.RouterFunction;
                import org.springframework.web.reactive.function.server.ServerResponse;

                import static org.springframework.web.reactive.function.server.RequestPredicates.*;
                import static org.springframework.web.reactive.function.server.RouterFunctions.*;

                public class JavaRoutes {
                    public RouterFunction<ServerResponse> routes(OrderController handler) {
                        return route(GET(ApiPaths.ORDERS + "/flux"), handler::get)
                                .and(route().path(ApiPaths.BASE, b -> b.PUT(ApiPaths.RUN, handler::run)).build())
                                .andRoute(DELETE(Other.GONE), handler::remove);
                    }
                }
                """);
        write("src/main/kotlin/com/ex/c/KotlinPaths.kt", """
                package com.ex.c

                const val V2 = "/v2"

                object KPaths {
                    const val USERS = "$V2/users"
                }
                """);
        write("src/main/kotlin/com/ex/c/UserController.kt", """
                package com.ex.c

                import org.springframework.web.bind.annotation.*

                @RestController
                @RequestMapping(KPaths.USERS)
                class UserController {
                    companion object {
                        const val ONE = "/{id}"
                    }

                    @GetMapping(ONE)
                    fun get(@PathVariable id: Long): String = ""

                    @PostMapping("$V2/register")
                    fun register(): String = ""
                }
                """);
        write("src/main/kotlin/com/ex/c/KotlinRoutes.kt", """
                package com.ex.c

                class KotlinRoutes(private val handler: UserController) {
                    fun routes() = coRouter {
                        KPaths.USERS.nest {
                            GET(UserController.ONE + "/flux", handler::get)
                        }
                        POST(Missing.CONST, handler::register)
                    }
                }
                """);
        result = CodeGraphEngine.analyze(root);
    }

    @Test
    void Java_상수_경로를_값으로_푼다() {
        assertThat(entries()).contains(
                "GET /api/orders/{id} -> com.ex.c.OrderController#get(Long)",
                "POST /api/orders/run -> com.ex.c.OrderController#run()",
                "DELETE /api/orders/${Unknown.PATH} -> com.ex.c.OrderController#remove()",
                "GET /api/orders/flux -> com.ex.c.OrderController#get(Long)",
                "PUT /api/run -> com.ex.c.OrderController#run()",
                "DELETE /${Other.GONE} -> com.ex.c.OrderController#remove()");
    }

    @Test
    void Kotlin_상수와_템플릿_경로를_값으로_푼다() {
        assertThat(entries()).contains(
                "GET /v2/users/{id} -> com.ex.c.UserController#get(Long)",
                "POST /v2/users/v2/register -> com.ex.c.UserController#register()",
                "GET /v2/users/{id}/flux -> com.ex.c.UserController#get(Long)",
                "POST /${Missing.CONST} -> com.ex.c.UserController#register()");
    }

    private static List<String> entries() {
        return result.draft().nodes().stream().filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT).map(NodeDraft::qualifiedName).toList();
    }

    private static void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
