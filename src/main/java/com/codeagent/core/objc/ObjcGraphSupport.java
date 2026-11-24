package com.codeagent.core.objc;

import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.objc.ObjcProject.ObjcMethod;
import com.codeagent.core.objc.ObjcProject.ObjcType;
import com.codeagent.core.rules.SinkRule;
import org.treesitter.TSNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import static com.codeagent.core.objc.ObjcFile.field;
import static com.codeagent.core.objc.ObjcFile.namedChildren;

/**
 * Objective-C 코드 그래프를 만드는 공통 부품 ({@link com.codeagent.core.swift.SwiftGraphSupport} 의 Objective-C 판).
 * 메시지 보내기([받는쪽 셀렉터:인자])와 C 함수 호출을 잇고, 규칙은 쓰는 쪽이 (타입, 셀렉터) 로 묻는다.
 * C 함수는 타입 "" 과 함수 이름으로 묻는다. 노드 props 에 language=objc 를 단다.
 */
public final class ObjcGraphSupport {

    private static final int SNIPPET_MAX_LENGTH = 300;

    /** [NSUserDefaults standardUserDefaults], [Foo sharedInstance] 처럼 클래스에 보내 자기 타입을 돌려받는 셀렉터의 앞부분 */
    private static final List<String> SINGLETON_PREFIXES = List.of("shared", "standard", "default", "main", "current");
    /** 받는 쪽 타입을 그대로 돌려주는 셀렉터 */
    private static final Set<String> SAME_TYPE = Set.of("alloc", "new", "copy", "mutableCopy", "autorelease", "retain");

    private final ObjcProject project;
    private final CodeGraphDraft draft;
    private BiFunction<String, String, Optional<String>> foreign = (type, selector) -> Optional.empty();

    public ObjcGraphSupport(ObjcProject project, CodeGraphDraft draft) {
        this.project = project;
        this.draft = draft;
    }

    /**
     * 프로젝트 안 다른 언어(Swift)의 메서드를 찾을 곳 ({@link com.codeagent.core.interop.SwiftObjcInterop}).
     * (받는 쪽 타입, 셀렉터) → 그 메서드의 그래프 키
     */
    public void foreignCalls(BiFunction<String, String, Optional<String>> foreign) {
        this.foreign = foreign;
    }

    // ---- 구조 ----

    public void addStructure(ObjcType type) {
        String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, type.filePath(), type.filePath(), null, null,
                Map.of("language", "objc")));
        String classKey = draft.addNode(new NodeDraft(CodeNodeKind.CLASS, type.name(), type.filePath(), type.startLine(),
                type.endLine(), Map.of("declaration", type.declaration(), "superTypes", type.superTypes(), "language", "objc")));
        draft.addEdge(fileKey, classKey, CodeEdgeKind.CONTAINS);
        for (ObjcMethod method : type.methods()) {
            String methodKey = draft.addNode(new NodeDraft(CodeNodeKind.METHOD, method.key(), method.file().filePath,
                    method.startLine(), method.endLine(), Map.of("name", method.selector(), "language", "objc")));
            draft.addEdge(classKey, methodKey, CodeEdgeKind.CONTAINS);
        }
    }

    public void addOverrides(ObjcType type) {
        for (ObjcType ancestor : project.ancestors(type)) {
            for (ObjcMethod method : type.methods()) {
                ancestor.methods().stream()
                        .filter(m -> m.selector().equals(method.selector()))
                        .forEach(m -> draft.addEdge(methodKey(m), methodKey(method), CodeEdgeKind.OVERRIDDEN_BY));
            }
        }
    }

    // ---- 호출 ----

    /**
     * 메서드·함수 안의 메시지와 C 함수 호출을 모두 본다. 프로젝트 것이면 CALLS 로 잇고, 아니면 rules 로 위험 지점인지 본다.
     *
     * @return 받는 쪽 타입을 몰라 판단하지 못한 메시지 수
     */
    public int addCalls(ObjcMethod method, BiFunction<String, String, Optional<SinkRule>> rules, Map<String, Object> extraProps) {
        if (method.node() == null) {
            return 0;
        }
        ObjcFile file = method.file();
        Map<String, String> locals = locals(method);
        int unresolved = 0;
        for (TSNode call : ObjcFile.descendants(method.node(), "call_expression", null)) {
            TSNode function = field(call, "function");
            if (function == null || !function.getType().equals("identifier")) {
                continue;
            }
            String name = file.text(function);
            Optional<ObjcMethod> target = project.findFunction(name);
            if (target.isPresent()) {
                draft.addEdge(methodKey(method), methodKey(target.get()), CodeEdgeKind.CALLS);
                continue;
            }
            Optional<SinkRule> rule = rules.apply("", name);
            if (rule.isPresent()) {
                addSink(method, call, rule.get(), name, extraProps);
            }
        }
        for (TSNode message : ObjcFile.descendants(method.node(), "message_expression", null)) {
            String selector = selector(file, message);
            if (selector == null) {
                continue;
            }
            String receiverType = typeOf(field(message, "receiver"), method, locals);
            if (receiverType != null && project.isProjectType(receiverType)) {
                Optional<ObjcMethod> target = project.findMethod(receiverType, selector);
                if (target.isPresent()) {
                    draft.addEdge(methodKey(method), methodKey(target.get()), CodeEdgeKind.CALLS);
                    continue;
                }
            }
            if (receiverType != null) {
                Optional<String> swiftFunction = foreign.apply(receiverType, selector);
                if (swiftFunction.isPresent()) {
                    draft.addEdge(methodKey(method), swiftFunction.get(), CodeEdgeKind.CALLS);
                    continue;
                }
            }
            Optional<SinkRule> rule = receiverType == null ? Optional.empty() : rules.apply(receiverType, selector);
            if (rule.isPresent()) {
                addSink(method, message, rule.get(), rule.get().api(selector), extraProps);
            } else if (receiverType == null) {
                unresolved++;
                draft.countUnresolvedCall();
            }
        }
        return unresolved;
    }

    /** 파라미터와 본문에서 선언한 지역 변수 (NSString *x = ...; 의 적힌 타입) */
    private Map<String, String> locals(ObjcMethod method) {
        ObjcFile file = method.file();
        Map<String, String> locals = new HashMap<>(method.paramTypes());
        for (TSNode declaration : ObjcFile.descendants(method.node(), "declaration", null)) {
            String type = project.typeName(file, field(declaration, "type"));
            if (type == null) {
                continue;
            }
            for (TSNode child : namedChildren(declaration)) {
                if (child.getType().endsWith("declarator")) {
                    String name = ObjcProject.declaredName(file, child);
                    String declared = type;
                    if ("id".equals(type) && child.getType().equals("init_declarator")) {
                        // id x = [[Foo alloc] init];
                        String value = typeOf(field(child, "value"), method, locals);
                        declared = value != null ? value : type;
                    }
                    if (name != null) {
                        locals.putIfAbsent(name, declared);
                    }
                }
            }
        }
        return locals;
    }

    /** [x a:1 b:2] 의 a:b: / [x run] 의 run */
    public static String selector(ObjcFile file, TSNode message) {
        TSNode sel = field(message, "selector");
        if (sel != null && sel.getType().equals("identifier")) {
            return file.text(sel);
        }
        StringBuilder sb = new StringBuilder();
        for (TSNode list : namedChildren(message)) {
            if (!list.getType().equals("keyword_argument_list")) {
                continue;
            }
            for (TSNode arg : ObjcFile.children(list, "keyword_argument")) {
                TSNode kw = field(arg, "keyword");
                sb.append(kw == null ? "" : file.text(kw)).append(':');
            }
        }
        if (sb.length() > 0) {
            return sb.toString();
        }
        return sel == null ? null : file.text(sel);
    }

    /** 받는 쪽 식의 타입. 모르면 null. 프로젝트 밖 클래스는 적힌 이름 그대로 (NSUserDefaults) */
    String typeOf(TSNode expr, ObjcMethod context, Map<String, String> locals) {
        if (expr == null || ObjcFile.isNull(expr)) {
            return null;
        }
        ObjcFile file = context.file();
        switch (expr.getType()) {
            case "self" -> {
                return context.owner().name();
            }
            case "super" -> {
                return context.owner().superTypes().isEmpty() ? null : context.owner().superTypes().get(0);
            }
            case "identifier", "type_identifier" -> {
                String name = file.text(expr);
                if (locals.containsKey(name)) {
                    return locals.get(name);
                }
                String property = project.propertyType(context.owner(), name);
                if (property != null) {
                    return property;
                }
                // 클래스에 보내는 메시지 ([NSUserDefaults standardUserDefaults])
                return !name.isEmpty() && Character.isUpperCase(name.charAt(0)) ? name : null;
            }
            case "field_expression" -> {
                // self.db, store.handle
                String owner = typeOf(field(expr, "argument"), context, locals);
                TSNode fieldName = field(expr, "field");
                if (owner == null || fieldName == null) {
                    return null;
                }
                return project.type(owner).map(t -> project.propertyType(t, file.text(fieldName))).orElse(null);
            }
            case "message_expression" -> {
                TSNode receiver = field(expr, "receiver");
                String receiverType = typeOf(receiver, context, locals);
                String selector = selector(file, expr);
                if (receiverType == null || selector == null) {
                    return null;
                }
                if (SAME_TYPE.contains(selector) || selector.startsWith("init")) {
                    return receiverType;
                }
                Optional<ObjcMethod> target = project.findMethod(receiverType, selector);
                if (target.isPresent()) {
                    String returned = target.get().returnType();
                    return returned == null || returned.equals("id") ? null : returned;
                }
                boolean classReceiver = receiver != null && (receiver.getType().equals("type_identifier")
                        || receiver.getType().equals("identifier")) && file.text(receiver).equals(receiverType);
                if (classReceiver && SINGLETON_PREFIXES.stream().anyMatch(selector::startsWith)) {
                    return receiverType;
                }
                return null;
            }
            case "cast_expression" -> {
                return project.typeName(file, field(expr, "type"));
            }
            default -> {
                List<TSNode> inner = namedChildren(expr);
                return inner.size() == 1 ? typeOf(inner.get(0), context, locals) : null;
            }
        }
    }

    /**
     * 위험 지점 노드를 만들고 메서드에서 잇는다.
     *
     * @return 위험 지점 노드 키
     */
    public String addSink(ObjcMethod caller, TSNode expr, SinkRule rule, String api, Map<String, Object> extraProps) {
        ObjcFile file = caller.file();
        int line = ObjcFile.line(expr);
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
        props.put("language", "objc");
        props.putAll(extraProps);
        String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK, caller.key() + "@L" + line + ":" + column + " " + api,
                file.filePath, line, ObjcFile.endLine(expr), props));
        draft.addEdge(methodKey(caller), sinkKey, CodeEdgeKind.CALLS);
        return sinkKey;
    }

    public static String methodKey(ObjcMethod method) {
        return CodeGraphDraft.key(CodeNodeKind.METHOD, method.key());
    }
}
