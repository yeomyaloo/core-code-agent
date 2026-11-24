package com.codeagent.core.java;

import com.codeagent.core.java.JavaProject.MethodInfo;
import com.codeagent.core.java.JavaProject.TypeInfo;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.rules.SinkRule;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Java 코드 그래프를 만드는 공통 부품. 진입점·위험 지점 규칙과 상관없는 일을 맡는다:
 * 구조(FILE → CLASS → METHOD), 상속 연결, 호출 대상의 타입 알아내기, 프로젝트 메서드 호출 잇기, 위험 지점 노드 만들기.
 * Spring 용 {@link CodeGraphBuilder} 와, 이 엔진을 쓰는 다른 제품(예: 안드로이드 규칙을 쓰는 Sherlock Phones)이 함께 쓴다.
 */
public final class JavaGraphSupport {

    private static final int SNIPPET_MAX_LENGTH = 300;

    private final JavaProject project;
    private final CodeGraphDraft draft;
    private final Map<String, String> knownReturnTypes;

    /**
     * @param knownReturnTypes 라이브러리 jar 없이 반환 타입을 알 수 있는 메서드 이름 → 타입 FQN
     *                         (예: 안드로이드 getIntent → android.content.Intent). 없으면 빈 맵
     */
    public JavaGraphSupport(JavaProject project, CodeGraphDraft draft, Map<String, String> knownReturnTypes) {
        this.project = project;
        this.draft = draft;
        this.knownReturnTypes = Map.copyOf(knownReturnTypes);
    }

    public JavaProject project() {
        return project;
    }

    public CodeGraphDraft draft() {
        return draft;
    }

    // ---- 구조: FILE → CLASS → METHOD ----

    public void addStructure(TypeInfo type) {
        String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, type.filePath(), type.filePath(), null, null, Map.of()));
        String classKey = draft.addNode(node(CodeNodeKind.CLASS, type.fqn(), type.filePath(), type.decl(), Map.of(
                "declaration", declarationKind(type.decl()),
                "annotations", annotations(type.decl()),
                "superTypes", type.superTypes())));

        Optional<String> outer = type.decl().getParentNode()
                .filter(p -> p instanceof TypeDeclaration<?>)
                .flatMap(p -> ((TypeDeclaration<?>) p).getFullyQualifiedName());
        draft.addEdge(outer.map(o -> CodeGraphDraft.key(CodeNodeKind.CLASS, o)).orElse(fileKey), classKey, CodeEdgeKind.CONTAINS);

        for (MethodInfo method : type.methods()) {
            String methodKey = draft.addNode(node(CodeNodeKind.METHOD, method.key(), type.filePath(), method.decl(), Map.of(
                    "name", method.name(),
                    "annotations", annotations(method.decl()))));
            draft.addEdge(classKey, methodKey, CodeEdgeKind.CONTAINS);
        }
    }

    /** 상위 타입(인터페이스·부모 클래스)의 메서드 → 그 메서드를 구현·재정의한 메서드 */
    public void addOverrides(TypeInfo type) {
        for (TypeInfo ancestor : project.ancestors(type)) {
            for (MethodInfo method : type.methods()) {
                if (method.isConstructor()) {
                    continue;
                }
                ancestor.methods().stream()
                        .filter(m -> m.name().equals(method.name()) && m.paramTypes().size() == method.paramTypes().size())
                        .forEach(m -> draft.addEdge(methodKey(m), methodKey(method), CodeEdgeKind.OVERRIDDEN_BY));
            }
        }
    }

    // ---- 호출 ----

    /**
     * 호출이 프로젝트 메서드면 CALLS 로 잇는다. 스코프 없는 호출은 자기 클래스 → 바깥 클래스 순으로 찾는다.
     *
     * @return 프로젝트 메서드로 이었으면 true (위험 지점 규칙을 볼 필요가 없다)
     */
    public boolean linkProjectCall(MethodInfo caller, MethodCallExpr call, String targetType) {
        if (call.getScope().isEmpty()) {
            return linkProjectCall(caller, enclosingTypes(caller), call);
        }
        return project.isProjectType(targetType) && linkProjectCall(caller, List.of(targetType), call);
    }

    private boolean linkProjectCall(MethodInfo caller, List<String> candidateTypes, MethodCallExpr call) {
        for (String type : candidateTypes) {
            List<MethodInfo> targets = project.findMethods(type, call.getNameAsString(), call.getArguments().size());
            if (!targets.isEmpty()) {
                targets.forEach(t -> draft.addEdge(methodKey(caller), methodKey(t), CodeEdgeKind.CALLS));
                return true;
            }
        }
        return false;
    }

    /**
     * 위험 지점 노드를 만들고 caller 에서 잇는다.
     *
     * @param expr       위험한 호출·생성 식, 또는 메서드 선언 자체(예: 아무 인증서나 믿는 checkServerTrusted)
     * @param extraProps category·cwe·api·method·snippet 뒤에 붙일 속성 (없으면 빈 맵)
     * @return 위험 지점 노드 키
     */
    public String addSink(MethodInfo caller, Node expr, SinkRule rule, String api, Map<String, Object> extraProps) {
        int line = expr.getBegin().map(p -> p.line).orElse(0);
        int column = expr.getBegin().map(p -> p.column).orElse(0);
        String snippet = expr instanceof MethodDeclaration decl ? decl.getDeclarationAsString(false, false, true) : expr.toString();
        if (snippet.length() > SNIPPET_MAX_LENGTH) {
            snippet = snippet.substring(0, SNIPPET_MAX_LENGTH) + "...";
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("category", rule.category());
        props.put("cwe", rule.cwe());
        props.put("api", api);
        props.put("method", caller.key());
        props.put("snippet", snippet);
        props.putAll(extraProps);

        String sinkKey = draft.addNode(node(CodeNodeKind.SINK, caller.key() + "@L" + line + ":" + column + " " + api,
                caller.owner().filePath(), expr, props));
        draft.addEdge(methodKey(caller), sinkKey, CodeEdgeKind.CALLS);
        return sinkKey;
    }

    // ---- 타입 알아내기 ----

    /**
     * 호출 대상 표현식의 타입 FQN을 구한다.
     * 변수·필드 선언과 import 문을 먼저 보고(라이브러리 jar 없이 동작), 안 되면 symbol solver에 맡긴다.
     * 그래도 모르면 knownReturnTypes 로 프레임워크 메서드의 반환 타입을 본다 (getIntent().getStringExtra(...) 등).
     */
    public String typeOfScope(Expression scope, MethodInfo context) {
        TypeInfo owner = context.owner();
        if (scope.isThisExpr()) {
            return owner.fqn();
        }
        if (scope.isSuperExpr()) {
            return owner.superTypes().isEmpty() ? null : owner.superTypes().get(0);
        }
        if (scope instanceof NameExpr name) {
            String variableType = variableType(name.getNameAsString(), context);
            if (variableType != null) {
                return variableType;
            }
            String staticType = owner.resolver().resolve(name.getNameAsString());
            if (staticType != null) {
                return staticType;
            }
        }
        // new SAXReader().read(...) 처럼 만들자마자 부르는 호출
        if (scope instanceof ObjectCreationExpr creation) {
            String createdType = JavaProject.resolveType(creation.getType(), owner.resolver());
            if (createdType != null) {
                return createdType;
            }
        }
        if (scope instanceof FieldAccessExpr field && field.getScope().isThisExpr()) {
            String fieldType = project.fieldType(owner, field.getNameAsString());
            if (fieldType != null) {
                return fieldType;
            }
        }
        String solved = solve(scope);
        if (solved == null && scope instanceof MethodCallExpr call) {
            return knownReturnTypes.get(call.getNameAsString());
        }
        return solved;
    }

    private String variableType(String name, MethodInfo context) {
        TypeResolver resolver = context.owner().resolver();
        // 지역 변수 (var 면 초기값으로 추론)
        for (VariableDeclarator variable : context.decl().findAll(VariableDeclarator.class)) {
            if (variable.getNameAsString().equals(name)) {
                if (variable.getType().isVarType()) {
                    return variable.getInitializer().map(this::solve).orElse(null);
                }
                return JavaProject.resolveType(variable.getType(), resolver);
            }
        }
        // 메서드·람다·catch 파라미터
        for (Parameter parameter : context.decl().findAll(Parameter.class)) {
            if (parameter.getNameAsString().equals(name) && !parameter.getType().isUnknownType()) {
                return JavaProject.resolveType(parameter.getType(), resolver);
            }
        }
        // 필드 (자기 클래스 → 바깥 클래스, 각각 상위 타입 포함)
        for (String typeFqn : enclosingTypes(context)) {
            String fieldType = project.type(typeFqn).map(t -> project.fieldType(t, name)).orElse(null);
            if (fieldType != null) {
                return fieldType;
            }
        }
        return null;
    }

    /** 메서드를 감싼 클래스들 (안쪽부터) */
    public List<String> enclosingTypes(MethodInfo method) {
        List<String> result = new ArrayList<>();
        Node current = method.owner().decl();
        while (current != null) {
            if (current instanceof TypeDeclaration<?> td) {
                td.getFullyQualifiedName().ifPresent(result::add);
            }
            current = current.getParentNode().orElse(null);
        }
        return result;
    }

    private String solve(Expression expr) {
        try {
            ResolvedType type = expr.calculateResolvedType();
            return type.isReferenceType() ? type.asReferenceType().getQualifiedName() : null;
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    // ---- 헬퍼 ----

    public static String methodKey(MethodInfo method) {
        return CodeGraphDraft.key(CodeNodeKind.METHOD, method.key());
    }

    /** AST 노드의 시작·끝 줄로 노드를 만든다 */
    public static NodeDraft node(CodeNodeKind kind, String qualifiedName, String filePath, Node astNode, Map<String, Object> props) {
        Integer begin = astNode.getBegin().map(p -> p.line).orElse(null);
        Integer end = astNode.getEnd().map(p -> p.line).orElse(null);
        return new NodeDraft(kind, qualifiedName, filePath, begin, end, props);
    }

    private static List<String> annotations(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().map(a -> a.getName().getIdentifier()).toList();
    }

    private static String declarationKind(TypeDeclaration<?> decl) {
        if (decl.isClassOrInterfaceDeclaration()) {
            return decl.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
        }
        if (decl.isEnumDeclaration()) {
            return "enum";
        }
        if (decl.isRecordDeclaration()) {
            return "record";
        }
        return "annotation";
    }
}
