package com.codeagent.core.php;

import com.codeagent.core.SourceFiles;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPhp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.codeagent.core.php.PhpFile.field;
import static com.codeagent.core.php.PhpFile.isNull;
import static com.codeagent.core.php.PhpFile.namedChildren;

/**
 * PHP 저장소 색인: namespace 와 use 로 클래스 이름을 전체 이름(App\Http\Controllers\UserController)으로 풀고,
 * 클래스(부모, 인터페이스, 메서드, 프로퍼티 타입)·함수·파일 최상위 코드를 모은다.
 * 식의 타입은 PHP 에 적힌 타입(파라미터, 프로퍼티, 생성자 승격 프로퍼티, new, 반환 타입)으로 알아낸다.
 */
final class PhpProject {

    static final Set<String> SKIP_DIRS = Set.of(".git", "vendor", "node_modules", "storage", "cache", "var", "tests", "test",
            "Tests", "bootstrap/cache", "public/build", "dist", "build", ".idea");
    private static final long MAX_FILE_BYTES = 2_000_000;
    static final Set<String> FUNCTION_TYPES = Set.of("function_definition", "method_declaration", "anonymous_function",
            "arrow_function", "anonymous_function_creation_expression");
    /** 전역 이름 그대로 쓰는 내장 클래스 */
    private static final Set<String> BUILTIN_CLASSES = Set.of("PDO", "mysqli", "DOMDocument", "XMLReader", "SimpleXMLElement",
            "Exception", "RuntimeException", "InvalidArgumentException", "Closure", "DateTime", "DateTimeImmutable", "stdClass");
    /** use 없이 쓰는 Laravel 파사드 별칭 */
    private static final Map<String, String> FACADES = Map.ofEntries(
            Map.entry("DB", "Illuminate\\Support\\Facades\\DB"),
            Map.entry("Http", "Illuminate\\Support\\Facades\\Http"),
            Map.entry("Redirect", "Illuminate\\Support\\Facades\\Redirect"),
            Map.entry("Storage", "Illuminate\\Support\\Facades\\Storage"),
            Map.entry("File", "Illuminate\\Support\\Facades\\File"),
            Map.entry("Route", "Illuminate\\Support\\Facades\\Route"),
            Map.entry("Process", "Illuminate\\Support\\Facades\\Process"),
            Map.entry("Response", "Illuminate\\Support\\Facades\\Response"),
            Map.entry("Request", "Illuminate\\Support\\Facades\\Request"),
            Map.entry("Auth", "Illuminate\\Support\\Facades\\Auth"),
            Map.entry("Hash", "Illuminate\\Support\\Facades\\Hash"));

    // ---- 모델 ----

    /** 함수·메서드·익명 함수·파일 최상위 코드(&lt;main&gt;). parent 는 익명 함수를 감싼 함수 (use 변수 찾기) */
    record PhpFunc(String key, String name, PhpFile file, TSNode node, PhpClass owner, PhpFunc parent) {
    }

    static final class PhpClass {
        final String fqcn;
        final String name;
        final PhpFile file;
        final TSNode node;
        final boolean isInterface;
        String parent;
        final List<String> interfaces = new ArrayList<>();
        final Map<String, PhpFunc> methods = new LinkedHashMap<>();
        final Map<String, String> propertyTypes = new LinkedHashMap<>();

        PhpClass(String fqcn, String name, PhpFile file, TSNode node, boolean isInterface) {
            this.fqcn = fqcn;
            this.name = name;
            this.file = file;
            this.node = node;
            this.isInterface = isInterface;
        }
    }

    /** 지역 이름의 선언: 적힌 타입, 값 식 */
    record Local(String declaredType, TSNode value) {
    }

    record Callee(List<PhpFunc> functions, String external, String unknownMethod) {
        static Callee none() {
            return new Callee(List.of(), null, null);
        }
    }

    final Path root;
    private final List<PhpFile> files = new ArrayList<>();
    private final Map<String, PhpClass> classes = new HashMap<>();
    private final Map<String, PhpClass> classesBySimpleName = new HashMap<>();
    private final Map<String, PhpFunc> functionsByName = new HashMap<>();
    private final Map<String, PhpFunc> byNode = new HashMap<>();
    private final List<PhpFunc> functions = new ArrayList<>();
    private final Map<String, PhpFunc> mains = new LinkedHashMap<>();
    private final Set<String> keys = new HashSet<>();
    private final Map<String, Map<String, Local>> localsCache = new HashMap<>();

    private PhpProject(Path root) {
        this.root = root;
    }

    // ---- 불러오기 ----

    static PhpProject load(Path root, List<String> parseErrors) {
        PhpProject project = new PhpProject(root.toAbsolutePath().normalize());
        TSParser parser;
        try {
            parser = parser(new TreeSitterPhp());
        } catch (LinkageError | RuntimeException e) {
            parseErrors.add("(php): PHP 파서를 불러오지 못해 PHP 파일을 건너뜀 (지원하지 않는 OS/CPU 일 수 있음: " + e.getMessage() + ")");
            return project;
        }
        for (Path file : SourceFiles.find(project.root, SKIP_DIRS, MAX_FILE_BYTES, PhpProject::isSource)) {
            String relative = SourceFiles.relative(project.root, file);
            if (skipped(relative)) {
                continue;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                TSTree tree = parser.parseString(null, text);
                PhpFile pf = new PhpFile(relative, text.getBytes(StandardCharsets.UTF_8), tree);
                if (pf.root.hasError()) {
                    parseErrors.add(relative + ": 문법 오류가 있는 부분은 건너뜀");
                }
                project.files.add(pf);
            } catch (IOException | RuntimeException e) {
                parseErrors.add(relative + ": " + e.getMessage());
            }
        }
        project.files.forEach(project::indexHeader);
        project.files.forEach(project::indexDeclarations);
        project.classes.values().forEach(project::indexPropertyTypes);
        project.files.forEach(project::indexMain);
        return project;
    }

    static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".php") && !name.endsWith(".blade.php") && !name.endsWith("Test.php");
    }

    /** 캐시·테스트처럼 여러 단계 폴더로만 가릴 수 있는 것 */
    private static boolean skipped(String relative) {
        return relative.startsWith("bootstrap/cache/") || relative.contains("/vendor/") || relative.startsWith("database/migrations/");
    }

    private static TSParser parser(TSLanguage language) {
        TSParser parser = new TSParser();
        parser.setLanguage(language);
        return parser;
    }

    private void indexHeader(PhpFile file) {
        for (TSNode node : PhpFile.descendants(file.root, Set.of("namespace_definition", "namespace_use_declaration"),
                Set.of("class_declaration", "function_definition", "compound_statement"))) {
            if (node.getType().equals("namespace_definition")) {
                file.namespace = file.text(field(node, "name"));
                continue;
            }
            for (TSNode clause : PhpFile.descendants(node, Set.of("namespace_use_clause"), Set.of())) {
                List<TSNode> parts = namedChildren(clause);
                if (parts.isEmpty()) {
                    continue;
                }
                String full = file.text(parts.get(0)).replaceFirst("^\\\\", "");
                // 그룹 use: use App\Models\{User, Order};
                TSNode parent = clause.getParent();
                TSNode groupPrefix = PhpFile.firstChild(node, "namespace_name");
                if (!isNull(parent) && parent.getType().equals("namespace_use_group") && groupPrefix != null) {
                    full = file.text(groupPrefix).replaceFirst("^\\\\", "") + "\\" + full;
                }
                TSNode alias = field(clause, "alias");
                String simple = alias != null ? file.text(alias) : full.substring(full.lastIndexOf('\\') + 1);
                file.uses.put(simple, full);
            }
        }
    }

    private void indexDeclarations(PhpFile file) {
        for (TSNode node : PhpFile.descendants(file.root, Set.of("class_declaration", "interface_declaration", "trait_declaration",
                "enum_declaration", "function_definition"), Set.of("compound_statement", "class_declaration", "interface_declaration",
                "trait_declaration", "enum_declaration"))) {
            if (node.getType().equals("function_definition")) {
                String name = file.text(field(node, "name"));
                String owner = file.namespace.isEmpty() ? file.filePath : file.namespace;
                PhpFunc fn = register(file, node, owner + "#" + name, name, null, null);
                functionsByName.putIfAbsent(name.toLowerCase(Locale.ROOT), fn);
                if (!file.namespace.isEmpty()) {
                    functionsByName.put((file.namespace + "\\" + name).toLowerCase(Locale.ROOT), fn);
                }
                continue;
            }
            String name = file.text(field(node, "name"));
            String fqcn = file.namespace.isEmpty() ? name : file.namespace + "\\" + name;
            PhpClass cls = new PhpClass(fqcn, name, file, node, node.getType().equals("interface_declaration"));
            for (TSNode child : namedChildren(node)) {
                if (child.getType().equals("base_clause")) {
                    List<String> bases = namedChildren(child).stream().map(n -> resolveName(file, file.text(n), null)).toList();
                    if (cls.isInterface) {
                        cls.interfaces.addAll(bases);
                    } else if (!bases.isEmpty()) {
                        cls.parent = bases.get(0);
                    }
                } else if (child.getType().equals("class_interface_clause")) {
                    namedChildren(child).forEach(n -> cls.interfaces.add(resolveName(file, file.text(n), null)));
                }
            }
            classes.put(fqcn, cls);
            classesBySimpleName.putIfAbsent(name, cls);
            for (TSNode method : PhpFile.children(field(node, "body"), "method_declaration")) {
                String methodName = file.text(field(method, "name"));
                cls.methods.put(methodName.toLowerCase(Locale.ROOT), register(file, method, fqcn + "#" + methodName, methodName, cls, null));
            }
        }
    }

    private void indexPropertyTypes(PhpClass cls) {
        PhpFile file = cls.file;
        TSNode body = field(cls.node, "body");
        for (TSNode decl : PhpFile.children(body, "property_declaration")) {
            String type = typeName(file, field(decl, "type"), cls);
            for (TSNode element : PhpFile.children(decl, "property_element")) {
                String name = file.text(field(element, "name")).replaceFirst("^\\$", "");
                if (type != null) {
                    cls.propertyTypes.put(name, type);
                }
            }
        }
        PhpFunc constructor = cls.methods.get("__construct");
        if (constructor == null) {
            return;
        }
        for (TSNode param : namedChildren(field(constructor.node(), "parameters"))) {
            String type = typeName(file, field(param, "type"), cls);
            String name = file.text(field(param, "name")).replaceFirst("^\\$", "");
            if (param.getType().equals("property_promotion_parameter") && type != null) {
                cls.propertyTypes.put(name, type);
            }
        }
        // $this->repo = $repo;
        Map<String, Local> params = locals(constructor);
        for (TSNode assign : PhpFile.descendants(field(constructor.node(), "body"), Set.of("assignment_expression"), FUNCTION_TYPES)) {
            TSNode left = field(assign, "left");
            TSNode right = field(assign, "right");
            if (left != null && left.getType().equals("member_access_expression") && file.text(field(left, "object")).equals("$this")
                    && right != null && right.getType().equals("variable_name")) {
                Local param = params.get(file.text(right).replaceFirst("^\\$", ""));
                String property = file.text(field(left, "name"));
                if (param != null && param.declaredType() != null) {
                    cls.propertyTypes.putIfAbsent(property, param.declaredType());
                }
            }
        }
    }

    /** 클래스·함수 선언이 아닌 최상위 문장이 있으면 그 파일의 &lt;main&gt; 함수 */
    private void indexMain(PhpFile file) {
        if (topLevelStatements(file).isEmpty()) {
            return;
        }
        PhpFunc main = register(file, file.root, file.filePath + "#<main>", "<main>", null, null);
        mains.put(file.filePath, main);
    }

    List<TSNode> topLevelStatements(PhpFile file) {
        List<TSNode> result = new ArrayList<>();
        collectTopLevel(file.root, result);
        return result;
    }

    private void collectTopLevel(TSNode node, List<TSNode> out) {
        for (TSNode child : namedChildren(node)) {
            switch (child.getType()) {
                case "php_tag", "text_interpolation", "text", "comment", "namespace_use_declaration", "class_declaration",
                     "interface_declaration", "trait_declaration", "enum_declaration", "function_definition", "declare_statement",
                     "const_declaration" -> {
                }
                case "namespace_definition" -> collectTopLevel(field(child, "body"), out);
                default -> out.add(child);
            }
        }
    }

    // ---- 이름 ----

    /** 클래스 이름 → 전체 이름 (use, namespace, self/static/parent) */
    String resolveName(PhpFile file, String name, PhpClass context) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (context != null && (lower.equals("self") || lower.equals("static"))) {
            return context.fqcn;
        }
        if (context != null && lower.equals("parent")) {
            return context.parent;
        }
        if (name.startsWith("\\")) {
            return name.substring(1);
        }
        int slash = name.indexOf('\\');
        String first = slash < 0 ? name : name.substring(0, slash);
        String imported = file.uses.get(first);
        if (imported != null) {
            return slash < 0 ? imported : imported + name.substring(slash);
        }
        String namespaced = file.namespace.isEmpty() ? name : file.namespace + "\\" + name;
        if (classes.containsKey(namespaced) || slash >= 0) {
            return namespaced;
        }
        if (FACADES.containsKey(name)) {
            return FACADES.get(name);
        }
        if (BUILTIN_CLASSES.contains(name) || file.namespace.isEmpty()) {
            return name;
        }
        return namespaced;
    }

    String typeName(PhpFile file, TSNode type, PhpClass context) {
        if (isNull(type)) {
            return null;
        }
        return switch (type.getType()) {
            case "optional_type" -> {
                List<TSNode> inner = namedChildren(type);
                yield inner.isEmpty() ? null : typeName(file, inner.get(0), context);
            }
            case "named_type" -> resolveName(file, file.text(type), context);
            case "union_type", "intersection_type" -> {
                for (TSNode inner : namedChildren(type)) {
                    String name = typeName(file, inner, context);
                    if (name != null) {
                        yield name;
                    }
                }
                yield null;
            }
            default -> null;
        };
    }

    // ---- 함수 ----

    PhpFunc register(PhpFile file, TSNode node, String key, String name, PhpClass owner, PhpFunc parent) {
        String nodeKey = file.filePath + "@" + node.getStartByte();
        PhpFunc existing = byNode.get(nodeKey);
        if (existing != null) {
            return existing;
        }
        if (!keys.add(key)) {
            key = key + "@L" + PhpFile.line(node);
            keys.add(key);
        }
        PhpFunc fn = new PhpFunc(key, name, file, node, owner, parent);
        byNode.put(nodeKey, fn);
        functions.add(fn);
        return fn;
    }

    /** 익명 함수를 이름 붙여 색인한다 (라우트 처리기) */
    PhpFunc registerAnonymous(PhpFile file, TSNode node, String name, PhpFunc parent) {
        return register(file, node, file.filePath + "#" + name, name, parent == null ? null : parent.owner(), parent);
    }

    boolean isRegistered(PhpFile file, TSNode node) {
        return byNode.containsKey(file.filePath + "@" + node.getStartByte());
    }

    List<PhpFunc> functions() {
        return List.copyOf(functions);
    }

    List<PhpFile> files() {
        return files;
    }

    PhpFunc main(PhpFile file) {
        return mains.get(file.filePath);
    }

    PhpClass cls(String fqcn) {
        return fqcn == null ? null : classes.get(fqcn);
    }

    /** 이름만으로 클래스 찾기 ('UserController@show' 처럼 namespace 를 모를 때) */
    PhpClass classBySimpleName(String name) {
        return classesBySimpleName.get(name);
    }

    Optional<PhpFunc> functionAt(String filePath, int line) {
        PhpFunc best = null;
        for (PhpFunc fn : functions) {
            if (fn.file().filePath.equals(filePath) && PhpFile.line(fn.node()) <= line && line <= PhpFile.endLine(fn.node())
                    && (best == null || fn.node().getStartByte() > best.node().getStartByte())) {
                best = fn;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 노드를 감싼 색인된 함수 (색인하지 않은 익명 함수는 건너뛴다). 없으면 파일의 &lt;main&gt; */
    PhpFunc enclosing(PhpFile file, TSNode node) {
        for (TSNode current = node.getParent(); !isNull(current); current = current.getParent()) {
            if (FUNCTION_TYPES.contains(current.getType())) {
                PhpFunc fn = byNode.get(file.filePath + "@" + current.getStartByte());
                if (fn != null) {
                    return fn;
                }
            }
        }
        return mains.get(file.filePath);
    }

    /** 클래스와 부모에서 메서드 찾기 */
    PhpFunc findMethod(PhpClass cls, String name, int depth) {
        if (cls == null || depth > 8) {
            return null;
        }
        PhpFunc method = cls.methods.get(name.toLowerCase(Locale.ROOT));
        return method != null ? method : findMethod(classes.get(cls.parent), name, depth + 1);
    }

    String propertyType(PhpClass cls, String name, int depth) {
        if (cls == null || depth > 8) {
            return null;
        }
        String type = cls.propertyTypes.get(name);
        return type != null ? type : propertyType(classes.get(cls.parent), name, depth + 1);
    }

    /** 인터페이스(또는 부모 클래스)를 구현한 프로젝트 클래스의 같은 이름 메서드 */
    List<PhpFunc> implementations(String type, String method) {
        List<PhpFunc> result = new ArrayList<>();
        for (PhpClass cls : classes.values()) {
            if (cls.isInterface || cls.fqcn.equals(type) || !isSubtype(cls, type, 0)) {
                continue;
            }
            PhpFunc fn = findMethod(cls, method, 0);
            if (fn != null && !result.contains(fn)) {
                result.add(fn);
            }
        }
        return result;
    }

    private boolean isSubtype(PhpClass cls, String type, int depth) {
        if (cls == null || depth > 8) {
            return false;
        }
        if (cls.fqcn.equals(type) || cls.interfaces.contains(type)) {
            return true;
        }
        for (String iface : cls.interfaces) {
            if (isSubtype(classes.get(iface), type, depth + 1)) {
                return true;
            }
        }
        return isSubtype(classes.get(cls.parent), type, depth + 1);
    }

    // ---- 지역 이름 ----

    /** 함수 안의 지역 변수 ($ 를 뺀 이름): 파라미터, 첫 대입, foreach. 따로 색인한 안쪽 익명 함수 안은 제외 */
    Map<String, Local> locals(PhpFunc fn) {
        return localsCache.computeIfAbsent(fn.key(), k -> {
            Map<String, Local> locals = new LinkedHashMap<>();
            PhpFile file = fn.file();
            for (TSNode param : namedChildren(field(fn.node(), "parameters"))) {
                String type = typeName(file, field(param, "type"), fn.owner());
                String name = file.text(field(param, "name")).replaceFirst("^&?\\.*\\$", "");
                locals.put(name, new Local(type, null));
            }
            TSNode body = fn.name().equals("<main>") ? fn.node() : field(fn.node(), "body");
            collectLocals(fn, body, locals);
            return locals;
        });
    }

    private void collectLocals(PhpFunc fn, TSNode node, Map<String, Local> locals) {
        PhpFile file = fn.file();
        for (TSNode child : namedChildren(node)) {
            String type = child.getType();
            if (FUNCTION_TYPES.contains(type) && isRegistered(file, child) || type.equals("class_declaration")
                    || type.equals("function_definition") || type.equals("method_declaration")) {
                continue;
            }
            if (type.equals("assignment_expression")) {
                TSNode left = field(child, "left");
                if (left != null && left.getType().equals("variable_name")) {
                    locals.putIfAbsent(file.text(left).substring(1), new Local(null, field(child, "right")));
                }
            } else if (type.equals("foreach_statement")) {
                for (TSNode v : PhpFile.children(child, "variable_name")) {
                    locals.putIfAbsent(file.text(v).substring(1), new Local(null, null));
                }
                for (TSNode pair : PhpFile.children(child, "pair")) {
                    PhpFile.children(pair, "variable_name").forEach(v -> locals.putIfAbsent(file.text(v).substring(1), new Local(null, null)));
                }
            } else if (FUNCTION_TYPES.contains(type)) {
                for (TSNode param : namedChildren(field(child, "parameters"))) {
                    String name = file.text(field(param, "name")).replaceFirst("^&?\\.*\\$", "");
                    locals.putIfAbsent(name, new Local(typeName(file, field(param, "type"), fn.owner()), null));
                }
            }
            collectLocals(fn, child, locals);
        }
    }

    Local local(PhpFunc fn, String name) {
        for (PhpFunc current = fn; current != null; current = current.parent()) {
            Local local = locals(current).get(name);
            if (local != null) {
                return local;
            }
        }
        return null;
    }

    // ---- 타입 ----

    String typeOf(PhpFunc fn, TSNode expr) {
        return typeOf(fn, expr, 0);
    }

    private String typeOf(PhpFunc fn, TSNode expr, int depth) {
        expr = PhpFile.unwrap(expr);
        if (isNull(expr) || depth > 8) {
            return null;
        }
        PhpFile file = fn.file();
        switch (expr.getType()) {
            case "variable_name" -> {
                String name = file.text(expr).substring(1);
                if (name.equals("this")) {
                    return owner(fn) == null ? null : owner(fn).fqcn;
                }
                Local local = local(fn, name);
                if (local == null) {
                    return null;
                }
                return local.declaredType() != null ? local.declaredType() : typeOf(fn, local.value(), depth + 1);
            }
            case "member_access_expression", "nullsafe_member_access_expression" -> {
                PhpClass cls = classes.get(typeOf(fn, field(expr, "object"), depth + 1));
                return propertyType(cls, file.text(field(expr, "name")), 0);
            }
            case "object_creation_expression" -> {
                List<TSNode> parts = namedChildren(expr);
                return parts.isEmpty() ? null : resolveName(file, file.text(parts.get(0)), owner(fn));
            }
            case "member_call_expression", "nullsafe_member_call_expression", "scoped_call_expression", "function_call_expression" -> {
                for (PhpFunc target : callee(fn, expr).functions()) {
                    String type = returnType(target);
                    if (type != null) {
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

    private PhpClass owner(PhpFunc fn) {
        for (PhpFunc current = fn; current != null; current = current.parent()) {
            if (current.owner() != null) {
                return current.owner();
            }
        }
        return null;
    }

    String returnType(PhpFunc fn) {
        return typeName(fn.file(), field(fn.node(), "return_type"), fn.owner());
    }

    // ---- 호출 대상 ----

    Callee callee(PhpFunc fn, TSNode call) {
        PhpFile file = fn.file();
        switch (call.getType()) {
            case "function_call_expression" -> {
                TSNode function = field(call, "function");
                if (function == null || !function.getType().equals("name") && !function.getType().equals("qualified_name")) {
                    return Callee.none();
                }
                String name = file.text(function).replaceFirst("^\\\\", "");
                PhpFunc target = functionsByName.get((file.namespace.isEmpty() ? name : file.namespace + "\\" + name).toLowerCase(Locale.ROOT));
                if (target == null) {
                    target = functionsByName.get(name.toLowerCase(Locale.ROOT));
                }
                return target != null ? new Callee(List.of(target), null, null)
                        : new Callee(List.of(), name.substring(name.lastIndexOf('\\') + 1), null);
            }
            case "member_call_expression", "nullsafe_member_call_expression" -> {
                String method = file.text(field(call, "name"));
                String type = typeOf(fn, field(call, "object"));
                PhpClass cls = classes.get(type);
                if (cls != null) {
                    PhpFunc target = cls.isInterface ? null : findMethod(cls, method, 0);
                    if (target != null) {
                        return new Callee(List.of(target), null, null);
                    }
                    List<PhpFunc> impls = implementations(cls.fqcn, method);
                    return impls.isEmpty() ? Callee.none() : new Callee(impls, null, null);
                }
                if (type == null) {
                    // Http::timeout(10)->get($url): 파사드에서 시작한 체인은 그 파사드의 정적 호출로 본다
                    String facade = facadeRoot(fn, field(call, "object"));
                    if (facade != null) {
                        return new Callee(List.of(), facade + "::" + method, method);
                    }
                }
                return type != null ? new Callee(List.of(), type + "->" + method, method) : new Callee(List.of(), null, method);
            }
            case "scoped_call_expression" -> {
                String method = file.text(field(call, "name"));
                String type = resolveName(file, file.text(field(call, "scope")), owner(fn));
                PhpClass cls = classes.get(type);
                if (cls != null) {
                    PhpFunc target = findMethod(cls, method, 0);
                    return target != null ? new Callee(List.of(target), null, null) : Callee.none();
                }
                return type == null ? Callee.none() : new Callee(List.of(), type + "::" + method, null);
            }
            case "object_creation_expression" -> {
                List<TSNode> parts = namedChildren(call);
                if (parts.isEmpty() || parts.get(0).getType().equals("anonymous_class")) {
                    return Callee.none();
                }
                String type = resolveName(file, file.text(parts.get(0)), owner(fn));
                PhpClass cls = classes.get(type);
                if (cls != null) {
                    PhpFunc constructor = findMethod(cls, "__construct", 0);
                    return constructor != null ? new Callee(List.of(constructor), null, null) : Callee.none();
                }
                return type == null ? Callee.none() : new Callee(List.of(), "new " + type, null);
            }
            default -> {
                return Callee.none();
            }
        }
    }

    /** 호출 체인의 맨 앞이 Laravel 파사드 정적 호출이면 그 파사드 이름 */
    private String facadeRoot(PhpFunc fn, TSNode object) {
        TSNode current = PhpFile.unwrap(object);
        for (int i = 0; i < 10 && current != null && current.getType().equals("member_call_expression"); i++) {
            current = PhpFile.unwrap(field(current, "object"));
        }
        if (current == null || !current.getType().equals("scoped_call_expression")) {
            return null;
        }
        String scope = resolveName(fn.file(), fn.file().text(field(current, "scope")), owner(fn));
        return scope != null && scope.startsWith("Illuminate\\Support\\Facades\\") ? scope : null;
    }

    /** 상수로 보는 식: 리터럴, 상수 이름, 클래스 상수, 상수끼리 . */
    boolean constant(PhpFunc fn, TSNode expr, int depth) {
        expr = PhpFile.unwrap(expr);
        if (expr == null || depth > 6) {
            return false;
        }
        switch (expr.getType()) {
            case "string", "integer", "float", "boolean", "null", "name", "qualified_name", "class_constant_access_expression",
                 "nowdoc", "heredoc" -> {
                return !expr.getType().equals("heredoc") || PhpFile.interpolationFree(expr);
            }
            case "encapsed_string" -> {
                return PhpFile.interpolationFree(expr);
            }
            case "binary_expression" -> {
                return constant(fn, field(expr, "left"), depth + 1) && constant(fn, field(expr, "right"), depth + 1);
            }
            case "array_creation_expression" -> {
                for (TSNode element : namedChildren(expr)) {
                    for (TSNode value : namedChildren(element)) {
                        if (!constant(fn, value, depth + 1)) {
                            return false;
                        }
                    }
                }
                return true;
            }
            case "function_call_expression" -> {
                // __DIR__ . '/x' 같은 경로를 만드는 함수에 상수만 넘김
                String name = fn.file().text(field(expr, "function"));
                return Set.of("dirname", "base_path", "storage_path", "public_path", "app_path", "resource_path", "config_path")
                        .contains(name) && PhpFile.arguments(expr).stream().allMatch(a -> constant(fn, a, depth + 1));
            }
            default -> {
                return false;
            }
        }
    }
}
