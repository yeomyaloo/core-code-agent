package com.codeagent.core.python;

import org.treesitter.TSNode;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 파싱한 Python 파일 하나. 모듈 이름, import 표, 클래스·함수 색인, 모듈 변수 타입을 가진다.
 * tree-sitter 노드는 트리가 살아 있을 때만 쓸 수 있으므로 트리를 같이 들고 있는다.
 */
final class PythonModule {

    /** 함수(메서드 포함). node 가 null 이면 모듈 최상위 코드(&lt;module&gt;) */
    record FunctionInfo(String qualifiedName, String name, PythonModule module, ClassInfo owner,
                        TSNode node, TSNode outer, List<String> decorators) {

        boolean isModuleBody() {
            return node == null;
        }
    }

    record ClassInfo(String qualifiedName, String name, PythonModule module, TSNode node, TSNode outer,
                     List<String> bases, List<String> decorators, Map<String, FunctionInfo> methods) {
    }

    final String name;
    final String filePath;
    final boolean isPackage;
    final byte[] source;
    final TSTree tree;
    final TSNode root;

    /** 이 모듈에서 쓰는 이름 → 정규화 대상 (import os → os, from a.b import c as d → d: a.b.c). 상대 import 는 풀어서 넣는다 */
    final Map<String, String> imports = new LinkedHashMap<>();
    final Map<String, FunctionInfo> functions = new LinkedHashMap<>();
    final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    /** 모듈 최상위 변수 → 값을 만든 호출 식 (나중에 import 로 정규화) */
    final Map<String, TSNode> moduleAssignments = new LinkedHashMap<>();
    final FunctionInfo body;

    PythonModule(String name, String filePath, boolean isPackage, byte[] source, TSTree tree) {
        this.name = name;
        this.filePath = filePath;
        this.isPackage = isPackage;
        this.source = source;
        this.tree = tree;
        this.root = tree.getRootNode();
        this.body = new FunctionInfo(name + ".<module>", "<module>", this, null, null, null, List.of());
        index();
    }

    // ---- 색인 ----

    private void index() {
        collectImports(root);
        for (TSNode child : namedChildren(root)) {
            indexStatement(child, null, name);
        }
    }

    private void indexStatement(TSNode statement, ClassInfo owner, String scope) {
        TSNode def = statement;
        List<String> decorators = List.of();
        if (statement.getType().equals("decorated_definition")) {
            def = statement.getChildByFieldName("definition");
            decorators = decorators(statement);
        }
        if (isNull(def)) {
            return;
        }
        switch (def.getType()) {
            case "function_definition" -> {
                String fnName = text(def.getChildByFieldName("name"));
                String qualified = scope + "." + fnName;
                FunctionInfo fn = new FunctionInfo(qualified, fnName, this, owner, def, statement, decorators);
                functions.put(qualified, fn);
                if (owner != null) {
                    owner.methods().put(fnName, fn);
                }
                // 중첩 함수·클래스도 따로 색인한다 (바깥 함수 이름을 이름 앞에 붙임)
                TSNode fnBody = def.getChildByFieldName("body");
                for (TSNode inner : namedChildren(fnBody)) {
                    if (isDefinition(inner)) {
                        indexStatement(inner, null, qualified);
                    }
                }
            }
            case "class_definition" -> {
                String clsName = text(def.getChildByFieldName("name"));
                String qualified = scope + "." + clsName;
                List<String> bases = new ArrayList<>();
                TSNode superclasses = def.getChildByFieldName("superclasses");
                for (TSNode base : namedChildren(superclasses)) {
                    if (base.getType().equals("identifier") || base.getType().equals("attribute")) {
                        bases.add(text(base));
                    }
                }
                ClassInfo cls = new ClassInfo(qualified, clsName, this, def, statement, bases, decorators, new LinkedHashMap<>());
                classes.put(qualified, cls);
                for (TSNode member : namedChildren(def.getChildByFieldName("body"))) {
                    if (isDefinition(member)) {
                        indexStatement(member, isFunction(member) ? cls : null, qualified);
                    }
                }
            }
            case "expression_statement" -> {
                if (owner == null && scope.equals(name)) {
                    TSNode assignment = firstNamedChild(def);
                    if (!isNull(assignment) && assignment.getType().equals("assignment")) {
                        TSNode left = assignment.getChildByFieldName("left");
                        TSNode right = assignment.getChildByFieldName("right");
                        if (!isNull(left) && left.getType().equals("identifier") && !isNull(right)) {
                            moduleAssignments.put(text(left), right);
                        }
                    }
                }
            }
            default -> {
                // if __name__ == "__main__": 같은 블록 안의 정의도 찾는다
                if (owner == null && scope.equals(name) && (def.getType().equals("if_statement")
                        || def.getType().equals("try_statement") || def.getType().equals("block"))) {
                    for (TSNode child : namedChildren(def)) {
                        indexStatement(child, null, scope);
                    }
                }
            }
        }
    }

    private static boolean isDefinition(TSNode node) {
        String type = node.getType();
        return type.equals("function_definition") || type.equals("class_definition") || type.equals("decorated_definition");
    }

    private static boolean isFunction(TSNode node) {
        if (node.getType().equals("decorated_definition")) {
            TSNode def = node.getChildByFieldName("definition");
            return !isNull(def) && def.getType().equals("function_definition");
        }
        return node.getType().equals("function_definition");
    }

    private List<String> decorators(TSNode decorated) {
        List<String> result = new ArrayList<>();
        for (TSNode child : namedChildren(decorated)) {
            if (child.getType().equals("decorator")) {
                TSNode expr = firstNamedChild(child);
                if (!isNull(expr)) {
                    TSNode target = expr.getType().equals("call") ? expr.getChildByFieldName("function") : expr;
                    result.add(text(target));
                }
            }
        }
        return result;
    }

    // ---- import ----

    private void collectImports(TSNode node) {
        switch (node.getType()) {
            case "import_statement" -> {
                for (TSNode name : namedChildren(node)) {
                    if (name.getType().equals("dotted_name")) {
                        // import a.b → 이름 a 가 생긴다
                        String dotted = text(name);
                        String head = dotted.contains(".") ? dotted.substring(0, dotted.indexOf('.')) : dotted;
                        imports.putIfAbsent(head, head);
                    } else if (name.getType().equals("aliased_import")) {
                        imports.put(text(name.getChildByFieldName("alias")), text(name.getChildByFieldName("name")));
                    }
                }
                return;
            }
            case "import_from_statement" -> {
                TSNode moduleNode = node.getChildByFieldName("module_name");
                String base = moduleNode == null || isNull(moduleNode) ? "" : resolveModuleName(moduleNode);
                for (int i = 0; i < node.getNamedChildCount(); i++) {
                    TSNode child = node.getNamedChild(i);
                    if (TSNode.eq(child, moduleNode)) {
                        continue;
                    }
                    if (child.getType().equals("dotted_name")) {
                        String imported = text(child);
                        imports.put(imported.contains(".") ? imported.substring(imported.lastIndexOf('.') + 1) : imported,
                                join(base, imported));
                    } else if (child.getType().equals("aliased_import")) {
                        imports.put(text(child.getChildByFieldName("alias")), join(base, text(child.getChildByFieldName("name"))));
                    }
                }
                return;
            }
            default -> {
            }
        }
        for (TSNode child : namedChildren(node)) {
            collectImports(child);
        }
    }

    /** from 뒤의 모듈 이름. 상대 import(.x, ..x)는 이 모듈의 패키지 기준으로 푼다 */
    private String resolveModuleName(TSNode moduleNode) {
        if (!moduleNode.getType().equals("relative_import")) {
            return text(moduleNode);
        }
        String raw = text(moduleNode);
        int dots = 0;
        while (dots < raw.length() && raw.charAt(dots) == '.') {
            dots++;
        }
        String rest = raw.substring(dots);
        String pkg = isPackage ? name : parent(name);
        for (int i = 1; i < dots; i++) {
            pkg = parent(pkg);
        }
        return join(pkg, rest);
    }

    private static String parent(String dotted) {
        int dot = dotted.lastIndexOf('.');
        return dot < 0 ? "" : dotted.substring(0, dot);
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) {
            return b;
        }
        return b.isEmpty() ? a : a + "." + b;
    }

    // ---- tree-sitter 도우미 ----

    String text(TSNode node) {
        if (isNull(node)) {
            return "";
        }
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start < 0 || end > source.length || start > end) {
            return "";
        }
        return new String(source, start, end - start, StandardCharsets.UTF_8);
    }

    static boolean isNull(TSNode node) {
        return node == null || node.isNull();
    }

    static List<TSNode> namedChildren(TSNode node) {
        if (isNull(node)) {
            return List.of();
        }
        int count = node.getNamedChildCount();
        List<TSNode> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(node.getNamedChild(i));
        }
        return result;
    }

    static TSNode firstNamedChild(TSNode node) {
        return isNull(node) || node.getNamedChildCount() == 0 ? null : node.getNamedChild(0);
    }

    static int line(TSNode node) {
        return node.getStartPoint().getRow() + 1;
    }

    static int endLine(TSNode node) {
        return node.getEndPoint().getRow() + 1;
    }

    static int column(TSNode node) {
        return node.getStartPoint().getColumn() + 1;
    }
}
