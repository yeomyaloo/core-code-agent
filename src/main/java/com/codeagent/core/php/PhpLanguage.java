package com.codeagent.core.php;

import com.codeagent.core.LanguageParser;
import com.codeagent.core.SourceFiles;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.php.PhpProject.Callee;
import com.codeagent.core.php.PhpProject.Local;
import com.codeagent.core.php.PhpProject.PhpClass;
import com.codeagent.core.php.PhpProject.PhpFunc;
import com.codeagent.core.rules.PhpSinkRules;
import com.codeagent.core.rules.PhpSinkRules.PhpRule;
import org.treesitter.TSNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.codeagent.core.php.PhpFile.field;
import static com.codeagent.core.php.PhpFile.namedChildren;

/**
 * PHP 백엔드 파서 (tree-sitter). 저장소에 .php 소스가 있으면 구조·호출·위험 지점·HTTP 진입점을 만든다.
 * 호출 대상은 PHP 에 적힌 타입으로 찾는다: $this·타입 있는 파라미터·프로퍼티·new 로 만든 값의 메서드, 정적 호출,
 * 인터페이스 메서드는 그 인터페이스를 구현한 프로젝트 클래스의 메서드로 잇는다.
 */
public final class PhpLanguage implements LanguageParser {

    private static final int SNIPPET_MAX_LENGTH = 300;
    private static final Set<String> CALL_TYPES = Set.of("function_call_expression", "member_call_expression",
            "nullsafe_member_call_expression", "scoped_call_expression", "object_creation_expression", "include_expression",
            "include_once_expression", "require_expression", "require_once_expression", "echo_statement", "print_intrinsic",
            "shell_command_expression");
    private static final Set<String> DECLARATIONS = Set.of("class_declaration", "interface_declaration", "trait_declaration",
            "enum_declaration", "function_definition", "method_declaration");
    private static final Pattern SUPERGLOBAL = Pattern.compile("\\$_(GET|POST|REQUEST|COOKIE|FILES|SERVER)\\b");
    private static final Pattern REQUEST_CALL = Pattern.compile("\\$(\\w+)->(?:input|query|get|post|all|only|except|cookie|header"
            + "|route|json|file|getContent|getQueryParams|getParsedBody|getUploadedFiles|getCookieParams|getServerParams|getAttribute"
            + "|validated|string|integer|boolean|getGet|getPost|getVar|getJSON|getRawInput)\\b");
    private static final Pattern REQUEST_BAG = Pattern.compile("\\$(\\w+)->(?:query|request|cookies|headers|attributes|files)->");
    private static final Pattern FREE_REQUEST = Pattern.compile("\\brequest\\(|\\bRequest::(?:input|get|query|all|post)\\b|\\$this->request->");

    @Override
    public String language() {
        return "php";
    }

    @Override
    public boolean detect(Path root) {
        return SourceFiles.any(root, PhpProject.SKIP_DIRS, PhpProject::isSource);
    }

    @Override
    public void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors) {
        PhpProject project = PhpProject.load(root, parseErrors);
        new Builder(project, draft).build();
    }

    private static final class Builder {
        private final PhpProject project;
        private final CodeGraphDraft draft;
        private final Set<String> structured = new HashSet<>();
        private Set<String> handlers = Set.of();

        Builder(PhpProject project, CodeGraphDraft draft) {
            this.project = project;
            this.draft = draft;
        }

        void build() {
            // 진입점을 먼저 찾는다: 익명 처리기(Route::get('/x', function () {...}))에 "GET /x" 같은 이름을 붙여 따로 색인한다
            List<PhpRoutes.Route> routes = new PhpRoutes(project).detect();
            handlers = PhpRoutes.handlerKeys(routes);
            List<PhpFunc> functions = project.functions();
            functions.forEach(this::addStructure);
            for (PhpFunc fn : functions) {
                addCalls(fn);
            }
            routes.forEach(this::addEntryPoint);
        }

        private void addStructure(PhpFunc fn) {
            if (!structured.add(fn.key())) {
                return;
            }
            PhpFile file = fn.file();
            String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, file.filePath, file.filePath, null, null,
                    Map.of("language", "php")));
            String parent = fileKey;
            PhpClass owner = fn.owner();
            if (owner != null && fn.parent() == null) {
                List<String> superTypes = new ArrayList<>();
                if (owner.parent != null) {
                    superTypes.add(owner.parent);
                }
                superTypes.addAll(owner.interfaces);
                String ownerFile = draft.addNode(new NodeDraft(CodeNodeKind.FILE, owner.file.filePath, owner.file.filePath, null, null,
                        Map.of("language", "php")));
                parent = draft.addNode(new NodeDraft(CodeNodeKind.CLASS, owner.fqcn, owner.file.filePath, PhpFile.line(owner.node),
                        PhpFile.endLine(owner.node), Map.of("declaration", owner.isInterface ? "interface" : "class",
                        "superTypes", superTypes, "language", "php")));
                draft.addEdge(ownerFile, parent, CodeEdgeKind.CONTAINS);
            }
            boolean main = fn.name().equals("<main>");
            String methodKey = draft.addNode(new NodeDraft(CodeNodeKind.METHOD, fn.key(), file.filePath, main ? 1 : PhpFile.line(fn.node()),
                    PhpFile.endLine(fn.node()), Map.of("name", fn.name(), "annotations", List.of(), "language", "php")));
            draft.addEdge(parent, methodKey, CodeEdgeKind.CONTAINS);
        }

        private void addCalls(PhpFunc fn) {
            List<TSNode> calls = new ArrayList<>();
            TSNode body = fn.name().equals("<main>") ? fn.node() : field(fn.node(), "body");
            if (fn.node().getType().equals("arrow_function")) {
                body = field(fn.node(), "body");
                if (body != null && CALL_TYPES.contains(body.getType())) {
                    calls.add(body);
                }
            }
            collect(fn, body, calls);
            Set<String> requestNames = requestNames(fn);
            for (TSNode call : calls) {
                resolve(fn, call, requestNames);
            }
        }

        private void collect(PhpFunc fn, TSNode node, List<TSNode> out) {
            for (TSNode child : namedChildren(node)) {
                if (DECLARATIONS.contains(child.getType())
                        || PhpProject.FUNCTION_TYPES.contains(child.getType()) && project.isRegistered(fn.file(), child)) {
                    continue;
                }
                if (CALL_TYPES.contains(child.getType())) {
                    out.add(child);
                }
                collect(fn, child, out);
            }
        }

        private void resolve(PhpFunc fn, TSNode call, Set<String> requestNames) {
            PhpFile file = fn.file();
            String type = call.getType();
            List<TSNode> args;
            String name;
            Optional<PhpRule> rule;
            switch (type) {
                case "include_expression", "include_once_expression", "require_expression", "require_once_expression" -> {
                    name = type.replace("_expression", "");
                    args = namedChildren(call);
                    rule = PhpSinkRules.named(name);
                }
                case "echo_statement", "print_intrinsic" -> {
                    name = type.equals("echo_statement") ? "echo" : "print";
                    args = namedChildren(call);
                    rule = PhpSinkRules.named(name);
                }
                case "shell_command_expression" -> {
                    if (PhpFile.interpolationFree(call)) {
                        return;
                    }
                    addSink(fn, call, "COMMAND", "CWE-78", "`shell`");
                    return;
                }
                default -> {
                    Callee callee = project.callee(fn, call);
                    if (!callee.functions().isEmpty()) {
                        for (PhpFunc target : callee.functions()) {
                            draft.addEdge(methodKey(fn), methodKey(target), CodeEdgeKind.CALLS);
                            addStructure(target);
                        }
                        return;
                    }
                    args = PhpFile.arguments(call);
                    if (redirectChain(file, call)) {
                        name = "redirect()->" + file.text(field(call, "name"));
                        rule = PhpSinkRules.named("redirect");
                    } else if (callee.external() != null && PhpSinkRules.named(callee.external()).isPresent()) {
                        name = callee.external();
                        rule = PhpSinkRules.named(name);
                    } else if (callee.unknownMethod() != null) {
                        name = "*->" + callee.unknownMethod();
                        rule = PhpSinkRules.method(callee.unknownMethod());
                        if (rule.isEmpty() && callee.external() == null) {
                            draft.countUnresolvedCall();
                        }
                    } else {
                        return;
                    }
                }
            }
            if (rule.isEmpty() || !matches(fn, rule.get(), args, requestNames)) {
                return;
            }
            addSink(fn, call, rule.get().category(), rule.get().cwe(), shortApi(name));
        }

        /** redirect()->to($x) · redirect()->away($x) */
        private static boolean redirectChain(PhpFile file, TSNode call) {
            if (!call.getType().equals("member_call_expression")) {
                return false;
            }
            String method = file.text(field(call, "name"));
            TSNode object = PhpFile.unwrap(field(call, "object"));
            return Set.of("to", "away", "intended").contains(method) && object != null
                    && object.getType().equals("function_call_expression") && file.text(field(object, "function")).equals("redirect");
        }

        private boolean matches(PhpFunc fn, PhpRule rule, List<TSNode> args, Set<String> requestNames) {
            PhpFile file = fn.file();
            return switch (rule.condition()) {
                case ALWAYS -> true;
                case NOT_CONSTANT -> rule.argument() < args.size() && !project.constant(fn, args.get(rule.argument()), 0);
                case FORMATTED -> args.stream().anyMatch(a -> formatted(fn, a, 0));
                case REQUEST_VALUE -> args.stream().anyMatch(a -> fromRequest(fn, a, requestNames, 0));
                case WEAK_ALGORITHM -> !args.isEmpty() && PhpSinkRules.weakAlgorithm(file.stringValue(args.get(0)));
                case XML_ENTITIES -> args.stream().anyMatch(a -> file.text(a).contains("LIBXML_NOENT") || file.text(a).contains("LIBXML_DTDLOAD"));
                case LOCATION_HEADER -> !args.isEmpty() && !project.constant(fn, args.get(0), 0)
                        && file.text(args.get(0)).toLowerCase(Locale.ROOT).contains("location");
                case CURL_URL -> args.size() >= 3 && file.text(args.get(1)).contains("CURLOPT_URL") && !project.constant(fn, args.get(2), 0);
            };
        }

        /** 문자열 보간·. ·sprintf 로 상수가 아닌 값을 넣어 만든 문자열 (지역 변수와 .= 도 따라간다) */
        private boolean formatted(PhpFunc fn, TSNode expr, int depth) {
            expr = PhpFile.unwrap(expr);
            if (expr == null || depth > 5) {
                return false;
            }
            PhpFile file = fn.file();
            switch (expr.getType()) {
                case "encapsed_string", "heredoc" -> {
                    return !PhpFile.interpolationFree(expr);
                }
                case "binary_expression" -> {
                    return file.text(field(expr, "operator")).equals(".")
                            && (!project.constant(fn, field(expr, "left"), 0) || !project.constant(fn, field(expr, "right"), 0));
                }
                case "function_call_expression" -> {
                    String name = file.text(field(expr, "function")).toLowerCase(Locale.ROOT);
                    if (Set.of("sprintf", "vsprintf", "implode", "join", "str_replace", "strtr").contains(name)) {
                        List<TSNode> args = PhpFile.arguments(expr);
                        return args.stream().anyMatch(a -> !project.constant(fn, a, 0));
                    }
                    return false;
                }
                case "variable_name" -> {
                    String name = file.text(expr).substring(1);
                    Local local = project.local(fn, name);
                    if (local != null && local.value() != null && formatted(fn, local.value(), depth + 1)) {
                        return true;
                    }
                    TSNode body = fn.name().equals("<main>") ? fn.node() : field(fn.node(), "body");
                    for (TSNode assign : PhpFile.descendants(body, Set.of("augmented_assignment_expression", "assignment_expression"), Set.of())) {
                        if (!file.text(field(assign, "left")).equals("$" + name)) {
                            continue;
                        }
                        TSNode right = field(assign, "right");
                        boolean append = assign.getType().equals("augmented_assignment_expression");
                        if (append && !project.constant(fn, right, 0) || !append && right != null && formatted(fn, right, depth + 1)) {
                            return true;
                        }
                    }
                    return false;
                }
                default -> {
                    return false;
                }
            }
        }

        /** 요청 값을 담는 이름: Request 타입·$request 파라미터, 라우트 처리기의 스칼라 파라미터(경로 변수), Slim 의 $args */
        private Set<String> requestNames(PhpFunc fn) {
            Set<String> names = new HashSet<>(Set.of("request", "req"));
            for (PhpFunc current = fn; current != null; current = current.parent()) {
                boolean handler = handlers.contains(current.key());
                PhpFile file = current.file();
                for (TSNode param : namedChildren(field(current.node(), "parameters"))) {
                    String name = file.text(field(param, "name")).replaceFirst("^&?\\.*\\$", "");
                    TSNode typeNode = field(param, "type");
                    String type = project.typeName(file, typeNode, current.owner());
                    if (type != null && requestType(type)) {
                        names.add(name);
                    } else if (handler && (typeNode == null || typeNode.getType().equals("primitive_type")
                            || file.text(typeNode).matches("\\??(string|int|array|mixed)"))) {
                        // 경로 변수 ({id}) 와 Slim 의 $args
                        names.add("=" + name);
                    }
                }
            }
            return names;
        }

        private boolean requestType(String type) {
            String simple = type.substring(type.lastIndexOf('\\') + 1);
            if (simple.endsWith("Request") || simple.equals("ServerRequestInterface") || simple.equals("IncomingRequest")) {
                return true;
            }
            PhpClass cls = project.cls(type);
            return cls != null && cls.parent != null && cls.parent.endsWith("FormRequest");
        }

        private boolean fromRequest(PhpFunc fn, TSNode expr, Set<String> requestNames, int depth) {
            if (expr == null || depth > 4) {
                return false;
            }
            PhpFile file = fn.file();
            String text = file.text(expr);
            if (SUPERGLOBAL.matcher(text).find() || FREE_REQUEST.matcher(text).find()) {
                return true;
            }
            for (Pattern pattern : List.of(REQUEST_CALL, REQUEST_BAG)) {
                Matcher m = pattern.matcher(text);
                while (m.find()) {
                    if (requestNames.contains(m.group(1))) {
                        return true;
                    }
                }
            }
            List<TSNode> variables = new ArrayList<>(PhpFile.descendants(expr, Set.of("variable_name"), PhpProject.FUNCTION_TYPES));
            if (expr.getType().equals("variable_name")) {
                variables.add(expr);
            }
            for (TSNode variable : variables) {
                String name = file.text(variable).substring(1);
                if (requestNames.contains("=" + name)) {
                    return true;
                }
                Local local = project.local(fn, name);
                if (local != null && local.value() != null && local.value() != expr && fromRequest(fn, local.value(), requestNames, depth + 1)) {
                    return true;
                }
            }
            return false;
        }

        private void addSink(PhpFunc fn, TSNode node, String category, String cwe, String api) {
            PhpFile file = fn.file();
            int line = PhpFile.line(node);
            int column = node.getStartPoint().getColumn() + 1;
            String snippet = file.text(node).strip().replaceAll("\\s*\\n\\s*", " ");
            if (snippet.length() > SNIPPET_MAX_LENGTH) {
                snippet = snippet.substring(0, SNIPPET_MAX_LENGTH) + "...";
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("category", category);
            props.put("cwe", cwe);
            props.put("api", api);
            props.put("method", fn.key());
            props.put("snippet", snippet);
            props.put("language", "php");
            String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK, fn.key() + "@L" + line + ":" + column + " " + api,
                    file.filePath, line, PhpFile.endLine(node), props));
            draft.addEdge(methodKey(fn), sinkKey, CodeEdgeKind.CALLS);
        }

        private void addEntryPoint(PhpRoutes.Route route) {
            PhpFunc handler = route.handler();
            addStructure(handler);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("httpMethod", route.httpMethod());
            props.put("path", route.path());
            props.put("handler", handler.key());
            props.put("framework", route.framework());
            props.put("guards", route.guards());
            props.put("language", "php");
            boolean main = handler.name().equals("<main>");
            String entryKey = draft.addNode(new NodeDraft(CodeNodeKind.ENTRY_POINT,
                    route.httpMethod() + " " + route.path() + " -> " + handler.key(), handler.file().filePath,
                    main ? 1 : PhpFile.line(handler.node()), PhpFile.endLine(handler.node()), props));
            draft.addEdge(entryKey, methodKey(handler), CodeEdgeKind.ROUTES_TO);
        }

        static String methodKey(PhpFunc fn) {
            return CodeGraphDraft.key(CodeNodeKind.METHOD, fn.key());
        }
    }

    /** Illuminate\Support\Facades\DB::select → DB::select, PDO->query 그대로 */
    static String shortApi(String name) {
        int op = Math.max(name.indexOf("::"), name.indexOf("->"));
        String head = op < 0 ? name : name.substring(0, op);
        String tail = op < 0 ? "" : name.substring(op);
        String prefix = head.startsWith("new ") ? "new " : "";
        head = head.replaceFirst("^new ", "");
        return prefix + head.substring(head.lastIndexOf('\\') + 1) + tail;
    }
}
