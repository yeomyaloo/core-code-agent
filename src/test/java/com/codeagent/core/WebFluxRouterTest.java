package com.codeagent.core;

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

/** WebFlux 함수형 라우터: Java(RouterFunctions·빌더)와 Kotlin(router·coRouter DSL) */
class WebFluxRouterTest {

    @TempDir
    static Path root;

    private static CodeGraphEngine.Result result;

    @BeforeAll
    static void build() throws IOException {
        write("src/main/java/com/ex/flux/JavaRoutes.java", """
                package com.ex.flux;

                import org.springframework.context.annotation.Bean;
                import org.springframework.web.reactive.function.server.RouterFunction;
                import org.springframework.web.reactive.function.server.ServerResponse;

                import static org.springframework.web.reactive.function.server.RequestPredicates.*;
                import static org.springframework.web.reactive.function.server.RouterFunctions.*;

                public class JavaRoutes {

                    @Bean
                    public RouterFunction<ServerResponse> predicates(OrderHandler handler) {
                        return route(GET("/orders/{id}"), handler::get)
                                .andRoute(POST("/orders").and(accept(APPLICATION_JSON)), handler::create)
                                .and(nest(path("/admin"), route(DELETE("/orders/{id}"), req -> handler.remove(req))));
                    }

                    @Bean
                    public RouterFunction<ServerResponse> builder(OrderHandler handler) {
                        return route()
                                .GET("/run", handler::run)
                                .path("/v2", b -> b.PUT("/orders", accept(APPLICATION_JSON), handler::create))
                                .build();
                    }

                    public String notARouter() {
                        return GET("/ignored").toString();
                    }
                }
                """);
        write("src/main/java/com/ex/flux/OrderHandler.java", """
                package com.ex.flux;

                public class OrderHandler {
                    public Object get(Object req) { return null; }
                    public Object create(Object req) { return null; }
                    public Object remove(Object req) { return null; }
                    public Object run(Object req) throws java.io.IOException {
                        Runtime.getRuntime().exec(req.toString());
                        return null;
                    }
                }
                """);
        write("src/main/kotlin/com/ex/flux/KotlinRoutes.kt", """
                package com.ex.flux

                import org.springframework.context.annotation.Bean
                import org.springframework.web.reactive.function.server.coRouter

                class KotlinRoutes(private val handler: UserHandler) {

                    @Bean
                    fun routes() = coRouter {
                        "/api".nest {
                            GET("/users/{id}", handler::get)
                            POST("/users") { req -> handler.create(req) }
                        }
                        path("/v2").nest {
                            DELETE("/self", this@KotlinRoutes::remove)
                        }
                        GET("/exec", handler::exec)
                    }

                    suspend fun remove(req: Any): Any = req

                    fun notRouter() {
                        GET("/ignored", handler::get)
                    }
                }

                class UserHandler {
                    suspend fun get(req: Any): Any = req
                    suspend fun create(req: Any): Any = req
                    suspend fun exec(req: Any): Any {
                        Runtime.getRuntime().exec(req.toString())
                        return req
                    }
                }
                """);
        result = CodeGraphEngine.analyze(root);
    }

    @Test
    void Java_라우터의_경로와_처리_메서드를_찾는다() {
        assertThat(entries()).contains(
                "GET /orders/{id} -> com.ex.flux.OrderHandler#get(Object)",
                "POST /orders -> com.ex.flux.OrderHandler#create(Object)",
                "DELETE /admin/orders/{id} -> com.ex.flux.OrderHandler#remove(Object)",
                "GET /run -> com.ex.flux.OrderHandler#run(Object)",
                "PUT /v2/orders -> com.ex.flux.OrderHandler#create(Object)");
        assertThat(entries()).noneMatch(e -> e.contains("/ignored"));
        NodeDraft run = result.draft().nodes().stream().filter(n -> n.qualifiedName().startsWith("GET /run")).findFirst().orElseThrow();
        assertThat(run.props()).containsEntry("router", "com.ex.flux.JavaRoutes#builder(OrderHandler)")
                .containsEntry("handler", "com.ex.flux.OrderHandler#run(Object)");
        assertThat(run.filePath()).isEqualTo("src/main/java/com/ex/flux/OrderHandler.java");
    }

    @Test
    void Kotlin_라우터_DSL_의_경로와_처리_함수를_찾는다() {
        assertThat(entries()).contains(
                "GET /api/users/{id} -> com.ex.flux.UserHandler#get(Any)",
                "POST /api/users -> com.ex.flux.UserHandler#create(Any)",
                "DELETE /v2/self -> com.ex.flux.KotlinRoutes#remove(Any)",
                "GET /exec -> com.ex.flux.UserHandler#exec(Any)");
    }

    @Test
    void 라우터_진입점에서_위험_지점까지_잇는다() {
        CodeGraphDraft g = result.draft();
        assertThat(g.edges()).contains(
                new EdgeDraft(CodeGraphDraft.key(CodeNodeKind.ENTRY_POINT, "GET /run -> com.ex.flux.OrderHandler#run(Object)"),
                        CodeGraphDraft.key(CodeNodeKind.METHOD, "com.ex.flux.OrderHandler#run(Object)"), CodeEdgeKind.ROUTES_TO),
                new EdgeDraft(CodeGraphDraft.key(CodeNodeKind.ENTRY_POINT, "GET /exec -> com.ex.flux.UserHandler#exec(Any)"),
                        CodeGraphDraft.key(CodeNodeKind.METHOD, "com.ex.flux.UserHandler#exec(Any)"), CodeEdgeKind.ROUTES_TO));
        assertThat(g.nodes()).filteredOn(n -> n.kind() == CodeNodeKind.SINK).extracting(n -> n.props().get("method"))
                .contains("com.ex.flux.OrderHandler#run(Object)", "com.ex.flux.UserHandler#exec(Any)");
    }

    private static java.util.List<String> entries() {
        return result.draft().nodes().stream().filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT).map(NodeDraft::qualifiedName).toList();
    }

    private static void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
