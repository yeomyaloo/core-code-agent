package com.codeagent.core.ruby;

import com.codeagent.core.java.EntryPointDetector;
import com.codeagent.core.ruby.RubyProject.RubyClass;
import com.codeagent.core.ruby.RubyProject.RubyFunc;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static com.codeagent.core.ruby.RubyFile.field;
import static com.codeagent.core.ruby.RubyFile.isNull;
import static com.codeagent.core.ruby.RubyFile.namedChildren;

/**
 * Ruby HTTP 진입점.
 * <ul>
 *   <li>Rails config/routes.rb (draw(:x) 로 나눈 config/routes/x.rb 포함): get·post·…·match·root, resources·resource
 *       (only·except·path·controller, member·collection, 중첩), namespace·scope·controller, Devise authenticate.
 *       guards 는 authenticate 블록과 컨트롤러(부모 포함)의 인증 관련 before_action (only·except·skip 반영).</li>
 *   <li>Sinatra: get '/x' do ... end (클래식·Sinatra::Base), namespace '/api' do, before 필터.</li>
 * </ul>
 */
final class RubyRoutes {

    record Route(String httpMethod, String path, RubyFunc handler, List<String> guards, String framework) {
    }

    /** 라우트 묶음: 경로, 컨트롤러 module, 컨트롤러(resource·controller 블록), 인증 블록 */
    private record Context(String path, List<String> modules, String controller, List<String> guards) {
        Context path(String more) {
            return new Context(EntryPointDetector.joinPath(path, more), modules, controller, guards);
        }
    }

    private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete", "options", "head");
    private static final Pattern AUTH_FILTER = Pattern.compile("(?i)(auth|login|admin|signed|require|verify|permission|authoriz|"
            + "access|current_user|role|token|session|logged)");

    private final RubyProject project;
    private final List<Route> routes = new ArrayList<>();

    RubyRoutes(RubyProject project) {
        this.project = project;
    }

    List<Route> detect() {
        for (RubyFile file : project.files()) {
            if (file.filePath.endsWith("config/routes.rb")) {
                for (TSNode call : RubyFile.descendants(file.root, Set.of("call"), Set.of())) {
                    if (file.text(field(call, "method")).equals("draw") && field(call, "block") != null) {
                        rails(file, blockBody(field(call, "block")), new Context("", List.of(), null, List.of()));
                    }
                }
            }
        }
        sinatra();
        return routes;
    }

    // ---- Rails ----

    private void rails(RubyFile file, List<TSNode> statements, Context ctx) {
        for (TSNode statement : statements) {
            if (!statement.getType().equals("call") || field(statement, "receiver") != null) {
                continue;
            }
            String method = file.text(field(statement, "method"));
            List<TSNode> args = RubyFile.positional(statement);
            TSNode block = field(statement, "block");
            switch (method) {
                case "get", "post", "put", "patch", "delete", "options", "head", "match", "root" -> railsRoute(file, statement, method, ctx);
                case "resources", "resource" -> {
                    for (TSNode arg : args) {
                        String name = file.stringValue(arg);
                        if (name != null) {
                            resources(file, statement, name, method.equals("resource"), ctx);
                        }
                    }
                }
                case "namespace" -> {
                    String name = args.isEmpty() ? null : file.stringValue(args.get(0));
                    if (name != null && block != null) {
                        List<String> modules = new ArrayList<>(ctx.modules());
                        modules.add(name);
                        String path = file.stringValue(file.keyword(statement, "path"));
                        rails(file, blockBody(block), new Context(EntryPointDetector.joinPath(ctx.path(), path != null ? path : name),
                                modules, null, ctx.guards()));
                    }
                }
                case "scope" -> {
                    if (block == null) {
                        continue;
                    }
                    String path = args.isEmpty() ? file.stringValue(file.keyword(statement, "path")) : file.stringValue(args.get(0));
                    List<String> modules = new ArrayList<>(ctx.modules());
                    String module = file.stringValue(file.keyword(statement, "module"));
                    if (module != null) {
                        modules.add(module);
                    }
                    rails(file, blockBody(block), new Context(EntryPointDetector.joinPath(ctx.path(), path == null ? "" : path.replaceAll("\\(.*?\\)", "")),
                            modules, ctx.controller(), ctx.guards()));
                }
                case "controller" -> {
                    String name = args.isEmpty() ? null : file.stringValue(args.get(0));
                    if (name != null && block != null) {
                        rails(file, blockBody(block), new Context(ctx.path(), ctx.modules(), name, ctx.guards()));
                    }
                }
                case "authenticate", "authenticated" -> {
                    if (block != null) {
                        List<String> guards = new ArrayList<>(ctx.guards());
                        guards.add(file.text(statement).split("\\s+do\\b|\\{", 2)[0].strip());
                        rails(file, blockBody(block), new Context(ctx.path(), ctx.modules(), ctx.controller(), guards));
                    }
                }
                case "constraints", "defaults", "with_options" -> {
                    if (block != null) {
                        rails(file, blockBody(block), ctx);
                    }
                }
                case "draw" -> {
                    // draw(:admin) → config/routes/admin.rb
                    String name = args.isEmpty() ? null : file.stringValue(args.get(0));
                    for (RubyFile other : project.files()) {
                        if (name != null && other.filePath.endsWith("config/routes/" + name + ".rb")) {
                            rails(other, namedChildren(other.root), ctx);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private void railsRoute(RubyFile file, TSNode call, String verb, Context ctx) {
        List<TSNode> args = RubyFile.positional(call);
        String path = args.isEmpty() ? null : file.stringValue(args.get(0));
        String target = file.stringValue(file.keyword(call, "to"));
        TSNode[] rocket = file.rocketPair(call);
        if (rocket != null) {
            path = file.stringValue(rocket[0]);
            target = file.stringValue(rocket[1]);
        }
        if (verb.equals("root")) {
            target = target != null ? target : path;
            path = "/";
        }
        if (path == null) {
            return;
        }
        String controller = file.stringValue(file.keyword(call, "controller"));
        String action = file.stringValue(file.keyword(call, "action"));
        if (target != null && target.contains("#")) {
            String[] parts = target.split("#", 2);
            controller = parts[0];
            action = parts[1];
        } else if (ctx.controller() != null) {
            controller = controller != null ? controller : ctx.controller();
            action = action != null ? action : path.replaceAll("^/", "").replaceAll("/.*", "").replaceAll(":", "");
        } else if (controller == null && path.contains("/") && !path.startsWith(":")) {
            // get 'photos/search' → photos#search
            String clean = path.replaceAll("^/", "");
            int slash = clean.lastIndexOf('/');
            controller = clean.substring(0, slash);
            action = clean.substring(slash + 1);
        }
        if (controller == null || action == null) {
            return;
        }
        List<String> methods = new ArrayList<>();
        if (verb.equals("match")) {
            List<String> via = file.strings(file.keyword(call, "via"));
            methods.addAll(via.isEmpty() || via.contains("all") ? List.of("ANY") : via.stream().map(v -> v.toUpperCase(Locale.ROOT)).toList());
        } else {
            methods.add(verb.equals("root") ? "GET" : verb.toUpperCase(Locale.ROOT));
        }
        String fullPath = EntryPointDetector.joinPath(ctx.path(), path);
        for (String m : methods) {
            addRails(m, fullPath, controller, action, ctx);
        }
    }

    private void resources(RubyFile file, TSNode call, String name, boolean singular, Context ctx) {
        String segment = file.stringValue(file.keyword(call, "path"));
        String base = EntryPointDetector.joinPath(ctx.path(), segment != null ? segment : name);
        String controllerOption = file.stringValue(file.keyword(call, "controller"));
        String controller = controllerOption != null ? controllerOption : singular ? plural(name) : name;
        List<String> only = file.strings(file.keyword(call, "only"));
        List<String> except = file.strings(file.keyword(call, "except"));
        String member = singular ? base : base + "/:id";
        String[][] actions = singular
                ? new String[][]{{"show", "GET", base}, {"new", "GET", base + "/new"}, {"create", "POST", base}, {"edit", "GET", base + "/edit"},
                {"update", "PATCH", base}, {"update", "PUT", base}, {"destroy", "DELETE", base}}
                : new String[][]{{"index", "GET", base}, {"new", "GET", base + "/new"}, {"create", "POST", base}, {"show", "GET", member},
                {"edit", "GET", member + "/edit"}, {"update", "PATCH", member}, {"update", "PUT", member}, {"destroy", "DELETE", member}};
        for (String[] action : actions) {
            if (!only.isEmpty() && !only.contains(action[0]) || except.contains(action[0])) {
                continue;
            }
            addRails(action[1], action[2], controller, action[0], ctx);
        }
        TSNode block = field(call, "block");
        if (block == null) {
            return;
        }
        String nested = singular ? base : base + "/:" + singularOf(name) + "_id";
        for (TSNode statement : blockBody(block)) {
            if (!statement.getType().equals("call") || field(statement, "receiver") != null) {
                continue;
            }
            String method = file.text(field(statement, "method"));
            TSNode inner = field(statement, "block");
            if ((method.equals("member") || method.equals("collection")) && inner != null) {
                String path = method.equals("member") ? member : base;
                resourceVerbs(file, blockBody(inner), new Context(path, ctx.modules(), controller, ctx.guards()));
            } else if (VERBS.contains(method)) {
                String on = file.stringValue(file.keyword(statement, "on"));
                String path = "member".equals(on) ? member : "collection".equals(on) ? base : nested;
                resourceVerbs(file, List.of(statement), new Context(path, ctx.modules(), controller, ctx.guards()));
            } else {
                rails(file, List.of(statement), new Context(nested, ctx.modules(), null, ctx.guards()));
            }
        }
    }

    /** member·collection 안의 get :preview · post 'refund' */
    private void resourceVerbs(RubyFile file, List<TSNode> statements, Context ctx) {
        for (TSNode statement : statements) {
            if (!statement.getType().equals("call") || field(statement, "receiver") != null) {
                continue;
            }
            String verb = file.text(field(statement, "method"));
            if (!VERBS.contains(verb)) {
                continue;
            }
            List<TSNode> args = RubyFile.positional(statement);
            String name = args.isEmpty() ? null : file.stringValue(args.get(0));
            if (name == null) {
                continue;
            }
            String target = file.stringValue(file.keyword(statement, "to"));
            String action = target != null && target.contains("#") ? target.split("#", 2)[1] : file.stringValue(file.keyword(statement, "action"));
            addRails(verb.toUpperCase(Locale.ROOT), EntryPointDetector.joinPath(ctx.path(), name), ctx.controller(),
                    action != null ? action : name, ctx);
        }
    }

    private void addRails(String method, String path, String controller, String action, Context ctx) {
        RubyClass cls = controllerClass(controller, ctx.modules());
        RubyFunc handler = cls == null ? null : project.findMethod(cls, action, false, 0);
        if (handler == null) {
            return;
        }
        List<String> guards = new ArrayList<>(ctx.guards());
        guards.addAll(filters(cls, action));
        routes.add(new Route(method, path, handler, List.copyOf(new LinkedHashSet<>(guards)), "rails"));
    }

    /** 'admin/reports' (module admin 안에서는 'reports') → Admin::ReportsController */
    private RubyClass controllerClass(String controller, List<String> modules) {
        List<String> parts = new ArrayList<>();
        if (!controller.startsWith("/")) {
            parts.addAll(modules);
        }
        for (String part : controller.replaceAll("^/", "").split("/")) {
            parts.add(part);
        }
        List<String> camel = parts.stream().map(RubyRoutes::camelize).toList();
        String fullName = String.join("::", camel) + "Controller";
        RubyClass cls = project.cls(fullName);
        if (cls != null) {
            return cls;
        }
        String simple = camel.get(camel.size() - 1) + "Controller";
        for (RubyClass candidate : project.classes()) {
            if (candidate.fullName.equals(simple) || candidate.fullName.endsWith("::" + simple)) {
                return candidate;
            }
        }
        return null;
    }

    /** 이 액션에 걸리는 인증 관련 before_action (부모 클래스부터, skip_before_action 반영) */
    private List<String> filters(RubyClass cls, String action) {
        List<RubyClass> chain = new ArrayList<>();
        for (RubyClass current = cls; current != null && chain.size() < 8; current = project.superClass(current)) {
            chain.add(0, current);
        }
        List<String> result = new ArrayList<>();
        for (RubyClass current : chain) {
            for (TSNode call : RubyFile.children(field(current.node, "body"), "call")) {
                if (current.file.text(field(call, "method")).equals("http_basic_authenticate_with")) {
                    result.add("http_basic_authenticate_with");
                }
            }
            for (String[] filter : current.filters) {
                boolean applies = (filter[1].isEmpty() || List.of(filter[1].split(",")).contains(action))
                        && (filter[2].isEmpty() || !List.of(filter[2].split(",")).contains(action));
                if (!applies) {
                    continue;
                }
                if (filter[3].equals("skip")) {
                    result.remove(filter[0]);
                } else if (AUTH_FILTER.matcher(filter[0]).find()) {
                    result.add(filter[0]);
                }
            }
        }
        return result;
    }

    // ---- Sinatra ----

    private void sinatra() {
        for (RubyFile file : project.files()) {
            String text = new String(file.source, java.nio.charset.StandardCharsets.UTF_8);
            if (!text.contains("sinatra") && !text.contains("Sinatra::")) {
                continue;
            }
            sinatraScope(file, namedChildren(file.root), "", new ArrayList<>());
        }
    }

    private void sinatraScope(RubyFile file, List<TSNode> statements, String prefix, List<String[]> befores) {
        List<String[]> scopeBefores = new ArrayList<>(befores);
        // 같은 범위의 before 필터를 먼저 모은다 (Sinatra 는 정의 순서와 상관없이 모든 요청 앞에 돈다)
        for (TSNode statement : statements) {
            if (statement.getType().equals("call") && field(statement, "receiver") == null
                    && file.text(field(statement, "method")).equals("before") && field(statement, "block") != null) {
                List<TSNode> args = RubyFile.positional(statement);
                String pattern = args.isEmpty() ? "" : file.stringValue(args.get(0));
                String body = file.text(field(statement, "block")).replaceAll("\\s+", " ");
                scopeBefores.add(new String[]{EntryPointDetector.joinPath(prefix, pattern == null ? "" : pattern),
                        "before" + (pattern == null || pattern.isEmpty() ? "" : " '" + pattern + "'") + ": "
                                + (body.length() > 60 ? body.substring(0, 60) + "..." : body)});
            }
        }
        for (TSNode statement : statements) {
            String type = statement.getType();
            if (type.equals("class") || type.equals("module")) {
                sinatraScope(file, namedChildren(field(statement, "body")), prefix, scopeBefores);
                continue;
            }
            if (type.equals("body_statement")) {
                sinatraScope(file, namedChildren(statement), prefix, scopeBefores);
                continue;
            }
            if (!type.equals("call") || field(statement, "receiver") != null) {
                continue;
            }
            String method = file.text(field(statement, "method"));
            TSNode block = field(statement, "block");
            List<TSNode> args = RubyFile.positional(statement);
            String path = args.isEmpty() ? null : file.stringValue(args.get(0));
            if (method.equals("namespace") && block != null && path != null) {
                sinatraScope(file, blockBody(block), EntryPointDetector.joinPath(prefix, path), scopeBefores);
            } else if ((VERBS.contains(method) || method.equals("link")) && block != null && path != null) {
                String full = EntryPointDetector.joinPath(prefix, path);
                String httpMethod = method.toUpperCase(Locale.ROOT);
                RubyFunc handler = project.registerBlock(file, block, httpMethod + " " + full);
                List<String> guards = new ArrayList<>();
                for (String[] before : scopeBefores) {
                    if (globMatches(before[0], full)) {
                        guards.add(before[1]);
                    }
                }
                routes.add(new Route(httpMethod, full, handler, guards, "sinatra"));
            }
        }
    }

    /** before '/admin/*' 같은 패턴이 경로에 걸리는지 ("" 이나 "/" 는 모두) */
    private static boolean globMatches(String pattern, String path) {
        if (pattern.isEmpty() || pattern.equals("/")) {
            return true;
        }
        String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q").replaceAll(":\\w+", "\\\\E[^/]+\\\\Q");
        return path.matches(regex) || path.startsWith(pattern.replace("*", ""));
    }

    // ---- 공용 ----

    private static List<TSNode> blockBody(TSNode block) {
        TSNode body = field(block, "body");
        return body == null ? List.of() : namedChildren(body);
    }

    static String camelize(String snake) {
        StringBuilder sb = new StringBuilder();
        for (String part : snake.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private static String plural(String name) {
        return name.endsWith("s") ? name : name.endsWith("y") ? name.substring(0, name.length() - 1) + "ies" : name + "s";
    }

    private static String singularOf(String name) {
        if (name.endsWith("ies")) {
            return name.substring(0, name.length() - 3) + "y";
        }
        return name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
    }

    static Set<String> handlerKeys(List<Route> routes) {
        Set<String> keys = new HashSet<>();
        routes.forEach(r -> keys.add(r.handler().key()));
        return keys;
    }

    static boolean isNullNode(TSNode node) {
        return isNull(node);
    }
}
