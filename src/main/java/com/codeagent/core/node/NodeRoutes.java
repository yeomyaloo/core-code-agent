package com.codeagent.core.node;

import com.codeagent.core.java.EntryPointDetector;
import com.codeagent.core.node.NodeProject.External;
import com.codeagent.core.node.NodeProject.FnTarget;
import com.codeagent.core.node.NodeProject.NodeClass;
import com.codeagent.core.node.NodeProject.NodeFunction;
import com.codeagent.core.node.NodeProject.NodeModule;
import com.codeagent.core.node.NodeProject.Target;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.codeagent.core.node.NodeFile.children;
import static com.codeagent.core.node.NodeFile.field;
import static com.codeagent.core.node.NodeFile.isNull;
import static com.codeagent.core.node.NodeFile.namedChildren;

/**
 * Node.js HTTP 진입점을 찾는다.
 * <ul>
 *   <li>Express·Koa·Fastify·Hono·Restify: app·router 객체의 get/post/… 와 route(path).get(…), use(prefix, router)·register(plugin, {prefix})
 *       로 붙인 경로. 라우터를 파라미터로 받는 함수(module.exports = (app) =&gt; …)도 부르는 곳의 라우터로 잇는다</li>
 *   <li>NestJS: @Controller('users') 클래스의 @Get(':id') 메서드, app.setGlobalPrefix</li>
 *   <li>Next.js: pages/api/** 의 기본 export, app/**&#47;route.ts 의 GET·POST… export</li>
 * </ul>
 * 미들웨어(라우트 앞 인자, router.use(fn), NestJS @UseGuards …)는 인증 단서(guards)로 남긴다.
 */
final class NodeRoutes {

    /** @param handlers 경로를 이을 프로젝트 함수 (미들웨어 + 처리기) */
    record Route(String httpMethod, String path, NodeFunction handler, List<NodeFunction> handlers, List<String> guards,
                 String framework) {
    }

    private static final Set<String> VERBS = Set.of("get", "post", "put", "delete", "patch", "options", "head", "all");
    private static final Set<String> NEST_VERBS = Set.of("Get", "Post", "Put", "Delete", "Patch", "Options", "Head", "All");
    /** 인증과 상관없는 흔한 미들웨어 */
    private static final Set<String> COMMON_MIDDLEWARE = Set.of("express.json", "express.urlencoded", "express.static", "cors",
            "helmet", "morgan", "compression", "cookieParser", "bodyParser.json", "bodyParser.urlencoded", "bodyParser",
            "express.raw", "express.text", "logger", "json", "urlencoded");
    /** 보안 헤더·로깅·파서·업로드 같은 기반 미들웨어 (인증 단서가 아니다) */
    private static final List<String> COMMON_PREFIXES = List.of("helmet", "featurePolicy", "express.", "bodyParser", "cookieParser",
            "compression", "morgan", "cors", "i18n", "robots", "serveIndex", "metrics.", "uploadTo", "multer", "swaggerUi", "favicon",
            "serveStatic", "responseTime", "methodOverride", "csurf", "<inline>");
    private static final int MAX_DEPTH = 10;

    private final class Router {
        final String key;
        final String framework;
        String ownPrefix = "";
        final List<String[]> mounts = new ArrayList<>(); // {부모 key, 경로}
        final List<Middleware> middlewares = new ArrayList<>();
        /** app.use('/rest/basket', auth): 이 라우터 기준 경로 아래에만 걸리는 미들웨어 {경로, 이름} */
        final List<String[]> scoped = new ArrayList<>();

        Router(String key, String framework) {
            this.key = key;
            this.framework = framework;
        }
    }

    /**
     * @param handlerText 처리기 인자 그대로 (같은 경로의 자동 생성 라우트에 guards 로 합칠 때 쓴다)
     * @param generated   finale-rest 처럼 라이브러리가 만든 라우트
     */
    private record PendingRoute(String routerKey, String method, String path, NodeFunction handler, List<NodeFunction> handlers,
                                List<String> guards, String handlerText, boolean generated, String file, int offset) {
    }

    /** 라우터 전체에 건 미들웨어와 등록 위치 (offset -1 이면 위치와 상관없이 모든 라우트) */
    private record Middleware(String name, String file, int offset) {
    }

    /** 응답을 직접 보내는 처리기 (미들웨어가 아니라 끝점) */
    private static final Pattern RESPONDS = Pattern.compile("\\b(?:res|response|reply)\\s*\\.\\s*(?:send|sendFile|sendStatus|json|jsonp|render|end|download|"
            + "redirect|attachment|write|status\\s*\\([^)]*\\)\\s*\\.\\s*(?:send|json|end|render))\\b|\\bctx\\.body\\s*=");
    /** next() 로 다음에 넘기는 미들웨어 (next(err) 는 오류 처리라 끝점일 수 있다) */
    private static final Pattern PASS_THROUGH = Pattern.compile("\\bnext\\s*\\(\\s*\\)");
    /** 경로 아래 파일을 그대로 내주는 미들웨어 */
    private static final Pattern STATIC_SERVER = Pattern.compile("(?i)^(serveIndex|express\\.static|serveStatic|static|sirv|koaStatic|"
            + "createProxyMiddleware|proxy)\\b");

    private final NodeProject project;
    private final Map<String, Router> routers = new LinkedHashMap<>();
    private final List<PendingRoute> pending = new ArrayList<>();

    NodeRoutes(NodeProject project) {
        this.project = project;
    }

    List<Route> detect() {
        for (NodeModule module : project.modules()) {
            for (TSNode call : NodeFile.descendants(module.file.root, Set.of("call_expression"), Set.of())) {
                visitCall(module, call);
            }
        }
        List<Route> result = new ArrayList<>();
        // finale-rest 가 만든 라우트와 같은 경로·메서드의 app.get('/api/Users', security.isAuthorized()) 는 따로 끝점이 아니라 그 앞 미들웨어다
        Map<String, List<String>> extraGuards = new HashMap<>();
        Set<String> generated = new HashSet<>();
        pending.stream().filter(PendingRoute::generated).forEach(r -> generated.add(r.routerKey() + " " + r.method() + " " + r.path()));
        List<PendingRoute> emitted = new ArrayList<>();
        for (PendingRoute route : pending) {
            String key = route.routerKey() + " " + route.method() + " " + route.path();
            if (!route.generated() && generated.contains(key)) {
                List<String> merged = extraGuards.computeIfAbsent(key, k -> new ArrayList<>());
                merged.addAll(route.guards());
                merged.add(route.handlerText());
            } else if (route.handler() != null) {
                emitted.add(route);
            }
        }
        for (PendingRoute route : emitted) {
            Router router = routers.get(route.routerKey());
            List<String> guards = new ArrayList<>(route.guards());
            guards.addAll(extraGuards.getOrDefault(route.routerKey() + " " + route.method() + " " + route.path(), List.of()));
            for (Middleware mw : middlewares(router, new HashSet<>())) {
                // 같은 파일에서 라우트보다 뒤에 등록한 app.use 는 그 라우트 앞에서 돌지 않는다
                if (mw.offset() < 0 || !mw.file().equals(route.file()) || mw.offset() < route.offset()) {
                    guards.add(mw.name());
                }
            }
            guards.removeIf(g -> g.isBlank() || isCommon(g));
            for (String prefix : prefixes(router, new HashSet<>())) {
                String full = EntryPointDetector.joinPath(prefix, route.path());
                List<String> withScoped = new ArrayList<>(guards);
                withScoped.addAll(scopedGuards(router, full, new HashSet<>()));
                result.add(new Route(route.method(), full, route.handler(), route.handlers(),
                        List.copyOf(new LinkedHashSet<>(withScoped)), router.framework));
            }
        }
        result.addAll(nestRoutes());
        result.addAll(nextRoutes());
        return result;
    }

    // ---- Express·Koa·Fastify·Hono ----

    private void visitCall(NodeModule module, TSNode call) {
        NodeFile file = module.file;
        TSNode callee = field(call, "function");
        List<TSNode> args = arguments(call);
        NodeFunction context = enclosingFunction(module, call);
        if (callee == null) {
            return;
        }
        if (callee.getType() != null && callee.getType().equals("member_expression")) {
            String property = file.text(field(callee, "property"));
            TSNode object = field(callee, "object");
            if (property.equals("resource") && args.size() == 1 && args.get(0).getType().equals("object")
                    && file.text(object).matches("(?i).*(finale|epilogue).*")) {
                finaleResource(module, context, call, args.get(0));
                return;
            }
            // X.route('/p').get(h).post(h)
            if (VERBS.contains(property) && object != null && object.getType().equals("call_expression")) {
                TSNode routeCall = routeCall(file, object);
                if (routeCall != null) {
                    Router router = routerOf(module, context, field(field(routeCall, "function"), "object"), 0);
                    String path = arguments(routeCall).isEmpty() ? null : file.stringValue(arguments(routeCall).get(0));
                    if (router != null && path != null) {
                        addRoute(router, httpMethod(property), path, args, module, context);
                    }
                    return;
                }
            }
            Router router = routerOf(module, context, object, 0);
            if (router == null) {
                // 라우터를 파라미터로 받는 함수: module.exports = (app) => { app.get(...) }
                router = parameterRouter(module, context, object, property);
            }
            if (router == null) {
                linkRouterArguments(module, context, call, args);
                return;
            }
            if (VERBS.contains(property) && args.size() >= 2) {
                String path = file.stringValue(args.get(0));
                int from = 1;
                if (path != null && args.size() >= 3 && file.stringValue(args.get(1)) != null) {
                    // Koa 이름 붙은 라우트: router.get('user', '/users/:id', handler)
                    path = file.stringValue(args.get(1));
                    from = 2;
                }
                if (path != null) {
                    addRoute(router, httpMethod(property), path, args.subList(from, args.size()), module, context);
                }
            } else if (property.equals("route") && args.size() == 1 && args.get(0).getType().equals("object")) {
                fastifyRoute(module, context, router, args.get(0));
            } else if (property.equals("use")) {
                use(module, context, router, args);
            } else if (property.equals("register") && !args.isEmpty()) {
                register(module, context, router, args);
            } else if (property.equals("prefix") && args.size() == 1 && file.stringValue(args.get(0)) != null) {
                router.ownPrefix = file.stringValue(args.get(0));
            }
            return;
        }
        linkRouterArguments(module, context, call, args);
    }

    /** f(app) / require('./routes')(app): 라우터를 넘겨받는 프로젝트 함수의 파라미터 라우터를 그 라우터 아래에 붙인다 */
    private void linkRouterArguments(NodeModule module, NodeFunction context, TSNode call, List<TSNode> args) {
        if (args.isEmpty()) {
            return;
        }
        Target target = project.evaluate(module, context, field(call, "function"), project.locals(context), 0);
        if (!(target instanceof FnTarget f)) {
            return;
        }
        List<String> params = new ArrayList<>(f.fn().paramTypes().keySet());
        for (int i = 0; i < args.size() && i < params.size(); i++) {
            Router parent = routerOf(module, context, args.get(i), 0);
            if (parent != null) {
                Router child = routers.computeIfAbsent(f.fn().key() + "#param:" + params.get(i), k -> new Router(k, parent.framework));
                child.mounts.add(new String[]{parent.key, ""});
            }
        }
    }

    private void use(NodeModule module, NodeFunction context, Router router, List<TSNode> args) {
        NodeFile file = module.file;
        String path = args.isEmpty() ? null : file.stringValue(args.get(0));
        List<String> paths = new ArrayList<>();
        if (path != null) {
            paths.add(path);
        } else if (!args.isEmpty() && args.get(0).getType().equals("array")) {
            // app.use(['/a', '/b'], fn)
            for (TSNode item : NodeFile.namedChildren(args.get(0))) {
                String value = file.stringValue(item);
                if (value != null) {
                    paths.add(value);
                }
            }
        }
        boolean pathScoped = !paths.isEmpty();
        if (pathScoped && args.size() >= 2 && useEndpoint(module, context, router, paths, args)) {
            return;
        }
        List<Router> children = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (TSNode arg : pathScoped ? args.subList(1, args.size()) : args) {
            Router child = routerOf(module, context, arg, 0);
            if (child != null && child != router) {
                child.mounts.add(new String[]{router.key, path == null ? "" : path});
                children.add(child);
            } else {
                String name = guardName(file, arg);
                if (!isCommon(name)) {
                    names.add(name);
                }
            }
        }
        if (children.isEmpty()) {
            if (!pathScoped) {
                // app.use(auth): 이 라우터의 모든 라우트 앞
                int offset = args.isEmpty() ? 0 : args.get(0).getStartByte();
                names.forEach(n -> router.middlewares.add(new Middleware(n, file.filePath, offset)));
            } else {
                // app.use('/rest/basket', auth): 그 경로 아래 라우트에만
                for (String scope : paths) {
                    names.forEach(n -> router.scoped.add(new String[]{scope, n}));
                }
            }
        } else {
            // app.use('/api/orders', requireAuth, orders): 같이 붙인 미들웨어는 그 라우터 앞
            children.forEach(c -> names.forEach(n -> c.middlewares.add(new Middleware(n, file.filePath, -1))));
        }
    }

    /** fastify.register(plugin, { prefix: '/api' }) */
    private void register(NodeModule module, NodeFunction context, Router router, List<TSNode> args) {
        NodeFile file = module.file;
        if (NodeProject.isFunction(args.get(0))) {
            project.functionFor(file, args.get(0), "<plugin>@L" + NodeFile.line(args.get(0)));
        }
        Target target = project.evaluate(module, context, args.get(0), project.locals(context), 0);
        if (!(target instanceof FnTarget f) || f.fn().paramTypes().isEmpty()) {
            return;
        }
        String prefix = "";
        if (args.size() >= 2 && args.get(1).getType().equals("object")) {
            for (TSNode pair : children(args.get(1), "pair")) {
                if (NodeProject.propertyName(file, field(pair, "key")).equals("prefix")) {
                    String value = file.stringValue(field(pair, "value"));
                    prefix = value == null ? "" : value;
                }
            }
        }
        String param = f.fn().paramTypes().keySet().iterator().next();
        Router child = routers.computeIfAbsent(f.fn().key() + "#param:" + param, k -> new Router(k, router.framework));
        child.mounts.add(new String[]{router.key, prefix});
    }

    /** fastify.route({ method: 'GET', url: '/x', preHandler, handler }) */
    private void fastifyRoute(NodeModule module, NodeFunction context, Router router, TSNode options) {
        NodeFile file = module.file;
        List<String> methods = new ArrayList<>();
        String url = null;
        TSNode handler = null;
        List<TSNode> before = new ArrayList<>();
        for (TSNode pair : children(options, "pair")) {
            String key = NodeProject.propertyName(file, field(pair, "key"));
            TSNode value = field(pair, "value");
            switch (key) {
                case "method" -> {
                    String m = file.stringValue(value);
                    if (m != null) {
                        methods.add(m.toUpperCase());
                    } else {
                        namedChildren(value).forEach(v -> {
                            String s = file.stringValue(v);
                            if (s != null) {
                                methods.add(s.toUpperCase());
                            }
                        });
                    }
                }
                case "url", "path" -> url = file.stringValue(value);
                case "handler" -> handler = value;
                case "preHandler", "onRequest", "preValidation" -> before.add(value);
                default -> {
                }
            }
        }
        if (url == null || handler == null) {
            return;
        }
        List<TSNode> args = new ArrayList<>(before);
        args.add(handler);
        for (String method : methods.isEmpty() ? List.of("GET") : methods) {
            addRoute(router, method, url, args, module, context);
        }
    }

    private static TSNode routeCall(NodeFile file, TSNode call) {
        TSNode current = call;
        for (int i = 0; i < 10 && current != null && current.getType().equals("call_expression"); i++) {
            TSNode callee = field(current, "function");
            if (callee == null || !callee.getType().equals("member_expression")) {
                return null;
            }
            if (file.text(field(callee, "property")).equals("route")) {
                return current;
            }
            current = field(callee, "object");
        }
        return null;
    }

    /** 식이 가리키는 app·router 객체 (선언한 곳으로 구분). 모르면 null */
    private Router routerOf(NodeModule module, NodeFunction context, TSNode expr, int depth) {
        if (isNull(expr) || depth > MAX_DEPTH) {
            return null;
        }
        NodeFile file = module.file;
        switch (expr.getType()) {
            case "identifier", "shorthand_property_identifier" -> {
                String name = file.text(expr);
                // 함수 안 지역 변수
                if (context != null) {
                    Map<String, TSNode> locals = project.locals(context);
                    if (locals.containsKey(name)) {
                        TSNode value = locals.get(name);
                        if (value != null) {
                            return declared(module, context, context.key() + "#" + name, value, depth);
                        }
                        Router param = routers.get(context.key() + "#param:" + name);
                        if (param != null) {
                            return param;
                        }
                        return null;
                    }
                }
                TSNode value = module.values.get(name);
                if (value != null) {
                    return declared(module, module.body, module.file.filePath + "#" + name, value, depth);
                }
                NodeProject.Binding binding = module.imports.get(name);
                if (binding != null) {
                    NodeModule target = project.resolveModule(file, binding.spec());
                    if (target != null) {
                        return exportedRouter(target, binding.imported(), depth + 1);
                    }
                }
                return null;
            }
            case "call_expression" -> {
                TSNode callee = field(expr, "function");
                // require('./routes/users')
                if (callee != null && file.text(callee).equals("require")) {
                    List<TSNode> args = arguments(expr);
                    NodeModule target = args.isEmpty() ? null : project.resolveModule(file, file.stringValue(args.get(0)));
                    return target == null ? null : exportedRouter(target, "*", depth + 1);
                }
                // router.routes() / router.middleware() (Koa)
                if (callee != null && callee.getType().equals("member_expression")) {
                    String property = file.text(field(callee, "property"));
                    if (Set.of("routes", "middleware", "allowedMethods").contains(property)) {
                        return routerOf(module, context, field(callee, "object"), depth + 1);
                    }
                }
                // createRouter() 처럼 라우터를 만들어 돌려주는 함수
                Target target = project.evaluate(module, context, callee, project.locals(context), depth + 1);
                if (target instanceof FnTarget f) {
                    NodeModule fnModule = project.module(f.fn().file());
                    for (TSNode ret : NodeFile.descendants(f.fn().node(), Set.of("return_statement"), NodeProject.FUNCTION_TYPES)) {
                        Router r = routerOf(fnModule, f.fn(), NodeProject.firstNamedChild(ret), depth + 1);
                        if (r != null) {
                            return r;
                        }
                    }
                }
                return factory(module, context, expr, file.filePath + "#L" + NodeFile.line(expr));
            }
            case "new_expression" -> {
                return factory(module, context, expr, file.filePath + "#L" + NodeFile.line(expr));
            }
            case "member_expression", "await_expression", "parenthesized_expression" -> {
                if (expr.getType().equals("member_expression")) {
                    // require('./x').router
                    TSNode object = field(expr, "object");
                    if (object != null && object.getType().equals("call_expression") && file.text(field(object, "function")).equals("require")) {
                        List<TSNode> args = arguments(object);
                        NodeModule target = args.isEmpty() ? null : project.resolveModule(file, file.stringValue(args.get(0)));
                        return target == null ? null : exportedRouter(target, file.text(field(expr, "property")), depth + 1);
                    }
                    return null;
                }
                return routerOf(module, context, NodeProject.firstNamedChild(expr), depth + 1);
            }
            default -> {
                return null;
            }
        }
    }

    /** 변수 선언 값이 app·router 를 만드는 식이면 그 라우터 */
    private Router declared(NodeModule module, NodeFunction context, String key, TSNode value, int depth) {
        Router existing = routers.get(key);
        if (existing != null) {
            return existing;
        }
        if (value.getType().equals("call_expression") || value.getType().equals("new_expression")) {
            Router viaFactory = factory(module, context, value, key);
            if (viaFactory != null) {
                return viaFactory;
            }
        }
        if (value.getType().equals("identifier") || value.getType().equals("call_expression") || value.getType().equals("member_expression")) {
            return routerOf(module, context, value, depth + 1);
        }
        return null;
    }

    /** express() · express.Router() · new Router({prefix}) · fastify() · new Hono() 이면 라우터를 만든다 */
    private Router factory(NodeModule module, NodeFunction context, TSNode expr, String key) {
        Target target = project.evaluate(module, context, expr, project.locals(context), 0);
        if (!(target instanceof External e)) {
            return null;
        }
        String framework = framework(e.name());
        if (framework == null) {
            return null;
        }
        Router router = routers.computeIfAbsent(key, k -> new Router(k, framework));
        // new Router({ prefix: '/api' }) / fastify 는 없음
        if (expr.getType().equals("new_expression")) {
            List<TSNode> args = arguments(expr);
            if (!args.isEmpty() && args.get(0).getType().equals("object")) {
                for (TSNode pair : children(args.get(0), "pair")) {
                    if (NodeProject.propertyName(module.file, field(pair, "key")).equals("prefix")) {
                        String prefix = module.file.stringValue(field(pair, "value"));
                        router.ownPrefix = prefix == null ? "" : prefix;
                    }
                }
            }
        }
        return router;
    }

    private static String framework(String name) {
        if (name.equals("express()") || name.startsWith("express.Router") || name.equals("express.default()")) {
            return "express";
        }
        if (name.startsWith("new @koa/router") || name.startsWith("new koa-router") || name.startsWith("koa-router()")
                || name.startsWith("@koa/router()")) {
            return "koa";
        }
        if (name.startsWith("fastify") && name.endsWith("()")) {
            return "fastify";
        }
        if (name.startsWith("new hono")) {
            return "hono";
        }
        if (name.startsWith("restify.createServer")) {
            return "restify";
        }
        return null;
    }

    /** 모듈이 export 한 라우터 (module.exports = router, export default router) */
    private Router exportedRouter(NodeModule module, String name, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        String local = module.exportNames.get(name);
        if (local != null) {
            TSNode value = module.values.get(local);
            return value == null ? null : declared(module, module.body, module.file.filePath + "#" + local, value, depth + 1);
        }
        for (String key : name.equals("*") || name.equals("default") ? List.of("*", "default") : List.of(name)) {
            TSNode value = module.exportValues.get(key);
            if (value != null) {
                Router r = routerOf(module, module.body, value, depth + 1);
                if (r != null) {
                    return r;
                }
                if (value.getType().equals("call_expression") || value.getType().equals("new_expression")) {
                    return factory(module, module.body, value, module.file.filePath + "#export");
                }
            }
        }
        return null;
    }

    /** 받는 쪽이 함수 파라미터인데 라우트를 등록하면, 그 파라미터를 라우터로 본다 */
    private Router parameterRouter(NodeModule module, NodeFunction context, TSNode object, String property) {
        if (context == null || object == null || !object.getType().equals("identifier")) {
            return null;
        }
        String name = module.file.text(object);
        if (!context.paramTypes().containsKey(name)) {
            return null;
        }
        if (!VERBS.contains(property) && !Set.of("use", "route", "register").contains(property)) {
            return null;
        }
        String type = context.paramTypes().get(name);
        String framework = type != null && type.toLowerCase().contains("fastify") ? "fastify"
                : type != null && type.toLowerCase().contains("koa") ? "koa" : "express";
        return routers.computeIfAbsent(context.key() + "#param:" + name, k -> new Router(k, framework));
    }

    private List<String> prefixes(Router router, Set<String> visiting) {
        if (router == null) {
            return List.of("");
        }
        if (router.mounts.isEmpty() || !visiting.add(router.key)) {
            return List.of(router.ownPrefix);
        }
        Set<String> result = new LinkedHashSet<>();
        for (String[] mount : router.mounts) {
            for (String parent : prefixes(routers.get(mount[0]), visiting)) {
                result.add(EntryPointDetector.joinPath(EntryPointDetector.joinPath(parent, mount[1]), router.ownPrefix));
            }
        }
        visiting.remove(router.key);
        return List.copyOf(result);
    }

    private List<Middleware> middlewares(Router router, Set<String> visiting) {
        if (router == null || !visiting.add(router.key)) {
            return List.of();
        }
        List<Middleware> result = new ArrayList<>(router.middlewares);
        for (String[] mount : router.mounts) {
            // 바깥 라우터의 미들웨어는 마운트 위치를 모르므로 모두 건다
            middlewares(routers.get(mount[0]), visiting).forEach(m -> result.add(new Middleware(m.name(), m.file(), -1)));
        }
        return result;
    }

    /** 라우트 하나: 처리기(마지막 인자)와 미들웨어를 지금 푼다 (안쪽 호출을 보기 전에 이름 없는 처리기에 이름을 붙이려고) */
    private void addRoute(Router router, String method, String path, List<TSNode> args, NodeModule module, NodeFunction context) {
        List<String> guards = new ArrayList<>();
        List<NodeFunction> handlers = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            TSNode arg = args.get(i);
            boolean last = i == args.size() - 1;
            NodeFunction fn = handlerFunction(module, context, method, path, arg, last);
            if (fn != null) {
                handlers.add(fn);
            }
            if (!last && !arg.getType().equals("object")) {
                guards.add(guardName(module.file, arg));
            }
            if (arg.getType().equals("object")) {
                // Fastify 옵션 { preHandler: [auth], onRequest: verify }
                for (TSNode pair : children(arg, "pair")) {
                    String key = NodeProject.propertyName(module.file, field(pair, "key"));
                    if (Set.of("preHandler", "onRequest", "preValidation", "beforeHandler").contains(key)) {
                        guards.add(key + ":" + module.file.text(field(pair, "value")).replaceAll("\\s+", " "));
                    }
                }
            }
        }
        TSNode lastArg = args.isEmpty() ? null : args.get(args.size() - 1);
        String handlerText = lastArg == null ? "" : NodeProject.isFunction(lastArg) ? "inline@L" + NodeFile.line(lastArg)
                : module.file.text(lastArg).replaceAll("\\s+", " ");
        int offset = lastArg == null ? 0 : lastArg.getStartByte();
        pending.add(new PendingRoute(router.key, method, path, handlers.isEmpty() ? null : handlers.get(handlers.size() - 1),
                List.copyOf(handlers), guards, handlerText, false, module.file.filePath, offset));
    }

    /** 라우트 인자 → 프로젝트 함수 (이름 없는 처리기는 "METHOD 경로" 로 이름 붙인다) */
    private NodeFunction handlerFunction(NodeModule module, NodeFunction context, String method, String path, TSNode arg, boolean last) {
        NodeFile file = module.file;
        if (NodeProject.isFunction(arg)) {
            String name = last ? method + " " + path : "<middleware>@L" + NodeFile.line(arg);
            return project.functionFor(file, arg, name);
        }
        Target target = project.evaluate(module, context, arg, project.locals(context), 0);
        if (target instanceof FnTarget f) {
            return f.fn();
        }
        if (arg.getType().equals("call_expression")) {
            // asyncHandler(controller.get) · wrap(fn) · asyncHandler(resetPassword())
            for (TSNode inner : arguments(arg)) {
                Target t = project.evaluate(module, context, inner, project.locals(context), 0);
                if (t instanceof FnTarget f) {
                    return f.fn();
                }
                if (NodeProject.isFunction(inner)) {
                    return project.functionFor(file, inner, method + " " + path);
                }
                if (inner.getType().equals("call_expression")) {
                    NodeFunction factory = handlerFunction(module, context, method, path, inner, last);
                    if (factory != null) {
                        return factory;
                    }
                }
            }
            // login() 처럼 처리기를 만들어 돌려주는 함수: 돌려주는 함수가 그 안에 있으므로 만드는 함수를 처리기로 본다
            Target callee = project.evaluate(module, context, field(arg, "function"), project.locals(context), 0);
            if (callee instanceof FnTarget f && returnsFunction(f.fn())) {
                return f.fn();
            }
        }
        return null;
    }

    /** return (req, res) => {...} · return function (req, res) {...} 가 있는 함수 */
    private static boolean returnsFunction(NodeFunction fn) {
        for (TSNode ret : NodeFile.descendants(fn.node(), Set.of("return_statement"), Set.of())) {
            TSNode value = NodeProject.firstNamedChild(ret);
            if (value != null && NodeProject.isFunction(value)) {
                return true;
            }
        }
        return NodeProject.isFunction(field(fn.node(), "body"));
    }

    /**
     * 경로를 준 app.use 의 마지막 인자가 응답을 직접 보내거나(파일 내주기 등) 정적 서버면 그 경로의 끝점으로 본다.
     * 앞의 인자들은 그 끝점의 guards 이자 경로 아래 미들웨어다.
     */
    private boolean useEndpoint(NodeModule module, NodeFunction context, Router router, List<String> paths, List<TSNode> args) {
        NodeFile file = module.file;
        TSNode last = args.get(args.size() - 1);
        if (routerOf(module, context, last, 0) != null) {
            return false;
        }
        String text = file.text(last).replaceAll("\\s+", " ");
        NodeFunction handler = null;
        if (STATIC_SERVER.matcher(text).find()) {
            handler = project.functionFor(file, last, "<static> " + paths.get(0));
        } else if (NodeProject.isFunction(last)) {
            if (!responds(file.text(last))) {
                return false;
            }
            handler = project.functionFor(file, last, "ANY " + paths.get(0));
        } else {
            NodeFunction resolved = handlerFunction(module, context, "ANY", paths.get(0), last, true);
            if (resolved == null || !responds(resolved.file().text(resolved.node()))) {
                return false;
            }
            handler = resolved;
        }
        List<String> guards = new ArrayList<>();
        for (TSNode arg : args.subList(1, args.size() - 1)) {
            String name = guardName(file, arg);
            if (!isCommon(name)) {
                guards.add(name);
                paths.forEach(p -> router.scoped.add(new String[]{p, name}));
            }
        }
        for (String path : paths) {
            pending.add(new PendingRoute(router.key, "ANY", path, handler, List.of(handler), guards, text, false, file.filePath,
                    last.getStartByte()));
        }
        return true;
    }

    /** 응답을 보내고 다음으로 넘기지 않는다 (next() 로 넘기면 미들웨어, next(err) 는 오류 처리라 괜찮다) */
    private static boolean responds(String code) {
        return RESPONDS.matcher(code).find() && !PASS_THROUGH.matcher(code).find();
    }

    /** finale-rest·epilogue: finale.resource({ model, endpoints: ['/api/Users', '/api/Users/:id'] }) 가 만드는 REST 라우트 */
    private void finaleResource(NodeModule module, NodeFunction context, TSNode call, TSNode config) {
        NodeFile file = module.file;
        TSNode endpoints = null;
        for (TSNode pair : NodeFile.children(config, "pair")) {
            if ("endpoints".equals(NodeProject.propertyName(file, field(pair, "key")))) {
                endpoints = field(pair, "value");
            }
        }
        Router router = finaleRouter(module, context);
        if (endpoints == null || router == null) {
            return;
        }
        NodeFunction handler = project.functionFor(file, call, "<finale resource>");
        for (TSNode item : NodeFile.namedChildren(endpoints)) {
            for (String path : expandTemplate(module, context, call, item)) {
                boolean single = path.matches(".*/:\\w+$");
                for (String method : single ? List.of("GET", "PUT", "PATCH", "DELETE") : List.of("GET", "POST")) {
                    pending.add(new PendingRoute(router.key, method, path, handler, List.of(handler), List.of(), "", true,
                            file.filePath, call.getStartByte()));
                }
            }
        }
    }

    /** finale.initialize({ app }) 의 app */
    private Router finaleRouter(NodeModule module, NodeFunction context) {
        NodeFile file = module.file;
        for (TSNode call : NodeFile.descendants(file.root, Set.of("call_expression"), Set.of())) {
            TSNode callee = field(call, "function");
            List<TSNode> args = arguments(call);
            if (callee == null || !callee.getType().equals("member_expression") || args.isEmpty() || !args.get(0).getType().equals("object")
                    || !file.text(field(callee, "property")).equals("initialize")) {
                continue;
            }
            for (TSNode member : NodeFile.namedChildren(args.get(0))) {
                TSNode value = member.getType().equals("pair") && "app".equals(NodeProject.propertyName(file, field(member, "key")))
                        ? field(member, "value") : member.getType().equals("shorthand_property_identifier") && file.text(member).equals("app")
                        ? member : null;
                if (value != null) {
                    Router router = routerOf(module, enclosingFunction(module, call), value, 0);
                    if (router != null) {
                        return router;
                    }
                }
            }
        }
        return null;
    }

    /**
     * '/api/Users' 그대로, 또는 for (const { name } of models) 안의 `/api/${name}s` 를 배열 원소마다 편다.
     * 배열은 객체 리터럴 배열이어야 한다 (원소의 문자열 속성만 쓴다).
     */
    private List<String> expandTemplate(NodeModule module, NodeFunction context, TSNode at, TSNode item) {
        NodeFile file = module.file;
        String plain = file.stringValue(item);
        if (plain != null) {
            return List.of(plain);
        }
        if (!item.getType().equals("template_string")) {
            return List.of();
        }
        String template = file.text(item);
        template = template.substring(1, template.length() - 1);
        TSNode loop = null;
        for (TSNode current = at.getParent(); !isNull(current); current = current.getParent()) {
            if (current.getType().equals("for_in_statement")) {
                loop = current;
                break;
            }
        }
        if (loop == null) {
            return List.of();
        }
        TSNode right = field(loop, "right");
        TSNode array = null;
        if (right != null && right.getType().equals("identifier")) {
            String name = file.text(right);
            array = context == null ? null : project.locals(context).get(name);
            if (array == null) {
                array = module.values.get(name);
            }
        } else if (right != null && right.getType().equals("array")) {
            array = right;
        }
        if (array == null || !array.getType().equals("array")) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (TSNode element : NodeFile.namedChildren(array)) {
            if (!element.getType().equals("object")) {
                continue;
            }
            Map<String, String> values = new HashMap<>();
            for (TSNode pair : NodeFile.children(element, "pair")) {
                String value = file.stringValue(field(pair, "value"));
                if (value != null) {
                    values.put(NodeProject.propertyName(file, field(pair, "key")), value);
                }
            }
            java.util.regex.Matcher m = Pattern.compile("\\$\\{\\s*([\\w.]+)\\s*}").matcher(template);
            StringBuilder sb = new StringBuilder();
            boolean ok = true;
            while (m.find()) {
                String expr = m.group(1);
                String value = values.get(expr.substring(expr.lastIndexOf('.') + 1));
                if (value == null) {
                    ok = false;
                    break;
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
            }
            if (ok) {
                m.appendTail(sb);
                result.add(sb.toString());
            }
        }
        return result;
    }

    static boolean isCommon(String name) {
        String base = name.replaceAll("\\(.*", "");
        return COMMON_MIDDLEWARE.contains(base) || COMMON_PREFIXES.stream().anyMatch(base::startsWith);
    }

    /** 라우트 전체 경로에 걸리는 경로 지정 미들웨어 (이 라우터와 마운트한 바깥 라우터들) */
    private List<String> scopedGuards(Router router, String fullPath, Set<String> visited) {
        List<String> result = new ArrayList<>();
        if (router == null || !visited.add(router.key)) {
            return result;
        }
        for (String[] scoped : router.scoped) {
            for (String prefix : prefixes(router, new HashSet<>())) {
                if (pathCovers(EntryPointDetector.joinPath(prefix, scoped[0]), fullPath)) {
                    result.add(scoped[1]);
                    break;
                }
            }
        }
        for (String[] mount : router.mounts) {
            result.addAll(scopedGuards(routers.get(mount[0]), fullPath, visited));
        }
        return result;
    }

    /** /rest/basket 은 /rest/basket/:id/checkout 를 덮는다 (:id·* 는 아무 조각) */
    private static boolean pathCovers(String scope, String path) {
        String[] a = scope.split("/");
        String[] b = path.split("/");
        if (a.length > b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i]) && !a[i].startsWith(":") && !a[i].equals("*") && !b[i].startsWith(":") && !a[i].contains("(")) {
                return false;
            }
        }
        return true;
    }

    private static String guardName(NodeFile file, TSNode arg) {
        String text = file.text(arg).replaceAll("\\s+", " ");
        if (NodeProject.isFunction(arg)) {
            return "<inline>";
        }
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    private static String httpMethod(String verb) {
        return verb.equals("all") ? "ANY" : verb.toUpperCase();
    }

    // ---- NestJS ----

    private List<Route> nestRoutes() {
        List<Route> result = new ArrayList<>();
        String globalPrefix = globalPrefix();
        for (NodeClass cls : project.classes()) {
            NodeFile file = cls.file();
            TSNode controller = decorator(file, cls.decorators(), "Controller");
            if (controller == null) {
                continue;
            }
            List<String> classPaths = decoratorPaths(file, controller);
            List<String> classGuards = guardDecorators(file, cls.decorators());
            for (NodeFunction method : cls.methods()) {
                for (TSNode d : method.decorators()) {
                    String name = decoratorName(file, d);
                    if (!NEST_VERBS.contains(name)) {
                        continue;
                    }
                    List<String> guards = new ArrayList<>(classGuards);
                    guards.addAll(guardDecorators(file, method.decorators()));
                    for (String classPath : classPaths) {
                        for (String methodPath : decoratorPaths(file, d)) {
                            String path = EntryPointDetector.joinPath(EntryPointDetector.joinPath(globalPrefix, classPath), methodPath);
                            result.add(new Route(name.equals("All") ? "ANY" : name.toUpperCase(), path, method, List.of(method),
                                    List.copyOf(guards), "nestjs"));
                        }
                    }
                }
            }
        }
        return result;
    }

    private String globalPrefix() {
        for (NodeModule module : project.modules()) {
            for (TSNode call : NodeFile.descendants(module.file.root, Set.of("call_expression"), Set.of())) {
                TSNode callee = field(call, "function");
                if (callee != null && callee.getType().equals("member_expression")
                        && module.file.text(field(callee, "property")).equals("setGlobalPrefix")) {
                    List<TSNode> args = arguments(call);
                    String prefix = args.isEmpty() ? null : module.file.stringValue(args.get(0));
                    if (prefix != null) {
                        return prefix;
                    }
                }
            }
        }
        return "";
    }

    private static TSNode decorator(NodeFile file, List<TSNode> decorators, String name) {
        return decorators.stream().filter(d -> decoratorName(file, d).equals(name)).findFirst().orElse(null);
    }

    /** @Get(':id') / @Controller({ path: 'users' }) / @Get() 의 경로 */
    private static List<String> decoratorPaths(NodeFile file, TSNode decorator) {
        TSNode call = NodeProject.firstNamedChild(decorator);
        if (call == null || !call.getType().equals("call_expression")) {
            return List.of("");
        }
        List<TSNode> args = arguments(call);
        if (args.isEmpty()) {
            return List.of("");
        }
        TSNode first = args.get(0);
        String path = file.stringValue(first);
        if (path != null) {
            return List.of(path);
        }
        if (first.getType().equals("array")) {
            List<String> paths = namedChildren(first).stream().map(file::stringValue).filter(p -> p != null).toList();
            return paths.isEmpty() ? List.of("") : paths;
        }
        if (first.getType().equals("object")) {
            for (TSNode pair : children(first, "pair")) {
                if (NodeProject.propertyName(file, field(pair, "key")).equals("path")) {
                    String value = file.stringValue(field(pair, "value"));
                    return List.of(value == null ? "" : value);
                }
            }
        }
        return List.of("${" + file.text(first) + "}");
    }

    static String decoratorName(NodeFile file, TSNode decorator) {
        TSNode expr = NodeProject.firstNamedChild(decorator);
        if (expr == null) {
            return "";
        }
        TSNode name = expr.getType().equals("call_expression") ? field(expr, "function") : expr;
        String text = file.text(name);
        return text.substring(text.lastIndexOf('.') + 1);
    }

    /** 인증·권한과 관련 있어 보이는 데코레이터 (@UseGuards, @Roles, @Public, @Auth …) */
    private static List<String> guardDecorators(NodeFile file, List<TSNode> decorators) {
        List<String> result = new ArrayList<>();
        for (TSNode d : decorators) {
            String name = decoratorName(file, d);
            if (name.matches("(?i).*(guard|role|public|auth|permission|scope|policy|claim|jwt).*")) {
                result.add(file.text(d).substring(1).replaceAll("\\s+", " "));
            }
        }
        return result;
    }

    // ---- Next.js ----

    private List<Route> nextRoutes() {
        List<Route> result = new ArrayList<>();
        for (NodeModule module : project.modules()) {
            String path = module.file.filePath;
            int pagesApi = path.indexOf("pages/api/");
            if (pagesApi >= 0) {
                Target t = project.exportOf(module, "default", 0);
                if (t instanceof FnTarget f) {
                    String route = "/api/" + nextPath(path.substring(pagesApi + "pages/api/".length()));
                    result.add(new Route("ANY", EntryPointDetector.joinPath("", route), f.fn(), List.of(f.fn()), List.of(), "nextjs"));
                }
                continue;
            }
            if (path.matches("(.*/)?app/.*route\\.[cm]?[jt]s$")) {
                String dir = path.substring(path.indexOf("app/") + 4, path.lastIndexOf('/') + 1);
                String route = "/" + nextPath(dir + "route.ts").replaceAll("/?route$", "");
                for (String verb : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
                    Target t = project.exportOf(module, verb, 0);
                    if (t instanceof FnTarget f) {
                        result.add(new Route(verb, EntryPointDetector.joinPath("", route), f.fn(), List.of(f.fn()), List.of(), "nextjs"));
                    }
                }
            }
        }
        return result;
    }

    /** users/[id].ts → users/:id, index → "", (group)/ 는 뺀다 */
    private static String nextPath(String relative) {
        String noExt = relative.replaceAll("\\.[cm]?[jt]sx?$", "");
        List<String> parts = new ArrayList<>();
        for (String part : noExt.split("/")) {
            if (part.isEmpty() || part.equals("index") || part.startsWith("(") && part.endsWith(")")) {
                continue;
            }
            parts.add(part.replaceAll("^\\[\\.\\.\\.(.+)]$", "*$1").replaceAll("^\\[\\[\\.\\.\\.(.+)]]$", "*$1").replaceAll("^\\[(.+)]$", ":$1"));
        }
        return String.join("/", parts);
    }

    // ---- 도우미 ----

    static List<TSNode> arguments(TSNode call) {
        TSNode args = field(call, "arguments");
        if (args == null) {
            return List.of();
        }
        return namedChildren(args).stream().filter(a -> !a.getType().equals("comment")).toList();
    }

    /** 호출을 감싼 함수 (모듈 최상위면 모듈 본문). 따로 색인하지 않은 콜백은 건너뛰고 바깥 함수를 쓴다 */
    private NodeFunction enclosingFunction(NodeModule module, TSNode node) {
        for (TSNode current = node.getParent(); !isNull(current); current = current.getParent()) {
            if (NodeProject.FUNCTION_TYPES.contains(current.getType()) && project.isRegistered(module.file, current)) {
                return project.functionFor(module.file, current, "");
            }
        }
        return module.body;
    }
}
