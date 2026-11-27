package com.codeagent.core.golang;

import com.codeagent.core.golang.GoProject.Callee;
import com.codeagent.core.golang.GoProject.GoFunc;
import com.codeagent.core.golang.GoProject.GoPackage;
import com.codeagent.core.golang.GoProject.GoType;
import com.codeagent.core.golang.GoProject.Local;
import com.codeagent.core.java.EntryPointDetector;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codeagent.core.golang.GoFile.field;
import static com.codeagent.core.golang.GoFile.isNull;
import static com.codeagent.core.golang.GoFile.namedChildren;

/**
 * Go HTTP 진입점: net/http ServeMux(Go 1.22 의 "GET /items/{id}" 패턴 포함), gin, echo, chi, gorilla/mux, fiber.
 * 라우터 그룹·하위 라우터·Mount·StripPrefix 의 경로를 잇고, 함수 인자로 넘긴 라우터(func Register(r *gin.RouterGroup))도
 * 부르는 곳의 경로를 받는다. 그룹·라우트에 붙인 미들웨어는 guards(인증 단서)로 남긴다.
 */
final class GoRoutes {

    record Route(String httpMethod, String path, GoFunc handler, List<GoFunc> handlers, List<String> guards, String framework) {
    }

    private static final class Router {
        final String key;
        final String framework;
        final List<String[]> mounts = new ArrayList<>();
        final List<String> middlewares = new ArrayList<>();

        Router(String key, String framework) {
            this.key = key;
            this.framework = framework;
        }
    }

    private record Pending(String routerKey, String method, String path, GoFunc handler, List<GoFunc> handlers, List<String> guards) {
    }

    private record Handler(GoFunc fn, List<String> guards) {
    }

    /** 라우터 타입 → 프레임워크 */
    private static final Map<String, String> ROUTER_TYPES = Map.ofEntries(
            Map.entry("github.com/gin-gonic/gin.Engine", "gin"),
            Map.entry("github.com/gin-gonic/gin.RouterGroup", "gin"),
            Map.entry("github.com/gin-gonic/gin.IRouter", "gin"),
            Map.entry("github.com/gin-gonic/gin.IRoutes", "gin"),
            Map.entry("github.com/labstack/echo/v4.Echo", "echo"),
            Map.entry("github.com/labstack/echo/v4.Group", "echo"),
            Map.entry("github.com/go-chi/chi/v5.Mux", "chi"),
            Map.entry("github.com/go-chi/chi/v5.Router", "chi"),
            Map.entry("github.com/go-chi/chi.Mux", "chi"),
            Map.entry("github.com/go-chi/chi.Router", "chi"),
            Map.entry("github.com/gorilla/mux.Router", "gorilla"),
            Map.entry("github.com/gofiber/fiber/v2.App", "fiber"),
            Map.entry("github.com/gofiber/fiber/v2.Router", "fiber"),
            Map.entry("github.com/gofiber/fiber/v2.Group", "fiber"),
            Map.entry("net/http.ServeMux", "net/http"));
    private static final Map<String, String> FACTORIES = Map.of(
            "github.com/gin-gonic/gin.Default", "gin",
            "github.com/gin-gonic/gin.New", "gin",
            "github.com/labstack/echo/v4.New", "echo",
            "github.com/go-chi/chi/v5.NewRouter", "chi",
            "github.com/go-chi/chi.NewRouter", "chi",
            "github.com/gorilla/mux.NewRouter", "gorilla",
            "github.com/gofiber/fiber/v2.New", "fiber",
            "net/http.NewServeMux", "net/http");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "CONNECT", "TRACE");
    private static final Set<String> COMMON_MIDDLEWARE = Set.of("gin.Logger", "gin.Recovery", "middleware.Logger",
            "middleware.Recoverer", "middleware.Recover", "middleware.RequestID", "middleware.RealIP", "middleware.CORS",
            "middleware.CORSWithConfig", "middleware.Timeout", "middleware.Compress", "middleware.Gzip", "middleware.StripSlashes",
            "middleware.CleanPath", "middleware.Heartbeat", "cors.Default", "cors.New", "cors.Handler", "cors.AllowAll",
            "logger.New", "recover.New", "requestid.New", "compress.New", "gzip.Gzip", "handlers.LoggingHandler",
            "handlers.CompressHandler", "handlers.RecoveryHandler");
    private static final String DEFAULT_MUX = "net/http.DefaultServeMux";

    private final GoProject project;
    private final Map<String, Router> routers = new HashMap<>();
    private final Map<String, Router> resolving = new HashMap<>();
    private final Set<String> inProgress = new HashSet<>();
    private final List<Pending> pending = new ArrayList<>();

    GoRoutes(GoProject project) {
        this.project = project;
    }

    List<Route> detect() {
        for (GoFile file : project.files()) {
            for (TSNode call : GoFile.descendants(file.root, Set.of("call_expression"), Set.of())) {
                GoFunc context = project.enclosing(file, call);
                if (context == null) {
                    context = new GoFunc((file.dir.isEmpty() ? "." : file.dir) + "#<init>", "<init>", file, file.root, null, null);
                }
                visit(context, call);
            }
        }
        List<Route> result = new ArrayList<>();
        for (Pending route : pending) {
            Router router = routers.get(route.routerKey());
            List<String> guards = new ArrayList<>(route.guards());
            guards.addAll(middlewares(router, new HashSet<>()));
            for (String prefix : prefixes(router, new HashSet<>())) {
                result.add(new Route(route.method(), EntryPointDetector.joinPath(prefix, route.path()), route.handler(), route.handlers(),
                        List.copyOf(new LinkedHashSet<>(guards)), router.framework));
            }
        }
        return result;
    }

    // ---- 호출 하나 ----

    private void visit(GoFunc ctx, TSNode call) {
        GoFile file = ctx.file();
        TSNode function = GoFile.unwrap(field(call, "function"));
        List<TSNode> args = namedChildren(field(call, "arguments"));
        linkRouterArguments(ctx, call, args);
        if (function == null || !function.getType().equals("selector_expression")) {
            return;
        }
        String method = file.text(field(function, "field"));
        TSNode operand = field(function, "operand");
        String importPath = project.importOf(ctx, operand);
        Router router;
        if ("net/http".equals(importPath) && (method.equals("HandleFunc") || method.equals("Handle"))) {
            router = routers.computeIfAbsent(DEFAULT_MUX, k -> new Router(k, "net/http"));
        } else if (importPath != null) {
            return;
        } else {
            router = routerOf(ctx, operand, 0);
        }
        if (router == null) {
            return;
        }
        switch (router.framework) {
            case "gin" -> gin(ctx, router, method, args);
            case "echo" -> echo(ctx, router, method, args);
            case "chi" -> chi(ctx, router, method, args);
            case "gorilla" -> gorilla(ctx, router, method, args, call);
            case "fiber" -> fiber(ctx, router, method, args);
            default -> netHttp(ctx, router, method, args);
        }
    }

    private void gin(GoFunc ctx, Router router, String method, List<TSNode> args) {
        String upper = method.toUpperCase();
        if (HTTP_METHODS.contains(method) || method.equals("Any")) {
            route(ctx, router, method.equals("Any") ? "ANY" : upper, path(ctx, args, 0), args.subList(Math.min(1, args.size()), args.size()), true);
        } else if (method.equals("Handle") && args.size() >= 2) {
            route(ctx, router, methodName(ctx, args.get(0)), path(ctx, args, 1), args.subList(2, args.size()), true);
        } else if (method.equals("Use")) {
            middleware(ctx, router, args);
        }
    }

    private void echo(GoFunc ctx, Router router, String method, List<TSNode> args) {
        if (HTTP_METHODS.contains(method) || method.equals("Any")) {
            // echo 는 처리기가 먼저, 미들웨어가 뒤에 온다: e.GET(path, h, mw...)
            route(ctx, router, method.equals("Any") ? "ANY" : method, path(ctx, args, 0), args.subList(Math.min(1, args.size()), args.size()), false);
        } else if (method.equals("Add") && args.size() >= 3) {
            route(ctx, router, methodName(ctx, args.get(0)), path(ctx, args, 1), args.subList(2, args.size()), false);
        } else if (method.equals("Use")) {
            middleware(ctx, router, args);
        }
    }

    private void chi(GoFunc ctx, Router router, String method, List<TSNode> args) {
        String upper = method.toUpperCase();
        switch (method) {
            case "Get", "Post", "Put", "Delete", "Patch", "Head", "Options", "Connect", "Trace" ->
                    route(ctx, router, upper, path(ctx, args, 0), args.subList(Math.min(1, args.size()), args.size()), true);
            case "Handle", "HandleFunc" -> mountOrRoute(ctx, router, "ANY", path(ctx, args, 0), args.subList(Math.min(1, args.size()), args.size()));
            case "Method", "MethodFunc" -> {
                if (args.size() >= 3) {
                    route(ctx, router, methodName(ctx, args.get(0)), path(ctx, args, 1), args.subList(2, args.size()), true);
                }
            }
            case "Mount" -> mountOrRoute(ctx, router, "ANY", path(ctx, args, 0), args.subList(Math.min(1, args.size()), args.size()));
            case "Route" -> callbackRouter(ctx, router, path(ctx, args, 0), args, "chi");
            case "Group" -> callbackRouter(ctx, router, "", args, "chi");
            case "Use" -> middleware(ctx, router, args);
            default -> {
            }
        }
    }

    private void gorilla(GoFunc ctx, Router router, String method, List<TSNode> args, TSNode call) {
        if (method.equals("HandleFunc") || method.equals("Handle")) {
            List<String> methods = chainedMethods(ctx, call);
            for (String m : methods) {
                mountOrRoute(ctx, router, m, path(ctx, args, 0), args.subList(Math.min(1, args.size()), args.size()));
            }
        } else if (method.equals("Use")) {
            middleware(ctx, router, args);
        }
    }

    private void fiber(GoFunc ctx, Router router, String method, List<TSNode> args) {
        switch (method) {
            case "Get", "Post", "Put", "Delete", "Patch", "Head", "Options", "Connect", "Trace", "All" -> route(ctx, router,
                    method.equals("All") ? "ANY" : method.toUpperCase(), path(ctx, args, 0), args.subList(Math.min(1, args.size()), args.size()), true);
            case "Add" -> {
                if (args.size() >= 3) {
                    route(ctx, router, methodName(ctx, args.get(0)), path(ctx, args, 1), args.subList(2, args.size()), true);
                }
            }
            case "Route" -> callbackRouter(ctx, router, path(ctx, args, 0), args, "fiber");
            case "Use" -> {
                if (!args.isEmpty() && GoFile.isString(args.get(0))) {
                    // app.Use("/api", sub) 는 하위 앱 마운트, app.Use("/api", auth) 는 그 경로에만 걸려서 모른다
                    for (TSNode arg : args.subList(1, args.size())) {
                        Router child = routerOf(ctx, arg, 0);
                        if (child != null && child != router) {
                            child.mounts.add(new String[]{router.key, path(ctx, args, 0)});
                        }
                    }
                } else {
                    middleware(ctx, router, args);
                }
            }
            default -> {
            }
        }
    }

    private void netHttp(GoFunc ctx, Router router, String method, List<TSNode> args) {
        if (!method.equals("HandleFunc") && !method.equals("Handle") || args.size() < 2) {
            return;
        }
        String pattern = path(ctx, args, 0);
        if (pattern == null) {
            return;
        }
        // Go 1.22: "GET /items/{id}", "example.com/x" 같은 호스트는 뗀다
        String httpMethod = "ANY";
        String path = pattern.strip();
        int space = path.indexOf(' ');
        if (space > 0 && HTTP_METHODS.contains(path.substring(0, space))) {
            httpMethod = path.substring(0, space);
            path = path.substring(space).strip();
        }
        if (!path.startsWith("/") && path.contains("/")) {
            path = path.substring(path.indexOf('/'));
        }
        mountOrRoute(ctx, router, httpMethod, path, args.subList(1, args.size()));
    }

    // ---- 라우트·마운트 ----

    /**
     * 라우트 하나. handlerLast 면 마지막 인자가 처리기이고 앞은 미들웨어(gin·chi·fiber),
     * 아니면 첫 인자가 처리기이고 뒤가 미들웨어(echo)
     */
    private void route(GoFunc ctx, Router router, String method, String path, List<TSNode> args, boolean handlerLast) {
        if (path == null || method == null || args.isEmpty()) {
            return;
        }
        int handlerIndex = handlerLast ? args.size() - 1 : 0;
        Handler handler = handlerOf(ctx, args.get(handlerIndex), method, path, 0);
        if (handler == null) {
            return;
        }
        List<String> guards = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (i != handlerIndex) {
                String name = guardName(ctx, args.get(i));
                if (name != null && !isCommon(name)) {
                    guards.add(name);
                }
            }
        }
        guards.addAll(handler.guards());
        pending.add(new Pending(router.key, method, path, handler.fn(), List.of(handler.fn()), guards));
    }

    /** Handle·Mount: 하위 라우터면 경로 아래에 잇고, 아니면 라우트 */
    private void mountOrRoute(GoFunc ctx, Router router, String method, String path, List<TSNode> args) {
        if (path == null || args.isEmpty()) {
            return;
        }
        TSNode target = args.get(args.size() - 1);
        List<String> guards = new ArrayList<>();
        String strip = null;
        // 미들웨어(auth(sub))·http.StripPrefix("/api", sub) 를 벗긴다
        for (int i = 0; i < 6; i++) {
            TSNode unwrapped = GoFile.unwrap(target);
            if (unwrapped == null || !unwrapped.getType().equals("call_expression") || routerOf(ctx, unwrapped, 0) != null) {
                break;
            }
            List<TSNode> inner = namedChildren(field(unwrapped, "arguments"));
            String calleeText = ctx.file().text(field(unwrapped, "function"));
            if (inner.isEmpty()) {
                break;
            }
            if (calleeText.endsWith("StripPrefix")) {
                strip = ctx.file().stringValue(inner.get(0));
            } else if (inner.stream().noneMatch(a -> routerOf(ctx, a, 0) != null)) {
                break;
            } else {
                guards.add(calleeText);
            }
            target = inner.get(inner.size() - 1);
        }
        Router child = routerOf(ctx, target, 0);
        if (child != null && child != router) {
            String prefix = router.framework.equals("net/http") || router.framework.equals("gorilla") ? (strip == null ? "" : strip) : path;
            child.mounts.add(new String[]{router.key, prefix});
            guards.stream().filter(g -> !isCommon(g)).forEach(child.middlewares::add);
            return;
        }
        route(ctx, router, method, path, args, true);
    }

    /** chi r.Route("/x", func(r chi.Router) {...}) · r.Group(func(r chi.Router) {...}) · fiber app.Route */
    private void callbackRouter(GoFunc ctx, Router router, String prefix, List<TSNode> args, String framework) {
        for (TSNode arg : args) {
            TSNode unwrapped = GoFile.unwrap(arg);
            if (unwrapped != null && unwrapped.getType().equals("func_literal")) {
                GoFunc literal = project.register(ctx.file(), unwrapped, "<routes>@L" + GoFile.line(unwrapped), null, ctx);
                String param = firstParameter(literal);
                if (param != null) {
                    Router child = routers.computeIfAbsent(literal.key() + ":" + param, k -> new Router(k, framework));
                    child.mounts.add(new String[]{router.key, prefix == null ? "" : prefix});
                }
            }
        }
    }

    private String firstParameter(GoFunc fn) {
        for (TSNode param : namedChildren(field(fn.node(), "parameters"))) {
            List<TSNode> names = GoFile.fields(param, "name");
            if (!names.isEmpty()) {
                return fn.file().text(names.get(0));
            }
        }
        return null;
    }

    private void middleware(GoFunc ctx, Router router, List<TSNode> args) {
        for (TSNode arg : args) {
            String name = guardName(ctx, arg);
            if (name != null && !isCommon(name)) {
                router.middlewares.add(name);
            }
        }
    }

    /** f(r) 처럼 라우터를 인자로 넘기면, f 의 라우터 파라미터는 r 아래에 있다 */
    private void linkRouterArguments(GoFunc ctx, TSNode call, List<TSNode> args) {
        if (args.isEmpty()) {
            return;
        }
        Callee callee = project.callee(ctx, call);
        for (GoFunc target : callee.functions()) {
            Map<String, String> params = project.parameters(target);
            for (int i = 0; i < args.size(); i++) {
                String name = project.parameterName(target, i);
                String type = name == null ? null : params.get(name);
                if (type == null || !ROUTER_TYPES.containsKey(type)) {
                    continue;
                }
                Router argument = routerOf(ctx, args.get(i), 0);
                if (argument != null) {
                    Router param = routers.computeIfAbsent(target.key() + ":" + name, k -> new Router(k, ROUTER_TYPES.get(type)));
                    if (param != argument && param.mounts.stream().noneMatch(m -> m[0].equals(argument.key))) {
                        param.mounts.add(new String[]{argument.key, ""});
                    }
                }
            }
        }
    }

    // ---- 라우터 찾기 ----

    private Router routerOf(GoFunc ctx, TSNode expr, int depth) {
        expr = GoFile.unwrap(expr);
        if (expr == null || depth > 6) {
            return null;
        }
        GoFile file = ctx.file();
        switch (expr.getType()) {
            case "identifier" -> {
                String name = file.text(expr);
                for (GoFunc owner = ctx; owner != null; owner = owner.parent()) {
                    Local local = project.locals(owner).get(name);
                    if (local != null) {
                        return localRouter(owner, name, local, depth);
                    }
                }
                GoPackage pkg = project.packageOf(file);
                Local var = pkg == null ? null : pkg.vars.get(name);
                if (var != null) {
                    GoFunc init = new GoFunc((file.dir.isEmpty() ? "." : file.dir) + "#<init>", "<init>", var.file(), var.file().root, null, null);
                    return localRouter(init, pkg.path + "." + name, var, depth);
                }
                return null;
            }
            case "selector_expression" -> {
                TSNode operand = field(expr, "operand");
                String fieldName = file.text(field(expr, "field"));
                String importPath = project.importOf(ctx, operand);
                if (importPath != null) {
                    return "net/http".equals(importPath) && fieldName.equals("DefaultServeMux")
                            ? routers.computeIfAbsent(DEFAULT_MUX, k -> new Router(k, "net/http")) : null;
                }
                // 구조체 필드에 든 라우터 (s.router): 타입 + 필드 이름 하나로 본다
                String owner = project.typeOf(ctx, operand);
                String type = owner == null ? null : project.fieldType(owner, fieldName, 0);
                if (type != null && ROUTER_TYPES.containsKey(type)) {
                    return routers.computeIfAbsent("field:" + owner + "." + fieldName, k -> new Router(k, ROUTER_TYPES.get(type)));
                }
                return null;
            }
            case "call_expression" -> {
                return callRouter(ctx, expr, depth);
            }
            case "unary_expression" -> {
                return routerOf(ctx, field(expr, "operand"), depth + 1);
            }
            default -> {
                return null;
            }
        }
    }

    private Router localRouter(GoFunc owner, String name, Local local, int depth) {
        String key = owner.key() + ":" + name;
        Router existing = routers.get(key);
        if (existing != null) {
            return existing;
        }
        if (local.value() != null) {
            if (!inProgress.add(key)) {
                return null;
            }
            try {
                Router viaValue = routerOf(owner, local.value(), depth + 1);
                if (viaValue != null) {
                    return viaValue;
                }
            } finally {
                inProgress.remove(key);
            }
        }
        String type = local.declaredType();
        if (type != null && ROUTER_TYPES.containsKey(type)) {
            return routers.computeIfAbsent(key, k -> new Router(k, ROUTER_TYPES.get(type)));
        }
        return null;
    }

    private Router callRouter(GoFunc ctx, TSNode call, int depth) {
        GoFile file = ctx.file();
        String callKey = file.filePath + "@" + call.getStartByte();
        Router existing = resolving.get(callKey);
        if (existing != null) {
            return existing;
        }
        TSNode function = GoFile.unwrap(field(call, "function"));
        List<TSNode> args = namedChildren(field(call, "arguments"));
        Callee callee = project.callee(ctx, call);
        if (callee.external() != null) {
            String framework = FACTORIES.get(callee.external());
            if (framework != null) {
                return remember(callKey, new Router(callKey, framework));
            }
        }
        if (function != null && function.getType().equals("selector_expression")) {
            String method = file.text(field(function, "field"));
            TSNode operand = field(function, "operand");
            if (project.importOf(ctx, operand) == null) {
                Router parent = routerOf(ctx, operand, depth + 1);
                if (parent != null) {
                    switch (method) {
                        case "Group" -> {
                            if (!parent.framework.equals("chi")) {
                                Router child = remember(callKey, new Router(callKey, parent.framework));
                                String prefix = path(ctx, args, 0);
                                child.mounts.add(new String[]{parent.key, prefix == null ? "" : prefix});
                                middleware(ctx, child, args.subList(Math.min(1, args.size()), args.size()));
                                return child;
                            }
                            Router child = remember(callKey, new Router(callKey, "chi"));
                            child.mounts.add(new String[]{parent.key, ""});
                            return child;
                        }
                        case "With" -> {
                            Router child = remember(callKey, new Router(callKey, parent.framework));
                            child.mounts.add(new String[]{parent.key, ""});
                            middleware(ctx, child, args);
                            return child;
                        }
                        default -> {
                        }
                    }
                }
                // gorilla: r.PathPrefix("/api").Subrouter()
                if (method.equals("Subrouter")) {
                    TSNode inner = GoFile.unwrap(operand);
                    if (inner != null && inner.getType().equals("call_expression")) {
                        TSNode innerFunction = field(inner, "function");
                        if (innerFunction != null && innerFunction.getType().equals("selector_expression")) {
                            Router base = routerOf(ctx, field(innerFunction, "operand"), depth + 1);
                            if (base != null) {
                                Router child = remember(callKey, new Router(callKey, base.framework));
                                String innerMethod = file.text(field(innerFunction, "field"));
                                String prefix = innerMethod.equals("PathPrefix") ? path(ctx, namedChildren(field(inner, "arguments")), 0) : "";
                                child.mounts.add(new String[]{base.key, prefix == null ? "" : prefix});
                                return child;
                            }
                        }
                    }
                }
            }
        }
        // 라우터를 만들어 돌려주는 프로젝트 함수: return r
        for (GoFunc target : callee.functions()) {
            String result = project.resultType(target);
            if (result == null || !ROUTER_TYPES.containsKey(result) || !inProgress.add(target.key())) {
                continue;
            }
            try {
                for (TSNode ret : GoFile.descendants(field(target.node(), "body"), Set.of("return_statement"), Set.of("func_literal"))) {
                    List<TSNode> values = namedChildren(GoFile.firstChild(ret, "expression_list"));
                    if (!values.isEmpty()) {
                        Router router = routerOf(target, values.get(0), depth + 1);
                        if (router != null) {
                            return router;
                        }
                    }
                }
            } finally {
                inProgress.remove(target.key());
            }
        }
        return null;
    }

    private Router remember(String key, Router router) {
        resolving.put(key, router);
        routers.put(key, router);
        return router;
    }

    // ---- 처리기 ----

    private Handler handlerOf(GoFunc ctx, TSNode expr, String method, String path, int depth) {
        expr = GoFile.unwrap(expr);
        if (expr == null || depth > 5) {
            return null;
        }
        GoFile file = ctx.file();
        if (expr.getType().equals("func_literal")) {
            return new Handler(project.register(file, expr, method + " " + path, null, ctx), List.of());
        }
        GoFunc value = project.functionValue(ctx, expr);
        if (value != null) {
            return new Handler(value, List.of());
        }
        if (expr.getType().equals("call_expression")) {
            List<TSNode> args = namedChildren(field(expr, "arguments"));
            String calleeText = file.text(field(expr, "function"));
            if (calleeText.endsWith("HandlerFunc") && args.size() == 1 || calleeText.endsWith("StripPrefix") && args.size() == 2
                    || calleeText.endsWith("WrapF") || calleeText.endsWith("WrapH")) {
                return handlerOf(ctx, args.get(args.size() - 1), method, path, depth + 1);
            }
            // auth(h) · alice.New(a).Then(h) · chi.Chain(a).HandlerFunc(h): 처리기가 인자에 있으면 감싼 쪽은 미들웨어
            for (int i = args.size() - 1; i >= 0; i--) {
                Handler inner = handlerOf(ctx, args.get(i), method, path, depth + 1);
                if (inner != null) {
                    List<String> guards = new ArrayList<>();
                    guards.add(guardCallee(ctx, expr));
                    guards.addAll(inner.guards());
                    return new Handler(inner.fn(), guards);
                }
            }
            // h.GetUser() 처럼 처리기를 만들어 돌려주는 함수: 그 함수를 처리기로 본다
            List<GoFunc> functions = project.callee(ctx, expr).functions();
            if (!functions.isEmpty()) {
                return new Handler(functions.get(0), List.of());
            }
            return null;
        }
        // ServeHTTP 를 가진 타입의 값
        GoType type = project.type(project.typeOf(ctx, expr));
        GoFunc serve = type == null ? null : project.findMethod(type, "ServeHTTP", 0);
        return serve == null ? null : new Handler(serve, List.of());
    }

    // ---- 이름 ----

    private String path(GoFunc ctx, List<TSNode> args, int index) {
        if (index >= args.size()) {
            return null;
        }
        TSNode arg = GoFile.unwrap(args.get(index));
        String value = ctx.file().stringValue(arg);
        if (value != null) {
            return value;
        }
        // 상수 이름
        if (arg != null && arg.getType().equals("identifier")) {
            GoPackage pkg = project.packageOf(ctx.file());
            TSNode constant = pkg == null ? null : pkg.consts.get(ctx.file().text(arg));
            return constant == null ? null : ctx.file().stringValue(constant);
        }
        return null;
    }

    private String methodName(GoFunc ctx, TSNode arg) {
        String value = ctx.file().stringValue(GoFile.unwrap(arg));
        if (value != null) {
            return value.toUpperCase();
        }
        String text = ctx.file().text(arg);
        // http.MethodGet → GET
        return text.matches(".*Method[A-Z][a-z]+$") ? text.replaceAll(".*Method", "").toUpperCase() : null;
    }

    /** gorilla r.HandleFunc(...).Methods("GET", "POST") */
    private List<String> chainedMethods(GoFunc ctx, TSNode call) {
        TSNode parent = call.getParent();
        if (!isNull(parent) && parent.getType().equals("selector_expression")
                && ctx.file().text(field(parent, "field")).equals("Methods")) {
            TSNode methodsCall = parent.getParent();
            if (!isNull(methodsCall) && methodsCall.getType().equals("call_expression")) {
                List<String> methods = new ArrayList<>();
                for (TSNode arg : namedChildren(field(methodsCall, "arguments"))) {
                    String m = methodName(ctx, arg);
                    if (m != null) {
                        methods.add(m);
                    }
                }
                if (!methods.isEmpty()) {
                    return methods;
                }
            }
        }
        return List.of("ANY");
    }

    private String guardName(GoFunc ctx, TSNode arg) {
        String text = ctx.file().text(arg).replaceAll("\\s+", " ");
        if (text.isEmpty() || GoFile.isString(arg)) {
            return null;
        }
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }

    private String guardCallee(GoFunc ctx, TSNode call) {
        return ctx.file().text(field(call, "function")).replaceAll("\\s+", " ");
    }

    private static boolean isCommon(String name) {
        return COMMON_MIDDLEWARE.contains(name.replaceAll("\\(.*", ""));
    }

    // ---- 경로 계산 ----

    private List<String> prefixes(Router router, Set<String> visited) {
        if (!visited.add(router.key) || router.mounts.isEmpty()) {
            return List.of("");
        }
        List<String> result = new ArrayList<>();
        for (String[] mount : router.mounts) {
            Router parent = routers.get(mount[0]);
            if (parent == null) {
                continue;
            }
            for (String prefix : prefixes(parent, new HashSet<>(visited))) {
                String joined = EntryPointDetector.joinPath(prefix, mount[1]);
                if (!result.contains(joined)) {
                    result.add(joined);
                }
            }
        }
        return result.isEmpty() ? List.of("") : result;
    }

    private List<String> middlewares(Router router, Set<String> visited) {
        if (!visited.add(router.key)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(router.middlewares);
        for (String[] mount : router.mounts) {
            Router parent = routers.get(mount[0]);
            if (parent != null) {
                result.addAll(middlewares(parent, visited));
            }
        }
        return result;
    }
}
