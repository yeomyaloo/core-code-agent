package com.codeagent.core.java;

import com.codeagent.core.java.JavaProject.TypeInfo;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 프로젝트에 선언한 문자열 상수의 값을 구한다 (경로 상수: {@code @GetMapping(Paths.USERS)}, {@code GET(API + "/x")}).
 * 문자열 리터럴, + 로 이은 것, 같은 클래스·바깥 클래스·상위 클래스의 필드, 다른 클래스의 필드(Paths.USERS),
 * static import 한 필드를 따라간다. 초기값이 문자열로 정해지지 않으면 빈 값.
 */
public final class JavaConstants {

    private static final int MAX_DEPTH = 8;

    private final JavaProject project;

    public JavaConstants(JavaProject project) {
        this.project = project;
    }

    public Optional<String> value(Expression expr, TypeInfo context) {
        return Optional.ofNullable(evaluate(expr, context, 0));
    }

    private String evaluate(Expression expr, TypeInfo context, int depth) {
        if (depth > MAX_DEPTH || expr == null) {
            return null;
        }
        if (expr instanceof StringLiteralExpr literal) {
            return literal.asString();
        }
        if (expr instanceof EnclosedExpr enclosed) {
            return evaluate(enclosed.getInner(), context, depth + 1);
        }
        if (expr instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            String left = evaluate(binary.getLeft(), context, depth + 1);
            String right = evaluate(binary.getRight(), context, depth + 1);
            return left == null || right == null ? null : left + right;
        }
        if (expr instanceof NameExpr name) {
            return field(context, name.getNameAsString(), depth);
        }
        if (expr instanceof FieldAccessExpr access) {
            String owner = context.resolver().resolve(access.getScope().toString());
            return project.type(owner).map(t -> field(t, access.getNameAsString(), depth)).orElse(null);
        }
        return null;
    }

    /** 이름으로 찾는 필드: 자기·바깥·상위 클래스, 그다음 static import */
    private String field(TypeInfo context, String name, int depth) {
        for (TypeInfo type : scopes(context)) {
            Optional<FieldDeclaration> field = type.decl().getFieldByName(name);
            if (field.isPresent()) {
                return field.get().getVariables().stream()
                        .filter(v -> v.getNameAsString().equals(name))
                        .findFirst()
                        .flatMap(VariableDeclarator::getInitializer)
                        .map(init -> evaluate(init, type, depth + 1))
                        .orElse(null);
            }
        }
        Optional<CompilationUnit> unit = context.decl().findCompilationUnit();
        if (unit.isEmpty()) {
            return null;
        }
        for (ImportDeclaration imp : unit.get().getImports()) {
            if (!imp.isStatic()) {
                continue;
            }
            String imported = imp.getNameAsString();
            String ownerName = imp.isAsterisk() ? imported : imported.endsWith("." + name) ? imported.substring(0, imported.lastIndexOf('.')) : null;
            if (ownerName == null) {
                continue;
            }
            Optional<TypeInfo> owner = project.type(ownerName);
            if (owner.isPresent() && owner.get().decl().getFieldByName(name).isPresent()) {
                return field(owner.get(), name, depth + 1);
            }
        }
        return null;
    }

    /** 자기 클래스, 바깥 클래스들, 프로젝트 안 상위 타입 */
    private List<TypeInfo> scopes(TypeInfo context) {
        List<TypeInfo> result = new ArrayList<>();
        result.add(context);
        for (Node parent = context.decl().getParentNode().orElse(null); parent != null; parent = parent.getParentNode().orElse(null)) {
            if (parent instanceof com.github.javaparser.ast.body.TypeDeclaration<?> outer) {
                outer.getFullyQualifiedName().flatMap(project::type).ifPresent(result::add);
            }
        }
        result.addAll(project.ancestors(context));
        return result;
    }
}
