package com.codeagent.core.java;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.frontend.FrontendGraph;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.java.EntryPointDetector.EntryPoint;
import com.codeagent.core.java.JavaProject.MethodInfo;
import com.codeagent.core.java.JavaProject.TypeInfo;
import com.codeagent.core.rules.SinkRule;
import com.codeagent.core.rules.SinkRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 파싱된 Java/Spring 프로젝트에서 코드 그래프(파일·클래스·메서드·진입점·위험 지점과 그 관계)를 만든다.
 * 진입점(Spring MVC)과 위험 지점 규칙(SinkRules, IDOR 후보)만 여기서 정하고, 나머지는 {@link JavaGraphSupport} 가 맡는다.
 */
public class CodeGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphBuilder.class);

    private final JavaProject project;
    private final EntryPointDetector entryPointDetector = new EntryPointDetector();
    private final CodeGraphDraft draft;
    private final JavaGraphSupport support;

    public CodeGraphBuilder(JavaProject project) {
        this(project, new CodeGraphDraft());
    }

    /** 이미 다른 언어가 채운 그래프에 Java 부분을 더할 때 */
    public CodeGraphBuilder(JavaProject project, CodeGraphDraft draft) {
        this.project = project;
        this.draft = draft;
        this.support = new JavaGraphSupport(project, draft, Map.of());
    }

    /** 호출을 잇기 전에 다른 언어와 연결할 때 (JavaKotlinInterop.connect) */
    public JavaGraphSupport support() {
        return support;
    }

    /** Java 그래프와 프론트엔드 위험 지점을 만든다 (예전 동작 그대로) */
    public CodeGraphDraft build() {
        buildJava();
        FrontendGraph.contribute(project.root(), draft);
        return draft;
    }

    /** Java 부분만 만든다 */
    public CodeGraphDraft buildJava() {
        project.types().forEach(support::addStructure);
        project.types().forEach(support::addOverrides);
        project.types().forEach(this::addEntryPoints);
        project.types().forEach(t -> t.methods().forEach(this::addCalls));
        return draft;
    }

    private void addEntryPoints(TypeInfo type) {
        for (EntryPoint entryPoint : entryPointDetector.detect(type)) {
            MethodInfo handler = entryPoint.handler();
            String entryKey = draft.addNode(JavaGraphSupport.node(CodeNodeKind.ENTRY_POINT, entryPoint.qualifiedName(), type.filePath(),
                    handler.decl(), Map.of(
                            "httpMethod", entryPoint.httpMethod(),
                            "path", entryPoint.path(),
                            "handler", handler.key())));
            draft.addEdge(entryKey, JavaGraphSupport.methodKey(handler), CodeEdgeKind.ROUTES_TO);
        }
    }

    // ---- 호출 관계와 위험 지점 ----

    private void addCalls(MethodInfo caller) {
        for (MethodCallExpr call : caller.decl().findAll(MethodCallExpr.class)) {
            String targetType = call.getScope()
                    .map(scope -> support.typeOfScope(scope, caller))
                    .orElse(null);

            // 저장소·매퍼가 프로젝트에 선언한 메서드도 id 하나로 데이터를 다루면 IDOR 후보다
            Optional<SinkRule> idor = idorCandidate(caller, call, targetType);
            if (idor.isPresent()) {
                addSink(caller, call, idor.get(), call.getNameAsString());
                continue;
            }

            if (support.linkProjectCall(caller, call, targetType)) {
                continue;
            }

            String method = call.getNameAsString();
            // 저장소의 findById 등은 상속받은 라이브러리 메서드라 프로젝트 호출로 이어지지 않는다
            Optional<SinkRule> sink = SinkRules.match(targetType, method)
                    .or(() -> project.isSpringDataRepository(targetType)
                            ? SinkRules.repositoryIdAccess(targetType, method) : Optional.empty());
            if (sink.isPresent()) {
                addSink(caller, call, sink.get(), method);
            } else if (targetType == null) {
                draft.countUnresolvedCall();
                log.debug("호출 대상 타입을 알 수 없음: {} ({})", call, caller.key());
            }
        }

        for (ObjectCreationExpr creation : caller.decl().findAll(ObjectCreationExpr.class)) {
            String type = caller.owner().resolver().resolve(creation.getType().getNameWithScope());
            SinkRules.match(type, "<init>").ifPresent(rule -> addSink(caller, creation, rule, "<init>"));
        }
    }

    /**
     * IDOR 후보:
     * - Spring Data 저장소에 선언한 메서드: 조건이 식별자 하나뿐인 파생 쿼리(findByBuyerId), 식별자 인자 하나만 받는 @Query 메서드
     * - MyBatis 매퍼(@Mapper) 메서드: 식별자 인자 하나만 받는 것 (주인 조건 인자가 같이 있으면 제외)
     * - Spring Data save 에 @RequestBody·@ModelAttribute 로 받은 객체를 그대로 넘기는 호출 (본문의 id 로 남의 데이터를 덮어씀)
     */
    private Optional<SinkRule> idorCandidate(MethodInfo caller, MethodCallExpr call, String targetType) {
        if (targetType == null) {
            return Optional.empty();
        }
        String method = call.getNameAsString();
        boolean repository = project.isSpringDataRepository(targetType);
        boolean mapper = project.type(targetType)
                .map(t -> t.decl().getAnnotations().stream().anyMatch(a -> isAnnotation(t, a.getNameAsString(), SinkRules.MYBATIS_MAPPER)))
                .orElse(false);
        if (!repository && !mapper) {
            return Optional.empty();
        }
        if (repository && SinkRules.SAVE_METHODS.contains(method) && call.getArguments().size() == 1
                && call.getArgument(0) instanceof NameExpr arg && isRequestBodyParameter(caller, arg.getNameAsString())) {
            return Optional.of(SinkRules.idorCandidate(targetType, method));
        }
        List<MethodInfo> declared = project.findMethods(targetType, method, call.getArguments().size());
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        CallableDeclaration<?> decl = declared.get(0).decl();
        if (decl.getParameters().size() != 1) {
            return Optional.empty();
        }
        Parameter parameter = decl.getParameter(0);
        boolean idParameter = SinkRules.idLikeName(paramName(parameter));
        boolean candidate = mapper
                ? idParameter
                : SinkRules.singleIdCondition(method) || (decl.getAnnotationByName("Query").isPresent() && idParameter);
        return candidate ? Optional.of(SinkRules.idorCandidate(targetType, method)) : Optional.empty();
    }

    /** @Param("orderId") 가 있으면 그 이름, 없으면 파라미터 이름 */
    private static String paramName(Parameter parameter) {
        return parameter.getAnnotationByName("Param")
                .flatMap(a -> a.isSingleMemberAnnotationExpr()
                        ? Optional.of(a.asSingleMemberAnnotationExpr().getMemberValue())
                        : a.isNormalAnnotationExpr()
                        ? a.asNormalAnnotationExpr().getPairs().stream().filter(p -> p.getNameAsString().equals("value")).findFirst().map(p -> p.getValue())
                        : Optional.empty())
                .filter(e -> e.isStringLiteralExpr())
                .map(e -> e.asStringLiteralExpr().asString())
                .orElse(parameter.getNameAsString());
    }

    private static boolean isRequestBodyParameter(MethodInfo caller, String name) {
        return caller.decl().getParameters().stream()
                .anyMatch(p -> p.getNameAsString().equals(name)
                        && (p.getAnnotationByName("RequestBody").isPresent() || p.getAnnotationByName("ModelAttribute").isPresent()));
    }

    private static boolean isAnnotation(TypeInfo type, String simpleOrQualified, String fqn) {
        if (simpleOrQualified.equals(fqn)) {
            return true;
        }
        return fqn.endsWith("." + simpleOrQualified) && fqn.equals(type.resolver().resolve(simpleOrQualified));
    }

    private void addSink(MethodInfo caller, Expression expr, SinkRule rule, String method) {
        support.addSink(caller, expr, rule, rule.api(method), Map.of());
    }
}
