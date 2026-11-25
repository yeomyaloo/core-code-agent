package com.codeagent.core.kotlin;

import com.codeagent.core.java.EntryPointDetector;
import com.codeagent.core.kotlin.KotlinProject.KotlinFunction;
import com.codeagent.core.kotlin.KotlinProject.KotlinType;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.codeagent.core.kotlin.KotlinFile.children;
import static com.codeagent.core.kotlin.KotlinFile.firstChild;
import static com.codeagent.core.kotlin.KotlinFile.namedChildren;

/**
 * Spring WebFlux 의 Kotlin 라우터 DSL 에서 HTTP 진입점을 찾는다.
 * <pre>
 * fun routes() = coRouter {               // router { } 도
 *     "/api".nest {                        // path("/api").nest { } 도
 *         GET("/users/{id}", handler::get) // 함수 참조
 *         POST("/users") { req -> handler.create(req) }  // 람다: 안에서 처음 부르는 메서드
 *     }
 * }
 * </pre>
 * 처리 함수를 찾지 못하면 라우터 함수 자체로 잇는다(람다 안 호출은 라우터 함수의 호출로 이어져 있다).
 */
public final class KotlinRouterDetector {

    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private static final Set<String> ROUTER_DSL = Set.of("router", "coRouter");

    /** @param handler 처리 함수 (못 찾으면 null) */
    public record RouterEntry(String httpMethod, String path, KotlinFunction router, KotlinFunction handler) {

        public KotlinFunction target() {
            return handler != null ? handler : router;
        }

        public String qualifiedName() {
            return httpMethod + " " + path + " -> " + target().key();
        }
    }

    public List<RouterEntry> detect(KotlinProject project, KotlinType type) {
        List<RouterEntry> result = new ArrayList<>();
        for (KotlinFunction function : type.functions()) {
            KotlinFile file = type.file();
            for (TSNode call : KotlinFile.descendants(function.node(), "call_expression", "function_declaration")) {
                List<TSNode> parts = namedChildren(call);
                if (parts.isEmpty() || !parts.get(0).getType().equals("simple_identifier")) {
                    continue;
                }
                String httpMethod = file.text(parts.get(0));
                if (!HTTP_METHODS.contains(httpMethod) || !insideRouter(file, call)) {
                    continue;
                }
                List<TSNode> arguments = children(firstChild(firstChild(call, "call_suffix"), "value_arguments"), "value_argument");
                String path = arguments.isEmpty() ? null : stringValue(file, last(namedChildren(arguments.get(0))));
                if (path == null) {
                    continue;
                }
                TSNode handlerNode = arguments.size() >= 2 ? last(namedChildren(arguments.get(arguments.size() - 1))) : trailingLambda(call);
                KotlinFunction handler = handlerNode == null ? null : resolveHandler(project, function, handlerNode);
                result.add(new RouterEntry(httpMethod, EntryPointDetector.joinPath(prefix(file, call), path), function, handler));
            }
        }
        return result;
    }

    /** router { } / coRouter { } 람다 안인지 */
    private static boolean insideRouter(KotlinFile file, TSNode node) {
        for (TSNode current = node.getParent(); !KotlinFile.isNull(current); current = current.getParent()) {
            if (current.getType().equals("call_expression")) {
                List<TSNode> parts = namedChildren(current);
                if (!parts.isEmpty() && parts.get(0).getType().equals("simple_identifier") && ROUTER_DSL.contains(file.text(parts.get(0)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "/api".nest { } · path("/api").nest { } 를 바깥부터 이은 경로 */
    private static String prefix(KotlinFile file, TSNode node) {
        String prefix = "";
        for (TSNode current = node.getParent(); !KotlinFile.isNull(current); current = current.getParent()) {
            if (!current.getType().equals("call_expression")) {
                continue;
            }
            List<TSNode> parts = namedChildren(current);
            if (parts.isEmpty() || !parts.get(0).getType().equals("navigation_expression")) {
                continue;
            }
            List<TSNode> nav = namedChildren(parts.get(0));
            TSNode suffix = nav.size() < 2 ? null : firstChild(last(nav), "simple_identifier");
            if (suffix == null || !file.text(suffix).equals("nest")) {
                continue;
            }
            String segment = nestPath(file, nav.get(0));
            if (segment != null) {
                prefix = EntryPointDetector.joinPath(segment, prefix);
            }
        }
        return prefix;
    }

    private static String nestPath(KotlinFile file, TSNode target) {
        if (target.getType().equals("string_literal")) {
            return stringValue(file, target);
        }
        if (target.getType().equals("call_expression")) {
            List<TSNode> parts = namedChildren(target);
            if (!parts.isEmpty() && parts.get(0).getType().equals("simple_identifier") && file.text(parts.get(0)).equals("path")) {
                List<TSNode> arguments = children(firstChild(firstChild(target, "call_suffix"), "value_arguments"), "value_argument");
                return arguments.isEmpty() ? null : stringValue(file, last(namedChildren(arguments.get(0))));
            }
        }
        return null;
    }

    /** POST("/x") { req -> … }: 바깥 call_expression 이 끝에 붙은 람다를 갖는다 */
    private static TSNode trailingLambda(TSNode call) {
        TSNode parent = call.getParent();
        if (KotlinFile.isNull(parent) || !parent.getType().equals("call_expression")) {
            return null;
        }
        TSNode suffix = firstChild(parent, "call_suffix");
        TSNode annotated = firstChild(suffix, "annotated_lambda");
        return annotated == null ? null : firstChild(annotated, "lambda_literal");
    }

    /** handler::get, ::get, this@Routes::get, { req -> handler.create(req) } */
    private static KotlinFunction resolveHandler(KotlinProject project, KotlinFunction router, TSNode node) {
        KotlinFile file = router.owner().file();
        String receiver = null;
        String name = null;
        switch (node.getType()) {
            case "callable_reference" -> {
                List<TSNode> parts = namedChildren(node);
                if (parts.isEmpty()) {
                    return null;
                }
                name = file.text(last(parts));
                receiver = parts.size() > 1 ? file.text(parts.get(0)) : null;
            }
            case "navigation_expression" -> {
                // this@Routes::remove 가 this_expression 탐색으로 읽힌다
                List<TSNode> parts = namedChildren(node);
                TSNode suffix = parts.size() < 2 ? null : firstChild(last(parts), "simple_identifier");
                if (suffix == null || !parts.get(0).getType().equals("this_expression")) {
                    return null;
                }
                name = file.text(suffix);
            }
            case "lambda_literal" -> {
                for (TSNode inner : KotlinFile.descendants(node, "call_expression", "lambda_literal")) {
                    List<TSNode> parts = namedChildren(inner);
                    if (parts.isEmpty() || !parts.get(0).getType().equals("navigation_expression")) {
                        continue;
                    }
                    List<TSNode> nav = namedChildren(parts.get(0));
                    TSNode suffix = nav.size() < 2 ? null : firstChild(last(nav), "simple_identifier");
                    if (suffix == null) {
                        continue;
                    }
                    TSNode target = nav.get(0);
                    if (target.getType().equals("simple_identifier") || target.getType().equals("this_expression")) {
                        receiver = target.getType().equals("this_expression") ? null : file.text(target);
                        name = file.text(suffix);
                        break;
                    }
                }
            }
            default -> {
                return null;
            }
        }
        if (name == null) {
            return null;
        }
        String type = receiverType(project, router, receiver);
        if (type == null) {
            return null;
        }
        List<KotlinFunction> found = project.findFunctions(type, name, -1);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 받는 쪽 이름 → 타입: 없거나 this 면 자기 클래스, 라우터 함수 파라미터, 프로퍼티, object 이름 순 */
    private static String receiverType(KotlinProject project, KotlinFunction router, String receiver) {
        KotlinType owner = router.owner();
        if (receiver == null || receiver.equals("this")) {
            return owner.fqn();
        }
        String param = router.paramTypes().get(receiver);
        if (param != null) {
            return project.isProjectType(param) ? param : project.resolve(owner.file(), param);
        }
        String property = project.propertyType(owner, receiver);
        if (property != null) {
            return property;
        }
        return Character.isUpperCase(receiver.charAt(0)) ? project.resolve(owner.file(), receiver) : null;
    }

    private static String stringValue(KotlinFile file, TSNode node) {
        if (node == null || !node.getType().equals("string_literal")) {
            return null;
        }
        String text = file.text(node);
        return text.length() >= 2 ? text.substring(1, text.length() - 1) : text;
    }

    private static TSNode last(List<TSNode> nodes) {
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
    }
}
