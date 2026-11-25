package com.codeagent.core.kotlin;

import com.codeagent.core.kotlin.KotlinProject.KotlinFunction;
import com.codeagent.core.kotlin.KotlinProject.KotlinType;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.rules.SinkRule;
import org.treesitter.TSNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import static com.codeagent.core.kotlin.KotlinFile.firstChild;
import static com.codeagent.core.kotlin.KotlinFile.namedChildren;

/**
 * Kotlin 코드 그래프를 만드는 공통 부품 ({@link com.codeagent.core.java.JavaGraphSupport} 의 Kotlin 판).
 * 구조(FILE → CLASS → METHOD), 상속 연결, 호출 대상 타입 알아내기, 프로젝트 함수 호출 잇기, 위험 지점 노드를 맡는다.
 * 위험 지점 규칙과 진입점은 쓰는 쪽이 정한다 (서버: {@link KotlinLanguage}, 모바일: Sherlock Phones).
 * 노드 props 에 language=kotlin 을 단다.
 */
public final class KotlinGraphSupport {

    private static final int SNIPPET_MAX_LENGTH = 300;

    private final KotlinProject project;
    private final CodeGraphDraft draft;
    private final Map<String, String> knownReturnTypes;

    /**
     * @param knownReturnTypes 라이브러리 jar 없이 반환 타입을 아는 메서드 이름 → 타입 FQN (예: getIntent → android.content.Intent).
     *                         Kotlin 프로퍼티 접근(intent, settings)은 getter 이름(getIntent, getSettings)으로 찾는다
     */
    public KotlinGraphSupport(KotlinProject project, CodeGraphDraft draft, Map<String, String> knownReturnTypes) {
        this.project = project;
        this.draft = draft;
        this.knownReturnTypes = Map.copyOf(knownReturnTypes);
    }

    /** 같은 앱의 다른 언어 메서드를 찾는 곳 ({@link com.codeagent.core.interop.JavaKotlinInterop}) */
    public interface ForeignCalls {
        /** type.name(인자 argCount 개) 를 받는 다른 언어 메서드의 그래프 키 */
        List<String> find(String type, String name, int argCount);

        /** 그 메서드의 반환 타입 FQN */
        default Optional<String> returnType(String type, String name, int argCount) {
            return Optional.empty();
        }
    }

    private ForeignCalls foreign = (type, name, argCount) -> List.of();

    public void foreignCalls(ForeignCalls foreign) {
        this.foreign = foreign;
    }

    public CodeGraphDraft draft() {
        return draft;
    }

    // ---- 구조 ----

    public void addStructure(KotlinType type) {
        String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, type.filePath(), type.filePath(), null, null,
                Map.of("language", "kotlin")));
        String classKey = draft.addNode(new NodeDraft(CodeNodeKind.CLASS, type.fqn(), type.filePath(), type.startLine(),
                type.endLine(), Map.of("declaration", type.declaration(), "annotations", type.annotations(),
                "superTypes", type.superTypes(), "language", "kotlin")));
        int dot = type.fqn().lastIndexOf('.');
        String outer = dot < 0 ? null : type.fqn().substring(0, dot);
        draft.addEdge(outer != null && project.isProjectType(outer) ? CodeGraphDraft.key(CodeNodeKind.CLASS, outer) : fileKey,
                classKey, CodeEdgeKind.CONTAINS);
        for (KotlinFunction function : type.functions()) {
            String methodKey = draft.addNode(new NodeDraft(CodeNodeKind.METHOD, function.key(), type.filePath(),
                    function.startLine(), function.endLine(), Map.of("name", function.name(),
                    "annotations", function.annotations(), "language", "kotlin")));
            draft.addEdge(classKey, methodKey, CodeEdgeKind.CONTAINS);
        }
    }

    public void addOverrides(KotlinType type) {
        for (KotlinType ancestor : project.ancestors(type)) {
            for (KotlinFunction function : type.functions()) {
                ancestor.functions().stream()
                        .filter(f -> f.name().equals(function.name()) && f.paramTypes().size() == function.paramTypes().size())
                        .forEach(f -> draft.addEdge(methodKey(f), methodKey(function), CodeEdgeKind.OVERRIDDEN_BY));
            }
        }
    }

    // ---- 호출 ----

    /**
     * 함수 안의 호출을 모두 본다. 프로젝트 함수면 CALLS 로 잇고, 아니면 rules(타입, 메서드)로 위험 지점인지 본다.
     * 생성자 호출(Foo(...))은 메서드 이름 &lt;init&gt; 으로 묻는다.
     *
     * @param extraProps 위험 지점 props 에 더할 것 (없으면 빈 맵)
     * @return 타입을 몰라 판단하지 못한 호출 수
     */
    public int addCalls(KotlinFunction function, BiFunction<String, String, Optional<SinkRule>> rules, Map<String, Object> extraProps) {
        KotlinFile file = function.owner().file();
        Map<String, String> locals = new HashMap<>(function.paramTypes());
        int unresolved = 0;
        for (TSNode call : KotlinFile.descendants(function.node(), "call_expression", "function_declaration")) {
            collectLocal(file, call, locals);
            List<TSNode> parts = namedChildren(call);
            if (parts.isEmpty()) {
                continue;
            }
            TSNode callee = parts.get(0);
            int args = argumentCount(call);
            if (callee.getType().equals("simple_identifier")) {
                String name = file.text(callee);
                // 자기 클래스(와 바깥 클래스·같은 파일 최상위) 함수
                if (linkUnscoped(function, name, args)) {
                    continue;
                }
                if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                    String type = project.resolve(file, name);
                    if (type != null) {
                        // 같은 앱의 Java 클래스 생성자
                        List<String> constructors = foreign.find(type, "<init>", args);
                        if (!constructors.isEmpty()) {
                            constructors.forEach(t -> draft.addEdge(methodKey(function), t, CodeEdgeKind.CALLS));
                            continue;
                        }
                        Optional<SinkRule> rule = rules.apply(type, "<init>");
                        if (rule.isPresent()) {
                            addSink(function, call, rule.get(), rule.get().api("<init>"), extraProps);
                        }
                    }
                }
                continue;
            }
            if (!callee.getType().equals("navigation_expression")) {
                continue;
            }
            List<TSNode> nav = namedChildren(callee);
            if (nav.size() < 2) {
                continue;
            }
            TSNode suffix = nav.get(nav.size() - 1);
            TSNode nameNode = firstChild(suffix, "simple_identifier");
            if (nameNode == null) {
                continue;
            }
            String method = file.text(nameNode);
            String targetType = typeOf(nav.get(0), function, locals);
            if (targetType != null && project.isProjectType(targetType)) {
                List<KotlinFunction> targets = project.findFunctions(targetType, method, args);
                if (!targets.isEmpty()) {
                    targets.forEach(t -> draft.addEdge(methodKey(function), methodKey(t), CodeEdgeKind.CALLS));
                    continue;
                }
            }
            if (targetType != null) {
                // 같은 앱의 Java 클래스 메서드
                List<String> javaTargets = foreign.find(targetType, method, args);
                if (!javaTargets.isEmpty()) {
                    javaTargets.forEach(t -> draft.addEdge(methodKey(function), t, CodeEdgeKind.CALLS));
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

    private boolean linkUnscoped(KotlinFunction caller, String name, int args) {
        KotlinType owner = caller.owner();
        String fqn = owner.fqn();
        // 자기 클래스 → 바깥 클래스 순
        while (fqn != null) {
            List<KotlinFunction> found = project.findFunctions(fqn, name, args);
            if (!found.isEmpty()) {
                found.forEach(t -> draft.addEdge(methodKey(caller), methodKey(t), CodeEdgeKind.CALLS));
                return true;
            }
            int dot = fqn.lastIndexOf('.');
            fqn = dot < 0 ? null : fqn.substring(0, dot);
            if (fqn != null && !project.isProjectType(fqn)) {
                break;
            }
        }
        // 같은 패키지의 파일 최상위 함수
        String pkg = owner.file().packageName();
        for (KotlinType type : project.types()) {
            if (type.declaration().equals("file") && type.file().packageName().equals(pkg)) {
                List<KotlinFunction> found = project.findFunctions(type.fqn(), name, args);
                if (!found.isEmpty()) {
                    found.forEach(t -> draft.addEdge(methodKey(caller), methodKey(t), CodeEdgeKind.CALLS));
                    return true;
                }
            }
        }
        return false;
    }

    /** val x: Foo = ... / val x = Foo(...) 로 선언한 지역 변수 */
    private void collectLocal(KotlinFile file, TSNode call, Map<String, String> locals) {
        TSNode parent = call.getParent();
        if (KotlinFile.isNull(parent) || !parent.getType().equals("property_declaration")) {
            return;
        }
        TSNode variable = firstChild(parent, "variable_declaration");
        TSNode name = variable == null ? null : firstChild(variable, "simple_identifier");
        if (name == null || locals.containsKey(file.text(name))) {
            return;
        }
        String declared = project.typeOf(file, variable);
        if (declared == null) {
            declared = project.constructedType(file, call);
        }
        if (declared == null) {
            declared = callResultType(file, call);
        }
        if (declared != null) {
            locals.put(file.text(name), declared);
        }
    }

    /** 받는 쪽 식의 타입. 모르면 null */
    String typeOf(TSNode expr, KotlinFunction context, Map<String, String> locals) {
        KotlinFile file = context.owner().file();
        switch (expr.getType()) {
            case "this_expression" -> {
                return context.owner().fqn();
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
                    // Foo.bar() : object·companion·정적 호출
                    return project.resolve(file, name);
                }
                // intent, settings 같은 프레임워크 프로퍼티 (getter 이름으로)
                return knownReturnTypes.get("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
            }
            case "call_expression" -> {
                String constructed = project.constructedType(file, expr);
                if (constructed != null) {
                    return constructed;
                }
                // javaObject.getStore(): Java 메서드의 반환 타입
                List<TSNode> parts = namedChildren(expr);
                if (!parts.isEmpty() && parts.get(0).getType().equals("navigation_expression")) {
                    List<TSNode> nav = namedChildren(parts.get(0));
                    TSNode nameNode = nav.size() < 2 ? null : firstChild(nav.get(nav.size() - 1), "simple_identifier");
                    String owner = nameNode == null ? null : typeOf(nav.get(0), context, locals);
                    if (owner != null) {
                        Optional<String> returned = foreign.returnType(owner, file.text(nameNode), argumentCount(expr));
                        if (returned.isPresent()) {
                            return returned.get();
                        }
                    }
                }
                return callResultType(file, expr);
            }
            case "navigation_expression" -> {
                List<TSNode> nav = namedChildren(expr);
                TSNode suffix = nav.get(nav.size() - 1);
                TSNode name = firstChild(suffix, "simple_identifier");
                if (name == null) {
                    return null;
                }
                String prop = file.text(name);
                String owner = typeOf(nav.get(0), context, locals);
                if (owner != null && project.isProjectType(owner)) {
                    String t = project.type(owner).map(o -> project.propertyType(o, prop)).orElse(null);
                    if (t != null) {
                        return t;
                    }
                }
                // Java 클래스의 getter (user.name → getName())
                if (owner != null) {
                    Optional<String> getter = foreign.returnType(owner, "get" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1), 0);
                    if (getter.isPresent()) {
                        return getter.get();
                    }
                }
                // intent.data → getData
                return knownReturnTypes.get("get" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1));
            }
            case "parenthesized_expression" -> {
                List<TSNode> inner = namedChildren(expr);
                return inner.isEmpty() ? null : typeOf(inner.get(0), context, locals);
            }
            default -> {
                return null;
            }
        }
    }

    /** a.getIntent() 처럼 알려진 프레임워크 메서드 호출의 결과 타입 */
    private String callResultType(KotlinFile file, TSNode call) {
        TSNode callee = namedChildren(call).isEmpty() ? null : namedChildren(call).get(0);
        if (callee == null) {
            return null;
        }
        TSNode nameNode = callee.getType().equals("navigation_expression")
                ? firstChild(namedChildren(callee).get(namedChildren(callee).size() - 1), "simple_identifier")
                : callee.getType().equals("simple_identifier") ? callee : null;
        return nameNode == null ? null : knownReturnTypes.get(file.text(nameNode));
    }

    private static int argumentCount(TSNode call) {
        TSNode suffix = firstChild(call, "call_suffix");
        TSNode args = suffix == null ? null : firstChild(suffix, "value_arguments");
        int count = args == null ? 0 : KotlinFile.children(args, "value_argument").size();
        // 끝에 붙인 람다도 인자 하나
        return count + (suffix != null && firstChild(suffix, "annotated_lambda") != null ? 1 : 0);
    }

    /**
     * 위험 지점 노드를 만들고 함수에서 잇는다.
     *
     * @return 위험 지점 노드 키
     */
    public String addSink(KotlinFunction caller, TSNode expr, SinkRule rule, String api, Map<String, Object> extraProps) {
        KotlinFile file = caller.owner().file();
        int line = KotlinFile.line(expr);
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
        props.put("language", "kotlin");
        props.putAll(extraProps);
        String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK, caller.key() + "@L" + line + ":" + column + " " + api,
                file.filePath, line, KotlinFile.endLine(expr), props));
        draft.addEdge(methodKey(caller), sinkKey, CodeEdgeKind.CALLS);
        return sinkKey;
    }

    public static String methodKey(KotlinFunction function) {
        return CodeGraphDraft.key(CodeNodeKind.METHOD, function.key());
    }
}
