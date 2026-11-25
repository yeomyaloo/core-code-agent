package com.codeagent.core.java;

import com.codeagent.core.java.JavaProject.MethodInfo;
import com.codeagent.core.java.JavaProject.TypeInfo;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Spring WebFlux 함수형 라우터(RouterFunction 을 돌려주는 메서드)에서 HTTP 진입점을 찾는다.
 * <pre>
 * route(GET("/users/{id}"), handler::get).andRoute(POST("/users"), handler::create)   // RequestPredicates
 * route().GET("/run", handler::run).path("/api", b -&gt; b.DELETE("/x", handler::remove)).build()   // 빌더
 * nest(path("/v2"), route(GET("/y"), h::y))   // RouterFunctions.nest
 * </pre>
 * 처리 함수는 메서드 참조나, 람다 안에서 처음 부르는 메서드다. 찾지 못하면 라우터 메서드 자체로 잇는다.
 */
public final class WebFluxRouteDetector {

    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    /** @param handler 처리 메서드 (못 찾으면 null) */
    public record RouteEntry(String httpMethod, String path, MethodInfo router, MethodInfo handler) {

        public MethodInfo target() {
            return handler != null ? handler : router;
        }

        public String qualifiedName() {
            return httpMethod + " " + path + " -> " + target().key();
        }
    }

    public List<RouteEntry> detect(JavaProject project, JavaGraphSupport support, TypeInfo type) {
        List<RouteEntry> result = new ArrayList<>();
        for (MethodInfo method : type.methods()) {
            if (!(method.decl() instanceof MethodDeclaration decl) || !decl.getType().asString().contains("RouterFunction")) {
                continue;
            }
            for (MethodCallExpr call : decl.findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                if (!HTTP_METHODS.contains(name) || call.getArguments().isEmpty()) {
                    continue;
                }
                String path = path(project, type, call.getArgument(0));
                if (path == null) {
                    continue;
                }
                Expression handlerExpr;
                if (call.getScope().isPresent() && call.getArguments().size() >= 2) {
                    // 빌더: .GET("/x", handler::get) / .GET("/x", accept(JSON), handler::get)
                    handlerExpr = call.getArgument(call.getArguments().size() - 1);
                } else if (call.getArguments().size() == 1) {
                    // 조건: route(GET("/x"), handler) / .andRoute(GET("/x").and(accept(JSON)), handler)
                    handlerExpr = routeHandler(call).orElse(null);
                    if (handlerExpr == null) {
                        continue;
                    }
                } else {
                    continue;
                }
                MethodInfo handler = resolve(project, support, method, handlerExpr);
                result.add(new RouteEntry(name, EntryPointDetector.joinPath(prefix(call, project, type), path), method, handler));
            }
        }
        return result;
    }

    /** GET("/x") 를 첫 인자로 받는 route / andRoute 호출의 두 번째 인자 */
    private static Optional<Expression> routeHandler(MethodCallExpr predicate) {
        Node current = predicate;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().get();
            if (parent instanceof MethodCallExpr route && (route.getNameAsString().equals("route") || route.getNameAsString().equals("andRoute"))
                    && route.getArguments().size() >= 2 && within(route.getArgument(0), predicate)) {
                return Optional.of(route.getArgument(1));
            }
            current = parent;
        }
        return Optional.empty();
    }

    /** .path("/api", b -> …) · .nest(path("/api"), b -> …) · nest(path("/api"), route(…)) 를 바깥부터 이은 경로 */
    private static String prefix(MethodCallExpr call, JavaProject project, TypeInfo type) {
        String prefix = "";
        Node current = call;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().get();
            // 두 번째 인자부터(람다·안쪽 라우터) 안에 있을 때만. 빌더 앞쪽(.GET(…).path(…))은 받는 쪽이라 해당하지 않는다
            if (parent instanceof MethodCallExpr outer && outer.getArguments().size() >= 2
                    && outer.getArguments().stream().skip(1).anyMatch(a -> within(a, call))) {
                String segment = null;
                if (outer.getNameAsString().equals("path")) {
                    segment = path(project, type, outer.getArgument(0));
                } else if (outer.getNameAsString().equals("nest") && outer.getArgument(0) instanceof MethodCallExpr predicate
                        && predicate.getNameAsString().equals("path") && predicate.getArguments().size() == 1) {
                    segment = path(project, type, predicate.getArgument(0));
                }
                if (segment != null) {
                    prefix = EntryPointDetector.joinPath(segment, prefix);
                }
            }
            current = parent;
        }
        return prefix;
    }

    /**
     * 경로 인자: 문자열, 값을 아는 상수(Paths.USERS, API + "/x"), 값을 모르는 상수 모양(대문자 이름·+ 로 이은 식)은 ${식}.
     * accept(JSON) 같은 조건이나 처리 함수가 첫 인자면 경로가 아니다(null)
     */
    private static String path(JavaProject project, TypeInfo type, Expression expr) {
        if (expr instanceof StringLiteralExpr literal) {
            return literal.getValue();
        }
        Optional<String> constant = new JavaConstants(project).value(expr, type);
        if (constant.isPresent()) {
            return constant.get();
        }
        String name = expr instanceof NameExpr n ? n.getNameAsString()
                : expr instanceof com.github.javaparser.ast.expr.FieldAccessExpr f ? f.getNameAsString() : null;
        boolean constantLike = (name != null && name.matches("[A-Z][A-Z0-9_]*"))
                || expr instanceof com.github.javaparser.ast.expr.BinaryExpr;
        return constantLike ? "${" + expr + "}" : null;
    }

    private static boolean within(Node container, Node node) {
        return container == node || container.isAncestorOf(node);
    }

    /** handler::get, this::get, UserHandler::get, req -> handler.create(req) */
    private static MethodInfo resolve(JavaProject project, JavaGraphSupport support, MethodInfo router, Expression handlerExpr) {
        Expression scope;
        String name;
        if (handlerExpr instanceof MethodReferenceExpr reference) {
            scope = reference.getScope();
            name = reference.getIdentifier();
        } else if (handlerExpr instanceof LambdaExpr lambda) {
            Optional<MethodCallExpr> first = lambda.getBody().findAll(MethodCallExpr.class).stream()
                    .filter(c -> c.getScope().isPresent())
                    .findFirst();
            if (first.isEmpty()) {
                return null;
            }
            scope = first.get().getScope().get();
            name = first.get().getNameAsString();
        } else {
            return null;
        }
        String type;
        if (scope instanceof ThisExpr) {
            type = router.owner().fqn();
        } else if (scope instanceof TypeExpr typeExpr) {
            // JavaParser 는 handler::get 의 handler 도 타입 이름으로 읽는다. 소문자면 변수로 본다
            String written = typeExpr.getType().asString();
            type = Character.isLowerCase(written.charAt(0))
                    ? support.typeOfScope(new NameExpr(written), router)
                    : router.owner().resolver().resolve(written);
        } else {
            type = support.typeOfScope(scope, router);
        }
        if (type == null) {
            return null;
        }
        List<MethodInfo> found = project.findMethods(type, name, 1);
        if (found.isEmpty()) {
            found = project.findMethods(type, name, 0);
        }
        return found.isEmpty() ? null : found.get(0);
    }
}
