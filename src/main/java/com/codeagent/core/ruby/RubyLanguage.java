package com.codeagent.core.ruby;

import com.codeagent.core.LanguageParser;
import com.codeagent.core.SourceFiles;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.rules.RubySinkRules;
import com.codeagent.core.rules.RubySinkRules.RubyRule;
import com.codeagent.core.ruby.RubyProject.Callee;
import com.codeagent.core.ruby.RubyProject.RubyClass;
import com.codeagent.core.ruby.RubyProject.RubyFunc;
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

import static com.codeagent.core.ruby.RubyFile.field;
import static com.codeagent.core.ruby.RubyFile.namedChildren;

/**
 * Ruby 백엔드 파서 (tree-sitter). 저장소에 .rb 소스가 있으면 구조·호출·위험 지점·HTTP 진입점(Rails, Sinatra)을 만든다.
 * 호출 대상: 같은 클래스(부모 포함)의 메서드, 클래스 메서드(Foo.bar), X.new·인스턴스 변수로 타입을 아는 값의 메서드.
 * 모델(User.where)과 타입을 모르는 값의 메서드는 이름 규칙(where·order·execute…)으로 본다.
 */
public final class RubyLanguage implements LanguageParser {

    private static final int SNIPPET_MAX_LENGTH = 300;
    private static final Set<String> DECLARATIONS = Set.of("method", "singleton_method", "class", "module", "singleton_class");
    private static final Pattern REQUEST = Pattern.compile("\\bparams\\b|\\brequest\\.(?:body|raw_post|query_string|params|"
            + "query_parameters|request_parameters|POST|GET|headers|env|url|fullpath|path|referer|referrer|cookies|user_agent)\\b"
            + "|\\bcookies\\[");

    @Override
    public String language() {
        return "ruby";
    }

    @Override
    public boolean detect(Path root) {
        return SourceFiles.any(root, RubyProject.SKIP_DIRS, RubyProject::isSource);
    }

    @Override
    public void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors) {
        RubyProject project = RubyProject.load(root, parseErrors);
        new Builder(project, draft).build();
    }

    private static final class Builder {
        private final RubyProject project;
        private final CodeGraphDraft draft;
        private final Set<String> structured = new HashSet<>();
        private Set<String> handlers = Set.of();

        Builder(RubyProject project, CodeGraphDraft draft) {
            this.project = project;
            this.draft = draft;
        }

        void build() {
            // 진입점을 먼저 찾는다: Sinatra 블록 처리기에 "GET /x" 같은 이름을 붙여 따로 색인한다
            List<RubyRoutes.Route> routes = new RubyRoutes(project).detect();
            handlers = RubyRoutes.handlerKeys(routes);
            List<RubyFunc> functions = project.functions();
            functions.forEach(this::addStructure);
            for (RubyFunc fn : functions) {
                addCalls(fn);
            }
            routes.forEach(this::addEntryPoint);
        }

        private void addStructure(RubyFunc fn) {
            if (!structured.add(fn.key())) {
                return;
            }
            RubyFile file = fn.file();
            String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, file.filePath, file.filePath, null, null,
                    Map.of("language", "ruby")));
            String parent = fileKey;
            RubyClass owner = fn.owner();
            boolean method = fn.node().getType().equals("method") || fn.node().getType().equals("singleton_method");
            if (owner != null && method) {
                List<String> superTypes = owner.superName == null ? List.of() : List.of(owner.superName);
                String ownerFile = draft.addNode(new NodeDraft(CodeNodeKind.FILE, owner.file.filePath, owner.file.filePath, null, null,
                        Map.of("language", "ruby")));
                parent = draft.addNode(new NodeDraft(CodeNodeKind.CLASS, owner.fullName, owner.file.filePath, RubyFile.line(owner.node),
                        RubyFile.endLine(owner.node), Map.of("declaration", owner.node.getType(), "superTypes", superTypes, "language", "ruby")));
                draft.addEdge(ownerFile, parent, CodeEdgeKind.CONTAINS);
            }
            String methodKey = draft.addNode(new NodeDraft(CodeNodeKind.METHOD, fn.key(), file.filePath, RubyFile.line(fn.node()),
                    RubyFile.endLine(fn.node()), Map.of("name", fn.name(), "annotations", List.of(), "language", "ruby")));
            draft.addEdge(parent, methodKey, CodeEdgeKind.CONTAINS);
        }

        private void addCalls(RubyFunc fn) {
            List<TSNode> calls = new ArrayList<>();
            collect(fn, fn.node(), calls, true);
            Set<String> requestNames = requestNames(fn);
            for (TSNode call : calls) {
                if (call.getType().equals("subshell")) {
                    if (!RubyFile.interpolationFree(call)) {
                        addSink(fn, call, "COMMAND", "CWE-78", "`shell`");
                    }
                } else {
                    resolve(fn, call, requestNames);
                }
            }
        }

        private void collect(RubyFunc fn, TSNode node, List<TSNode> out, boolean top) {
            for (TSNode child : namedChildren(node)) {
                String type = child.getType();
                if (!top && DECLARATIONS.contains(type)) {
                    continue;
                }
                if ((type.equals("do_block") || type.equals("block")) && project.isRegistered(fn.file(), child)
                        && child.getStartByte() != fn.node().getStartByte()) {
                    continue;
                }
                if (type.equals("call") || type.equals("subshell")) {
                    out.add(child);
                }
                collect(fn, child, out, false);
            }
        }

        private void resolve(RubyFunc fn, TSNode call, Set<String> requestNames) {
            RubyFile file = fn.file();
            String method = file.text(field(call, "method"));
            if (method.equals("render") && field(call, "receiver") == null) {
                render(fn, call);
                return;
            }
            Callee callee = project.callee(fn, call);
            if (!callee.functions().isEmpty()) {
                for (RubyFunc target : callee.functions()) {
                    draft.addEdge(methodKey(fn), methodKey(target), CodeEdgeKind.CALLS);
                    addStructure(target);
                }
                return;
            }
            Optional<RubyRule> rule = callee.external() == null ? Optional.empty() : RubySinkRules.named(callee.external());
            String api = callee.external();
            if (rule.isEmpty() && callee.unknownMethod() != null) {
                rule = RubySinkRules.method(callee.unknownMethod());
                api = "." + callee.unknownMethod();
            }
            if (rule.isEmpty()) {
                return;
            }
            List<TSNode> args = RubyFile.positional(call);
            if (!matches(fn, rule.get(), call, args, requestNames)) {
                return;
            }
            addSink(fn, call, rule.get().category(), rule.get().cwe(), api);
        }

        /** render inline: x (템플릿 주입) · render file: x (경로) */
        private void render(RubyFunc fn, TSNode call) {
            RubyFile file = fn.file();
            TSNode inline = file.keyword(call, "inline");
            if (inline != null && !project.constant(fn, inline, 0)) {
                addSink(fn, call, "CODE_INJECTION", "CWE-1336", "render inline:");
            }
            TSNode path = file.keyword(call, "file");
            if (path != null && !project.constant(fn, path, 0)) {
                addSink(fn, call, "PATH", "CWE-22", "render file:");
            }
        }

        private boolean matches(RubyFunc fn, RubyRule rule, TSNode call, List<TSNode> args, Set<String> requestNames) {
            return switch (rule.condition()) {
                case ALWAYS -> true;
                case NOT_CONSTANT -> rule.argument() < args.size() && !project.constant(fn, args.get(rule.argument()), 0);
                case FORMATTED -> args.stream().anyMatch(a -> formatted(fn, a, 0));
                case REQUEST_VALUE -> RubyFile.argumentValues(call).stream().anyMatch(a -> fromRequest(fn, a, requestNames, 0));
                case RECEIVER_REQUEST -> fromRequest(fn, field(call, "receiver"), requestNames, 0);
            };
        }

        /** 보간·+·format·% 로 상수가 아닌 값을 넣어 만든 문자열 (지역 변수와 <<·+= 도 따라간다) */
        private boolean formatted(RubyFunc fn, TSNode expr, int depth) {
            expr = RubyFile.unwrap(expr);
            if (expr == null || depth > 5) {
                return false;
            }
            RubyFile file = fn.file();
            switch (expr.getType()) {
                case "string" -> {
                    return !RubyFile.interpolationFree(expr);
                }
                case "binary" -> {
                    String op = file.text(field(expr, "operator"));
                    TSNode left = field(expr, "left");
                    return (op.equals("+") || op.equals("%")) && (left != null && left.getType().equals("string") || formatted(fn, left, depth + 1))
                            && (!project.constant(fn, left, 0) || !project.constant(fn, field(expr, "right"), 0));
                }
                case "call" -> {
                    String method = file.text(field(expr, "method"));
                    return (method.equals("format") || method.equals("sprintf")) && field(expr, "receiver") == null
                            && RubyFile.positional(expr).stream().anyMatch(a -> !project.constant(fn, a, 0));
                }
                case "identifier" -> {
                    String name = file.text(expr);
                    if (!project.isLocal(fn, name)) {
                        return false;
                    }
                    TSNode value = project.localValue(fn, name);
                    if (value != null && formatted(fn, value, depth + 1)) {
                        return true;
                    }
                    // sql << " AND name = '#{x}'" · sql += ...
                    for (TSNode node : RubyFile.descendants(fn.node(), Set.of("operator_assignment", "binary"), Set.of())) {
                        TSNode left = field(node, "left");
                        String op = file.text(field(node, "operator"));
                        if (left != null && file.text(left).equals(name) && (op.equals("+=") || op.equals("<<"))
                                && !project.constant(fn, field(node, "right"), 0)) {
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

        /** 요청 값 이름: Sinatra 블록 파라미터(경로 변수) */
        private Set<String> requestNames(RubyFunc fn) {
            Set<String> names = new HashSet<>();
            if (handlers.contains(fn.key()) && !fn.node().getType().equals("method")) {
                TSNode params = field(fn.node(), "parameters");
                for (TSNode param : namedChildren(params)) {
                    names.add(fn.file().text(param.getType().equals("identifier") ? param : field(param, "name")));
                }
            }
            return names;
        }

        private boolean fromRequest(RubyFunc fn, TSNode expr, Set<String> requestNames, int depth) {
            if (expr == null || depth > 4) {
                return false;
            }
            RubyFile file = fn.file();
            String text = file.text(expr);
            if (REQUEST.matcher(text).find()) {
                return true;
            }
            List<TSNode> ids = new ArrayList<>(RubyFile.descendants(expr, Set.of("identifier"), Set.of("do_block", "block")));
            if (expr.getType().equals("identifier")) {
                ids.add(expr);
            }
            for (TSNode id : ids) {
                String name = file.text(id);
                if (requestNames.contains(name)) {
                    return true;
                }
                if (project.isLocal(fn, name)) {
                    TSNode value = project.localValue(fn, name);
                    if (value != null && value != expr && fromRequest(fn, value, requestNames, depth + 1)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void addSink(RubyFunc fn, TSNode node, String category, String cwe, String api) {
            RubyFile file = fn.file();
            int line = RubyFile.line(node);
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
            props.put("language", "ruby");
            String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK, fn.key() + "@L" + line + ":" + column + " " + api,
                    file.filePath, line, RubyFile.endLine(node), props));
            draft.addEdge(methodKey(fn), sinkKey, CodeEdgeKind.CALLS);
        }

        private void addEntryPoint(RubyRoutes.Route route) {
            RubyFunc handler = route.handler();
            addStructure(handler);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("httpMethod", route.httpMethod());
            props.put("path", route.path());
            props.put("handler", handler.key());
            props.put("framework", route.framework());
            props.put("guards", route.guards());
            props.put("language", "ruby");
            String entryKey = draft.addNode(new NodeDraft(CodeNodeKind.ENTRY_POINT,
                    route.httpMethod() + " " + route.path() + " -> " + handler.key(), handler.file().filePath,
                    RubyFile.line(handler.node()), RubyFile.endLine(handler.node()), props));
            draft.addEdge(entryKey, methodKey(handler), CodeEdgeKind.ROUTES_TO);
        }

        static String methodKey(RubyFunc fn) {
            return CodeGraphDraft.key(CodeNodeKind.METHOD, fn.key());
        }
    }
}
