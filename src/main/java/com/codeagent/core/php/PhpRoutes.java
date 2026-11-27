package com.codeagent.core.php;

import com.codeagent.core.java.EntryPointDetector;
import com.codeagent.core.php.PhpProject.Local;
import com.codeagent.core.php.PhpProject.PhpClass;
import com.codeagent.core.php.PhpProject.PhpFunc;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.codeagent.core.php.PhpFile.field;
import static com.codeagent.core.php.PhpFile.isNull;
import static com.codeagent.core.php.PhpFile.namedChildren;

/**
 * PHP HTTP 진입점.
 * <ul>
 *   <li>Laravel: Route::get·post·…·match·any·resource·apiResource, prefix·middleware·controller·namespace·group 체인과
 *       Route::group([...], fn), routes/api.php 는 /api 아래. 컨트롤러 생성자의 $this->middleware('auth') 도 guards.</li>
 *   <li>Symfony: #[Route] 속성과 @Route 주석 (클래스 접두사 포함), #[IsGranted]·@IsGranted·@Security 는 guards.</li>
 *   <li>Slim·CodeIgniter 4: $app->get('/x', h), $app->group('/api', function ($group) {...})->add(M), $routes->group(..., ['filter' => 'auth'])</li>
 *   <li>그 밖의 PHP 파일: 최상위 코드가 $_GET·$_POST 등을 읽으면 그 파일 경로가 진입점 (public/ 같은 웹 루트는 뗀다)</li>
 * </ul>
 */
final class PhpRoutes {

    record Route(String httpMethod, String path, PhpFunc handler, List<String> guards, String framework) {
    }

    /** 라우트 묶음의 경로·미들웨어·컨트롤러·namespace */
    private record Context(String prefix, List<String> middleware, String controller, String namespace) {
        Context with(String morePrefix, List<String> moreMiddleware, String newController, String newNamespace) {
            List<String> mw = new ArrayList<>(middleware);
            mw.addAll(moreMiddleware);
            return new Context(EntryPointDetector.joinPath(prefix, morePrefix == null ? "" : morePrefix), mw,
                    newController != null ? newController : controller, newNamespace != null ? newNamespace : namespace);
        }
    }

    private static final Set<String> LARAVEL_VERBS = Set.of("get", "post", "put", "patch", "delete", "options", "any");
    private static final Set<String> SLIM_VERBS = Set.of("get", "post", "put", "patch", "delete", "options", "any", "head");
    private static final Set<String> COMMON_MIDDLEWARE = Set.of("web", "api", "bindings", "cors", "throttle", "verified", "signed",
            "csrf", "toolbar", "honeypot", "invalidchars", "secureheaders");
    private static final Pattern SUPERGLOBAL = Pattern.compile("\\$_(GET|POST|REQUEST|COOKIE|FILES)\\b");
    private static final Pattern DOC_ROUTE = Pattern.compile("@Route\\(\\s*\"([^\"]*)\"(?:[^)]*?methods\\s*=\\s*\\{([^}]*)})?");
    private static final Pattern DOC_GUARD = Pattern.compile("@(IsGranted|Security)\\(\\s*\"([^\"]*)\"");
    private static final Pattern AUTH_INCLUDE = Pattern.compile("(?i)(auth|login|session|admin|guard|check|secure)");
    private static final List<String> WEB_ROOTS = List.of("public/", "public_html/", "htdocs/", "www/", "web/", "html/");

    private final PhpProject project;
    private final List<Route> routes = new ArrayList<>();

    PhpRoutes(PhpProject project) {
        this.project = project;
    }

    List<Route> detect() {
        for (PhpFile file : project.files()) {
            boolean apiFile = file.filePath.matches("(.*/)?routes/api\\.php");
            Context root = new Context(apiFile ? "/api" : "", List.of(), null, null);
            statements(file, project.topLevelStatements(file), root, project.main(file));
        }
        symfony();
        slimAndCodeIgniter();
        plainScripts();
        return routes;
    }

    // ---- Laravel ----

    private void statements(PhpFile file, List<TSNode> statements, Context ctx, PhpFunc context) {
        for (TSNode statement : statements) {
            TSNode expr = statement.getType().equals("expression_statement") ? PhpFile.namedChildren(statement).stream().findFirst().orElse(null)
                    : null;
            if (expr != null) {
                laravelChain(file, expr, ctx, context);
            }
        }
    }

    /** Route::prefix('x')->middleware('auth')->group(fn) · Route::get('/x', [C::class, 'm'])->middleware('auth') */
    private void laravelChain(PhpFile file, TSNode expr, Context ctx, PhpFunc context) {
        List<TSNode> chain = new ArrayList<>();
        TSNode current = PhpFile.unwrap(expr);
        while (current != null && current.getType().equals("member_call_expression")) {
            chain.add(0, current);
            current = PhpFile.unwrap(field(current, "object"));
        }
        if (current == null || !current.getType().equals("scoped_call_expression")) {
            return;
        }
        String scope = project.resolveName(file, file.text(field(current, "scope")), null);
        if (!"Illuminate\\Support\\Facades\\Route".equals(scope) && !"Route".equals(scope)) {
            return;
        }
        chain.add(0, current);
        String prefix = null;
        List<String> middleware = new ArrayList<>();
        String controller = null;
        String namespace = null;
        for (TSNode call : chain) {
            String name = file.text(field(call, "name"));
            List<TSNode> args = PhpFile.arguments(call);
            switch (name) {
                case "prefix" -> prefix = args.isEmpty() ? null : file.stringValue(args.get(0));
                case "middleware" -> args.forEach(a -> middleware.addAll(strings(file, a)));
                case "controller" -> controller = args.isEmpty() ? null : classOf(file, args.get(0), ctx);
                case "namespace" -> namespace = args.isEmpty() ? null : file.stringValue(args.get(0));
                default -> {
                }
            }
        }
        for (TSNode call : chain) {
            String name = file.text(field(call, "name"));
            List<TSNode> args = PhpFile.arguments(call);
            if (name.equals("group")) {
                String groupPrefix = prefix;
                List<String> groupMiddleware = new ArrayList<>(middleware);
                String groupNamespace = namespace;
                TSNode body = null;
                for (TSNode arg : args) {
                    if (arg.getType().equals("array_creation_expression")) {
                        // Route::group(['prefix' => 'admin', 'middleware' => 'auth'], fn)
                        String p = file.stringValue(arrayValue(file, arg, "prefix"));
                        if (p != null) {
                            groupPrefix = groupPrefix == null ? p : groupPrefix + "/" + p;
                        }
                        TSNode mw = arrayValue(file, arg, "middleware");
                        if (mw != null) {
                            groupMiddleware.addAll(strings(file, mw));
                        }
                        String ns = file.stringValue(arrayValue(file, arg, "namespace"));
                        if (ns != null) {
                            groupNamespace = ns;
                        }
                    } else if (PhpProject.FUNCTION_TYPES.contains(arg.getType())) {
                        body = field(arg, "body");
                    }
                }
                if (body != null) {
                    Context inner = ctx.with(groupPrefix, groupMiddleware, controller, groupNamespace);
                    statements(file, namedChildren(body), inner, context);
                }
                return;
            }
        }
        Context routeCtx = ctx.with(prefix, middleware, controller, namespace);
        // Route::middleware('auth')->get(...): 체인 중 라우트를 만드는 호출
        TSNode verbCall = chain.stream().filter(c -> {
            String n = file.text(field(c, "name")).toLowerCase(Locale.ROOT);
            return LARAVEL_VERBS.contains(n) || n.equals("match") || n.equals("resource") || n.equals("apiresource");
        }).findFirst().orElse(null);
        if (verbCall == null) {
            return;
        }
        String verb = file.text(field(verbCall, "name")).toLowerCase(Locale.ROOT);
        List<TSNode> args = PhpFile.arguments(verbCall);
        if (LARAVEL_VERBS.contains(verb) && args.size() >= 2) {
            laravelRoute(file, verb.equals("any") ? "ANY" : verb.toUpperCase(Locale.ROOT), file.stringValue(args.get(0)), args.get(1), routeCtx, context);
        } else if (verb.equals("match") && args.size() >= 3) {
            for (String m : strings(file, args.get(0))) {
                laravelRoute(file, m.toUpperCase(Locale.ROOT), file.stringValue(args.get(1)), args.get(2), routeCtx, context);
            }
        } else if ((verb.equals("resource") || verb.equals("apiresource")) && args.size() >= 2) {
            resource(file, file.stringValue(args.get(0)), classOf(file, args.get(1), routeCtx), verb.equals("apiresource"), routeCtx);
        }
    }

    private void laravelRoute(PhpFile file, String method, String uri, TSNode action, Context ctx, PhpFunc context) {
        if (uri == null) {
            return;
        }
        String path = EntryPointDetector.joinPath(ctx.prefix(), uri);
        PhpFunc handler = null;
        if (PhpProject.FUNCTION_TYPES.contains(action.getType())) {
            handler = project.registerAnonymous(file, action, method + " " + path, context);
        } else if (action.getType().equals("array_creation_expression")) {
            List<TSNode> items = arrayItems(action);
            if (items.size() == 2) {
                handler = method(classOf(file, items.get(0), ctx), file.stringValue(items.get(1)));
            }
        } else if (action.getType().equals("class_constant_access_expression")) {
            handler = method(classOf(file, action, ctx), "__invoke");
        } else {
            String value = file.stringValue(action);
            if (value != null && value.contains("@")) {
                String[] parts = value.split("@", 2);
                handler = method(controllerClass(parts[0], ctx), parts[1]);
            } else if (value != null && ctx.controller() != null) {
                handler = method(ctx.controller(), value);
            }
        }
        if (handler != null) {
            add(method, path, handler, guards(ctx.middleware(), handler), "laravel");
        }
    }

    private void resource(PhpFile file, String name, String controller, boolean api, Context ctx) {
        if (name == null || controller == null) {
            return;
        }
        String base = EntryPointDetector.joinPath(ctx.prefix(), name);
        String last = name.substring(name.lastIndexOf('/') + 1).replace('.', '/');
        String param = "{" + (last.endsWith("s") ? last.substring(0, last.length() - 1) : last) + "}";
        String[][] actions = {{"index", "GET", ""}, {"create", "GET", "/create"}, {"store", "POST", ""}, {"show", "GET", "/" + param},
                {"edit", "GET", "/" + param + "/edit"}, {"update", "PUT", "/" + param}, {"destroy", "DELETE", "/" + param}};
        for (String[] action : actions) {
            if (api && (action[0].equals("create") || action[0].equals("edit"))) {
                continue;
            }
            PhpFunc handler = method(controller, action[0]);
            if (handler != null) {
                add(action[1], EntryPointDetector.joinPath(base, action[2]), handler, guards(ctx.middleware(), handler), "laravel");
            }
        }
    }

    /** 'UserController' → App\Http\Controllers\UserController (group namespace 가 있으면 그 아래) */
    private String controllerClass(String name, Context ctx) {
        String ns = ctx.namespace() != null ? ctx.namespace() : "App\\Http\\Controllers";
        String full = name.startsWith("\\") ? name.substring(1) : ns + "\\" + name;
        if (project.cls(full) != null) {
            return full;
        }
        PhpClass bySimple = project.classBySimpleName(name.substring(name.lastIndexOf('\\') + 1));
        return bySimple != null ? bySimple.fqcn : full;
    }

    private String classOf(PhpFile file, TSNode node, Context ctx) {
        node = PhpFile.unwrap(node);
        if (node == null) {
            return null;
        }
        if (node.getType().equals("class_constant_access_expression")) {
            List<TSNode> parts = namedChildren(node);
            if (parts.isEmpty()) {
                return null;
            }
            String name = file.text(parts.get(0));
            String resolved = project.resolveName(file, name, null);
            if (project.cls(resolved) == null) {
                // use 없이 쓴 컨트롤러 이름: 같은 이름의 클래스
                PhpClass bySimple = project.classBySimpleName(name.substring(name.lastIndexOf('\\') + 1));
                return bySimple != null ? bySimple.fqcn : resolved;
            }
            return resolved;
        }
        String value = file.stringValue(node);
        return value == null ? null : ctx == null ? project.resolveName(file, value, null) : controllerClass(value, ctx);
    }

    private PhpFunc method(String fqcn, String name) {
        PhpClass cls = project.cls(fqcn);
        return cls == null || name == null ? null : project.findMethod(cls, name, 0);
    }

    /** 미들웨어 이름 + 컨트롤러 생성자의 $this->middleware('auth') */
    private List<String> guards(List<String> middleware, PhpFunc handler) {
        List<String> result = new ArrayList<>();
        for (String m : middleware) {
            if (!COMMON_MIDDLEWARE.contains(m.replaceAll("[:,].*", "").toLowerCase(Locale.ROOT))) {
                result.add(m);
            }
        }
        if (handler.owner() != null) {
            PhpFunc constructor = project.findMethod(handler.owner(), "__construct", 0);
            if (constructor != null) {
                PhpFile file = constructor.file();
                for (TSNode call : PhpFile.descendants(field(constructor.node(), "body"), Set.of("member_call_expression"), Set.of())) {
                    if (file.text(field(call, "name")).equals("middleware") && file.text(field(call, "object")).equals("$this")) {
                        PhpFile.arguments(call).forEach(a -> result.addAll(strings(file, a)));
                    }
                }
            }
        }
        return result;
    }

    // ---- Symfony ----

    private void symfony() {
        for (PhpFunc fn : project.functions()) {
            PhpClass cls = fn.owner();
            if (cls == null || !fn.node().getType().equals("method_declaration")) {
                continue;
            }
            PhpFile file = fn.file();
            List<String[]> methodRoutes = routeAttributes(file, fn.node());
            if (methodRoutes.isEmpty()) {
                continue;
            }
            List<String[]> classRoutes = routeAttributes(file, cls.node);
            List<String> prefixes = classRoutes.isEmpty() ? List.of("") : classRoutes.stream().map(r -> r[0]).toList();
            List<String> guards = new ArrayList<>(guardAttributes(file, cls.node));
            guards.addAll(guardAttributes(file, fn.node()));
            for (String prefix : prefixes) {
                for (String[] route : methodRoutes) {
                    String path = EntryPointDetector.joinPath(prefix, route[0]);
                    for (String method : route[1].split(",")) {
                        add(method.isBlank() ? "ANY" : method.strip().toUpperCase(Locale.ROOT), path, fn, guards, "symfony");
                    }
                }
            }
        }
    }

    /** [경로, "GET,POST"] 목록: #[Route('/x', methods: ['GET'])] 와 @Route("/x", methods={"GET"}) */
    private List<String[]> routeAttributes(PhpFile file, TSNode declaration) {
        List<String[]> result = new ArrayList<>();
        for (TSNode attribute : attributes(declaration)) {
            String name = attributeName(file, attribute);
            if (!name.equals("Route") && !Set.of("Get", "Post", "Put", "Patch", "Delete").contains(name)) {
                continue;
            }
            TSNode params = field(attribute, "parameters");
            List<TSNode> positional = file.positionalArguments(params);
            TSNode pathNode = file.namedArgument(attribute, "path");
            String path = file.stringValue(pathNode != null ? pathNode : positional.isEmpty() ? null : positional.get(0));
            if (path == null) {
                continue;
            }
            String methods = name.equals("Route") ? "" : name.toUpperCase(Locale.ROOT);
            TSNode methodsNode = file.namedArgument(attribute, "methods");
            if (methodsNode != null) {
                methods = String.join(",", strings(file, methodsNode));
            }
            result.add(new String[]{path, methods});
        }
        String doc = docComment(file, declaration);
        if (doc != null) {
            Matcher m = DOC_ROUTE.matcher(doc);
            while (m.find()) {
                result.add(new String[]{m.group(1), m.group(2) == null ? "" : m.group(2).replace("\"", "").replace(" ", "")});
            }
        }
        return result;
    }

    private List<String> guardAttributes(PhpFile file, TSNode declaration) {
        List<String> result = new ArrayList<>();
        for (TSNode attribute : attributes(declaration)) {
            String name = attributeName(file, attribute);
            if (name.equals("IsGranted") || name.equals("Security")) {
                result.add(file.text(attribute).replaceAll("\\s+", " "));
            }
        }
        String doc = docComment(file, declaration);
        if (doc != null) {
            Matcher m = DOC_GUARD.matcher(doc);
            while (m.find()) {
                result.add(m.group(1) + "(" + m.group(2) + ")");
            }
        }
        return result;
    }

    private static List<TSNode> attributes(TSNode declaration) {
        TSNode list = field(declaration, "attributes");
        return list == null ? List.of() : PhpFile.descendants(list, Set.of("attribute"), Set.of("attribute"));
    }

    private static String attributeName(PhpFile file, TSNode attribute) {
        List<TSNode> parts = namedChildren(attribute);
        String name = parts.isEmpty() ? "" : file.text(parts.get(0));
        return name.substring(name.lastIndexOf('\\') + 1);
    }

    /** 선언 바로 앞의 /** 주석 *&#47; */
    private static String docComment(PhpFile file, TSNode declaration) {
        TSNode previous = declaration.getPrevNamedSibling();
        if (!isNull(previous) && previous.getType().equals("comment") && file.text(previous).startsWith("/**")) {
            return file.text(previous);
        }
        return null;
    }

    // ---- Slim · CodeIgniter 4 ----

    private void slimAndCodeIgniter() {
        for (PhpFile file : project.files()) {
            for (TSNode call : PhpFile.descendants(file.root, Set.of("member_call_expression"), Set.of())) {
                TSNode object = PhpFile.unwrap(field(call, "object"));
                if (object == null || !object.getType().equals("variable_name")) {
                    continue;
                }
                PhpFunc context = project.enclosing(file, call);
                Context router = routerContext(file, object, context, 0);
                if (router == null) {
                    continue;
                }
                String framework = router.controller();
                String verb = file.text(field(call, "name")).toLowerCase(Locale.ROOT);
                List<TSNode> args = PhpFile.arguments(call);
                List<String> routeMiddleware = new ArrayList<>(chainedAdds(file, call));
                if (SLIM_VERBS.contains(verb) && args.size() >= 2) {
                    if (framework.equals("codeigniter") && args.size() >= 3) {
                        routeMiddleware.addAll(filters(file, args.get(2)));
                    }
                    String method = verb.equals("any") ? "ANY" : verb.toUpperCase(Locale.ROOT);
                    slimRoute(file, method, file.stringValue(args.get(0)), args.get(1), router, routeMiddleware, context, framework);
                } else if ((verb.equals("map") || verb.equals("match")) && args.size() >= 3) {
                    for (String m : strings(file, args.get(0))) {
                        slimRoute(file, m.toUpperCase(Locale.ROOT), file.stringValue(args.get(1)), args.get(2), router, routeMiddleware, context, framework);
                    }
                }
            }
        }
    }

    /**
     * 변수가 라우터면 그 묶음. $app/$group/$routes 이름이나 타입(Slim\App, RouteCollectorProxy, RouteCollection),
     * group('/x', function ($group) {...}) 콜백의 파라미터는 바깥 라우터 + 접두사. controller 자리에 프레임워크 이름을 둔다.
     */
    private Context routerContext(PhpFile file, TSNode variable, PhpFunc context, int depth) {
        if (depth > 6 || context == null) {
            return null;
        }
        String name = file.text(variable).substring(1);
        // 이 변수가 group 콜백의 파라미터인가
        for (TSNode current = variable.getParent(); !isNull(current); current = current.getParent()) {
            if (!PhpProject.FUNCTION_TYPES.contains(current.getType())) {
                continue;
            }
            boolean isParam = false;
            for (TSNode param : namedChildren(field(current, "parameters"))) {
                if (file.text(field(param, "name")).equals("$" + name)) {
                    isParam = true;
                }
            }
            if (!isParam) {
                continue;
            }
            TSNode argument = current.getParent();
            TSNode argumentsNode = argument == null ? null : argument.getParent();
            TSNode groupCall = argumentsNode == null ? null : argumentsNode.getParent();
            if (!isNull(groupCall) && groupCall.getType().equals("member_call_expression")
                    && file.text(field(groupCall, "name")).equalsIgnoreCase("group")) {
                TSNode parentObject = PhpFile.unwrap(field(groupCall, "object"));
                if (parentObject != null && parentObject.getType().equals("variable_name")) {
                    Context parent = routerContext(file, parentObject, project.enclosing(file, groupCall), depth + 1);
                    if (parent != null) {
                        List<TSNode> args = PhpFile.arguments(groupCall);
                        String prefix = args.isEmpty() ? null : file.stringValue(args.get(0));
                        List<String> middleware = new ArrayList<>(chainedAdds(file, groupCall));
                        if (parent.controller().equals("codeigniter") && args.size() >= 3) {
                            middleware.addAll(filters(file, args.get(1)));
                        }
                        return parent.with(prefix, middleware, null, null);
                    }
                }
            }
            break;
        }
        Local local = project.local(context, name);
        String type = local == null ? null : local.declaredType();
        if (type == null && local != null && local.value() != null) {
            String valueText = file.text(local.value());
            if (valueText.contains("AppFactory::create") || valueText.matches("new\\s+\\\\?(Slim\\\\)?App\\b.*")) {
                type = "Slim\\App";
            }
        }
        if (type != null) {
            if (type.equals("Slim\\App") || type.endsWith("RouteCollectorProxy") || type.endsWith("RouteCollectorProxyInterface")) {
                return new Context("", appMiddleware(file, name), "slim", null);
            }
            if (type.endsWith("RouteCollection")) {
                return new Context("", List.of(), "codeigniter", null);
            }
            return null;
        }
        if (name.equals("app")) {
            return new Context("", appMiddleware(file, name), "slim", null);
        }
        if (name.equals("routes")) {
            return new Context("", List.of(), "codeigniter", null);
        }
        return null;
    }

    /** 같은 파일의 $app->add(M) (앱 전체 미들웨어) */
    private List<String> appMiddleware(PhpFile file, String variable) {
        List<String> result = new ArrayList<>();
        for (TSNode call : PhpFile.descendants(file.root, Set.of("member_call_expression"), Set.of())) {
            if (file.text(field(call, "name")).equals("add") && file.text(field(call, "object")).equals("$" + variable)) {
                PhpFile.arguments(call).forEach(a -> result.add(middlewareName(file, a)));
            }
        }
        return result;
    }

    /** ...->add(M)->add(N) 처럼 이 호출 뒤에 이어 붙인 미들웨어 */
    private List<String> chainedAdds(PhpFile file, TSNode call) {
        List<String> result = new ArrayList<>();
        TSNode current = call;
        while (!isNull(current.getParent()) && current.getParent().getType().equals("member_call_expression")
                && field(current.getParent(), "object") != null && field(current.getParent(), "object").getStartByte() == current.getStartByte()) {
            TSNode parent = current.getParent();
            if (file.text(field(parent, "name")).equals("add")) {
                PhpFile.arguments(parent).forEach(a -> result.add(middlewareName(file, a)));
            }
            current = parent;
        }
        return result;
    }

    private String middlewareName(PhpFile file, TSNode arg) {
        TSNode node = PhpFile.unwrap(arg);
        if (node != null && node.getType().equals("object_creation_expression")) {
            List<TSNode> parts = namedChildren(node);
            return parts.isEmpty() ? file.text(node) : file.text(parts.get(0));
        }
        if (node != null && node.getType().equals("class_constant_access_expression")) {
            List<TSNode> parts = namedChildren(node);
            return parts.isEmpty() ? file.text(node) : file.text(parts.get(0));
        }
        return file.text(node).replaceAll("\\s+", " ");
    }

    /** CodeIgniter ['filter' => 'auth'] */
    private List<String> filters(PhpFile file, TSNode options) {
        TSNode value = arrayValue(file, PhpFile.unwrap(options), "filter");
        return value == null ? List.of() : strings(file, value).stream().map(f -> "filter:" + f).toList();
    }

    private void slimRoute(PhpFile file, String method, String uri, TSNode action, Context router, List<String> routeMiddleware,
                           PhpFunc context, String framework) {
        if (uri == null) {
            return;
        }
        String path = EntryPointDetector.joinPath(router.prefix(), uri);
        PhpFunc handler = null;
        TSNode node = PhpFile.unwrap(action);
        if (node == null) {
            return;
        }
        if (PhpProject.FUNCTION_TYPES.contains(node.getType())) {
            handler = project.registerAnonymous(file, node, method + " " + path, context);
        } else if (node.getType().equals("array_creation_expression")) {
            List<TSNode> items = arrayItems(node);
            if (items.size() == 2) {
                handler = method(classOf(file, items.get(0), null), file.stringValue(items.get(1)));
            }
        } else if (node.getType().equals("class_constant_access_expression")) {
            handler = method(classOf(file, node, null), "__invoke");
        } else {
            String value = file.stringValue(node);
            if (value != null && value.contains(":")) {
                String[] parts = value.split("::?", 2);
                String cls = framework.equals("codeigniter") && !parts[0].startsWith("\\") ? "App\\Controllers\\" + parts[0]
                        : project.resolveName(file, parts[0], null);
                if (project.cls(cls) == null && project.classBySimpleName(parts[0].substring(parts[0].lastIndexOf('\\') + 1)) != null) {
                    cls = project.classBySimpleName(parts[0].substring(parts[0].lastIndexOf('\\') + 1)).fqcn;
                }
                handler = method(cls, parts[1].replaceAll("/.*", ""));
            }
        }
        if (handler == null) {
            return;
        }
        List<String> guards = new ArrayList<>(routeMiddleware);
        guards.addAll(router.middleware());
        add(method, path, handler, guards, framework);
    }

    // ---- 순수 PHP 파일 ----

    private void plainScripts() {
        Set<String> handled = new HashSet<>();
        routes.forEach(r -> handled.add(r.handler().file().filePath));
        for (PhpFile file : project.files()) {
            PhpFunc main = project.main(file);
            if (main == null || handled.contains(file.filePath) || file.filePath.matches("(.*/)?routes/.*")
                    || file.filePath.startsWith("config/")) {
                continue;
            }
            List<TSNode> statements = project.topLevelStatements(file);
            boolean readsRequest = false;
            List<String> guards = new ArrayList<>();
            for (TSNode statement : statements) {
                String text = file.text(statement);
                if (SUPERGLOBAL.matcher(text).find()) {
                    readsRequest = true;
                }
                for (TSNode include : PhpFile.descendants(statement, Set.of("include_expression", "include_once_expression",
                        "require_expression", "require_once_expression"), Set.of())) {
                    if (AUTH_INCLUDE.matcher(file.text(include)).find()) {
                        guards.add(file.text(include).replaceAll("\\s+", " "));
                    }
                }
                if (text.contains("$_SESSION") && (text.contains("header(") || text.contains("exit") || text.contains("die"))) {
                    guards.add("$_SESSION 확인");
                }
            }
            if (!readsRequest) {
                continue;
            }
            String path = file.filePath;
            for (String webRoot : WEB_ROOTS) {
                if (path.startsWith(webRoot)) {
                    path = path.substring(webRoot.length());
                    break;
                }
            }
            add("ANY", "/" + path, main, List.copyOf(new LinkedHashSet<>(guards)), "php");
        }
    }

    // ---- 공용 ----

    private void add(String method, String path, PhpFunc handler, List<String> guards, String framework) {
        routes.add(new Route(method, path, handler, List.copyOf(new LinkedHashSet<>(guards)), framework));
    }

    /** 'auth' 또는 ['auth', 'can:x'] → 문자열 목록 */
    private List<String> strings(PhpFile file, TSNode node) {
        node = PhpFile.unwrap(node);
        List<String> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        String single = file.stringValue(node);
        if (single != null) {
            result.add(single);
            return result;
        }
        if (node.getType().equals("array_creation_expression")) {
            for (TSNode item : arrayItems(node)) {
                String value = file.stringValue(item);
                if (value != null) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    /** [a, b] 의 값들 (키 없는 원소) */
    private static List<TSNode> arrayItems(TSNode array) {
        List<TSNode> result = new ArrayList<>();
        for (TSNode element : PhpFile.children(array, "array_element_initializer")) {
            List<TSNode> parts = namedChildren(element);
            if (parts.size() == 1) {
                result.add(parts.get(0));
            }
        }
        return result;
    }

    /** ['key' => value] 의 값 */
    private static TSNode arrayValue(PhpFile file, TSNode array, String key) {
        if (array == null || !array.getType().equals("array_creation_expression")) {
            return null;
        }
        for (TSNode element : PhpFile.children(array, "array_element_initializer")) {
            List<TSNode> parts = namedChildren(element);
            if (parts.size() == 2 && key.equals(file.stringValue(parts.get(0)))) {
                return parts.get(1);
            }
        }
        return null;
    }

    /** 라우트 처리기 키 (처리기 파라미터를 요청 값으로 볼 때 쓴다) */
    static Set<String> handlerKeys(List<Route> routes) {
        Set<String> keys = new HashSet<>();
        routes.forEach(r -> keys.add(r.handler().key()));
        return keys;
    }
}
