package com.codeagent.core.csharp;

import com.codeagent.core.SourceFiles;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCSharp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.codeagent.core.csharp.CsFile.field;
import static com.codeagent.core.csharp.CsFile.isNull;
import static com.codeagent.core.csharp.CsFile.namedChildren;

/**
 * C# 저장소 색인: namespace·클래스(부모·인터페이스, 메서드, 필드·속성 타입, 기본 생성자 파라미터)와 최상위 문장(Program.cs)을 모은다.
 * 타입 이름은 이름만 쓴다 (Shop.Data.ProductRepository → ProductRepository, List&lt;T&gt; → List, T? → T).
 * 식의 타입은 C# 에 적힌 타입(파라미터, 필드, 속성, var x = new T(), 반환 타입)으로 알아낸다.
 */
final class CsProject {

    static final Set<String> SKIP_DIRS = Set.of(".git", "bin", "obj", "node_modules", "packages", ".vs", "TestResults", "wwwroot",
            "Migrations", "dist", "build");
    private static final long MAX_FILE_BYTES = 2_000_000;
    static final Set<String> TYPE_DECLARATIONS = Set.of("class_declaration", "interface_declaration", "record_declaration",
            "struct_declaration");
    static final Set<String> LAMBDAS = Set.of("lambda_expression", "anonymous_method_expression");
    /** 외부 메서드 이름 끝 → 반환 타입 */
    private static final Map<String, String> KNOWN_RETURNS = Map.of(
            ".CreateClient", "HttpClient",
            ".CreateCommand", "DbCommand",
            "XmlReader.Create", "XmlReader",
            "Process.Start", "Process");

    record CsFunc(String key, String name, CsFile file, TSNode node, CsClass owner, CsFunc parent) {
    }

    static final class CsClass {
        final String fullName;
        final String name;
        final CsFile file;
        final TSNode node;
        final boolean isInterface;
        final List<String> bases = new ArrayList<>();
        final Map<String, List<CsFunc>> methods = new LinkedHashMap<>();
        final Map<String, String> memberTypes = new LinkedHashMap<>();

        CsClass(String fullName, String name, CsFile file, TSNode node, boolean isInterface) {
            this.fullName = fullName;
            this.name = name;
            this.file = file;
            this.node = node;
            this.isInterface = isInterface;
        }
    }

    /** 지역 이름: 적힌 타입(var 면 null), 값 식 */
    record Local(String declaredType, TSNode value) {
    }

    record Callee(List<CsFunc> functions, String external, String unknownMethod) {
        static Callee none() {
            return new Callee(List.of(), null, null);
        }
    }

    final Path root;
    private final List<CsFile> files = new ArrayList<>();
    private final Map<String, CsClass> classes = new LinkedHashMap<>();
    private final Map<String, CsClass> bySimpleName = new HashMap<>();
    private final Map<String, CsFunc> byNode = new HashMap<>();
    private final List<CsFunc> functions = new ArrayList<>();
    private final Map<String, CsFunc> mains = new LinkedHashMap<>();
    private final Set<String> keys = new HashSet<>();
    private final Map<String, Map<String, Local>> localsCache = new HashMap<>();

    private CsProject(Path root) {
        this.root = root;
    }

    // ---- 불러오기 ----

    static CsProject load(Path root, List<String> parseErrors) {
        CsProject project = new CsProject(root.toAbsolutePath().normalize());
        TSParser parser;
        try {
            parser = parser(new TreeSitterCSharp());
        } catch (LinkageError | RuntimeException e) {
            parseErrors.add("(csharp): C# 파서를 불러오지 못해 C# 파일을 건너뜀 (지원하지 않는 OS/CPU 일 수 있음: " + e.getMessage() + ")");
            return project;
        }
        for (Path file : SourceFiles.find(project.root, SKIP_DIRS, MAX_FILE_BYTES, CsProject::isSource)) {
            String relative = SourceFiles.relative(project.root, file);
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) {
                    text = text.substring(1);
                }
                TSTree tree = parser.parseString(null, text);
                CsFile cf = new CsFile(relative, text.getBytes(StandardCharsets.UTF_8), tree);
                if (cf.root.hasError()) {
                    parseErrors.add(relative + ": 문법 오류가 있는 부분은 건너뜀");
                }
                project.files.add(cf);
            } catch (IOException | RuntimeException e) {
                parseErrors.add(relative + ": " + e.getMessage());
            }
        }
        project.files.forEach(project::indexFile);
        return project;
    }

    static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".cs") && !name.endsWith(".Designer.cs") && !name.endsWith(".g.cs") && !name.endsWith("Tests.cs")
                && !name.endsWith("Test.cs");
    }

    private static TSParser parser(TSLanguage language) {
        TSParser parser = new TSParser();
        parser.setLanguage(language);
        return parser;
    }

    private void indexFile(CsFile file) {
        for (TSNode using : CsFile.children(file.root, "using_directive")) {
            TSNode alias = field(using, "name");
            List<TSNode> parts = namedChildren(using);
            if (alias != null && parts.size() >= 2) {
                file.aliases.put(file.text(alias), file.text(parts.get(parts.size() - 1)));
            } else if (!parts.isEmpty()) {
                file.usings.add(file.text(parts.get(parts.size() - 1)));
            }
        }
        boolean hasGlobal = false;
        for (TSNode child : namedChildren(file.root)) {
            switch (child.getType()) {
                case "file_scoped_namespace_declaration" -> file.namespace = file.text(field(child, "name"));
                case "namespace_declaration" -> {
                    String ns = file.text(field(child, "name"));
                    if (file.namespace.isEmpty()) {
                        file.namespace = ns;
                    }
                    indexTypes(file, field(child, "body"), ns);
                }
                case "global_statement" -> hasGlobal = true;
                default -> {
                }
            }
        }
        indexTypes(file, file.root, file.namespace);
        if (hasGlobal) {
            mains.put(file.filePath, register(file, file.root, file.filePath + "#<main>", "<main>", null, null));
        }
    }

    private void indexTypes(CsFile file, TSNode container, String prefix) {
        for (TSNode decl : namedChildren(container)) {
            if (!TYPE_DECLARATIONS.contains(decl.getType())) {
                continue;
            }
            String name = file.text(field(decl, "name"));
            String fullName = prefix.isEmpty() ? name : prefix + "." + name;
            CsClass cls = new CsClass(fullName, name, file, decl, decl.getType().equals("interface_declaration"));
            for (TSNode base : namedChildren(CsFile.firstChild(decl, "base_list"))) {
                cls.bases.add(simpleType(file.text(base)));
            }
            // 기본 생성자 파라미터 (class X(IRepo repo))
            for (TSNode param : CsFile.children(CsFile.firstChild(decl, "parameter_list"), "parameter")) {
                cls.memberTypes.put(file.text(field(param, "name")), simpleType(file.text(field(param, "type"))));
            }
            classes.put(fullName, cls);
            bySimpleName.putIfAbsent(name, cls);
            TSNode body = field(decl, "body");
            for (TSNode member : namedChildren(body)) {
                switch (member.getType()) {
                    case "field_declaration" -> {
                        TSNode declaration = CsFile.firstChild(member, "variable_declaration");
                        String type = simpleType(file.text(field(declaration, "type")));
                        for (TSNode declarator : CsFile.children(declaration, "variable_declarator")) {
                            cls.memberTypes.put(file.text(field(declarator, "name")), type);
                        }
                    }
                    case "property_declaration" ->
                            cls.memberTypes.put(file.text(field(member, "name")), simpleType(file.text(field(member, "type"))));
                    case "method_declaration" -> {
                        String methodName = file.text(field(member, "name"));
                        cls.methods.computeIfAbsent(methodName, k -> new ArrayList<>())
                                .add(register(file, member, fullName + "#" + methodName, methodName, cls, null));
                    }
                    case "constructor_declaration" -> cls.methods.computeIfAbsent(".ctor", k -> new ArrayList<>())
                            .add(register(file, member, fullName + "#.ctor", ".ctor", cls, null));
                    default -> {
                    }
                }
            }
            indexTypes(file, body, fullName);
        }
    }

    /** Shop.Data.Repo → Repo, List&lt;T&gt; → List, T? → T, Task&lt;T&gt; → T */
    static String simpleType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String t = type.strip().replaceAll("\\?$", "");
        if (t.matches("(Task|ValueTask|ActionResult|Task<ActionResult)<.+>")) {
            t = t.substring(t.indexOf('<') + 1, t.lastIndexOf('>'));
        }
        t = t.replaceAll("<.*>", "");
        return t.substring(t.lastIndexOf('.') + 1);
    }

    // ---- 함수 ----

    CsFunc register(CsFile file, TSNode node, String key, String name, CsClass owner, CsFunc parent) {
        String nodeKey = file.filePath + "@" + node.getStartByte();
        CsFunc existing = byNode.get(nodeKey);
        if (existing != null) {
            return existing;
        }
        if (!keys.add(key)) {
            key = key + "@L" + CsFile.line(node);
            keys.add(key);
        }
        CsFunc fn = new CsFunc(key, name, file, node, owner, parent);
        byNode.put(nodeKey, fn);
        functions.add(fn);
        return fn;
    }

    CsFunc registerLambda(CsFile file, TSNode node, String name, CsFunc parent) {
        return register(file, node, file.filePath + "#" + name, name, parent == null ? null : parent.owner(), parent);
    }

    boolean isRegistered(CsFile file, TSNode node) {
        return byNode.containsKey(file.filePath + "@" + node.getStartByte());
    }

    List<CsFunc> functions() {
        return List.copyOf(functions);
    }

    List<CsFile> files() {
        return files;
    }

    java.util.Collection<CsClass> classes() {
        return classes.values();
    }

    CsFunc main(CsFile file) {
        return mains.get(file.filePath);
    }

    CsClass cls(String simpleName) {
        return simpleName == null ? null : bySimpleName.get(simpleName);
    }

    Optional<CsFunc> functionAt(String filePath, int line) {
        CsFunc best = null;
        for (CsFunc fn : functions) {
            if (fn.file().filePath.equals(filePath) && CsFile.line(fn.node()) <= line && line <= CsFile.endLine(fn.node())
                    && (best == null || fn.node().getStartByte() > best.node().getStartByte())) {
                best = fn;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 노드를 감싼 색인된 함수 (없으면 파일 최상위) */
    CsFunc enclosing(CsFile file, TSNode node) {
        for (TSNode current = node.getParent(); !isNull(current); current = current.getParent()) {
            CsFunc fn = byNode.get(file.filePath + "@" + current.getStartByte());
            if (fn != null && !fn.name().equals("<main>")) {
                return fn;
            }
        }
        return mains.get(file.filePath);
    }

    CsClass baseClass(CsClass cls) {
        if (cls == null) {
            return null;
        }
        for (String base : cls.bases) {
            CsClass candidate = bySimpleName.get(base);
            if (candidate != null && !candidate.isInterface) {
                return candidate;
            }
        }
        return null;
    }

    /** 부모 클래스까지의 이름 (외부 부모는 이름만: ControllerBase) */
    List<String> ancestorNames(CsClass cls) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < 8 && cls != null; i++) {
            result.addAll(cls.bases);
            cls = baseClass(cls);
        }
        return result;
    }

    CsFunc findMethod(CsClass cls, String name, int argCount, int depth) {
        if (cls == null || depth > 8) {
            return null;
        }
        List<CsFunc> overloads = cls.methods.get(name);
        if (overloads != null && !overloads.isEmpty()) {
            for (CsFunc fn : overloads) {
                if (parameterCount(fn) == argCount) {
                    return fn;
                }
            }
            return overloads.get(0);
        }
        return findMethod(baseClass(cls), name, argCount, depth + 1);
    }

    String memberType(CsClass cls, String name, int depth) {
        if (cls == null || depth > 8) {
            return null;
        }
        String type = cls.memberTypes.get(name);
        return type != null ? type : memberType(baseClass(cls), name, depth + 1);
    }

    int parameterCount(CsFunc fn) {
        return CsFile.children(field(fn.node(), "parameters"), "parameter").size();
    }

    /** 인터페이스(또는 부모)를 구현한 클래스의 같은 이름 메서드 */
    List<CsFunc> implementations(String type, String method, int argCount) {
        List<CsFunc> result = new ArrayList<>();
        for (CsClass cls : classes.values()) {
            if (cls.isInterface || cls.name.equals(type) || !ancestorNames(cls).contains(type)) {
                continue;
            }
            CsFunc fn = findMethod(cls, method, argCount, 0);
            if (fn != null) {
                result.add(fn);
            }
        }
        return result;
    }

    // ---- 지역 이름 ----

    Map<String, Local> locals(CsFunc fn) {
        return localsCache.computeIfAbsent(fn.key(), k -> {
            Map<String, Local> locals = new LinkedHashMap<>();
            CsFile file = fn.file();
            for (TSNode param : CsFile.children(field(fn.node(), "parameters"), "parameter")) {
                locals.put(file.text(field(param, "name")), new Local(simpleType(file.text(field(param, "type"))), null));
            }
            TSNode body = fn.name().equals("<main>") ? fn.node() : field(fn.node(), "body");
            collectLocals(fn, body, locals);
            return locals;
        });
    }

    private void collectLocals(CsFunc fn, TSNode node, Map<String, Local> locals) {
        CsFile file = fn.file();
        for (TSNode child : namedChildren(node)) {
            String type = child.getType();
            if (TYPE_DECLARATIONS.contains(type) || LAMBDAS.contains(type) && isRegistered(file, child)) {
                continue;
            }
            if (type.equals("variable_declaration")) {
                TSNode declaredType = field(child, "type");
                String declared = declaredType == null || declaredType.getType().equals("implicit_type") ? null : simpleType(file.text(declaredType));
                for (TSNode declarator : CsFile.children(child, "variable_declarator")) {
                    List<TSNode> parts = namedChildren(declarator);
                    TSNode value = parts.size() > 1 ? parts.get(parts.size() - 1) : null;
                    if (value != null && value.getType().equals("equals_value_clause")) {
                        List<TSNode> inner = namedChildren(value);
                        value = inner.isEmpty() ? null : inner.get(0);
                    }
                    locals.putIfAbsent(file.text(field(declarator, "name")), new Local(declared, value));
                }
            } else if (type.equals("foreach_statement")) {
                TSNode left = field(child, "left");
                if (left != null) {
                    locals.putIfAbsent(file.text(left), new Local(null, null));
                }
            } else if (LAMBDAS.contains(type)) {
                for (TSNode param : CsFile.children(field(child, "parameters"), "parameter")) {
                    locals.putIfAbsent(file.text(field(param, "name")), new Local(simpleType(file.text(field(param, "type"))), null));
                }
            }
            collectLocals(fn, child, locals);
        }
    }

    Local local(CsFunc fn, String name) {
        for (CsFunc current = fn; current != null; current = current.parent()) {
            Local local = locals(current).get(name);
            if (local != null) {
                return local;
            }
        }
        return null;
    }

    // ---- 타입 ----

    String typeOf(CsFunc fn, TSNode expr) {
        return typeOf(fn, expr, 0);
    }

    private String typeOf(CsFunc fn, TSNode expr, int depth) {
        expr = CsFile.unwrap(expr);
        if (isNull(expr) || depth > 8) {
            return null;
        }
        CsFile file = fn.file();
        switch (expr.getType()) {
            case "identifier" -> {
                String name = file.text(expr);
                Local local = local(fn, name);
                if (local != null) {
                    return local.declaredType() != null ? local.declaredType() : typeOf(fn, local.value(), depth + 1);
                }
                return memberType(owner(fn), name, 0);
            }
            case "this_expression", "this" -> {
                return owner(fn) == null ? null : owner(fn).name;
            }
            case "member_access_expression" -> {
                TSNode object = field(expr, "expression");
                String name = file.text(field(expr, "name"));
                if (object != null && (object.getType().equals("this_expression") || file.text(object).equals("this"))) {
                    return memberType(owner(fn), name, 0);
                }
                return memberType(bySimpleName.get(typeOf(fn, object, depth + 1)), name, 0);
            }
            case "object_creation_expression" -> {
                return simpleType(file.text(field(expr, "type")));
            }
            case "await_expression", "cast_expression" -> {
                if (expr.getType().equals("cast_expression")) {
                    return simpleType(file.text(field(expr, "type")));
                }
                List<TSNode> inner = namedChildren(expr);
                return inner.isEmpty() ? null : typeOf(fn, inner.get(inner.size() - 1), depth + 1);
            }
            case "invocation_expression" -> {
                Callee callee = callee(fn, expr);
                if (callee.functions().isEmpty() && callee.external() != null) {
                    // 외부 팩터리의 반환 타입 (IHttpClientFactory.CreateClient → HttpClient)
                    String external = callee.external();
                    for (Map.Entry<String, String> known : KNOWN_RETURNS.entrySet()) {
                        if (external.endsWith(known.getKey())) {
                            return known.getValue();
                        }
                    }
                    return null;
                }
                for (CsFunc target : callee.functions()) {
                    String type = simpleType(file.text(field(target.node(), "returns")));
                    if (type == null) {
                        type = simpleType(target.file().text(field(target.node(), "type")));
                    }
                    if (type != null && !type.equals("void")) {
                        return type;
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    CsClass owner(CsFunc fn) {
        for (CsFunc current = fn; current != null; current = current.parent()) {
            if (current.owner() != null) {
                return current.owner();
            }
        }
        return null;
    }

    // ---- 호출 대상 ----

    Callee callee(CsFunc fn, TSNode node) {
        CsFile file = fn.file();
        if (node.getType().equals("object_creation_expression")) {
            String type = simpleType(file.text(field(node, "type")));
            CsClass cls = bySimpleName.get(type);
            if (cls != null) {
                CsFunc ctor = findMethod(cls, ".ctor", CsFile.arguments(node).size(), 0);
                return ctor != null ? new Callee(List.of(ctor), null, null) : Callee.none();
            }
            return type == null ? Callee.none() : new Callee(List.of(), "new " + type, null);
        }
        TSNode function = field(node, "function");
        int argCount = CsFile.arguments(node).size();
        if (function == null) {
            return Callee.none();
        }
        if (function.getType().equals("identifier") || function.getType().equals("generic_name")) {
            String name = methodName(file, function);
            CsFunc target = findMethod(owner(fn), name, argCount, 0);
            return target != null ? new Callee(List.of(target), null, null) : new Callee(List.of(), name, null);
        }
        if (!function.getType().equals("member_access_expression")) {
            return Callee.none();
        }
        TSNode object = field(function, "expression");
        String name = methodName(file, field(function, "name"));
        String objectText = file.text(object);
        if (objectText.equals("this") || objectText.equals("base")) {
            CsFunc target = findMethod(objectText.equals("base") ? baseClass(owner(fn)) : owner(fn), name, argCount, 0);
            return target != null ? new Callee(List.of(target), null, null) : new Callee(List.of(), name, null);
        }
        String type = typeOf(fn, object);
        CsClass cls = bySimpleName.get(type);
        if (cls != null) {
            if (cls.isInterface) {
                List<CsFunc> impls = implementations(cls.name, name, argCount);
                return impls.isEmpty() ? Callee.none() : new Callee(impls, null, null);
            }
            CsFunc target = findMethod(cls, name, argCount, 0);
            return target != null ? new Callee(List.of(target), null, null) : new Callee(List.of(), null, name);
        }
        if (type != null) {
            return new Callee(List.of(), type + "." + name, name);
        }
        // 정적 호출: File.ReadAllText · Results.Redirect · Random.Shared.Next · 프로젝트 정적 클래스
        if (object != null && (object.getType().equals("identifier") || object.getType().equals("member_access_expression"))
                && Character.isUpperCase(objectText.charAt(0)) && local(fn, objectText) == null) {
            CsClass staticClass = bySimpleName.get(objectText.substring(objectText.lastIndexOf('.') + 1));
            if (staticClass != null) {
                CsFunc target = findMethod(staticClass, name, argCount, 0);
                if (target != null) {
                    return new Callee(List.of(target), null, null);
                }
            }
            return new Callee(List.of(), objectText + "." + name, name);
        }
        return new Callee(List.of(), null, name);
    }

    private static String methodName(CsFile file, TSNode node) {
        if (node != null && node.getType().equals("generic_name")) {
            List<TSNode> parts = namedChildren(node);
            return parts.isEmpty() ? file.text(node) : file.text(parts.get(0));
        }
        return file.text(node);
    }

    /** 상수: 리터럴, const, nameof, 상수끼리 + */
    boolean constant(CsFunc fn, TSNode expr, int depth) {
        expr = CsFile.unwrap(expr);
        if (expr == null || depth > 6) {
            return false;
        }
        CsFile file = fn.file();
        switch (expr.getType()) {
            case "string_literal", "verbatim_string_literal", "raw_string_literal", "integer_literal", "real_literal", "boolean_literal",
                 "null_literal", "character_literal", "typeof_expression" -> {
                return true;
            }
            case "interpolated_string_expression" -> {
                return CsFile.firstChild(expr, "interpolation") == null;
            }
            case "binary_expression" -> {
                return constant(fn, field(expr, "left"), depth + 1) && constant(fn, field(expr, "right"), depth + 1);
            }
            case "member_access_expression" -> {
                // HttpMethod.Get · Path.Combine 의 상수 인자 같은 것은 아님: 상수 필드(대문자로 시작하는 정적 멤버)
                String text = file.text(expr);
                return text.matches("[A-Z]\\w*(\\.[A-Z]\\w*)+") && !text.matches("(Request|Response|HttpContext|User|RouteData)\\..*");
            }
            case "invocation_expression" -> {
                String text = file.text(field(expr, "function"));
                return (text.equals("nameof") || text.equals("Path.Combine")) && CsFile.arguments(expr).stream().allMatch(a -> constant(fn, a, depth + 1));
            }
            case "identifier" -> {
                Local local = local(fn, file.text(expr));
                if (local != null) {
                    return false;
                }
                // const 필드
                CsClass cls = owner(fn);
                if (cls != null) {
                    for (TSNode member : CsFile.children(field(cls.node, "body"), "field_declaration")) {
                        if (file == cls.file && cls.file.text(member).contains("const ")
                                && cls.file.text(member).matches("(?s).*\\b" + java.util.regex.Pattern.quote(file.text(expr)) + "\\b.*")) {
                            return true;
                        }
                    }
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }
}
