package com.codeagent.core.python;

import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.python.PythonModule.ClassInfo;
import com.codeagent.core.python.PythonModule.FunctionInfo;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codeagent.core.python.PythonModule.firstNamedChild;
import static com.codeagent.core.python.PythonModule.isNull;
import static com.codeagent.core.python.PythonModule.namedChildren;

/**
 * Flask 와 Django(Django REST framework 포함) 의 HTTP 진입점. FastAPI 는 {@link PythonGraphBuilder} 가 맡는다.
 * <ul>
 *   <li>Flask: app = Flask(...), bp = Blueprint(..., url_prefix=), app.register_blueprint(bp, url_prefix=) (중첩 포함),
 *       @app.route(methods=[...]) · @bp.get/post/…, app.add_url_rule(rule, view_func=함수 | View.as_view(...)),
 *       Flask-RESTful·RESTX 의 api.add_resource(Resource, "/x") 와 @ns.route("/x") 클래스</li>
 *   <li>Django: settings 의 ROOT_URLCONF(문자열·환경 변수 기본값·.env 값)에서 시작해(없거나 못 찾으면 include 되지 않은 urls 모두에서)
 *       urlpatterns 의 path / re_path / url, include("app.urls" | 모듈 | router.urls | [...]),
 *       함수 뷰, View.as_view() 클래스 뷰, DRF router.register("users", ViewSet) 와 @action</li>
 * </ul>
 * 진입점 props 의 guards 에는 인증·권한과 관련 있을 만한 것(데코레이터, Mixin, permission_classes, before_request)을 적는다.
 * 비어 있다고 인증이 없다는 뜻은 아니다(설정·미들웨어의 전역 인증은 보지 않는다).
 */
final class PythonWebFrameworks {

    private static final Set<String> FLASK_APPS = Set.of("flask.Flask", "flask.app.Flask");
    private static final Set<String> FLASK_BLUEPRINTS = Set.of("flask.Blueprint", "flask.blueprints.Blueprint");
    private static final Set<String> RESTFUL_APIS = Set.of("flask_restful.Api", "flask_restx.Api", "flask_restplus.Api");
    private static final Set<String> RESTX_NAMESPACES = Set.of("flask_restx.Namespace", "flask_restplus.Namespace");
    private static final Set<String> DRF_ROUTERS = Set.of("rest_framework.routers.DefaultRouter", "rest_framework.routers.SimpleRouter");
    /** 정규화할 때 만든 타입이 아니라 변수 자체를 가리킬 앱·라우터 객체 */
    static final Set<String> ROUTER_OBJECT_TYPES;

    static {
        Set<String> all = new HashSet<>();
        all.addAll(FLASK_APPS);
        all.addAll(FLASK_BLUEPRINTS);
        all.addAll(RESTFUL_APIS);
        all.addAll(RESTX_NAMESPACES);
        all.addAll(DRF_ROUTERS);
        ROUTER_OBJECT_TYPES = Set.copyOf(all);
    }

    private static final Map<String, String> FLASK_VERBS = Map.of(
            "route", "", "get", "GET", "post", "POST", "put", "PUT", "delete", "DELETE", "patch", "PATCH");
    private static final List<String> VIEW_VERBS = List.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final Set<String> DJANGO_PATHS = Set.of("django.urls.path", "django.urls.re_path", "django.conf.urls.url",
            "django.urls.conf.path", "django.urls.conf.re_path");
    private static final Set<String> DJANGO_INCLUDES = Set.of("django.urls.include", "django.conf.urls.include", "django.urls.conf.include");

    /** 상속받은 라이브러리 뷰가 처리하는 HTTP 메서드 (마지막 이름으로 맞춘다) */
    private static final Map<String, List<String>> GENERIC_VIEW_VERBS = Map.ofEntries(
            Map.entry("ListAPIView", List.of("GET")), Map.entry("CreateAPIView", List.of("POST")),
            Map.entry("RetrieveAPIView", List.of("GET")), Map.entry("UpdateAPIView", List.of("PUT", "PATCH")),
            Map.entry("DestroyAPIView", List.of("DELETE")), Map.entry("ListCreateAPIView", List.of("GET", "POST")),
            Map.entry("RetrieveUpdateAPIView", List.of("GET", "PUT", "PATCH")),
            Map.entry("RetrieveDestroyAPIView", List.of("GET", "DELETE")),
            Map.entry("RetrieveUpdateDestroyAPIView", List.of("GET", "PUT", "PATCH", "DELETE")),
            Map.entry("ListView", List.of("GET")), Map.entry("DetailView", List.of("GET")), Map.entry("TemplateView", List.of("GET")),
            Map.entry("RedirectView", List.of("GET")), Map.entry("CreateView", List.of("GET", "POST")),
            Map.entry("UpdateView", List.of("GET", "POST")), Map.entry("FormView", List.of("GET", "POST")),
            Map.entry("DeleteView", List.of("GET", "POST")));
    /** DRF ViewSet 동작 → (HTTP 메서드, 상세 경로인지) */
    private static final Map<String, String[]> VIEWSET_ACTIONS = new LinkedHashMap<>();

    static {
        VIEWSET_ACTIONS.put("list", new String[]{"GET", ""});
        VIEWSET_ACTIONS.put("create", new String[]{"POST", ""});
        VIEWSET_ACTIONS.put("retrieve", new String[]{"GET", "{pk}"});
        VIEWSET_ACTIONS.put("update", new String[]{"PUT", "{pk}"});
        VIEWSET_ACTIONS.put("partial_update", new String[]{"PATCH", "{pk}"});
        VIEWSET_ACTIONS.put("destroy", new String[]{"DELETE", "{pk}"});
    }

    /** 상속받은 라이브러리 ViewSet·Mixin 이 주는 동작 */
    private static final Map<String, List<String>> LIBRARY_ACTIONS = Map.of(
            "ModelViewSet", List.of("list", "create", "retrieve", "update", "partial_update", "destroy"),
            "ReadOnlyModelViewSet", List.of("list", "retrieve"),
            "ListModelMixin", List.of("list"), "CreateModelMixin", List.of("create"),
            "RetrieveModelMixin", List.of("retrieve"), "UpdateModelMixin", List.of("update", "partial_update"),
            "DestroyModelMixin", List.of("destroy"));
    /** 라이브러리 뷰가 처리할 때 부르는 프로젝트 쪽 훅 (경로를 이을 곳) */
    private static final List<String> VIEW_HOOKS = List.of("get_queryset", "get_object", "get_serializer_class",
            "perform_create", "perform_update", "perform_destroy", "form_valid", "get_context_data", "dispatch");

    private static final int MAX_DEPTH = 10;

    /** Flask 앱·블루프린트·RESTX 이름공간 */
    private record FlaskObject(String key, String kind, String ownPrefix, List<Mount> mounts, List<String> hooks) {
    }

    /** @param prefix register_blueprint(url_prefix=) 처럼 붙일 때 바꾼 경로 (없으면 null) */
    private record Mount(String parent, String prefix) {
    }

    private final PythonGraphBuilder builder;
    private final java.nio.file.Path root;
    private final List<PythonModule> modules;
    private final CodeGraphDraft draft;
    private final Map<String, FlaskObject> flaskObjects = new LinkedHashMap<>();
    /** DRF 라우터 변수 → register 호출들 */
    private final Map<String, List<TSNode>> drfRegistrations = new LinkedHashMap<>();
    private final Map<String, PythonModule> drfRouterModules = new LinkedHashMap<>();

    PythonWebFrameworks(PythonGraphBuilder builder, java.nio.file.Path root, List<PythonModule> modules, CodeGraphDraft draft) {
        this.builder = builder;
        this.root = root;
        this.modules = modules;
        this.draft = draft;
    }

    void addEntryPoints() {
        modules.forEach(this::collectFlaskObjects);
        modules.forEach(this::collectFlaskMounts);
        modules.forEach(this::collectDrfRouters);
        modules.forEach(this::addFlaskRoutes);
        addDjangoRoutes();
    }

    // ---- Flask ----

    private void collectFlaskObjects(PythonModule module) {
        module.moduleAssignments.forEach((variable, value) -> {
            if (!value.getType().equals("call")) {
                return;
            }
            TSNode function = value.getChildByFieldName("function");
            TSNode args = value.getChildByFieldName("arguments");
            String key = module.name + "." + variable;
            String callee = builder.qualify(module.text(function), module.body, Map.of());
            if (callee != null && (FLASK_APPS.contains(callee) || FLASK_BLUEPRINTS.contains(callee))) {
                String prefix = PythonGraphBuilder.stringValue(module, PythonGraphBuilder.keywordArgument(module, args, "url_prefix"));
                flaskObjects.put(key, new FlaskObject(key, FLASK_APPS.contains(callee) ? "app" : "blueprint",
                        prefix == null ? "" : prefix, new ArrayList<>(), new ArrayList<>()));
            } else if (callee != null && RESTFUL_APIS.contains(callee)) {
                String prefix = PythonGraphBuilder.stringValue(module, PythonGraphBuilder.keywordArgument(module, args, "prefix"));
                FlaskObject api = new FlaskObject(key, "api", prefix == null ? "" : prefix, new ArrayList<>(), new ArrayList<>());
                // Api(app) / Api(bp): 그 앱·블루프린트 아래에 붙는다
                TSNode first = PythonGraphBuilder.firstPositional(args);
                String parent = first == null ? null : builder.qualify(module.text(first), module.body, Map.of());
                if (parent != null) {
                    api.mounts().add(new Mount(parent, null));
                }
                flaskObjects.put(key, api);
            } else if (callee != null && RESTX_NAMESPACES.contains(callee)) {
                flaskObjects.put(key, new FlaskObject(key, "namespace", namespacePath(module, args), new ArrayList<>(), new ArrayList<>()));
            } else if (function.getType().equals("attribute") && module.text(function.getChildByFieldName("attribute")).equals("namespace")) {
                // ns = api.namespace("users", path="/users")
                String parent = builder.qualify(module.text(function.getChildByFieldName("object")), module.body, Map.of());
                if (parent != null && flaskObjects.containsKey(parent)) {
                    FlaskObject ns = new FlaskObject(key, "namespace", namespacePath(module, args), new ArrayList<>(), new ArrayList<>());
                    ns.mounts().add(new Mount(parent, null));
                    flaskObjects.put(key, ns);
                }
            }
        });
    }

    /** Namespace("users", path="/users") 의 경로. path 가 없으면 /이름 */
    private static String namespacePath(PythonModule module, TSNode args) {
        String path = PythonGraphBuilder.stringValue(module, PythonGraphBuilder.keywordArgument(module, args, "path"));
        if (path != null) {
            return path;
        }
        String name = PythonGraphBuilder.stringValue(module, PythonGraphBuilder.firstPositional(args));
        return name == null ? "" : "/" + name;
    }

    /** app.register_blueprint(bp, url_prefix=…), api.add_namespace(ns, path=…), @bp.before_request */
    private void collectFlaskMounts(PythonModule module) {
        for (TSNode call : PythonGraphBuilder.calls(module.root, true)) {
            TSNode function = call.getChildByFieldName("function");
            if (!function.getType().equals("attribute")) {
                continue;
            }
            String method = module.text(function.getChildByFieldName("attribute"));
            if (!method.equals("register_blueprint") && !method.equals("add_namespace")) {
                continue;
            }
            String parent = builder.qualify(module.text(function.getChildByFieldName("object")), module.body, Map.of());
            TSNode args = call.getChildByFieldName("arguments");
            TSNode first = PythonGraphBuilder.firstPositional(args);
            FlaskObject child = first == null ? null : flaskObjects.get(builder.qualify(module.text(first), module.body, Map.of()));
            if (parent == null || child == null) {
                continue;
            }
            String prefix = PythonGraphBuilder.stringValue(module, PythonGraphBuilder.keywordArgument(module, args,
                    method.equals("register_blueprint") ? "url_prefix" : "path"));
            child.mounts().add(new Mount(parent, prefix));
        }
        for (FunctionInfo fn : module.functions.values()) {
            for (String decorator : fn.decorators()) {
                int dot = decorator.lastIndexOf('.');
                if (dot > 0 && (decorator.endsWith(".before_request") || decorator.endsWith(".before_app_request"))) {
                    FlaskObject owner = flaskObjects.get(builder.qualify(decorator.substring(0, dot), module.body, Map.of()));
                    if (owner != null) {
                        owner.hooks().add("before_request:" + fn.name());
                    }
                }
            }
        }
    }

    /** 앱에서 본 경로들 (블루프린트를 여러 번 붙이면 여럿) */
    private List<String> flaskPrefixes(FlaskObject object, int depth) {
        if (object.mounts().isEmpty() || depth > MAX_DEPTH) {
            return List.of(object.ownPrefix());
        }
        List<String> result = new ArrayList<>();
        for (Mount mount : object.mounts()) {
            FlaskObject parent = flaskObjects.get(mount.parent());
            List<String> parentPrefixes = parent == null ? List.of("") : flaskPrefixes(parent, depth + 1);
            String own = mount.prefix() != null ? mount.prefix() : object.ownPrefix();
            parentPrefixes.forEach(p -> result.add(PythonGraphBuilder.joinPath(p, own)));
        }
        return result;
    }

    /** 앱 전체의 before_request 와 경로에 걸린 객체들의 before_request */
    private List<String> flaskHooks(FlaskObject object, int depth) {
        Set<String> hooks = new LinkedHashSet<>(object.hooks());
        if (depth <= MAX_DEPTH) {
            for (Mount mount : object.mounts()) {
                FlaskObject parent = flaskObjects.get(mount.parent());
                if (parent != null) {
                    hooks.addAll(flaskHooks(parent, depth + 1));
                }
            }
        }
        return List.copyOf(hooks);
    }

    private void addFlaskRoutes(PythonModule module) {
        // 함수에 붙은 @app.route / @bp.get
        for (FunctionInfo fn : module.functions.values()) {
            for (TSNode call : decoratorCalls(fn.outer())) {
                TSNode function = call.getChildByFieldName("function");
                if (!function.getType().equals("attribute")) {
                    continue;
                }
                String verb = FLASK_VERBS.get(module.text(function.getChildByFieldName("attribute")));
                FlaskObject object = flaskObjects.get(builder.qualify(module.text(function.getChildByFieldName("object")), module.body, Map.of()));
                if (verb == null || object == null || object.kind().equals("namespace") || object.kind().equals("api")) {
                    continue;
                }
                TSNode args = call.getChildByFieldName("arguments");
                String rule = ruleArgument(module, args);
                List<String> methods = verb.isEmpty() ? methodsList(module, PythonGraphBuilder.keywordArgument(module, args, "methods"), "GET") : List.of(verb);
                List<String> guards = guards(fn.decorators(), object);
                for (String prefix : flaskPrefixes(object, 0)) {
                    for (String method : methods) {
                        addEntry(method, PythonGraphBuilder.joinPath(prefix, rule), fn.qualifiedName(), List.of(fn), fn.module(),
                                fn.outer(), "flask", guards);
                    }
                }
            }
        }
        // 클래스에 붙은 @ns.route("/x") (Flask-RESTX Resource)
        for (ClassInfo cls : module.classes.values()) {
            for (TSNode call : decoratorCalls(cls.outer())) {
                TSNode function = call.getChildByFieldName("function");
                if (!function.getType().equals("attribute") || !module.text(function.getChildByFieldName("attribute")).equals("route")) {
                    continue;
                }
                FlaskObject object = flaskObjects.get(builder.qualify(module.text(function.getChildByFieldName("object")), module.body, Map.of()));
                if (object == null) {
                    continue;
                }
                String rule = ruleArgument(module, call.getChildByFieldName("arguments"));
                for (String prefix : flaskPrefixes(object, 0)) {
                    addClassView(cls, PythonGraphBuilder.joinPath(prefix, rule), module, call, "flask", object, null);
                }
            }
        }
        // app.add_url_rule("/x", view_func=…), api.add_resource(Resource, "/a", "/b")
        for (TSNode call : PythonGraphBuilder.calls(module.root, true)) {
            TSNode function = call.getChildByFieldName("function");
            if (!function.getType().equals("attribute")) {
                continue;
            }
            String method = module.text(function.getChildByFieldName("attribute"));
            FlaskObject object = flaskObjects.get(builder.qualify(module.text(function.getChildByFieldName("object")), module.body, Map.of()));
            if (object == null) {
                continue;
            }
            TSNode args = call.getChildByFieldName("arguments");
            if (method.equals("add_url_rule")) {
                addUrlRule(module, call, args, object);
            } else if (method.equals("add_resource")) {
                List<TSNode> positional = positionals(args);
                if (positional.isEmpty()) {
                    continue;
                }
                ClassInfo cls = builder.projectClass(builder.qualify(module.text(positional.get(0)), module.body, Map.of()));
                if (cls == null) {
                    continue;
                }
                for (TSNode ruleNode : positional.subList(1, positional.size())) {
                    String rule = PythonGraphBuilder.stringValue(module, ruleNode);
                    if (rule == null) {
                        continue;
                    }
                    for (String prefix : flaskPrefixes(object, 0)) {
                        addClassView(cls, PythonGraphBuilder.joinPath(prefix, rule), module, call, "flask", object, null);
                    }
                }
            }
        }
    }

    private void addUrlRule(PythonModule module, TSNode call, TSNode args, FlaskObject object) {
        List<TSNode> positional = positionals(args);
        String rule = ruleArgument(module, args);
        TSNode view = PythonGraphBuilder.keywordArgument(module, args, "view_func");
        if (view == null && positional.size() >= 3) {
            view = positional.get(2);
        }
        if (view == null) {
            return;
        }
        ClassInfo cls = asViewClass(module, view);
        for (String prefix : flaskPrefixes(object, 0)) {
            String path = PythonGraphBuilder.joinPath(prefix, rule);
            if (cls != null) {
                addClassView(cls, path, module, call, "flask", object, null);
                continue;
            }
            FunctionInfo fn = builder.projectFunction(builder.qualify(module.text(view), module.body, Map.of()));
            if (fn == null) {
                continue;
            }
            for (String method : methodsList(module, PythonGraphBuilder.keywordArgument(module, args, "methods"), "GET")) {
                addEntry(method, path, fn.qualifiedName(), List.of(fn), fn.module(), fn.outer(), "flask", guards(fn.decorators(), object));
            }
        }
    }

    private static String ruleArgument(PythonModule module, TSNode args) {
        String rule = PythonGraphBuilder.stringValue(module, PythonGraphBuilder.firstPositional(args));
        if (rule == null) {
            rule = PythonGraphBuilder.stringValue(module, PythonGraphBuilder.keywordArgument(module, args, "rule"));
        }
        return rule == null ? "" : rule;
    }

    // ---- Django ----

    private void collectDrfRouters(PythonModule module) {
        module.moduleAssignments.forEach((variable, value) -> {
            if (value.getType().equals("call")) {
                String callee = builder.qualify(module.text(value.getChildByFieldName("function")), module.body, Map.of());
                if (callee != null && DRF_ROUTERS.contains(callee)) {
                    drfRegistrations.put(module.name + "." + variable, new ArrayList<>());
                    drfRouterModules.put(module.name + "." + variable, module);
                }
            }
        });
        for (TSNode call : PythonGraphBuilder.calls(module.root, true)) {
            TSNode function = call.getChildByFieldName("function");
            if (function.getType().equals("attribute") && module.text(function.getChildByFieldName("attribute")).equals("register")) {
                String router = builder.qualify(module.text(function.getChildByFieldName("object")), module.body, Map.of());
                if (router != null && drfRegistrations.containsKey(router)) {
                    drfRegistrations.get(router).add(call);
                }
            }
        }
    }

    private void addDjangoRoutes() {
        List<PythonModule> urlModules = modules.stream().filter(m -> !urlpatterns(m).isEmpty()).toList();
        if (urlModules.isEmpty()) {
            return;
        }
        // settings 의 ROOT_URLCONF 가 가리키는 모듈에서만 시작한다 (거기서 이어지지 않는 urls 는 실제로 열린 경로가 아니다)
        List<PythonModule> roots = rootUrlconfs();
        if (!roots.isEmpty()) {
            roots.forEach(root -> emitModule(root, "", new HashSet<>(), 0));
            return;
        }
        // ROOT_URLCONF 를 모르면: 다른 urls 가 include 하는 모듈은 그쪽 경로 아래에서만 만든다
        Set<String> included = new HashSet<>();
        for (PythonModule module : urlModules) {
            for (TSNode expr : urlpatterns(module)) {
                for (TSNode call : PythonGraphBuilder.calls(expr, true)) {
                    String callee = builder.qualify(module.text(call.getChildByFieldName("function")), module.body, Map.of());
                    if (callee != null && DJANGO_INCLUDES.contains(callee)) {
                        PythonModule target = includedModule(module, PythonGraphBuilder.firstPositional(call.getChildByFieldName("arguments")));
                        if (target != null && target != module) {
                            included.add(target.name);
                        }
                    }
                }
            }
        }
        for (PythonModule module : urlModules) {
            if (!included.contains(module.name)) {
                emitModule(module, "", new HashSet<>(), 0);
            }
        }
    }

    /**
     * 모듈 최상위의 ROOT_URLCONF 가 가리키는 urls 모듈들 (dev·prod 설정이 다르면 모두).
     * 문자열, 같은 모듈의 변수, "a" if DEBUG else "b", + 와 f-string, 환경 변수(os.environ.get·os.getenv·environ.get 의 기본값,
     * django-environ env(…, default=)·python-decouple config(…, default=), 기본값이 없으면 .env 파일의 값)를 푼다.
     * 하나도 못 찾으면 빈 목록
     */
    private List<PythonModule> rootUrlconfs() {
        Set<PythonModule> roots = new LinkedHashSet<>();
        for (PythonModule module : modules) {
            TSNode value = module.moduleAssignments.get("ROOT_URLCONF");
            if (value == null) {
                continue;
            }
            for (String name : settingValues(module, value, 0)) {
                PythonModule target = builder.moduleBySuffix(name);
                if (target != null && !urlpatterns(target).isEmpty()) {
                    roots.add(target);
                }
            }
        }
        return List.copyOf(roots);
    }

    /** 설정 식이 가질 수 있는 문자열 값들. 모르면 빈 목록 */
    private List<String> settingValues(PythonModule module, TSNode expr, int depth) {
        if (isNull(expr) || depth > MAX_DEPTH) {
            return List.of();
        }
        switch (expr.getType()) {
            case "string" -> {
                return interpolate(module, expr, depth);
            }
            case "parenthesized_expression" -> {
                return settingValues(module, firstNamedChild(expr), depth + 1);
            }
            case "identifier" -> {
                TSNode value = module.moduleAssignments.get(module.text(expr));
                return value == null || value == expr ? List.of() : settingValues(module, value, depth + 1);
            }
            case "conditional_expression" -> {
                // "a.urls" if DEBUG else "b.urls": 둘 다
                Set<String> values = new LinkedHashSet<>();
                List<TSNode> parts = namedChildren(expr);
                if (parts.size() == 3) {
                    values.addAll(settingValues(module, parts.get(0), depth + 1));
                    values.addAll(settingValues(module, parts.get(2), depth + 1));
                }
                return List.copyOf(values);
            }
            case "binary_operator" -> {
                List<String> left = settingValues(module, expr.getChildByFieldName("left"), depth + 1);
                List<String> right = settingValues(module, expr.getChildByFieldName("right"), depth + 1);
                List<String> values = new ArrayList<>();
                left.forEach(l -> right.forEach(r -> values.add(l + r)));
                return values;
            }
            case "call" -> {
                return environmentValues(module, expr, depth);
            }
            case "subscript" -> {
                // os.environ["DJANGO_URLCONF"]: 기본값이 없으니 .env 파일에서
                String target = builder.qualify(module.text(expr.getChildByFieldName("value")), module.body, Map.of());
                TSNode key = expr.getChildByFieldName("subscript");
                String name = PythonGraphBuilder.stringValue(module, key);
                return "os.environ".equals(target) && name != null ? dotenv(name) : List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    /** os.environ.get("X", "d") · os.getenv("X", "d") · env("X", default="d") · env.str(…) · config("X", default="d") */
    private List<String> environmentValues(PythonModule module, TSNode call, int depth) {
        TSNode function = call.getChildByFieldName("function");
        TSNode args = call.getChildByFieldName("arguments");
        String text = module.text(function);
        String qualified = builder.qualify(text, module.body, Map.of());
        String simple = text.substring(text.lastIndexOf('.') + 1);
        boolean osEnv = "os.environ.get".equals(qualified) || "os.getenv".equals(qualified) || "os.environ.setdefault".equals(qualified);
        boolean libraryEnv = qualified != null && (qualified.startsWith("environ.") || qualified.startsWith("decouple."))
                || text.equals("env") || text.startsWith("env.") || simple.equals("config") && qualified != null && qualified.startsWith("decouple");
        if (!osEnv && !libraryEnv) {
            return List.of();
        }
        List<TSNode> positional = positionals(args);
        String name = positional.isEmpty() ? null : PythonGraphBuilder.stringValue(module, positional.get(0));
        TSNode fallback = PythonGraphBuilder.keywordArgument(module, args, "default");
        if (fallback == null && osEnv && positional.size() >= 2) {
            fallback = positional.get(1);
        }
        Set<String> values = new LinkedHashSet<>();
        if (name != null) {
            values.addAll(dotenv(name));
        }
        if (fallback != null) {
            values.addAll(settingValues(module, fallback, depth + 1));
        }
        return List.copyOf(values);
    }

    /** "…" 와 f"{PROJECT}.urls" (같은 모듈 변수만 넣을 수 있음) */
    private List<String> interpolate(PythonModule module, TSNode string, int depth) {
        List<String> values = new ArrayList<>(List.of(""));
        for (TSNode child : namedChildren(string)) {
            List<String> parts;
            switch (child.getType()) {
                case "string_content" -> parts = List.of(module.text(child));
                case "interpolation" -> parts = settingValues(module, firstNamedChild(child), depth + 1);
                default -> {
                    continue;
                }
            }
            if (parts.isEmpty()) {
                return List.of();
            }
            List<String> next = new ArrayList<>();
            values.forEach(v -> parts.forEach(p -> next.add(v + p)));
            values = next;
        }
        return values;
    }

    /** 저장소의 .env 계열 파일(.env, .env.example, .env.production …)에서 NAME=값 */
    private List<String> dotenv(String name) {
        if (root == null) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        java.util.regex.Pattern line = java.util.regex.Pattern.compile("^\\s*(?:export\\s+)?" + java.util.regex.Pattern.quote(name)
                + "\\s*=\\s*['\"]?([\\w.]+)['\"]?\\s*(?:#.*)?$");
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root, 4)) {
            for (java.nio.file.Path file : files.filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().startsWith(".env"))
                    .filter(f -> !f.toString().contains("node_modules") && !f.toString().contains(".git" + java.io.File.separator))
                    .sorted().toList()) {
                if (java.nio.file.Files.size(file) > 200_000) {
                    continue;
                }
                for (String text : java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                    java.util.regex.Matcher m = line.matcher(text);
                    if (m.matches()) {
                        values.add(m.group(1));
                    }
                }
            }
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            // 못 읽는 파일은 건너뛴다 (UTF-8 이 아닌 파일 등)
        }
        return List.copyOf(values);
    }

    /** urlpatterns = […] 와 urlpatterns += … 의 식들 */
    private static List<TSNode> urlpatterns(PythonModule module) {
        List<TSNode> result = new ArrayList<>();
        TSNode assigned = module.moduleAssignments.get("urlpatterns");
        if (assigned != null) {
            result.add(assigned);
        }
        for (TSNode statement : namedChildren(module.root)) {
            TSNode expr = statement.getType().equals("expression_statement") ? firstNamedChild(statement) : null;
            if (!isNull(expr) && expr.getType().equals("augmented_assignment")
                    && module.text(expr.getChildByFieldName("left")).equals("urlpatterns")) {
                result.add(expr.getChildByFieldName("right"));
            }
        }
        return result;
    }

    private void emitModule(PythonModule module, String prefix, Set<String> visiting, int depth) {
        if (depth > MAX_DEPTH || !visiting.add(module.name)) {
            return;
        }
        for (TSNode expr : urlpatterns(module)) {
            emitPatterns(module, expr, prefix, visiting, depth);
        }
        visiting.remove(module.name);
    }

    private void emitPatterns(PythonModule module, TSNode expr, String prefix, Set<String> visiting, int depth) {
        if (isNull(expr) || depth > MAX_DEPTH) {
            return;
        }
        switch (expr.getType()) {
            case "list", "tuple", "parenthesized_expression" ->
                    namedChildren(expr).forEach(item -> emitPatterns(module, item, prefix, visiting, depth + 1));
            case "binary_operator" -> {
                emitPatterns(module, expr.getChildByFieldName("left"), prefix, visiting, depth + 1);
                emitPatterns(module, expr.getChildByFieldName("right"), prefix, visiting, depth + 1);
            }
            case "attribute" -> {
                // router.urls
                String router = builder.qualify(module.text(expr.getChildByFieldName("object")), module.body, Map.of());
                if (router != null && drfRegistrations.containsKey(router)) {
                    emitRouter(router, prefix);
                }
            }
            case "identifier" -> {
                // api_patterns = [...] 로 따로 둔 목록
                TSNode value = module.moduleAssignments.get(module.text(expr));
                if (value != null && !value.getType().equals("call")) {
                    emitPatterns(module, value, prefix, visiting, depth + 1);
                }
            }
            case "call" -> emitPathCall(module, expr, prefix, visiting, depth);
            default -> {
            }
        }
    }

    private void emitPathCall(PythonModule module, TSNode call, String prefix, Set<String> visiting, int depth) {
        String callee = builder.qualify(module.text(call.getChildByFieldName("function")), module.body, Map.of());
        if (callee == null || !DJANGO_PATHS.contains(callee)) {
            return;
        }
        TSNode args = call.getChildByFieldName("arguments");
        List<TSNode> positional = positionals(args);
        String route = PythonGraphBuilder.stringValue(module, positional.isEmpty() ? PythonGraphBuilder.keywordArgument(module, args, "route") : positional.get(0));
        TSNode view = positional.size() >= 2 ? positional.get(1) : PythonGraphBuilder.keywordArgument(module, args, "view");
        if (route == null || view == null) {
            return;
        }
        String path = PythonGraphBuilder.joinPath(prefix, route.replaceFirst("^\\^", "").replaceFirst("\\$$", ""));
        if (view.getType().equals("call")) {
            String viewCallee = builder.qualify(module.text(view.getChildByFieldName("function")), module.body, Map.of());
            if (viewCallee != null && DJANGO_INCLUDES.contains(viewCallee)) {
                emitInclude(module, PythonGraphBuilder.firstPositional(view.getChildByFieldName("arguments")), path, visiting, depth);
                return;
            }
            ClassInfo cls = asViewClass(module, view);
            if (cls != null) {
                TSNode mapping = PythonGraphBuilder.firstPositional(view.getChildByFieldName("arguments"));
                addClassView(cls, path, module, call, "django", null, mapping != null && mapping.getType().equals("dictionary") ? mapping : null);
            }
            return;
        }
        FunctionInfo fn = builder.projectFunction(builder.qualify(module.text(view), module.body, Map.of()));
        if (fn != null) {
            for (String method : djangoFunctionMethods(fn)) {
                addEntry(method, path, fn.qualifiedName(), List.of(fn), fn.module(), fn.outer(), "django", guards(fn.decorators(), null));
            }
        }
    }

    private void emitInclude(PythonModule module, TSNode target, String prefix, Set<String> visiting, int depth) {
        if (target == null) {
            return;
        }
        if (target.getType().equals("tuple")) {
            // include(("app.urls", "app"))
            emitInclude(module, firstNamedChild(target), prefix, visiting, depth);
            return;
        }
        if (target.getType().equals("list") || target.getType().equals("attribute") && module.text(target).endsWith(".urls")) {
            emitPatterns(module, target, prefix, visiting, depth + 1);
            return;
        }
        PythonModule included = includedModule(module, target);
        if (included != null) {
            emitModule(included, prefix, visiting, depth + 1);
        } else if (target.getType().equals("identifier")) {
            emitPatterns(module, target, prefix, visiting, depth + 1);
        }
    }

    /** include("app.urls") / include(app_urls) 의 모듈 */
    private PythonModule includedModule(PythonModule module, TSNode target) {
        if (target == null) {
            return null;
        }
        String name = PythonGraphBuilder.stringValue(module, target);
        if (name == null && (target.getType().equals("identifier") || target.getType().equals("attribute"))) {
            name = builder.qualify(module.text(target), module.body, Map.of());
        }
        return name == null ? null : builder.moduleBySuffix(name);
    }

    /** DRF 라우터에 register 한 ViewSet: 목록·상세 경로와 @action */
    private void emitRouter(String router, String prefix) {
        PythonModule module = drfRouterModules.get(router);
        for (TSNode call : drfRegistrations.getOrDefault(router, List.of())) {
            TSNode args = call.getChildByFieldName("arguments");
            List<TSNode> positional = positionals(args);
            if (positional.size() < 2) {
                continue;
            }
            String registered = PythonGraphBuilder.stringValue(module, positional.get(0));
            ClassInfo cls = builder.projectClass(builder.qualify(module.text(positional.get(1)), module.body, Map.of()));
            if (registered == null || cls == null) {
                continue;
            }
            String base = PythonGraphBuilder.joinPath(prefix, registered);
            List<String> guards = classGuards(cls);
            Set<String> actions = new LinkedHashSet<>();
            for (String action : VIEWSET_ACTIONS.keySet()) {
                if (builder.inheritedMethod(cls, action) != null) {
                    actions.add(action);
                }
            }
            for (String baseName : allBaseNames(cls)) {
                actions.addAll(LIBRARY_ACTIONS.getOrDefault(baseName, List.of()));
            }
            for (String action : VIEWSET_ACTIONS.keySet()) {
                if (!actions.contains(action)) {
                    continue;
                }
                String[] spec = VIEWSET_ACTIONS.get(action);
                FunctionInfo own = builder.inheritedMethod(cls, action);
                List<FunctionInfo> targets = own != null ? List.of(own) : hooks(cls);
                addEntry(spec[0], PythonGraphBuilder.joinPath(base, spec[1]), cls.qualifiedName() + "." + action, targets,
                        cls.module(), own != null ? own.outer() : cls.outer(), "django", guards);
            }
            // @action(detail=True, methods=["post"], url_path="reset")
            for (FunctionInfo method : allMethods(cls)) {
                for (TSNode decorator : decoratorCalls(method.outer())) {
                    String name = method.module().text(decorator.getChildByFieldName("function"));
                    if (!name.equals("action") && !name.endsWith(".action")) {
                        continue;
                    }
                    TSNode actionArgs = decorator.getChildByFieldName("arguments");
                    TSNode detail = PythonGraphBuilder.keywordArgument(method.module(), actionArgs, "detail");
                    String urlPath = PythonGraphBuilder.stringValue(method.module(), PythonGraphBuilder.keywordArgument(method.module(), actionArgs, "url_path"));
                    String segment = urlPath != null ? urlPath : method.name();
                    String path = detail != null && method.module().text(detail).equals("True")
                            ? PythonGraphBuilder.joinPath(PythonGraphBuilder.joinPath(base, "{pk}"), segment)
                            : PythonGraphBuilder.joinPath(base, segment);
                    for (String verb : methodsList(method.module(), PythonGraphBuilder.keywordArgument(method.module(), actionArgs, "methods"), "GET")) {
                        addEntry(verb, path, method.qualifiedName(), List.of(method), method.module(), method.outer(), "django",
                                mergeGuards(guards, method.decorators()));
                    }
                }
            }
        }
    }

    /** 함수 뷰가 받는 HTTP 메서드: @require_GET, @require_http_methods([...]), @api_view([...]) 없으면 ANY */
    private List<String> djangoFunctionMethods(FunctionInfo fn) {
        for (TSNode decorator : decoratorCallsAndNames(fn.outer())) {
            String name = fn.module().text(decorator.getType().equals("call") ? decorator.getChildByFieldName("function") : decorator);
            String simple = name.substring(name.lastIndexOf('.') + 1);
            switch (simple) {
                case "require_GET" -> {
                    return List.of("GET");
                }
                case "require_POST" -> {
                    return List.of("POST");
                }
                case "require_safe" -> {
                    return List.of("GET", "HEAD");
                }
                case "require_http_methods", "api_view" -> {
                    if (decorator.getType().equals("call")) {
                        TSNode args = decorator.getChildByFieldName("arguments");
                        TSNode list = PythonGraphBuilder.firstPositional(args);
                        if (list == null) {
                            list = PythonGraphBuilder.keywordArgument(fn.module(), args, "http_method_names");
                        }
                        return methodsList(fn.module(), list, simple.equals("api_view") ? "GET" : "ANY");
                    }
                    return List.of("GET");
                }
                default -> {
                }
            }
        }
        return List.of("ANY");
    }

    // ---- 클래스 뷰 (Flask MethodView·RESTful Resource, Django View·DRF APIView) ----

    /**
     * @param mapping ViewSet.as_view({"get": "list"}) 의 사전 (없으면 null)
     */
    private void addClassView(ClassInfo cls, String path, PythonModule declaredIn, TSNode declaration, String framework,
                              FlaskObject flaskObject, TSNode mapping) {
        List<String> guards = classGuards(cls);
        if (flaskObject != null) {
            guards = mergeGuards(guards, flaskHooks(flaskObject, 0));
        }
        if (mapping != null) {
            for (TSNode pair : namedChildren(mapping)) {
                if (!pair.getType().equals("pair")) {
                    continue;
                }
                String verb = PythonGraphBuilder.stringValue(cls.module() == declaredIn ? declaredIn : declaredIn, pair.getChildByFieldName("key"));
                String action = PythonGraphBuilder.stringValue(declaredIn, pair.getChildByFieldName("value"));
                if (verb == null || action == null) {
                    continue;
                }
                FunctionInfo method = builder.inheritedMethod(cls, action);
                addEntry(verb.toUpperCase(), path, cls.qualifiedName() + "." + action, method != null ? List.of(method) : hooks(cls),
                        cls.module(), method != null ? method.outer() : cls.outer(), framework, guards);
            }
            return;
        }
        boolean any = false;
        for (String verb : VIEW_VERBS) {
            FunctionInfo method = builder.inheritedMethod(cls, verb);
            if (method != null) {
                any = true;
                addEntry(verb.toUpperCase(), path, method.qualifiedName(), List.of(method), method.module(), method.outer(), framework,
                        mergeGuards(guards, method.decorators()));
            }
        }
        if (any) {
            return;
        }
        // ListCreateAPIView·UpdateView 처럼 라이브러리가 처리하는 뷰: 프로젝트 훅(get_queryset 등)으로 잇는다
        Set<String> verbs = new LinkedHashSet<>();
        for (String baseName : allBaseNames(cls)) {
            verbs.addAll(GENERIC_VIEW_VERBS.getOrDefault(baseName, List.of()));
        }
        if (verbs.isEmpty()) {
            verbs.add("ANY");
        }
        for (String verb : verbs) {
            addEntry(verb, path, cls.qualifiedName(), hooks(cls), cls.module(), cls.outer(), framework, guards);
        }
    }

    /** View.as_view(...) 호출이면 그 프로젝트 클래스 */
    private ClassInfo asViewClass(PythonModule module, TSNode view) {
        if (isNull(view) || !view.getType().equals("call")) {
            return null;
        }
        TSNode function = view.getChildByFieldName("function");
        if (!function.getType().equals("attribute") || !module.text(function.getChildByFieldName("attribute")).equals("as_view")) {
            return null;
        }
        return builder.projectClass(builder.qualify(module.text(function.getChildByFieldName("object")), module.body, Map.of()));
    }

    private List<FunctionInfo> hooks(ClassInfo cls) {
        List<FunctionInfo> result = new ArrayList<>();
        for (String hook : VIEW_HOOKS) {
            FunctionInfo method = builder.inheritedMethod(cls, hook);
            if (method != null) {
                result.add(method);
            }
        }
        return result;
    }

    private List<FunctionInfo> allMethods(ClassInfo cls) {
        List<FunctionInfo> result = new ArrayList<>(cls.methods().values());
        builder.ancestors(cls).forEach(a -> result.addAll(a.methods().values()));
        return result;
    }

    /** 클래스와 프로젝트 상위 클래스가 상속한 이름들의 마지막 부분 (ModelViewSet, LoginRequiredMixin …) */
    private List<String> allBaseNames(ClassInfo cls) {
        List<String> result = new ArrayList<>();
        List<ClassInfo> lineage = new ArrayList<>(List.of(cls));
        lineage.addAll(builder.ancestors(cls));
        for (ClassInfo c : lineage) {
            for (String base : c.bases()) {
                result.add(base.substring(base.lastIndexOf('.') + 1));
            }
        }
        return result;
    }

    // ---- 인증·권한 단서 ----

    /** 함수의 데코레이터(경로 데코레이터 빼고)와 Flask 객체의 before_request */
    private List<String> guards(List<String> decorators, FlaskObject object) {
        List<String> result = new ArrayList<>();
        for (String decorator : decorators) {
            String simple = decorator.substring(decorator.lastIndexOf('.') + 1);
            if (!FLASK_VERBS.containsKey(simple)) {
                result.add(decorator);
            }
        }
        if (object != null) {
            result.addAll(flaskHooks(object, 0));
        }
        return List.copyOf(new LinkedHashSet<>(result));
    }

    /** 클래스 데코레이터, *Mixin 상속, permission_classes·authentication_classes·decorators·method_decorators 속성 */
    private List<String> classGuards(ClassInfo cls) {
        Set<String> result = new LinkedHashSet<>(cls.decorators());
        List<ClassInfo> lineage = new ArrayList<>(List.of(cls));
        lineage.addAll(builder.ancestors(cls));
        for (ClassInfo c : lineage) {
            for (String base : c.bases()) {
                String simple = base.substring(base.lastIndexOf('.') + 1);
                if (simple.endsWith("Mixin") && !LIBRARY_ACTIONS.containsKey(simple)) {
                    result.add(simple);
                }
            }
            for (TSNode statement : namedChildren(c.node().getChildByFieldName("body"))) {
                TSNode expr = statement.getType().equals("expression_statement") ? firstNamedChild(statement) : null;
                if (isNull(expr) || !expr.getType().equals("assignment")) {
                    continue;
                }
                String name = c.module().text(expr.getChildByFieldName("left"));
                if (Set.of("permission_classes", "authentication_classes", "decorators", "method_decorators").contains(name)) {
                    result.add(name + "=" + c.module().text(expr.getChildByFieldName("right")).replaceAll("\\s+", " "));
                }
            }
            FunctionInfo dispatch = c.methods().get("dispatch");
            if (dispatch != null) {
                result.addAll(dispatch.decorators());
            }
        }
        return List.copyOf(result);
    }

    private static List<String> mergeGuards(List<String> a, List<String> b) {
        Set<String> merged = new LinkedHashSet<>(a);
        merged.addAll(b);
        return List.copyOf(merged);
    }

    // ---- 진입점 노드 ----

    /**
     * @param handler 진입점 이름에 쓸 처리 함수 이름 (라이브러리가 처리하면 클래스.동작)
     * @param targets 경로를 이을 프로젝트 함수 (없으면 진입점만 남는다)
     */
    private void addEntry(String httpMethod, String path, String handler, List<FunctionInfo> targets, PythonModule module,
                          TSNode node, String framework, List<String> guards) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("httpMethod", httpMethod);
        props.put("path", path);
        props.put("handler", handler);
        props.put("framework", framework);
        props.put("guards", guards);
        props.put("language", "python");
        String entryKey = draft.addNode(new NodeDraft(CodeNodeKind.ENTRY_POINT, httpMethod + " " + path + " -> " + handler,
                module.filePath, isNull(node) ? null : PythonModule.line(node), isNull(node) ? null : PythonModule.endLine(node), props));
        for (FunctionInfo target : targets) {
            draft.addEdge(entryKey, PythonGraphBuilder.methodKey(target), CodeEdgeKind.ROUTES_TO);
        }
    }

    // ---- 트리 도우미 ----

    /** 데코레이터 중 호출 모양인 것 (@app.route("/x"), @action(detail=True)) */
    private static List<TSNode> decoratorCalls(TSNode decorated) {
        return decoratorCallsAndNames(decorated).stream().filter(n -> n.getType().equals("call")).toList();
    }

    /** 데코레이터 식 (@require_POST 처럼 이름만 있는 것 포함) */
    private static List<TSNode> decoratorCallsAndNames(TSNode decorated) {
        List<TSNode> result = new ArrayList<>();
        if (isNull(decorated) || !decorated.getType().equals("decorated_definition")) {
            return result;
        }
        for (TSNode child : namedChildren(decorated)) {
            if (child.getType().equals("decorator")) {
                TSNode expr = firstNamedChild(child);
                if (!isNull(expr)) {
                    result.add(expr);
                }
            }
        }
        return result;
    }

    private static List<TSNode> positionals(TSNode args) {
        List<TSNode> result = new ArrayList<>();
        for (TSNode child : namedChildren(args)) {
            String type = child.getType();
            if (!type.equals("keyword_argument") && !type.equals("list_splat") && !type.equals("dictionary_splat") && !type.equals("comment")) {
                result.add(child);
            }
        }
        return result;
    }

    /** methods=["GET", "POST"] → [GET, POST]. 없으면 기본값 */
    private static List<String> methodsList(PythonModule module, TSNode list, String fallback) {
        List<String> methods = new ArrayList<>();
        if (list != null) {
            for (TSNode item : namedChildren(list)) {
                String value = PythonGraphBuilder.stringValue(module, item);
                if (value != null) {
                    methods.add(value.toUpperCase());
                }
            }
        }
        return methods.isEmpty() ? List.of(fallback) : List.copyOf(new LinkedHashSet<>(methods));
    }
}
