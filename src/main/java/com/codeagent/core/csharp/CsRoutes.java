package com.codeagent.core.csharp;

import com.codeagent.core.csharp.CsProject.CsClass;
import com.codeagent.core.csharp.CsProject.CsFunc;
import com.codeagent.core.csharp.CsProject.Local;
import com.codeagent.core.java.EntryPointDetector;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.codeagent.core.csharp.CsFile.field;
import static com.codeagent.core.csharp.CsFile.isNull;
import static com.codeagent.core.csharp.CsFile.namedChildren;

/**
 * ASP.NET Core HTTP 진입점.
 * <ul>
 *   <li>컨트롤러: [Route]·[HttpGet("{id}")] 특성 라우팅 ([controller]·[action] 치환, "/"·"~/" 로 시작하면 클래스 경로 무시),
 *       특성 라우트가 없는 MVC 컨트롤러는 기본 규칙 /{컨트롤러}/{액션}. [Authorize]·[AllowAnonymous]·인증 필터 특성을 guards 로,
 *       Program.cs 의 MapControllers().RequireAuthorization() 은 모든 컨트롤러 라우트에.</li>
 *   <li>Minimal API: app.MapGet·MapPost·…·MapMethods, MapGroup("/api") 변수 (여러 단계), RequireAuthorization()·AllowAnonymous()</li>
 * </ul>
 */
final class CsRoutes {

    record Route(String httpMethod, String path, CsFunc handler, List<String> guards, String framework) {
    }

    private static final Map<String, String> HTTP_ATTRIBUTES = Map.of("HttpGet", "GET", "HttpPost", "POST", "HttpPut", "PUT",
            "HttpDelete", "DELETE", "HttpPatch", "PATCH", "HttpHead", "HEAD", "HttpOptions", "OPTIONS");
    private static final Map<String, String> MAP_METHODS = Map.of("MapGet", "GET", "MapPost", "POST", "MapPut", "PUT",
            "MapDelete", "DELETE", "MapPatch", "PATCH", "Map", "ANY");
    private static final Pattern GUARD_ATTRIBUTE = Pattern.compile("(?i)(authoriz|auth|admin|role|permission|policy|apikey)");

    private final CsProject project;
    private final List<Route> routes = new ArrayList<>();
    private final List<String> globalControllerGuards = new ArrayList<>();

    CsRoutes(CsProject project) {
        this.project = project;
    }

    List<Route> detect() {
        minimalApis();
        controllers();
        return routes;
    }

    // ---- 컨트롤러 ----

    private void controllers() {
        for (CsClass cls : project.classes()) {
            if (cls.isInterface || !isController(cls)) {
                continue;
            }
            CsFile file = cls.file;
            List<String> classTemplates = new ArrayList<>();
            List<String> classGuards = new ArrayList<>();
            boolean attributeRouted = false;
            for (CsClass current = cls; current != null; current = project.baseClass(current)) {
                for (TSNode attribute : CsFile.attributes(current.node)) {
                    String name = current.file.attributeName(attribute);
                    if (name.equals("Route") && current == cls) {
                        String template = template(current.file, attribute);
                        if (template != null) {
                            classTemplates.add(template);
                            attributeRouted = true;
                        }
                    } else if (guardAttribute(name)) {
                        classGuards.add(0, current.file.text(attribute).replaceAll("\\s+", " "));
                    }
                }
            }
            String controllerName = cls.name.endsWith("Controller") ? cls.name.substring(0, cls.name.length() - "Controller".length()) : cls.name;
            for (List<CsFunc> overloads : cls.methods.values()) {
                for (CsFunc method : overloads) {
                    if (method.name().equals(".ctor") || !isPublicAction(file, method.node())) {
                        continue;
                    }
                    controllerAction(cls, method, controllerName, classTemplates, classGuards, attributeRouted);
                }
            }
        }
    }

    private void controllerAction(CsClass cls, CsFunc method, String controllerName, List<String> classTemplates, List<String> classGuards,
                                  boolean classRouted) {
        CsFile file = method.file();
        List<String[]> routesOfMethod = new ArrayList<>();
        List<String> guards = new ArrayList<>(classGuards);
        boolean anonymous = false;
        List<String> verbs = new ArrayList<>();
        for (TSNode attribute : CsFile.attributes(method.node())) {
            String name = file.attributeName(attribute);
            if (HTTP_ATTRIBUTES.containsKey(name)) {
                verbs.add(HTTP_ATTRIBUTES.get(name));
                String template = template(file, attribute);
                if (template != null) {
                    routesOfMethod.add(new String[]{HTTP_ATTRIBUTES.get(name), template});
                }
            } else if (name.equals("Route")) {
                String template = template(file, attribute);
                if (template != null) {
                    routesOfMethod.add(new String[]{null, template});
                }
            } else if (name.equals("AcceptVerbs")) {
                for (TSNode arg : file.attributePositional(attribute)) {
                    String verb = file.stringValue(arg);
                    if (verb != null) {
                        verbs.add(verb.toUpperCase(Locale.ROOT));
                    }
                }
            } else if (name.equals("AllowAnonymous")) {
                anonymous = true;
            } else if (name.equals("NonAction")) {
                return;
            } else if (guardAttribute(name)) {
                guards.add(file.text(attribute).replaceAll("\\s+", " "));
            }
        }
        if (anonymous) {
            guards.removeIf(g -> g.startsWith("Authorize"));
        } else {
            guards.addAll(globalControllerGuards);
        }
        List<String> paths = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        if (!routesOfMethod.isEmpty() || !verbs.isEmpty() && classRouted) {
            List<String[]> templates = routesOfMethod.isEmpty() ? List.<String[]>of(new String[]{null, ""}) : routesOfMethod;
            for (String[] route : templates) {
                String template = route[1];
                List<String> prefixes = template.startsWith("/") || template.startsWith("~/") || classTemplates.isEmpty()
                        ? List.of("") : classTemplates;
                for (String prefix : prefixes) {
                    paths.add(replaceTokens(EntryPointDetector.joinPath(prefix, template.replaceFirst("^~", "")), controllerName, method.name()));
                    methods.add(route[0]);
                }
            }
        } else if (!classRouted) {
            // 기본 규칙 라우트: /{controller}/{action}
            paths.add("/" + controllerName + "/" + method.name());
            methods.add(null);
        } else {
            // 특성 라우팅 컨트롤러인데 특성이 없는 액션: 클래스 경로 그대로
            for (String prefix : classTemplates) {
                paths.add(replaceTokens(EntryPointDetector.joinPath(prefix, ""), controllerName, method.name()));
                methods.add(null);
            }
        }
        for (int i = 0; i < paths.size(); i++) {
            List<String> httpMethods = methods.get(i) != null ? List.of(methods.get(i)) : verbs.isEmpty() ? List.of("ANY") : verbs;
            for (String httpMethod : httpMethods) {
                add(httpMethod, paths.get(i), method, guards, "aspnetcore");
            }
        }
    }

    private boolean isController(CsClass cls) {
        if (cls.name.endsWith("Controller")) {
            return true;
        }
        for (TSNode attribute : CsFile.attributes(cls.node)) {
            String name = cls.file.attributeName(attribute);
            if (name.equals("ApiController") || name.equals("Controller")) {
                return true;
            }
        }
        return project.ancestorNames(cls).stream().anyMatch(b -> b.equals("Controller") || b.equals("ControllerBase"));
    }

    private static boolean isPublicAction(CsFile file, TSNode method) {
        boolean isPublic = false;
        for (TSNode modifier : CsFile.children(method, "modifier")) {
            String text = file.text(modifier);
            if (text.equals("public")) {
                isPublic = true;
            }
            if (text.equals("static") || text.equals("abstract")) {
                return false;
            }
        }
        return isPublic;
    }

    private String template(CsFile file, TSNode attribute) {
        List<TSNode> positional = file.attributePositional(attribute);
        if (!positional.isEmpty()) {
            return file.stringValue(positional.get(0));
        }
        TSNode named = file.attributeNamed(attribute, "Template");
        return named == null ? (attribute.getType().equals("attribute") && file.attributeName(attribute).startsWith("Http") ? "" : null)
                : file.stringValue(named);
    }

    private static String replaceTokens(String path, String controller, String action) {
        return path.replaceAll("(?i)\\[controller]", controller).replaceAll("(?i)\\[action]", action).replaceAll("(?i)\\[area]/?", "");
    }

    private static boolean guardAttribute(String name) {
        return name.equals("Authorize") || !name.equals("AllowAnonymous") && GUARD_ATTRIBUTE.matcher(name).find()
                && !name.startsWith("Http") && !name.equals("Route");
    }

    // ---- Minimal API ----

    private void minimalApis() {
        for (CsFile file : project.files()) {
            String text = new String(file.source, java.nio.charset.StandardCharsets.UTF_8);
            if (text.matches("(?s).*MapControllers\\(\\)\\s*\\.\\s*RequireAuthorization\\(.*")) {
                globalControllerGuards.add("MapControllers().RequireAuthorization()");
            }
            for (TSNode call : CsFile.descendants(file.root, Set.of("invocation_expression"), Set.of())) {
                TSNode function = field(call, "function");
                if (function == null || !function.getType().equals("member_access_expression")) {
                    continue;
                }
                String method = file.text(field(function, "name"));
                List<TSNode> args = CsFile.arguments(call);
                String httpMethod = MAP_METHODS.get(method);
                if (method.equals("MapMethods") && args.size() >= 3) {
                    httpMethod = "MULTI";
                }
                if (httpMethod == null || args.size() < 2) {
                    continue;
                }
                String pattern = file.stringValue(args.get(0));
                if (pattern == null) {
                    continue;
                }
                CsFunc context = project.enclosing(file, call);
                List<String> guards = new ArrayList<>(chainedGuards(file, call));
                String prefix = groupPrefix(file, field(function, "expression"), context, guards, 0);
                if (prefix == null) {
                    continue;
                }
                if (guards.remove("AllowAnonymous()")) {
                    guards.removeIf(g -> g.startsWith("RequireAuthorization"));
                }
                String path = EntryPointDetector.joinPath(prefix, pattern);
                TSNode handlerNode = args.get(args.size() - 1);
                List<String> methods = new ArrayList<>();
                if (httpMethod.equals("MULTI")) {
                    for (TSNode item : CsFile.descendants(args.get(1), Set.of("string_literal"), Set.of())) {
                        String value = file.stringValue(item);
                        if (value != null) {
                            methods.add(value.toUpperCase(Locale.ROOT));
                        }
                    }
                } else {
                    methods.add(httpMethod);
                }
                CsFunc handler = handler(file, handlerNode, methods.isEmpty() ? "ANY" : methods.get(0), path, context);
                if (handler == null) {
                    continue;
                }
                for (String m : methods) {
                    add(m, path, handler, guards, "minimal-api");
                }
            }
        }
    }

    /** 라우트 그룹 변수의 경로 (app 이면 ""). 라우터를 모르면 null */
    private String groupPrefix(CsFile file, TSNode receiver, CsFunc context, List<String> guards, int depth) {
        receiver = CsFile.unwrap(receiver);
        if (receiver == null || depth > 6) {
            return null;
        }
        if (receiver.getType().equals("identifier")) {
            String name = file.text(receiver);
            Local local = context == null ? null : project.local(context, name);
            if (local != null && local.value() != null) {
                return groupPrefix(file, local.value(), context, guards, depth + 1);
            }
            if (local != null && local.declaredType() != null && !local.declaredType().matches("WebApplication|IEndpointRouteBuilder|RouteGroupBuilder")) {
                return null;
            }
            // app, endpoints, routes, group 파라미터
            return "";
        }
        if (receiver.getType().equals("invocation_expression")) {
            TSNode function = field(receiver, "function");
            if (function == null || !function.getType().equals("member_access_expression")) {
                return null;
            }
            String method = file.text(field(function, "name"));
            TSNode inner = field(function, "expression");
            switch (method) {
                case "MapGroup" -> {
                    List<TSNode> args = CsFile.arguments(receiver);
                    String prefix = args.isEmpty() ? null : file.stringValue(args.get(0));
                    String parent = groupPrefix(file, inner, context, guards, depth + 1);
                    return parent == null || prefix == null ? null : EntryPointDetector.joinPath(parent, prefix);
                }
                case "RequireAuthorization", "AllowAnonymous", "WithTags", "WithName", "WithOpenApi", "RequireCors",
                     "AddEndpointFilter", "RequireRateLimiting", "WithMetadata" -> {
                    if (method.equals("RequireAuthorization") || method.equals("AllowAnonymous")
                            || method.equals("AddEndpointFilter") && GUARD_ATTRIBUTE.matcher(file.text(receiver)).find()) {
                        guards.add(method + "(" + String.join(", ", CsFile.arguments(receiver).stream().map(file::text).toList()) + ")");
                    }
                    return groupPrefix(file, inner, context, guards, depth + 1);
                }
                case "Build" -> {
                    return "";
                }
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    /** app.MapGet(...).RequireAuthorization("admin") 처럼 뒤에 이어 붙인 인증 */
    private List<String> chainedGuards(CsFile file, TSNode call) {
        List<String> result = new ArrayList<>();
        TSNode current = call;
        for (int i = 0; i < 8; i++) {
            TSNode parent = current.getParent();
            if (isNull(parent) || !parent.getType().equals("member_access_expression")) {
                break;
            }
            TSNode invocation = parent.getParent();
            if (isNull(invocation) || !invocation.getType().equals("invocation_expression")) {
                break;
            }
            String method = file.text(field(parent, "name"));
            if (method.equals("RequireAuthorization") || method.equals("AllowAnonymous")) {
                result.add(method + "(" + String.join(", ", CsFile.arguments(invocation).stream().map(file::text).toList()) + ")");
            }
            current = invocation;
        }
        return result;
    }

    private CsFunc handler(CsFile file, TSNode node, String method, String path, CsFunc context) {
        node = CsFile.unwrap(node);
        if (node == null) {
            return null;
        }
        if (CsProject.LAMBDAS.contains(node.getType())) {
            return project.registerLambda(file, node, method + " " + path, context);
        }
        // 메서드 그룹: Handlers.Post · ProductEndpoints.Search · Search
        String text = file.text(node);
        String[] parts = text.split("\\.");
        String methodName = parts[parts.length - 1];
        CsClass cls = parts.length >= 2 ? project.cls(parts[parts.length - 2]) : project.owner(context);
        CsFunc target = cls == null ? null : project.findMethod(cls, methodName, -1, 0);
        return target;
    }

    private void add(String method, String path, CsFunc handler, List<String> guards, String framework) {
        routes.add(new Route(method, path, handler, List.copyOf(new LinkedHashSet<>(guards)), framework));
    }

    static Set<String> handlerKeys(List<Route> routes) {
        Set<String> keys = new HashSet<>();
        routes.forEach(r -> keys.add(r.handler().key()));
        return keys;
    }
}
