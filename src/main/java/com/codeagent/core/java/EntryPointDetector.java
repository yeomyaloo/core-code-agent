package com.codeagent.core.java;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.codeagent.core.java.JavaProject.MethodInfo;
import com.codeagent.core.java.JavaProject.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Spring MVC 컨트롤러에서 HTTP 진입점을 찾는다.
 */
public class EntryPointDetector {

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("RestController", "Controller");

    /** 매핑 애너테이션 → HTTP 메서드 (RequestMapping 은 method 인자로 정한다). Kotlin 진입점도 같이 쓴다 */
    public static final Map<String, String> MAPPING_ANNOTATIONS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "");

    public record EntryPoint(String httpMethod, String path, MethodInfo handler) {

        public String qualifiedName() {
            return httpMethod + " " + path + " -> " + handler.key();
        }
    }

    /** 경로 상수(Paths.USERS)를 값으로 풀 때. 없으면 ${Paths.USERS} 로 남긴다 */
    private final JavaConstants constants;

    public EntryPointDetector() {
        this(null);
    }

    public EntryPointDetector(JavaProject project) {
        this.constants = project == null ? null : new JavaConstants(project);
    }

    public List<EntryPoint> detect(TypeInfo type) {
        if (findAnnotation(type.decl(), CONTROLLER_ANNOTATIONS).isEmpty()) {
            return List.of();
        }
        List<String> classPaths = findAnnotation(type.decl(), Set.of("RequestMapping"))
                .map(a -> paths(a, type))
                .orElse(List.of(""));

        List<EntryPoint> result = new ArrayList<>();
        for (MethodInfo method : type.methods()) {
            if (!(method.decl() instanceof MethodDeclaration decl)) {
                continue;
            }
            for (AnnotationExpr annotation : decl.getAnnotations()) {
                String fixedMethod = MAPPING_ANNOTATIONS.get(annotation.getName().getIdentifier());
                if (fixedMethod == null) {
                    continue;
                }
                List<String> httpMethods = fixedMethod.isEmpty() ? requestMethods(annotation) : List.of(fixedMethod);
                for (String classPath : classPaths) {
                    for (String methodPath : paths(annotation, type)) {
                        for (String httpMethod : httpMethods) {
                            result.add(new EntryPoint(httpMethod, joinPath(classPath, methodPath), method));
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Optional<AnnotationExpr> findAnnotation(NodeWithAnnotations<?> node, Set<String> names) {
        return node.getAnnotations().stream()
                .filter(a -> names.contains(a.getName().getIdentifier()))
                .findFirst();
    }

    private List<String> paths(AnnotationExpr annotation, TypeInfo type) {
        List<String> paths = new ArrayList<>();
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            paths.addAll(values(single.getMemberValue(), type));
        } else if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                String name = pair.getNameAsString();
                if (name.equals("value") || name.equals("path")) {
                    paths.addAll(values(pair.getValue(), type));
                }
            }
        }
        return paths.isEmpty() ? List.of("") : paths;
    }

    private List<String> requestMethods(AnnotationExpr annotation) {
        List<String> methods = new ArrayList<>();
        if (annotation instanceof NormalAnnotationExpr normal) {
            normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("method"))
                    .forEach(p -> methods.addAll(values(p.getValue(), null)));
        }
        return methods.isEmpty() ? List.of("ANY") : methods;
    }

    /** @param context 상수를 풀 클래스 (요청 메서드처럼 풀 필요가 없으면 null) */
    private List<String> values(Expression expr, TypeInfo context) {
        if (expr instanceof ArrayInitializerExpr array) {
            return array.getValues().stream().flatMap(v -> values(v, context).stream()).toList();
        }
        if (expr instanceof StringLiteralExpr literal) {
            return List.of(literal.getValue());
        }
        if (constants != null && context != null) {
            Optional<String> constant = constants.value(expr, context);
            if (constant.isPresent()) {
                return List.of(constant.get());
            }
        }
        if (expr instanceof FieldAccessExpr field) {
            // RequestMethod.GET 은 GET 으로, 상수 경로는 ${Paths.USERS} 처럼 남긴다
            return List.of(field.getScope().toString().equals("RequestMethod")
                    ? field.getNameAsString() : "${" + field + "}");
        }
        if (expr instanceof NameExpr name) {
            String id = name.getNameAsString();
            return List.of(id.matches("GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE") ? id : "${" + id + "}");
        }
        return List.of("${" + expr + "}");
    }

    public static String joinPath(String classPath, String methodPath) {
        String joined = ("/" + classPath + "/" + methodPath).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }
}
