package com.codeagent.core.swift;

import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.rules.SinkRule;
import com.codeagent.core.swift.SwiftProject.SwiftFunction;
import com.codeagent.core.swift.SwiftProject.SwiftType;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import static com.codeagent.core.swift.SwiftFile.field;
import static com.codeagent.core.swift.SwiftFile.firstChild;
import static com.codeagent.core.swift.SwiftFile.namedChildren;

/**
 * Swift 코드 그래프를 만드는 공통 부품 ({@link com.codeagent.core.kotlin.KotlinGraphSupport} 의 Swift 판).
 * 구조(FILE → CLASS → METHOD), 상속 연결, 호출 대상 타입 알아내기, 프로젝트 함수 호출 잇기, 위험 지점 노드를 맡는다.
 * 위험 지점 규칙과 진입점은 쓰는 쪽이 정한다 (모바일: cotcha-pinch). 노드 props 에 language=swift 를 단다.
 */
public final class SwiftGraphSupport {

    private static final int SNIPPET_MAX_LENGTH = 300;

    /** UserDefaults.standard, URLSession.shared, FileManager.default 처럼 자기 타입을 돌려주는 싱글턴 프로퍼티 */
    public static final Set<String> SINGLETONS = Set.of("standard", "shared", "default", "main", "current");

    private final SwiftProject project;
    private final CodeGraphDraft draft;
    private final Map<String, String> knownReturnTypes;
    private ForeignCalls foreign = (type, name, labels) -> Optional.empty();

    /** 프로젝트 안 다른 언어(Objective-C)의 메서드를 찾는다. 찾으면 그 메서드의 그래프 키 */
    @FunctionalInterface
    public interface ForeignCalls {
        Optional<String> find(String type, String name, List<String> labels);

        /** 그 메서드의 반환 타입 (LegacyStore.shared() → LegacyStore) */
        default Optional<String> returnType(String type, String name, List<String> labels) {
            return Optional.empty();
        }
    }

    /**
     * @param knownReturnTypes 프레임워크 프로퍼티·메서드 이름 → 타입 (예: url → URL). 소스 없이 타입을 알아야 할 때만
     */
    public SwiftGraphSupport(SwiftProject project, CodeGraphDraft draft, Map<String, String> knownReturnTypes) {
        this.project = project;
        this.draft = draft;
        this.knownReturnTypes = Map.copyOf(knownReturnTypes);
    }

    /** Swift 프로젝트 밖 타입을 부를 때 물어볼 곳 ({@link com.codeagent.core.interop.SwiftObjcInterop}) */
    public void foreignCalls(ForeignCalls foreign) {
        this.foreign = foreign;
    }

    // ---- 구조 ----

    public void addStructure(SwiftType type) {
        String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, type.filePath(), type.filePath(), null, null,
                Map.of("language", "swift")));
        String classKey = draft.addNode(new NodeDraft(CodeNodeKind.CLASS, type.name(), type.filePath(), type.startLine(),
                type.endLine(), Map.of("declaration", type.declaration(), "superTypes", type.superTypes(), "language", "swift")));
        int dot = type.name().lastIndexOf('.');
        String outer = dot < 0 ? null : type.name().substring(0, dot);
        draft.addEdge(outer != null && project.isProjectType(outer) ? CodeGraphDraft.key(CodeNodeKind.CLASS, outer) : fileKey,
                classKey, CodeEdgeKind.CONTAINS);
        for (SwiftFunction function : type.functions()) {
            // extension 에 있는 함수는 다른 파일일 수 있다
            String methodKey = draft.addNode(new NodeDraft(CodeNodeKind.METHOD, function.key(), function.file().filePath,
                    function.startLine(), function.endLine(), Map.of("name", function.name(),
                    "annotations", function.attributes(), "language", "swift")));
            draft.addEdge(classKey, methodKey, CodeEdgeKind.CONTAINS);
        }
    }

    public void addOverrides(SwiftType type) {
        for (SwiftType ancestor : project.ancestors(type)) {
            for (SwiftFunction function : type.functions()) {
                ancestor.functions().stream()
                        .filter(f -> f.name().equals(function.name()) && f.paramTypes().size() == function.paramTypes().size())
                        .forEach(f -> draft.addEdge(methodKey(f), methodKey(function), CodeEdgeKind.OVERRIDDEN_BY));
            }
        }
    }

    // ---- 호출 ----

    /**
     * 함수 안의 호출을 모두 본다. 프로젝트 함수면 CALLS 로 잇고, 아니면 rules(타입, 메서드)로 위험 지점인지 본다.
     * 생성자 호출(Foo(...))은 메서드 이름 init 으로 묻는다. 클로저 안의 호출도 그 함수의 호출로 본다.
     *
     * @param extraProps 위험 지점 props 에 더할 것 (없으면 빈 맵)
     * @return 타입을 몰라 판단하지 못한 호출 수
     */
    public int addCalls(SwiftFunction function, BiFunction<String, String, Optional<SinkRule>> rules, Map<String, Object> extraProps) {
        SwiftFile file = function.file();
        Map<String, String> locals = locals(function);
        int unresolved = 0;
        for (TSNode call : SwiftFile.descendants(function.node(), "call_expression", "function_declaration")) {
            List<TSNode> parts = namedChildren(call);
            if (parts.isEmpty()) {
                continue;
            }
            TSNode callee = parts.get(0);
            int args = argumentCount(call);
            if (callee.getType().equals("simple_identifier")) {
                String name = file.text(callee);
                if (linkUnscoped(function, name, args)) {
                    continue;
                }
                if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                    if (project.isProjectType(name)) {
                        List<SwiftFunction> inits = project.findFunctions(name, "init", args);
                        inits.forEach(t -> draft.addEdge(methodKey(function), methodKey(t), CodeEdgeKind.CALLS));
                        continue;
                    }
                    Optional<String> objcInit = foreign.find(name, "init", argumentLabels(file, call));
                    if (objcInit.isPresent()) {
                        draft.addEdge(methodKey(function), objcInit.get(), CodeEdgeKind.CALLS);
                        continue;
                    }
                    Optional<SinkRule> rule = rules.apply(name, "init");
                    if (rule.isPresent()) {
                        addSink(function, call, rule.get(), rule.get().api("init"), extraProps);
                    }
                    continue;
                }
                // sqlite3_exec(...) 같은 C 함수: 타입 "" 과 함수 이름으로 묻는다
                Optional<SinkRule> rule = rules.apply("", name);
                if (rule.isPresent()) {
                    addSink(function, call, rule.get(), name, extraProps);
                }
                continue;
            }
            if (!callee.getType().equals("navigation_expression")) {
                continue;
            }
            String method = suffixName(file, callee);
            if (method == null) {
                continue;
            }
            String targetType = typeOf(field(callee, "target"), function, locals);
            if (targetType != null && project.isProjectType(targetType)) {
                List<SwiftFunction> targets = project.findFunctions(targetType, method, args);
                if (!targets.isEmpty()) {
                    targets.forEach(t -> draft.addEdge(methodKey(function), methodKey(t), CodeEdgeKind.CALLS));
                    continue;
                }
            }
            if (targetType != null) {
                Optional<String> objcMethod = foreign.find(targetType, method, argumentLabels(file, call));
                if (objcMethod.isPresent()) {
                    draft.addEdge(methodKey(function), objcMethod.get(), CodeEdgeKind.CALLS);
                    continue;
                }
            }
            Optional<SinkRule> rule = targetType == null ? Optional.empty() : rules.apply(targetType, method);
            if (rule.isPresent()) {
                addSink(function, call, rule.get(), rule.get().api(method), extraProps);
            } else if (targetType == null) {
                unresolved++;
                draft.countUnresolvedCall();
            }
        }
        return unresolved;
    }

    /** 자기 타입(extension 포함) → 바깥 타입 → 파일 최상위 함수(모듈 전체) 순으로 찾는다 */
    private boolean linkUnscoped(SwiftFunction caller, String name, int args) {
        String typeName = caller.owner().name();
        while (typeName != null) {
            List<SwiftFunction> found = project.findFunctions(typeName, name, args);
            if (!found.isEmpty()) {
                found.forEach(t -> draft.addEdge(methodKey(caller), methodKey(t), CodeEdgeKind.CALLS));
                return true;
            }
            int dot = typeName.lastIndexOf('.');
            typeName = dot < 0 ? null : typeName.substring(0, dot);
        }
        for (SwiftType type : project.types()) {
            if (type.declaration().equals("file")) {
                List<SwiftFunction> found = project.findFunctions(type.name(), name, args);
                if (!found.isEmpty()) {
                    found.forEach(t -> draft.addEdge(methodKey(caller), methodKey(t), CodeEdgeKind.CALLS));
                    return true;
                }
            }
        }
        return false;
    }

    /** 파라미터와 let/var 로 선언한 지역 변수의 타입 (적힌 타입, 생성자 호출, 싱글턴, 알려진 프로퍼티) */
    private Map<String, String> locals(SwiftFunction function) {
        SwiftFile file = function.file();
        Map<String, String> locals = new HashMap<>(function.paramTypes());
        TSNode body = field(function.node(), "body");
        for (TSNode property : SwiftFile.descendants(body, "property_declaration", "function_declaration")) {
            TSNode pattern = field(property, "name");
            TSNode bound = pattern == null ? null : field(pattern, "bound_identifier");
            if (bound == null || locals.containsKey(file.text(bound))) {
                continue;
            }
            String declared = project.annotatedType(file, firstChild(property, "type_annotation"));
            if (declared == null) {
                TSNode value = field(property, "value");
                declared = value == null ? null : typeOf(value, function, locals);
            }
            if (declared != null) {
                locals.put(file.text(bound), declared);
            }
        }
        return locals;
    }

    /** 받는 쪽 식의 타입. 모르면 null. 프로젝트 밖 타입은 적힌 이름 그대로 (UserDefaults, WKWebView) */
    String typeOf(TSNode expr, SwiftFunction context, Map<String, String> locals) {
        if (expr == null || SwiftFile.isNull(expr)) {
            return null;
        }
        SwiftFile file = context.file();
        switch (expr.getType()) {
            case "self_expression" -> {
                return context.owner().name();
            }
            case "super_expression" -> {
                return context.owner().superTypes().isEmpty() ? null : context.owner().superTypes().get(0);
            }
            case "simple_identifier" -> {
                String name = file.text(expr);
                if (locals.containsKey(name)) {
                    return locals.get(name);
                }
                String property = project.propertyType(context.owner(), name);
                if (property != null) {
                    return property;
                }
                if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                    // Foo.bar() : 정적 호출
                    return name;
                }
                return knownReturnTypes.get(name);
            }
            case "call_expression" -> {
                String constructed = project.constructedType(file, expr);
                if (constructed != null) {
                    return constructed;
                }
                TSNode callee = namedChildren(expr).isEmpty() ? null : namedChildren(expr).get(0);
                if (callee != null && callee.getType().equals("navigation_expression")) {
                    // 프로젝트 함수나 다른 언어(Objective-C) 메서드의 반환 타입
                    String method = suffixName(file, callee);
                    String owner = typeOf(field(callee, "target"), context, locals);
                    if (owner != null && method != null) {
                        Optional<String> returned = foreign.returnType(owner, method, argumentLabels(file, expr));
                        if (returned.isPresent()) {
                            return returned.get();
                        }
                    }
                }
                String called = callee == null ? null
                        : callee.getType().equals("navigation_expression") ? suffixName(file, callee)
                        : callee.getType().equals("simple_identifier") ? file.text(callee) : null;
                return called == null ? null : knownReturnTypes.get(called);
            }
            case "navigation_expression" -> {
                String prop = suffixName(file, expr);
                if (prop == null) {
                    return null;
                }
                TSNode target = field(expr, "target");
                String owner = typeOf(target, context, locals);
                if (owner != null && project.isProjectType(owner)) {
                    String t = project.type(owner).map(o -> project.propertyType(o, prop)).orElse(null);
                    if (t != null) {
                        return t;
                    }
                }
                // UserDefaults.standard, URLSession.shared
                if (owner != null && target != null && target.getType().equals("simple_identifier")
                        && file.text(target).equals(owner) && SINGLETONS.contains(prop)) {
                    return owner;
                }
                return knownReturnTypes.get(prop);
            }
            default -> {
                // (x), x!, x?, try x, await x 같은 감싼 식
                List<TSNode> inner = namedChildren(expr);
                return inner.size() == 1 ? typeOf(inner.get(0), context, locals) : null;
            }
        }
    }

    /** a.b 의 b */
    private static String suffixName(SwiftFile file, TSNode navigation) {
        TSNode suffix = field(navigation, "suffix");
        if (suffix == null) {
            List<TSNode> parts = namedChildren(navigation);
            suffix = parts.isEmpty() ? null : parts.get(parts.size() - 1);
        }
        if (suffix == null) {
            return null;
        }
        TSNode name = field(suffix, "suffix");
        if (name == null) {
            name = firstChild(suffix, "simple_identifier");
        }
        return name == null ? null : file.text(name);
    }

    private static int argumentCount(TSNode call) {
        TSNode suffix = firstChild(call, "call_suffix");
        TSNode args = suffix == null ? null : firstChild(suffix, "value_arguments");
        int count = args == null ? 0 : SwiftFile.children(args, "value_argument").size();
        // 끝에 붙인 클로저도 인자 하나
        return count + (suffix == null ? 0 : SwiftFile.children(suffix, "lambda_literal").size());
    }

    /** 호출에 적힌 인자 레이블 (레이블이 없으면 _) */
    public static List<String> argumentLabels(SwiftFile file, TSNode call) {
        List<String> labels = new ArrayList<>();
        TSNode suffix = firstChild(call, "call_suffix");
        TSNode args = suffix == null ? null : firstChild(suffix, "value_arguments");
        for (TSNode arg : SwiftFile.children(args, "value_argument")) {
            TSNode label = field(arg, "name");
            labels.add(label == null ? "_" : file.text(label).replace(":", "").strip());
        }
        return labels;
    }

    /**
     * 위험 지점 노드를 만들고 함수에서 잇는다.
     *
     * @return 위험 지점 노드 키
     */
    public String addSink(SwiftFunction caller, TSNode expr, SinkRule rule, String api, Map<String, Object> extraProps) {
        SwiftFile file = caller.file();
        int line = SwiftFile.line(expr);
        int column = expr.getStartPoint().getColumn() + 1;
        String snippet = file.text(expr).strip().replaceAll("\\s*\\n\\s*", " ");
        if (snippet.length() > SNIPPET_MAX_LENGTH) {
            snippet = snippet.substring(0, SNIPPET_MAX_LENGTH) + "...";
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("category", rule.category());
        props.put("cwe", rule.cwe());
        props.put("api", api);
        props.put("method", caller.key());
        props.put("snippet", snippet);
        props.put("language", "swift");
        props.putAll(extraProps);
        String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK, caller.key() + "@L" + line + ":" + column + " " + api,
                file.filePath, line, SwiftFile.endLine(expr), props));
        draft.addEdge(methodKey(caller), sinkKey, CodeEdgeKind.CALLS);
        return sinkKey;
    }

    public static String methodKey(SwiftFunction function) {
        return CodeGraphDraft.key(CodeNodeKind.METHOD, function.key());
    }
}
