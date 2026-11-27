package com.codeagent.core.csharp;

import com.codeagent.core.LanguageParser;
import com.codeagent.core.SourceFiles;
import com.codeagent.core.csharp.CsProject.Callee;
import com.codeagent.core.csharp.CsProject.CsClass;
import com.codeagent.core.csharp.CsProject.CsFunc;
import com.codeagent.core.csharp.CsProject.Local;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.rules.CsSinkRules;
import com.codeagent.core.rules.CsSinkRules.CsRule;
import org.treesitter.TSNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.codeagent.core.csharp.CsFile.field;
import static com.codeagent.core.csharp.CsFile.namedChildren;

/**
 * C# 백엔드 파서 (tree-sitter). 저장소에 .cs 소스가 있으면 구조·호출·위험 지점·HTTP 진입점(ASP.NET Core 컨트롤러, Minimal API)을 만든다.
 * 호출 대상은 C# 에 적힌 타입으로 찾는다: 필드·속성·기본 생성자 파라미터·지역 변수·파라미터의 타입, 인터페이스는 구현 클래스로.
 */
public final class CsLanguage implements LanguageParser {

    private static final int SNIPPET_MAX_LENGTH = 300;
    private static final Pattern REQUEST = Pattern.compile("\\b(?:HttpContext\\.|context\\.|ctx\\.)?Request\\.(?:Query|Form|Headers|Cookies|Body|"
            + "Path|QueryString|RouteValues|ReadFormAsync|ReadFromJsonAsync)");
    /** 요청 값이 아닌 파라미터 타입 (DI 서비스·취소 토큰) */
    private static final Pattern SERVICE_TYPE = Pattern.compile("^(I[A-Z]\\w*|\\w*(Service|Repository|Context|Client|Factory|Logger|Options|"
            + "Manager|Provider|Settings|Configuration)|CancellationToken|HttpContext|HttpRequest|ClaimsPrincipal|ILogger)$");

    @Override
    public String language() {
        return "csharp";
    }

    @Override
    public boolean detect(Path root) {
        return SourceFiles.any(root, CsProject.SKIP_DIRS, CsProject::isSource);
    }

    @Override
    public void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors) {
        CsProject project = CsProject.load(root, parseErrors);
        new Builder(project, draft).build();
    }

    private static final class Builder {
        private final CsProject project;
        private final CodeGraphDraft draft;
        private final Set<String> structured = new HashSet<>();
        private Set<String> handlers = Set.of();

        Builder(CsProject project, CodeGraphDraft draft) {
            this.project = project;
            this.draft = draft;
        }

        void build() {
            // 진입점을 먼저 찾는다: Minimal API 람다 처리기에 "GET /x" 같은 이름을 붙여 따로 색인한다
            List<CsRoutes.Route> routes = new CsRoutes(project).detect();
            handlers = CsRoutes.handlerKeys(routes);
            List<CsFunc> functions = project.functions();
            functions.forEach(this::addStructure);
            for (CsFunc fn : functions) {
                addCalls(fn);
            }
            routes.forEach(this::addEntryPoint);
        }

        private void addStructure(CsFunc fn) {
            if (!structured.add(fn.key())) {
                return;
            }
            CsFile file = fn.file();
            String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, file.filePath, file.filePath, null, null,
                    Map.of("language", "csharp")));
            String parent = fileKey;
            CsClass owner = fn.owner();
            if (owner != null && fn.parent() == null) {
                String ownerFile = draft.addNode(new NodeDraft(CodeNodeKind.FILE, owner.file.filePath, owner.file.filePath, null, null,
                        Map.of("language", "csharp")));
                parent = draft.addNode(new NodeDraft(CodeNodeKind.CLASS, owner.fullName, owner.file.filePath, CsFile.line(owner.node),
                        CsFile.endLine(owner.node), Map.of("declaration", owner.isInterface ? "interface" : "class",
                        "superTypes", owner.bases, "language", "csharp")));
                draft.addEdge(ownerFile, parent, CodeEdgeKind.CONTAINS);
            }
            boolean main = fn.name().equals("<main>");
            String methodKey = draft.addNode(new NodeDraft(CodeNodeKind.METHOD, fn.key(), file.filePath, main ? 1 : CsFile.line(fn.node()),
                    CsFile.endLine(fn.node()), Map.of("name", fn.name(), "annotations",
                    CsFile.attributes(fn.node()).stream().map(file::text).toList(), "language", "csharp")));
            draft.addEdge(parent, methodKey, CodeEdgeKind.CONTAINS);
        }

        private void addCalls(CsFunc fn) {
            List<TSNode> calls = new ArrayList<>();
            TSNode body = fn.name().equals("<main>") ? fn.node() : field(fn.node(), "body");
            if (body != null && (body.getType().equals("invocation_expression") || body.getType().equals("object_creation_expression"))) {
                calls.add(body);
            }
            collect(fn, body, calls);
            Set<String> requestNames = requestNames(fn);
            for (TSNode call : calls) {
                if (call.getType().equals("assignment_expression")) {
                    assignment(fn, call, requestNames);
                } else {
                    resolve(fn, call, requestNames);
                }
            }
        }

        private void collect(CsFunc fn, TSNode node, List<TSNode> out) {
            for (TSNode child : namedChildren(node)) {
                String type = child.getType();
                if (CsProject.TYPE_DECLARATIONS.contains(type) || type.equals("method_declaration")
                        || CsProject.LAMBDAS.contains(type) && project.isRegistered(fn.file(), child)) {
                    continue;
                }
                if (type.equals("invocation_expression") || type.equals("object_creation_expression") || type.equals("assignment_expression")) {
                    out.add(child);
                }
                collect(fn, child, out);
            }
        }

        private void resolve(CsFunc fn, TSNode call, Set<String> requestNames) {
            Callee callee = project.callee(fn, call);
            if (!callee.functions().isEmpty()) {
                for (CsFunc target : callee.functions()) {
                    draft.addEdge(methodKey(fn), methodKey(target), CodeEdgeKind.CALLS);
                    addStructure(target);
                }
                return;
            }
            Optional<CsRule> rule = callee.external() == null ? Optional.empty() : CsSinkRules.named(callee.external());
            String api = callee.external();
            if (rule.isEmpty() && callee.unknownMethod() != null) {
                rule = CsSinkRules.method(callee.unknownMethod());
                api = "." + callee.unknownMethod();
            }
            if (rule.isEmpty()) {
                return;
            }
            List<TSNode> args = arguments(call);
            if ("Process.Start".equals(api) && args.size() == 1 && "ProcessStartInfo".equals(project.typeOf(fn, args.get(0)))) {
                // Process.Start(startInfo): 위험 지점은 new ProcessStartInfo(...) 쪽에 이미 있다
                return;
            }
            if (!matches(fn, rule.get(), call, args, requestNames)) {
                return;
            }
            addSink(fn, call, rule.get().category(), rule.get().cwe(), api);
        }

        /** 호출 인자 + new T { A = x } 초기화 값 */
        private static List<TSNode> arguments(TSNode call) {
            List<TSNode> args = new ArrayList<>(CsFile.arguments(call));
            TSNode initializer = field(call, "initializer");
            for (TSNode assign : CsFile.children(initializer, "assignment_expression")) {
                TSNode right = field(assign, "right");
                if (right != null) {
                    args.add(right);
                }
            }
            return args;
        }

        /** cmd.CommandText = $"..." (SQL) · psi.Arguments = x (명령) */
        private void assignment(CsFunc fn, TSNode assign, Set<String> requestNames) {
            CsFile file = fn.file();
            TSNode left = field(assign, "left");
            TSNode right = field(assign, "right");
            if (left == null || right == null || !left.getType().equals("member_access_expression")) {
                return;
            }
            String member = file.text(field(left, "name"));
            if (member.equals("CommandText") && formatted(fn, right, 0)) {
                addSink(fn, assign, "SQL", "CWE-89", "CommandText");
            } else if ((member.equals("Arguments") || member.equals("FileName"))
                    && "ProcessStartInfo".equals(project.typeOf(fn, field(left, "expression"))) && !project.constant(fn, right, 0)) {
                addSink(fn, assign, "COMMAND", "CWE-78", "ProcessStartInfo." + member);
            }
        }

        private boolean matches(CsFunc fn, CsRule rule, TSNode call, List<TSNode> args, Set<String> requestNames) {
            CsFile file = fn.file();
            return switch (rule.condition()) {
                case ALWAYS -> true;
                case NOT_CONSTANT -> rule.argument() < args.size() && !project.constant(fn, args.get(rule.argument()), 0);
                case ANY_NOT_CONSTANT -> args.stream().anyMatch(a -> !project.constant(fn, a, 0));
                case FORMATTED -> args.stream().anyMatch(a -> formatted(fn, a, 0));
                case REQUEST_VALUE -> args.stream().anyMatch(a -> fromRequest(fn, a, requestNames, 0));
                case HTML_REQUEST_VALUE -> args.stream().anyMatch(a -> fromRequest(fn, a, requestNames, 0))
                        && args.stream().anyMatch(a -> file.text(a).toLowerCase().contains("html"));
                case TYPE_NAME_HANDLING -> {
                    String text = file.text(call);
                    for (TSNode arg : args) {
                        if (arg.getType().equals("identifier")) {
                            Local local = project.local(fn, file.text(arg));
                            if (local != null && local.value() != null) {
                                text += file.text(local.value());
                            }
                        }
                    }
                    yield text.contains("TypeNameHandling") && !text.matches("(?s).*TypeNameHandling\\s*=\\s*TypeNameHandling\\.None.*");
                }
                case XML_RESOLVER -> {
                    String body = file.text(fn.node());
                    yield body.contains("XmlUrlResolver") || body.contains("DtdProcessing.Parse");
                }
            };
        }

        /** 보간($"…{x}")·+·string.Format·string.Concat 으로 상수가 아닌 값을 넣은 문자열 (지역 변수와 += 도) */
        private boolean formatted(CsFunc fn, TSNode expr, int depth) {
            expr = CsFile.unwrap(expr);
            if (expr == null || depth > 5) {
                return false;
            }
            CsFile file = fn.file();
            switch (expr.getType()) {
                case "interpolated_string_expression" -> {
                    return CsFile.firstChild(expr, "interpolation") != null;
                }
                case "binary_expression" -> {
                    String op = file.text(field(expr, "operator"));
                    return op.equals("+") && (!project.constant(fn, field(expr, "left"), 0) || !project.constant(fn, field(expr, "right"), 0))
                            && (isString(field(expr, "left")) || isString(field(expr, "right"))
                            || formatted(fn, field(expr, "left"), depth + 1) || formatted(fn, field(expr, "right"), depth + 1));
                }
                case "invocation_expression" -> {
                    String name = file.text(field(expr, "function"));
                    return (name.equals("string.Format") || name.equals("String.Format") || name.equals("string.Concat"))
                            && CsFile.arguments(expr).stream().skip(1).anyMatch(a -> !project.constant(fn, a, 0));
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
                    TSNode body = fn.name().equals("<main>") ? fn.node() : field(fn.node(), "body");
                    for (TSNode assign : CsFile.descendants(body, Set.of("assignment_expression"), Set.of())) {
                        if (file.text(field(assign, "left")).equals(name)) {
                            String op = file.text(field(assign, "operator"));
                            TSNode right = field(assign, "right");
                            if (op.equals("+=") && !project.constant(fn, right, 0) || formatted(fn, right, depth + 1)) {
                                return true;
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

        private static boolean isString(TSNode node) {
            node = CsFile.unwrap(node);
            return node != null && (node.getType().equals("string_literal") || node.getType().equals("verbatim_string_literal")
                    || node.getType().equals("interpolated_string_expression"));
        }

        /** 요청 값 이름: 처리기(액션·Minimal API 람다)의 모델 바인딩 파라미터, [FromQuery] 등이 붙은 파라미터 */
        private Set<String> requestNames(CsFunc fn) {
            Set<String> names = new HashSet<>();
            for (CsFunc current = fn; current != null; current = current.parent()) {
                boolean handler = handlers.contains(current.key());
                CsFile file = current.file();
                for (TSNode param : CsFile.children(field(current.node(), "parameters"), "parameter")) {
                    String name = file.text(field(param, "name"));
                    String type = CsProject.simpleType(file.text(field(param, "type")));
                    String attrs = file.text(CsFile.firstChild(param, "attribute_list"));
                    if (attrs.contains("FromServices") || attrs.contains("FromKeyedServices")) {
                        continue;
                    }
                    if (attrs.matches("(?s).*From(Query|Body|Route|Form|Header).*") || handler && (type == null || !SERVICE_TYPE.matcher(type).matches())) {
                        names.add(name);
                    }
                }
            }
            return names;
        }

        private boolean fromRequest(CsFunc fn, TSNode expr, Set<String> requestNames, int depth) {
            if (expr == null || depth > 4) {
                return false;
            }
            CsFile file = fn.file();
            String text = file.text(expr);
            if (REQUEST.matcher(text).find()) {
                return true;
            }
            List<TSNode> ids = new ArrayList<>(CsFile.descendants(expr, Set.of("identifier"), CsProject.LAMBDAS));
            if (expr.getType().equals("identifier")) {
                ids.add(expr);
            }
            for (TSNode id : ids) {
                String name = file.text(id);
                if (requestNames.contains(name)) {
                    return true;
                }
                Local local = project.local(fn, name);
                if (local != null && local.value() != null && local.value() != expr && fromRequest(fn, local.value(), requestNames, depth + 1)) {
                    return true;
                }
            }
            return false;
        }

        private void addSink(CsFunc fn, TSNode node, String category, String cwe, String api) {
            CsFile file = fn.file();
            int line = CsFile.line(node);
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
            props.put("language", "csharp");
            String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK, fn.key() + "@L" + line + ":" + column + " " + api,
                    file.filePath, line, CsFile.endLine(node), props));
            draft.addEdge(methodKey(fn), sinkKey, CodeEdgeKind.CALLS);
        }

        private void addEntryPoint(CsRoutes.Route route) {
            CsFunc handler = route.handler();
            addStructure(handler);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("httpMethod", route.httpMethod());
            props.put("path", route.path());
            props.put("handler", handler.key());
            props.put("framework", route.framework());
            props.put("guards", route.guards());
            props.put("language", "csharp");
            String entryKey = draft.addNode(new NodeDraft(CodeNodeKind.ENTRY_POINT,
                    route.httpMethod() + " " + route.path() + " -> " + handler.key(), handler.file().filePath,
                    CsFile.line(handler.node()), CsFile.endLine(handler.node()), props));
            draft.addEdge(entryKey, methodKey(handler), CodeEdgeKind.ROUTES_TO);
        }

        static String methodKey(CsFunc fn) {
            return CodeGraphDraft.key(CodeNodeKind.METHOD, fn.key());
        }
    }
}
