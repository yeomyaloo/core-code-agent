package com.codeagent.core.golang;

import com.codeagent.core.LanguageParser;
import com.codeagent.core.SourceFiles;
import com.codeagent.core.golang.GoProject.Callee;
import com.codeagent.core.golang.GoProject.GoFunc;
import com.codeagent.core.golang.GoProject.GoType;
import com.codeagent.core.golang.GoProject.Local;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.rules.GoSinkRules;
import com.codeagent.core.rules.GoSinkRules.Condition;
import com.codeagent.core.rules.GoSinkRules.GoRule;
import org.treesitter.TSNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.codeagent.core.golang.GoFile.field;
import static com.codeagent.core.golang.GoFile.namedChildren;

/**
 * Go 백엔드 파서 (tree-sitter). 저장소에 .go 소스가 있으면 구조·호출·위험 지점·HTTP 진입점을 만든다.
 * 호출 대상은 Go 에 적힌 타입으로 찾는다: 패키지 함수, 리시버·파라미터·구조체 필드·:= 로 타입을 아는 값의 메서드,
 * 인터페이스 메서드는 그 인터페이스를 채우는 프로젝트 타입의 메서드로 잇는다.
 */
public final class GoLanguage implements LanguageParser {

    private static final int SNIPPET_MAX_LENGTH = 300;
    /** 요청 객체 타입 → 그 값이 요청에서 온다 */
    private static final Set<String> REQUEST_TYPES = Set.of("net/http.Request", "github.com/gin-gonic/gin.Context",
            "github.com/labstack/echo/v4.Context", "github.com/gofiber/fiber/v2.Ctx");
    private static final Pattern REQUEST_MEMBER = Pattern.compile("\\b(\\w+)\\.(?:URL\\.Query\\(\\)|URL\\.RawQuery|URL\\.Path"
            + "|FormValue|PostFormValue|Form\\b|PostForm\\b|MultipartForm|Header\\.Get|Header\\b|Cookie|Cookies|Body\\b|PathValue"
            + "|Query|DefaultQuery|QueryArray|QueryMap|Param|Params|DefaultPostForm|GetHeader|GetRawData|QueryParam|QueryParams"
            + "|FormParams|Request\\(\\)|Request\\.)");
    private static final Pattern FREE_REQUEST = Pattern.compile("\\b(?:chi\\.URLParam|mux\\.Vars)\\(");
    private static final Pattern BIND = Pattern.compile("(?:Bind\\w*|ShouldBind\\w*|BodyParser|Decode|Unmarshal)$");
    /** 응답에 쓰는 함수: 첫 인자가 응답일 때만 위험 지점 */
    private static final Set<String> WRITER_FUNCTIONS = Set.of("io.WriteString", "fmt.Fprintf", "fmt.Fprint", "fmt.Fprintln");
    private static final Set<String> RESPONSE_TYPES = Set.of("net/http.ResponseWriter", "github.com/gin-gonic/gin.ResponseWriter");

    @Override
    public String language() {
        return "go";
    }

    @Override
    public boolean detect(Path root) {
        return SourceFiles.any(root, GoProject.SKIP_DIRS, GoProject::isSource);
    }

    @Override
    public void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors) {
        GoProject project = GoProject.load(root, parseErrors);
        new Builder(project, draft).build();
    }

    private static final class Builder {
        private final GoProject project;
        private final CodeGraphDraft draft;
        private final Set<String> structured = new HashSet<>();

        Builder(GoProject project, CodeGraphDraft draft) {
            this.project = project;
            this.draft = draft;
        }

        void build() {
            // 진입점을 먼저 찾는다: 이름 없는 처리기(func 리터럴)에 "GET /items" 같은 이름을 붙여 따로 색인한다
            List<GoRoutes.Route> routes = new GoRoutes(project).detect();
            List<GoFunc> functions = project.functions();
            functions.forEach(this::addStructure);
            for (GoFunc fn : functions) {
                addCalls(fn);
            }
            routes.forEach(this::addEntryPoint);
        }

        private void addStructure(GoFunc fn) {
            if (!structured.add(fn.key())) {
                return;
            }
            GoFile file = fn.file();
            String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, file.filePath, file.filePath, null, null,
                    Map.of("language", "go")));
            String parent = fileKey;
            GoType owner = fn.receiver();
            if (owner != null) {
                String typeKey = (owner.file.dir.isEmpty() ? "." : owner.file.dir) + "#" + owner.name;
                parent = draft.addNode(new NodeDraft(CodeNodeKind.CLASS, typeKey, owner.file.filePath, GoFile.line(owner.node),
                        GoFile.endLine(owner.node), Map.of("declaration", "struct", "superTypes", owner.embedded, "language", "go")));
                String ownerFile = draft.addNode(new NodeDraft(CodeNodeKind.FILE, owner.file.filePath, owner.file.filePath, null, null,
                        Map.of("language", "go")));
                draft.addEdge(ownerFile, parent, CodeEdgeKind.CONTAINS);
            }
            String methodKey = draft.addNode(new NodeDraft(CodeNodeKind.METHOD, fn.key(), file.filePath, GoFile.line(fn.node()),
                    GoFile.endLine(fn.node()), Map.of("name", fn.name(), "annotations", List.of(), "language", "go")));
            draft.addEdge(parent, methodKey, CodeEdgeKind.CONTAINS);
        }

        /** 함수 안의 호출. 따로 색인한 함수 리터럴 안은 그 함수 몫이다 */
        private void addCalls(GoFunc fn) {
            List<TSNode> calls = new ArrayList<>();
            List<TSNode> literals = new ArrayList<>();
            collect(fn, field(fn.node(), "body"), calls, literals);
            Set<String> requestNames = requestNames(fn);
            for (TSNode call : calls) {
                resolveCall(fn, call, requestNames);
            }
            for (TSNode literal : literals) {
                insecureTls(fn, literal);
            }
        }

        private void collect(GoFunc fn, TSNode node, List<TSNode> calls, List<TSNode> literals) {
            for (TSNode child : namedChildren(node)) {
                if (child.getType().equals("func_literal") && project.isRegistered(fn.file(), child)) {
                    continue;
                }
                if (child.getType().equals("call_expression")) {
                    calls.add(child);
                } else if (child.getType().equals("composite_literal")) {
                    literals.add(child);
                }
                collect(fn, child, calls, literals);
            }
        }

        private void resolveCall(GoFunc fn, TSNode call, Set<String> requestNames) {
            Callee callee = project.callee(fn, call);
            if (!callee.functions().isEmpty()) {
                for (GoFunc target : callee.functions()) {
                    draft.addEdge(methodKey(fn), methodKey(target), CodeEdgeKind.CALLS);
                    addStructure(target);
                }
                return;
            }
            Optional<GoRule> rule = callee.external() == null ? Optional.empty() : GoSinkRules.named(callee.external());
            String api = callee.external() == null ? null : shortApi(callee.external());
            if (rule.isEmpty() && callee.external() == null && callee.unknownMethod() != null) {
                rule = GoSinkRules.method(callee.unknownMethod());
                api = "*." + callee.unknownMethod();
                if (rule.isEmpty()) {
                    draft.countUnresolvedCall();
                }
            }
            if (rule.isEmpty()) {
                return;
            }
            List<TSNode> args = namedChildren(field(call, "arguments"));
            if (WRITER_FUNCTIONS.contains(shortApi(callee.external() == null ? "" : callee.external()))
                    && (args.isEmpty() || !responseWriter(fn, args.get(0)))) {
                return;
            }
            if ("github.com/gin-gonic/gin.Context.Data".equals(callee.external()) && args.size() > 1
                    && !htmlContentType(fn.file().stringValue(GoFile.unwrap(args.get(1))))) {
                // c.Data(200, "application/json", body): HTML 이 아닌 응답은 XSS 가 아니다
                return;
            }
            if (!matches(fn, rule.get(), args, requestNames)) {
                return;
            }
            addSink(fn, call, rule.get().category(), rule.get().cwe(), api);
        }

        /** 상수가 아닌 Content-Type 이거나 HTML 계열 */
        private static boolean htmlContentType(String value) {
            return value == null || value.toLowerCase().contains("html") || value.toLowerCase().contains("svg");
        }

        private boolean responseWriter(GoFunc fn, TSNode arg) {
            String type = project.typeOf(fn, arg);
            if (type != null) {
                return RESPONSE_TYPES.contains(type);
            }
            String text = fn.file().text(arg);
            return text.equals("w") || text.endsWith(".Writer");
        }

        private boolean matches(GoFunc fn, GoRule rule, List<TSNode> args, Set<String> requestNames) {
            return switch (rule.condition()) {
                case ALWAYS -> true;
                case ANY_NOT_CONSTANT -> args.stream().anyMatch(a -> !project.constant(fn, a, 0));
                case NOT_CONSTANT -> rule.argument() < args.size() && !project.constant(fn, args.get(rule.argument()), 0);
                case FORMATTED -> args.stream().anyMatch(a -> formatted(fn, a, 0));
                case REQUEST_VALUE -> args.stream().anyMatch(a -> fromRequest(fn, a, requestNames, 0));
            };
        }

        /** fmt.Sprintf·+ 로 상수가 아닌 값을 넣어 만든 문자열 (지역 변수와 += 도 따라간다) */
        private boolean formatted(GoFunc fn, TSNode expr, int depth) {
            expr = GoFile.unwrap(expr);
            if (expr == null || depth > 5) {
                return false;
            }
            GoFile file = fn.file();
            switch (expr.getType()) {
                case "call_expression" -> {
                    String callee = file.text(field(expr, "function"));
                    if (callee.equals("fmt.Sprintf") || callee.equals("strings.Join") || callee.equals("strings.ReplaceAll")
                            || callee.equals("strings.Replace")) {
                        List<TSNode> args = namedChildren(field(expr, "arguments"));
                        return args.size() > 1 && args.subList(1, args.size()).stream().anyMatch(a -> !project.constant(fn, a, 0))
                                || callee.equals("strings.Join") && !args.isEmpty() && !project.constant(fn, args.get(0), 0);
                    }
                    return false;
                }
                case "binary_expression" -> {
                    if (!file.text(field(expr, "operator")).equals("+")) {
                        return false;
                    }
                    TSNode left = field(expr, "left");
                    TSNode right = field(expr, "right");
                    boolean hasString = GoFile.isString(left) || GoFile.isString(right) || formatted(fn, left, depth + 1)
                            || formatted(fn, right, depth + 1) || binaryWithString(left) || binaryWithString(right);
                    return hasString && (!project.constant(fn, left, 0) || !project.constant(fn, right, 0));
                }
                case "identifier" -> {
                    String name = file.text(expr);
                    Local local = project.local(fn, name);
                    if (local == null) {
                        return false;
                    }
                    if (local.value() != null && formatted(fn, local.value(), depth + 1)) {
                        return true;
                    }
                    // query += " AND name = '" + name + "'"
                    for (TSNode assign : GoFile.descendants(field(fn.node(), "body"), Set.of("assignment_statement"), Set.of())) {
                        List<TSNode> left = namedChildren(field(assign, "left"));
                        if (left.size() == 1 && file.text(left.get(0)).equals(name)) {
                            String operator = file.text(field(assign, "operator"));
                            for (TSNode value : namedChildren(field(assign, "right"))) {
                                if (operator.equals("+=") && !project.constant(fn, value, 0) || formatted(fn, value, depth + 1)) {
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                }
                default -> {
                    return false;
                }
            }
        }

        private static boolean binaryWithString(TSNode node) {
            node = GoFile.unwrap(node);
            if (node == null || !node.getType().equals("binary_expression")) {
                return false;
            }
            return GoFile.isString(field(node, "left")) || GoFile.isString(field(node, "right"))
                    || binaryWithString(field(node, "left")) || binaryWithString(field(node, "right"));
        }

        /** 요청 값을 담는 이름: 요청 객체 파라미터(r *http.Request, c *gin.Context …)와 Bind·Decode 로 채운 변수 */
        private Set<String> requestNames(GoFunc fn) {
            Set<String> names = new HashSet<>();
            for (GoFunc current = fn; current != null; current = current.parent()) {
                project.parameters(current).forEach((name, type) -> {
                    if (type != null && REQUEST_TYPES.contains(type)) {
                        names.add(name);
                    }
                });
            }
            GoFile file = fn.file();
            for (TSNode call : GoFile.descendants(field(fn.node(), "body"), Set.of("call_expression"), Set.of())) {
                TSNode function = field(call, "function");
                String name = function != null && function.getType().equals("selector_expression")
                        ? file.text(field(function, "field")) : file.text(function);
                if (!BIND.matcher(name).find()) {
                    continue;
                }
                for (TSNode arg : namedChildren(field(call, "arguments"))) {
                    if (arg.getType().equals("unary_expression") && file.text(arg).startsWith("&")) {
                        names.add("=" + file.text(field(arg, "operand")));
                    }
                }
            }
            return names;
        }

        private boolean fromRequest(GoFunc fn, TSNode expr, Set<String> requestNames, int depth) {
            if (expr == null || depth > 4) {
                return false;
            }
            GoFile file = fn.file();
            String text = file.text(expr);
            if (FREE_REQUEST.matcher(text).find()) {
                return true;
            }
            Matcher m = REQUEST_MEMBER.matcher(text);
            while (m.find()) {
                if (requestNames.contains(m.group(1))) {
                    return true;
                }
            }
            for (String name : requestNames) {
                if (name.startsWith("=") && Pattern.compile("\\b" + Pattern.quote(name.substring(1)) + "\\b").matcher(text).find()) {
                    return true;
                }
            }
            List<TSNode> ids = new ArrayList<>(GoFile.descendants(expr, Set.of("identifier"), Set.of("func_literal")));
            if (expr.getType().equals("identifier")) {
                ids.add(expr);
            }
            for (TSNode id : ids) {
                Local local = project.local(fn, file.text(id));
                if (local != null && local.value() != null && local.value() != expr
                        && fromRequest(fn, local.value(), requestNames, depth + 1)) {
                    return true;
                }
            }
            return false;
        }

        /** &tls.Config{InsecureSkipVerify: true} */
        private void insecureTls(GoFunc fn, TSNode literal) {
            GoFile file = fn.file();
            if (!"crypto/tls.Config".equals(project.typeName(file, field(literal, "type")))) {
                return;
            }
            for (TSNode element : GoFile.children(field(literal, "body"), "keyed_element")) {
                List<TSNode> parts = namedChildren(element);
                if (parts.size() == 2 && file.text(parts.get(0)).equals("InsecureSkipVerify") && file.text(parts.get(1)).equals("true")) {
                    addSink(fn, literal, "WEAK_CRYPTO", "CWE-295", "tls.Config.InsecureSkipVerify");
                }
            }
        }

        private void addSink(GoFunc fn, TSNode node, String category, String cwe, String api) {
            GoFile file = fn.file();
            int line = GoFile.line(node);
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
            props.put("language", "go");
            String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK, fn.key() + "@L" + line + ":" + column + " " + api,
                    file.filePath, line, GoFile.endLine(node), props));
            draft.addEdge(methodKey(fn), sinkKey, CodeEdgeKind.CALLS);
        }

        private void addEntryPoint(GoRoutes.Route route) {
            GoFunc handler = route.handler();
            route.handlers().forEach(this::addStructure);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("httpMethod", route.httpMethod());
            props.put("path", route.path());
            props.put("handler", handler.key());
            props.put("framework", route.framework());
            props.put("guards", route.guards());
            props.put("language", "go");
            String entryKey = draft.addNode(new NodeDraft(CodeNodeKind.ENTRY_POINT,
                    route.httpMethod() + " " + route.path() + " -> " + handler.key(), handler.file().filePath,
                    GoFile.line(handler.node()), GoFile.endLine(handler.node()), props));
            for (GoFunc fn : route.handlers()) {
                draft.addEdge(entryKey, methodKey(fn), CodeEdgeKind.ROUTES_TO);
            }
        }

        static String methodKey(GoFunc fn) {
            return CodeGraphDraft.key(CodeNodeKind.METHOD, fn.key());
        }
    }

    /** database/sql.DB.Query → sql.DB.Query, github.com/labstack/echo/v4.Context.File → echo.Context.File */
    static String shortApi(String qualified) {
        int slash = qualified.lastIndexOf('/');
        if (slash < 0) {
            return qualified;
        }
        String rest = qualified.substring(slash + 1);
        if (rest.matches("v\\d+\\..*")) {
            String before = qualified.substring(0, slash);
            String pkg = before.substring(before.lastIndexOf('/') + 1);
            return pkg.replaceFirst("^go-", "") + rest.substring(rest.indexOf('.'));
        }
        return rest;
    }
}
