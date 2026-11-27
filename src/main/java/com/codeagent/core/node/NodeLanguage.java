package com.codeagent.core.node;

import com.codeagent.core.LanguageParser;
import com.codeagent.core.SourceFiles;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.node.NodeProject.External;
import com.codeagent.core.node.NodeProject.FnTarget;
import com.codeagent.core.node.NodeProject.NodeClass;
import com.codeagent.core.node.NodeProject.NodeFunction;
import com.codeagent.core.node.NodeProject.NodeModule;
import com.codeagent.core.node.NodeProject.Target;
import com.codeagent.core.rules.NodeSinkRules;
import com.codeagent.core.rules.NodeSinkRules.Condition;
import com.codeagent.core.rules.NodeSinkRules.NodeRule;
import org.treesitter.TSNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.codeagent.core.node.NodeFile.field;
import static com.codeagent.core.node.NodeFile.namedChildren;

/**
 * Node.js 백엔드 파서 (JavaScript·TypeScript, tree-sitter). package.json 에 서버 프레임워크(Express, Koa, Fastify, NestJS,
 * Hono, Restify, Next.js)가 있으면 저장소를 Node 백엔드로 보고 구조·호출·위험 지점·HTTP 진입점을 만든다.
 * 프론트엔드 줄 단위 검사({@link com.codeagent.core.frontend.FrontendGraph})와 겹치지 않도록, 여기서 분석한 파일은
 * {@link #backendFiles(Path)} 로 알려 준다.
 */
public final class NodeLanguage implements LanguageParser {

    private static final Pattern BACKEND_DEPENDENCY = Pattern.compile(
            "\"(express|koa|@koa/router|koa-router|fastify|@nestjs/core|@hapi/hapi|hapi|restify|hono|next)\"\\s*:");
    private static final Set<String> PACKAGE_SKIP = Set.of(".git", "node_modules", "dist", "build", ".next", "coverage");
    private static final Set<String> CALL_TYPES = Set.of("call_expression", "new_expression");
    /** 고정 값으로 보는 인자 */
    private static final Set<String> LITERALS = Set.of("string", "number", "true", "false", "null", "undefined", "regex");
    private static final Set<String> GLOBALS = Set.of("eval", "Function", "fetch", "setTimeout", "setInterval", "Math", "JSON",
            "console", "Object", "Array", "Promise", "Buffer", "process", "Response", "URL", "parseInt", "String", "Number");
    private static final int SNIPPET_MAX_LENGTH = 300;

    /** 마지막으로 분석한 저장소 → 파일 경로 → 함수 (프론트엔드 줄 검사가 위험 지점을 감싼 함수에 달 때 쓴다) */
    private Path lastRoot;
    private NodeProject lastProject;

    @Override
    public String language() {
        return "node";
    }

    @Override
    public boolean detect(Path root) {
        for (Path packageJson : SourceFiles.find(root, PACKAGE_SKIP, 2_000_000, p -> p.getFileName().toString().equals("package.json"))) {
            try {
                if (BACKEND_DEPENDENCY.matcher(Files.readString(packageJson, StandardCharsets.UTF_8)).find()) {
                    return true;
                }
            } catch (IOException e) {
                // 못 읽는 package.json 은 건너뛴다
            }
        }
        return false;
    }

    @Override
    public void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors) {
        NodeProject project = NodeProject.load(root, parseErrors);
        lastRoot = root;
        lastProject = project;
        new Builder(project, draft).build();
    }

    /** 이 저장소에서 Node 백엔드로 분석한 파일 (프론트엔드 줄 검사의 eval·new Function 과 겹치지 않게) */
    public Set<String> backendFiles(Path root) {
        if (lastProject == null || !root.equals(lastRoot)) {
            return Set.of();
        }
        Set<String> files = new HashSet<>();
        lastProject.files().forEach(f -> files.add(f.filePath()));
        return files;
    }

    /** 파일·줄을 감싼 Node 함수의 그래프 키 (프론트엔드 줄 검사 결과를 경로에 잇는다) */
    public Optional<String> enclosingMethodKey(Path root, String filePath, int line) {
        if (lastProject == null || !root.equals(lastRoot)) {
            return Optional.empty();
        }
        return lastProject.functionAt(filePath, line).map(Builder::methodKey);
    }

    // ---- 그래프 ----

    private static final class Builder {
        private final NodeProject project;
        private final CodeGraphDraft draft;
        private final Set<String> structured = new HashSet<>();

        Builder(NodeProject project, CodeGraphDraft draft) {
            this.project = project;
            this.draft = draft;
        }

        void build() {
            // 진입점을 먼저 찾는다: 이름 없는 라우트 처리기에 "GET /users/:id" 같은 이름을 붙이고 따로 색인한다
            List<NodeRoutes.Route> routes = new NodeRoutes(project).detect();
            List<NodeFunction> functions = project.functions();
            Set<String> separate = new HashSet<>();
            functions.forEach(f -> separate.add(f.file().filePath + "@" + f.node().getStartByte()));
            functions.forEach(this::addStructure);
            for (NodeFunction fn : functions) {
                addCalls(fn, separate);
            }
            // 호출을 따라가다 새로 색인한 함수 (지역 화살표 함수 등)
            project.functions().forEach(this::addStructure);
            routes.forEach(this::addEntryPoint);
        }

        private void addStructure(NodeFunction fn) {
            if (!structured.add(fn.key())) {
                return;
            }
            NodeFile file = fn.file();
            String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, file.filePath, file.filePath, null, null,
                    Map.of("language", "node")));
            String parent = fileKey;
            NodeClass owner = fn.owner();
            if (owner != null) {
                List<String> superTypes = owner.superName == null ? List.of() : List.of(owner.superName);
                parent = draft.addNode(new NodeDraft(CodeNodeKind.CLASS, owner.key(), file.filePath, NodeFile.line(owner.node),
                        NodeFile.endLine(owner.node), Map.of("declaration", "class", "superTypes", superTypes, "language", "node")));
                draft.addEdge(fileKey, parent, CodeEdgeKind.CONTAINS);
            }
            boolean body = fn.node().getType().equals("program");
            String methodKey = draft.addNode(new NodeDraft(CodeNodeKind.METHOD, fn.key(), file.filePath,
                    body ? 1 : NodeFile.line(fn.node()), NodeFile.endLine(fn.node()),
                    Map.of("name", fn.name(), "annotations", fn.decorators().stream().map(file::text).toList(), "language", "node")));
            draft.addEdge(parent, methodKey, CodeEdgeKind.CONTAINS);
        }

        /** 함수 안의 호출. 따로 색인한 안쪽 함수 안은 그 함수 몫이고, 이름 없는 콜백 안은 이 함수의 호출로 본다 */
        private void addCalls(NodeFunction fn, Set<String> separate) {
            NodeModule module = project.module(fn.file());
            NodeFile file = fn.file();
            Map<String, TSNode> locals = project.locals(fn);
            Set<String> requestNames = requestNames(fn);
            for (TSNode call : calls(file, fn.node(), separate)) {
                resolveCall(module, fn, locals, requestNames, call);
            }
        }

        private List<TSNode> calls(NodeFile file, TSNode root, Set<String> separate) {
            List<TSNode> result = new ArrayList<>();
            collect(file, root, separate, result, true);
            return result;
        }

        private void collect(NodeFile file, TSNode node, Set<String> separate, List<TSNode> out, boolean top) {
            for (TSNode child : namedChildren(node)) {
                if (NodeProject.FUNCTION_TYPES.contains(child.getType())
                        && (separate.contains(file.filePath + "@" + child.getStartByte())
                        || child.getType().equals("class_declaration") || child.getType().equals("class"))) {
                    continue;
                }
                if (CALL_TYPES.contains(child.getType())) {
                    out.add(child);
                }
                collect(file, child, separate, out, false);
            }
        }

        private void resolveCall(NodeModule module, NodeFunction fn, Map<String, TSNode> locals, Set<String> requestNames, TSNode call) {
            NodeFile file = fn.file();
            boolean isNew = call.getType().equals("new_expression");
            TSNode callee = field(call, isNew ? "constructor" : "function");
            if (callee == null || file.text(callee).equals("require") || callee.getType().equals("super")) {
                return;
            }
            Target target = project.evaluate(module, fn, callee, locals, 0);
            if (target instanceof FnTarget t) {
                draft.addEdge(methodKey(fn), methodKey(t.fn()), CodeEdgeKind.CALLS);
                addStructure(t.fn());
                return;
            }
            if (target instanceof NodeProject.ClassTarget c && isNew) {
                NodeFunction constructor = c.cls().methods.get("constructor");
                if (constructor != null) {
                    draft.addEdge(methodKey(fn), methodKey(constructor), CodeEdgeKind.CALLS);
                }
                return;
            }
            String qualified = null;
            String methodName = null;
            if (target instanceof External e) {
                qualified = (isNew ? "new " : "") + e.name();
                if (callee.getType().equals("member_expression")) {
                    // mysql.createPool().query 처럼 이름 규칙이 없으면 메서드 이름으로 본다
                    methodName = file.text(field(callee, "property"));
                }
            } else if (callee.getType().equals("identifier") && !locals.containsKey(file.text(callee))
                    && project.resolveName(module, file.text(callee), 0) == null && GLOBALS.contains(file.text(callee))) {
                qualified = (isNew ? "new " : "") + "global." + file.text(callee);
            } else if (callee.getType().equals("member_expression")) {
                TSNode object = field(callee, "object");
                String objectText = file.text(object);
                methodName = file.text(field(callee, "property"));
                if (object != null && object.getType().equals("identifier") && GLOBALS.contains(objectText)
                        && !locals.containsKey(objectText) && project.resolveName(module, objectText, 0) == null) {
                    qualified = (isNew ? "new " : "") + "global." + objectText + "." + methodName;
                    methodName = null;
                }
            }
            Optional<NodeRule> named = qualified != null ? NodeSinkRules.named(qualified) : Optional.empty();
            Optional<NodeRule> rule = named.isPresent() ? named
                    : methodName != null && (target == null || target instanceof External) ? NodeSinkRules.method(methodName) : Optional.empty();
            if (rule.isEmpty()) {
                if (target == null && methodName != null) {
                    draft.countUnresolvedCall();
                }
                return;
            }
            List<TSNode> args = NodeRoutes.arguments(call);
            if (!matches(file, rule.get().condition(), args, locals, requestNames)) {
                return;
            }
            String api = named.isPresent() ? qualified.replaceFirst("^global\\.", "") : "*." + methodName;
            addSink(fn, call, rule.get(), api);
        }

        /** 요청 값을 담는 이름: 라우트 처리기의 첫 파라미터(req, ctx, request), NestJS @Body·@Query·@Param 파라미터 */
        private Set<String> requestNames(NodeFunction fn) {
            Set<String> names = new HashSet<>(Set.of("req", "request", "ctx"));
            NodeFile file = fn.file();
            TSNode params = field(fn.node(), "parameters");
            for (TSNode param : namedChildren(params)) {
                for (TSNode d : NodeFile.children(param, "decorator")) {
                    String name = NodeRoutes.decoratorName(file, d);
                    if (Set.of("Body", "Query", "Param", "Headers", "Req", "Request").contains(name)) {
                        TSNode pattern = field(param, "pattern");
                        if (pattern != null) {
                            names.add("=" + file.text(pattern));
                        }
                    }
                }
            }
            return names;
        }

        private static final Pattern REQUEST_MEMBER = Pattern.compile(
                "\\b(\\w+)\\.(?:body|query|params|cookies|headers|request\\.(?:body|query))\\b|\\bc\\.req\\.(?:query|param|json|parseBody|header)\\b");

        private boolean matches(NodeFile file, Condition condition, List<TSNode> args, Map<String, TSNode> locals, Set<String> requestNames) {
            TSNode first = args.isEmpty() ? null : args.get(0);
            return switch (condition) {
                case ALWAYS -> true;
                case NOT_LITERAL -> first != null && !constant(first);
                case FORMATTED -> first != null && (formatted(file, first) || first.getType().equals("identifier")
                        && formatted(file, locals.get(file.text(first))));
                case WEAK_ALGORITHM -> first != null && NodeSinkRules.weakAlgorithm(file.stringValue(first));
                case REQUEST_VALUE -> args.stream().anyMatch(a -> fromRequest(file, a, locals, requestNames, 0));
            };
        }

        private boolean fromRequest(NodeFile file, TSNode expr, Map<String, TSNode> locals, Set<String> requestNames, int depth) {
            if (expr == null || depth > 4) {
                return false;
            }
            String text = file.text(expr);
            var m = REQUEST_MEMBER.matcher(text);
            while (m.find()) {
                if (m.group(1) == null || requestNames.contains(m.group(1))) {
                    return true;
                }
            }
            for (String name : requestNames) {
                if (name.startsWith("=") && Pattern.compile("\\b" + Pattern.quote(name.substring(1)) + "\\b").matcher(text).find()) {
                    return true;
                }
            }
            // const name = req.body.name 처럼 요청 값을 담은 지역 변수
            for (TSNode id : NodeFile.descendants(expr, Set.of("identifier"), Set.of())) {
                TSNode value = locals.get(file.text(id));
                if (value != null && fromRequest(file, value, locals, requestNames, depth + 1)) {
                    return true;
                }
            }
            if (expr.getType().equals("identifier")) {
                TSNode value = locals.get(text);
                return value != null && fromRequest(file, value, locals, requestNames, depth + 1);
            }
            return false;
        }

        private static boolean constant(TSNode node) {
            String type = node.getType();
            if (LITERALS.contains(type)) {
                return true;
            }
            if (type.equals("template_string")) {
                return NodeFile.firstChild(node, "template_substitution") == null;
            }
            if (type.equals("array")) {
                return namedChildren(node).stream().allMatch(Builder::constant);
            }
            if (type.equals("object")) {
                for (TSNode pair : namedChildren(node)) {
                    TSNode value = field(pair, "value");
                    if (!pair.getType().equals("pair") || value == null || !constant(value)) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }

        /** 템플릿 치환이나 + 로 조립한 문자열 */
        private static boolean formatted(NodeFile file, TSNode node) {
            if (node == null) {
                return false;
            }
            if (node.getType().equals("template_string")) {
                return NodeFile.firstChild(node, "template_substitution") != null;
            }
            if (node.getType().equals("binary_expression")) {
                if (!file.text(field(node, "operator")).equals("+")) {
                    return false;
                }
                return containsString(field(node, "left")) || containsString(field(node, "right"))
                        || formatted(file, field(node, "left")) || formatted(file, field(node, "right"));
            }
            return false;
        }

        private static boolean containsString(TSNode node) {
            return node != null && (node.getType().equals("string") || node.getType().equals("template_string"));
        }

        private void addSink(NodeFunction fn, TSNode call, NodeRule rule, String api) {
            NodeFile file = fn.file();
            int line = NodeFile.line(call);
            int column = call.getStartPoint().getColumn() + 1;
            String snippet = file.text(call).strip().replaceAll("\\s*\\n\\s*", " ");
            if (snippet.length() > SNIPPET_MAX_LENGTH) {
                snippet = snippet.substring(0, SNIPPET_MAX_LENGTH) + "...";
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("category", rule.category());
            props.put("cwe", rule.cwe());
            props.put("api", api);
            props.put("method", fn.key());
            props.put("snippet", snippet);
            props.put("language", "node");
            String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK, fn.key() + "@L" + line + ":" + column + " " + api,
                    file.filePath, line, NodeFile.endLine(call), props));
            draft.addEdge(methodKey(fn), sinkKey, CodeEdgeKind.CALLS);
        }

        private void addEntryPoint(NodeRoutes.Route route) {
            NodeFunction handler = route.handler();
            route.handlers().forEach(this::addStructure);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("httpMethod", route.httpMethod());
            props.put("path", route.path());
            props.put("handler", handler.key());
            props.put("framework", route.framework());
            props.put("guards", route.guards());
            props.put("language", "node");
            String entryKey = draft.addNode(new NodeDraft(CodeNodeKind.ENTRY_POINT,
                    route.httpMethod() + " " + route.path() + " -> " + handler.key(), handler.file().filePath,
                    NodeFile.line(handler.node()), NodeFile.endLine(handler.node()), props));
            for (NodeFunction fn : route.handlers()) {
                draft.addEdge(entryKey, methodKey(fn), CodeEdgeKind.ROUTES_TO);
            }
        }

        static String methodKey(NodeFunction fn) {
            return CodeGraphDraft.key(CodeNodeKind.METHOD, fn.key());
        }
    }
}
