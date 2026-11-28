package com.codeagent.core.node;

import com.codeagent.core.SourceFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterTypescript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.codeagent.core.node.NodeFile.children;
import static com.codeagent.core.node.NodeFile.field;
import static com.codeagent.core.node.NodeFile.firstChild;
import static com.codeagent.core.node.NodeFile.isNull;
import static com.codeagent.core.node.NodeFile.namedChildren;

/**
 * 저장소의 Node.js 백엔드 소스(JavaScript·TypeScript)를 tree-sitter 로 파싱해 모듈·함수·클래스 색인을 만든다.
 * require·import 로 이름을 풀고(프로젝트 모듈이면 그 export, 아니면 "패키지.이름"), 식이 무엇을 가리키는지
 * ({@link Target}) 따라간다. 타입 추론은 하지 않는다: TypeScript 타입 표기, new, 생성자 파라미터 프로퍼티만 본다.
 */
public final class NodeProject {

    private static final Logger log = LoggerFactory.getLogger(NodeProject.class);

    /** 의존성·빌드 결과물·프론트엔드 폴더는 서버 코드가 아니다 */
    public static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "bower_components", "dist", "build", "out",
            ".next", ".nuxt", "coverage", "public", "static", "client", "frontend", "web", "www", "__tests__", "__mocks__",
            "test", "tests", "e2e", "cypress", "storybook", ".storybook");
    private static final Set<String> EXTENSIONS = Set.of("js", "mjs", "cjs", "ts", "mts", "cts");
    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_DEPTH = 12;

    // ---- 모델 ----

    /**
     * @param key        경로#이름 (클래스 메서드는 경로#클래스.메서드, 이름 없는 함수는 경로#&lt;anonymous&gt;@L줄)
     * @param node       함수 노드 (function_declaration, arrow_function, method_definition …). 모듈 최상위 코드는 program
     * @param decorators 메서드에 붙은 데코레이터 (NestJS @Get 등)
     */
    public record NodeFunction(String key, String name, NodeFile file, TSNode node, NodeClass owner,
                               Map<String, String> paramTypes, List<TSNode> decorators) {

        @Override
        public boolean equals(Object o) {
            return o instanceof NodeFunction other && key.equals(other.key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }

        @Override
        public String toString() {
            return key;
        }
    }

    public static final class NodeClass {
        final String key;
        final String name;
        final NodeFile file;
        final TSNode node;
        final NodeModule module;
        String superName;
        final Map<String, NodeFunction> methods = new LinkedHashMap<>();
        /** 필드 이름 → 타입 이름 (생성자 파라미터 프로퍼티, 타입이 적힌 필드) */
        final Map<String, String> fieldTypes = new LinkedHashMap<>();
        /** 필드 이름 → 생성자에서 대입한 식 (this.repo = new Repo()) */
        final Map<String, TSNode> fieldValues = new LinkedHashMap<>();
        final List<TSNode> decorators = new ArrayList<>();

        NodeClass(String key, String name, NodeFile file, TSNode node, NodeModule module) {
            this.key = key;
            this.name = name;
            this.file = file;
            this.node = node;
            this.module = module;
        }

        public String key() {
            return key;
        }

        public String name() {
            return name;
        }

        public NodeFile file() {
            return file;
        }

        public Collection<NodeFunction> methods() {
            return methods.values();
        }

        public List<TSNode> decorators() {
            return decorators;
        }
    }

    /** @param imported "*"(모듈 전체), "default", 또는 export 이름 */
    record Binding(String spec, String imported) {
    }

    static final class NodeModule {
        final NodeFile file;
        final Map<String, Binding> imports = new LinkedHashMap<>();
        final Map<String, NodeFunction> functions = new LinkedHashMap<>();
        final Map<String, NodeClass> classes = new LinkedHashMap<>();
        /** 최상위 변수 → 값 식 */
        final Map<String, TSNode> values = new LinkedHashMap<>();
        /** export 이름 → 모듈 안 이름 (export { a as b }, export function f) */
        final Map<String, String> exportNames = new LinkedHashMap<>();
        /** export 이름 → 식 (exports.x = …, export default …). "*" 는 module.exports = … */
        final Map<String, TSNode> exportValues = new LinkedHashMap<>();
        NodeFunction body;

        NodeModule(NodeFile file) {
            this.file = file;
        }
    }

    /** 식이 가리키는 것 */
    sealed interface Target permits FnTarget, ClassTarget, ModuleTarget, External, Instance, ObjectLiteral {
    }

    record FnTarget(NodeFunction fn) implements Target {
    }

    record ClassTarget(NodeClass cls) implements Target {
    }

    record ModuleTarget(NodeModule module) implements Target {
    }

    /** 프로젝트 밖 이름 (child_process.exec, express(), new koa-router) */
    record External(String name) implements Target {
    }

    record Instance(NodeClass cls) implements Target {
    }

    /** 객체 리터럴 (module.exports = { list() {} }) */
    record ObjectLiteral(NodeModule module, TSNode node) implements Target {
    }

    private final Path root;
    private final Map<String, NodeModule> modules = new LinkedHashMap<>();
    /** 파일 경로 → (함수 노드 시작 바이트 → 함수) */
    private final Map<String, Map<Integer, NodeFunction>> functionsByNode = new HashMap<>();
    private final Map<String, NodeClass> classesByKey = new LinkedHashMap<>();

    private NodeProject(Path root) {
        this.root = root;
    }

    public static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.endsWith(".d.ts") || name.matches(".*\\.(test|spec|e2e|stories)\\.[cm]?[jt]s$")
                || name.matches(".*\\.(min|bundle)\\.js$")) {
            return false;
        }
        return EXTENSIONS.contains(name.substring(dot + 1));
    }

    public static NodeProject load(Path root, List<String> parseErrors) {
        NodeProject project = new NodeProject(root.toAbsolutePath().normalize());
        TSParser jsParser;
        TSParser tsParser;
        try {
            jsParser = parser(new TreeSitterJavascript());
            tsParser = parser(new TreeSitterTypescript());
        } catch (LinkageError | RuntimeException e) {
            parseErrors.add("(node): JavaScript·TypeScript 파서를 불러오지 못해 Node.js 파일을 건너뜀 (지원하지 않는 OS/CPU 일 수 있음: "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + ") " + e);
            return project;
        }
        for (Path file : SourceFiles.find(project.root, SKIP_DIRS, MAX_FILE_BYTES, NodeProject::isSource)) {
            String relative = SourceFiles.relative(project.root, file);
            boolean ts = relative.matches(".*\\.[cm]?ts$");
            try {
                byte[] bytes = Files.readAllBytes(file);
                String text = new String(bytes, StandardCharsets.UTF_8);
                TSTree tree = (ts ? tsParser : jsParser).parseString(null, text);
                NodeFile nf = new NodeFile(relative, text.getBytes(StandardCharsets.UTF_8), tree, ts);
                if (nf.root.hasError()) {
                    parseErrors.add(relative + ": 문법 오류가 있는 부분만 건너뛰고 분석함");
                }
                project.modules.put(relative, new NodeModule(nf));
            } catch (IOException | RuntimeException e) {
                parseErrors.add(relative + ": 파싱 실패 (" + e.getMessage() + ")");
                log.debug("Node.js 파싱 실패: {}", relative, e);
            }
        }
        project.modules.values().forEach(project::index);
        return project;
    }

    private static TSParser parser(TSLanguage language) {
        TSParser parser = new TSParser();
        parser.setLanguage(language);
        return parser;
    }

    public Path root() {
        return root;
    }

    public Collection<NodeFile> files() {
        return modules.values().stream().map(m -> m.file).toList();
    }

    Collection<NodeModule> modules() {
        return modules.values();
    }

    NodeModule module(NodeFile file) {
        return modules.get(file.filePath);
    }

    public Collection<NodeClass> classes() {
        return classesByKey.values();
    }

    /** 색인한 함수 전부 (모듈 최상위 코드 포함) */
    public List<NodeFunction> functions() {
        List<NodeFunction> result = new ArrayList<>();
        functionsByNode.values().forEach(m -> result.addAll(m.values()));
        result.sort((a, b) -> a.key().compareTo(b.key()));
        return result;
    }

    /** 파일·줄을 감싼 가장 좁은 함수 (모듈 최상위 코드 제외) */
    public Optional<NodeFunction> functionAt(String filePath, int line) {
        return functionsByNode.getOrDefault(filePath, Map.of()).values().stream()
                .filter(f -> !f.node().getType().equals("program"))
                .filter(f -> NodeFile.line(f.node()) <= line && NodeFile.endLine(f.node()) >= line)
                .min((a, b) -> Integer.compare(NodeFile.endLine(a.node()) - NodeFile.line(a.node()),
                        NodeFile.endLine(b.node()) - NodeFile.line(b.node())));
    }

    // ---- 색인 ----

    private void index(NodeModule module) {
        NodeFile file = module.file;
        module.body = register(file, file.root, "<module>", null, List.of());
        for (TSNode statement : namedChildren(file.root)) {
            indexStatement(module, statement, List.of());
        }
    }

    private void indexStatement(NodeModule module, TSNode statement, List<TSNode> decorators) {
        NodeFile file = module.file;
        switch (statement.getType()) {
            case "import_statement" -> indexImport(module, statement);
            case "export_statement" -> {
                List<TSNode> exportDecorators = children(statement, "decorator");
                TSNode declaration = field(statement, "declaration");
                if (declaration != null) {
                    indexStatement(module, declaration, exportDecorators);
                    for (String name : declaredNames(file, declaration)) {
                        module.exportNames.put(name, name);
                    }
                    if (file.text(statement).startsWith("export default")) {
                        declaredNames(file, declaration).stream().findFirst().ifPresent(n -> module.exportNames.put("default", n));
                    }
                }
                TSNode value = field(statement, "value");
                if (value != null) {
                    // export default 식
                    module.exportValues.put("default", value);
                    if (isFunction(value)) {
                        register(file, value, "default", null, List.of());
                    } else if (value.getType().equals("class")) {
                        indexClass(module, value, "default", exportDecorators);
                    }
                }
                TSNode clause = firstChild(statement, "export_clause");
                TSNode source = field(statement, "source");
                for (TSNode specifier : children(clause, "export_specifier")) {
                    String local = file.text(field(specifier, "name"));
                    TSNode alias = field(specifier, "alias");
                    String exported = alias == null ? local : file.text(alias);
                    if (source != null) {
                        // export { a } from './x'
                        module.imports.put("__reexport_" + exported, new Binding(file.stringValue(source), local));
                        module.exportNames.put(exported, "__reexport_" + exported);
                    } else {
                        module.exportNames.put(exported, local);
                    }
                }
            }
            case "function_declaration", "generator_function_declaration" -> {
                String name = file.text(field(statement, "name"));
                module.functions.put(name, register(file, statement, name, null, List.of()));
            }
            case "class_declaration", "abstract_class_declaration" -> {
                String name = file.text(field(statement, "name"));
                indexClass(module, statement, name, decorators);
            }
            case "lexical_declaration", "variable_declaration" -> {
                for (TSNode declarator : children(statement, "variable_declarator")) {
                    indexDeclarator(module, declarator);
                }
            }
            case "expression_statement" -> indexExpression(module, firstNamedChild(statement));
            default -> {
            }
        }
    }

    private void indexDeclarator(NodeModule module, TSNode declarator) {
        NodeFile file = module.file;
        TSNode name = field(declarator, "name");
        TSNode value = field(declarator, "value");
        if (name == null || value == null) {
            return;
        }
        Binding required = requireBinding(file, value);
        if (name.getType().equals("identifier")) {
            String local = file.text(name);
            if (required != null) {
                module.imports.put(local, required);
            } else if (isFunction(value)) {
                module.functions.put(local, register(file, value, local, null, List.of()));
            } else if (value.getType().equals("class")) {
                indexClass(module, value, local, List.of());
            } else {
                module.values.put(local, value);
            }
            return;
        }
        if (name.getType().equals("object_pattern") && required != null) {
            // const { exec, spawn: run } = require('child_process')
            for (TSNode property : namedChildren(name)) {
                switch (property.getType()) {
                    case "shorthand_property_identifier_pattern" ->
                            module.imports.put(file.text(property), new Binding(required.spec(), file.text(property)));
                    case "pair_pattern" -> {
                        TSNode key = field(property, "key");
                        TSNode alias = field(property, "value");
                        if (key != null && alias != null && alias.getType().equals("identifier")) {
                            module.imports.put(file.text(alias), new Binding(required.spec(), file.text(key)));
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    /** require('x') / require('x').y 이면 그 바인딩 */
    private static Binding requireBinding(NodeFile file, TSNode value) {
        TSNode node = value;
        String member = null;
        if (node.getType().equals("member_expression")) {
            member = file.text(field(node, "property"));
            node = field(node, "object");
        }
        if (node == null || !node.getType().equals("call_expression")) {
            return null;
        }
        TSNode function = field(node, "function");
        if (function == null || !file.text(function).equals("require")) {
            return null;
        }
        TSNode args = field(node, "arguments");
        List<TSNode> list = namedChildren(args);
        String spec = list.isEmpty() ? null : file.stringValue(list.get(0));
        return spec == null ? null : new Binding(spec, member == null ? "*" : member);
    }

    private void indexImport(NodeModule module, TSNode statement) {
        NodeFile file = module.file;
        String spec = file.stringValue(field(statement, "source"));
        TSNode clause = firstChild(statement, "import_clause");
        if (spec == null || clause == null) {
            return;
        }
        for (TSNode part : namedChildren(clause)) {
            switch (part.getType()) {
                case "identifier" -> module.imports.put(file.text(part), new Binding(spec, "default"));
                case "namespace_import" -> {
                    TSNode id = firstChild(part, "identifier");
                    if (id != null) {
                        module.imports.put(file.text(id), new Binding(spec, "*"));
                    }
                }
                case "named_imports" -> {
                    for (TSNode specifier : children(part, "import_specifier")) {
                        String imported = file.text(field(specifier, "name"));
                        TSNode alias = field(specifier, "alias");
                        module.imports.put(alias == null ? imported : file.text(alias), new Binding(spec, imported));
                    }
                }
                default -> {
                }
            }
        }
    }

    /** module.exports = …, module.exports.x = …, exports.x = … */
    private void indexExpression(NodeModule module, TSNode expr) {
        if (isNull(expr) || !expr.getType().equals("assignment_expression")) {
            return;
        }
        NodeFile file = module.file;
        String left = file.text(field(expr, "left")).replaceAll("\\s+", "");
        TSNode right = field(expr, "right");
        if (right == null) {
            return;
        }
        String exportName = null;
        if (left.equals("module.exports")) {
            exportName = "*";
        } else if (left.startsWith("module.exports.") || left.startsWith("exports.")) {
            exportName = left.substring(left.lastIndexOf('.') + 1);
        }
        if (exportName == null) {
            return;
        }
        module.exportValues.put(exportName, right);
        if (isFunction(right)) {
            register(file, right, exportName.equals("*") ? "default" : exportName, null, List.of());
        } else if (right.getType().equals("class")) {
            indexClass(module, right, exportName.equals("*") ? "default" : exportName, List.of());
        } else if (right.getType().equals("object")) {
            indexObjectLiteral(module, right, exportName.equals("*") ? "" : exportName + ".");
        }
    }

    /** { list() {}, remove: (id) => … } 의 함수들 */
    private void indexObjectLiteral(NodeModule module, TSNode object, String prefix) {
        NodeFile file = module.file;
        for (TSNode member : namedChildren(object)) {
            if (member.getType().equals("method_definition")) {
                register(file, member, prefix + file.text(field(member, "name")), null, List.of());
            } else if (member.getType().equals("pair")) {
                TSNode value = field(member, "value");
                if (value != null && isFunction(value)) {
                    register(file, value, prefix + propertyName(file, field(member, "key")), null, List.of());
                }
            }
        }
    }

    private void indexClass(NodeModule module, TSNode decl, String name, List<TSNode> outerDecorators) {
        NodeFile file = module.file;
        NodeClass cls = new NodeClass(file.filePath + "#" + name, name, file, decl, module);
        cls.decorators.addAll(outerDecorators);
        cls.decorators.addAll(children(decl, "decorator"));
        TSNode heritage = firstChild(decl, "class_heritage");
        if (heritage != null) {
            TSNode extendsClause = firstChild(heritage, "extends_clause");
            TSNode value = extendsClause == null ? firstNamedChild(heritage) : field(extendsClause, "value");
            if (value != null) {
                cls.superName = file.text(value).replaceAll("<.*", "").strip();
            }
        }
        module.classes.put(name, cls);
        classesByKey.put(cls.key, cls);
        List<TSNode> pending = new ArrayList<>();
        for (TSNode member : namedChildren(field(decl, "body"))) {
            switch (member.getType()) {
                case "decorator" -> pending.add(member);
                case "method_definition" -> {
                    String methodName = propertyName(file, field(member, "name"));
                    List<TSNode> decorators = new ArrayList<>(pending);
                    decorators.addAll(children(member, "decorator"));
                    pending.clear();
                    NodeFunction method = register(file, member, name + "." + methodName, cls, decorators);
                    cls.methods.put(methodName, method);
                    if (methodName.equals("constructor")) {
                        indexConstructor(cls, member);
                    }
                }
                case "public_field_definition", "field_definition" -> {
                    pending.clear();
                    TSNode fieldName = firstChild(member, "property_identifier");
                    if (fieldName == null) {
                        fieldName = field(member, "name");
                    }
                    if (fieldName == null) {
                        continue;
                    }
                    String type = typeName(file, firstChild(member, "type_annotation"));
                    if (type != null) {
                        cls.fieldTypes.put(file.text(fieldName), type);
                    }
                    TSNode value = field(member, "value");
                    if (value != null) {
                        cls.fieldValues.put(file.text(fieldName), value);
                        if (isFunction(value)) {
                            cls.methods.put(file.text(fieldName), register(file, value, name + "." + file.text(fieldName), cls, List.of()));
                        }
                    }
                }
                default -> pending.clear();
            }
        }
    }

    /** constructor(private readonly users: UsersService) 와 this.x = … */
    private void indexConstructor(NodeClass cls, TSNode constructor) {
        NodeFile file = cls.file;
        for (TSNode param : namedChildren(field(constructor, "parameters"))) {
            if (firstChild(param, "accessibility_modifier") == null && firstChild(param, "override_modifier") == null
                    && !file.text(param).startsWith("readonly")) {
                continue;
            }
            TSNode pattern = field(param, "pattern");
            String type = typeName(file, firstChild(param, "type_annotation"));
            if (pattern != null && type != null) {
                cls.fieldTypes.put(file.text(pattern), type);
            }
        }
        for (TSNode assignment : NodeFile.descendants(field(constructor, "body"), Set.of("assignment_expression"), Set.of())) {
            String left = file.text(field(assignment, "left"));
            if (left.startsWith("this.") && left.indexOf('.', 5) < 0) {
                cls.fieldValues.put(left.substring(5), field(assignment, "right"));
            }
        }
    }

    private NodeFunction register(NodeFile file, TSNode node, String name, NodeClass owner, List<TSNode> decorators) {
        Map<Integer, NodeFunction> byNode = functionsByNode.computeIfAbsent(file.filePath, k -> new LinkedHashMap<>());
        NodeFunction existing = byNode.get(node.getStartByte());
        if (existing != null) {
            return existing;
        }
        NodeFunction fn = new NodeFunction(file.filePath + "#" + name, name, file, node, owner, paramTypes(file, node), List.copyOf(decorators));
        byNode.put(node.getStartByte(), fn);
        return fn;
    }

    /** 함수 노드의 NodeFunction. 색인하지 않은 이름 없는 함수(콜백, 라우트 처리기)는 이 이름으로 새로 만든다 */
    NodeFunction functionFor(NodeFile file, TSNode node, String nameIfNew) {
        NodeFunction existing = functionsByNode.getOrDefault(file.filePath, Map.of()).get(node.getStartByte());
        return existing != null ? existing : register(file, node, nameIfNew, null, List.of());
    }

    boolean isRegistered(NodeFile file, TSNode node) {
        return functionsByNode.getOrDefault(file.filePath, Map.of()).containsKey(node.getStartByte());
    }

    private static Map<String, String> paramTypes(NodeFile file, TSNode fn) {
        Map<String, String> result = new LinkedHashMap<>();
        TSNode params = field(fn, "parameters");
        if (params == null) {
            TSNode single = field(fn, "parameter");
            if (single != null) {
                result.put(file.text(single), null);
            }
            return result;
        }
        for (TSNode param : namedChildren(params)) {
            switch (param.getType()) {
                case "identifier" -> result.put(file.text(param), null);
                case "required_parameter", "optional_parameter" -> {
                    TSNode pattern = field(param, "pattern");
                    if (pattern != null && pattern.getType().equals("identifier")) {
                        result.put(file.text(pattern), typeName(file, firstChild(param, "type_annotation")));
                    }
                }
                case "assignment_pattern" -> {
                    TSNode left = field(param, "left");
                    if (left != null && left.getType().equals("identifier")) {
                        result.put(file.text(left), null);
                    }
                }
                default -> {
                }
            }
        }
        return result;
    }

    /** : UsersService / : Repository&lt;User&gt; 의 이름 */
    private static String typeName(NodeFile file, TSNode annotation) {
        if (annotation == null) {
            return null;
        }
        for (TSNode child : namedChildren(annotation)) {
            switch (child.getType()) {
                case "type_identifier" -> {
                    return file.text(child);
                }
                case "generic_type" -> {
                    TSNode name = field(child, "name");
                    return name == null ? null : file.text(name);
                }
                case "nested_type_identifier" -> {
                    return file.text(child);
                }
                default -> {
                }
            }
        }
        return null;
    }

    private static List<String> declaredNames(NodeFile file, TSNode declaration) {
        List<String> names = new ArrayList<>();
        switch (declaration.getType()) {
            case "function_declaration", "generator_function_declaration", "class_declaration", "abstract_class_declaration" -> {
                TSNode name = field(declaration, "name");
                if (name != null) {
                    names.add(file.text(name));
                }
            }
            case "lexical_declaration", "variable_declaration" -> {
                for (TSNode declarator : children(declaration, "variable_declarator")) {
                    TSNode name = field(declarator, "name");
                    if (name != null && name.getType().equals("identifier")) {
                        names.add(file.text(name));
                    }
                }
            }
            default -> {
            }
        }
        return names;
    }

    static boolean isFunction(TSNode node) {
        return !isNull(node) && Set.of("arrow_function", "function_expression", "function", "generator_function").contains(node.getType());
    }

    static String propertyName(NodeFile file, TSNode key) {
        if (key == null) {
            return "?";
        }
        String text = file.stringValue(key);
        return text != null ? text : file.text(key);
    }

    static TSNode firstNamedChild(TSNode node) {
        List<TSNode> list = namedChildren(node);
        return list.isEmpty() ? null : list.get(0);
    }

    // ---- 이름 풀기 ----

    /** 상대 경로 모듈 (./db, ../services/users) → 프로젝트 모듈. 패키지면 null */
    NodeModule resolveModule(NodeFile from, String spec) {
        if (spec == null || !(spec.startsWith(".") || spec.startsWith("/") || spec.startsWith("@/") || spec.startsWith("~/") || spec.startsWith("src/"))) {
            return null;
        }
        String base;
        if (spec.startsWith("@/") || spec.startsWith("~/")) {
            base = "src/" + spec.substring(2);
        } else if (spec.startsWith("src/")) {
            base = spec;
        } else {
            int slash = from.filePath.lastIndexOf('/');
            String dir = slash < 0 ? "" : from.filePath.substring(0, slash);
            base = Path.of(dir.isEmpty() ? "." : dir).resolve(spec).normalize().toString().replace('\\', '/');
        }
        base = base.replaceAll("\\.(js|mjs|cjs|ts|mts|cts)$", "");
        for (String suffix : List.of(".ts", ".js", ".mjs", ".cjs", ".mts", ".cts", "/index.ts", "/index.js", "/index.mjs")) {
            NodeModule module = modules.get(base + suffix);
            if (module != null) {
                return module;
            }
        }
        // tsconfig paths 처럼 루트가 다를 때: 끝이 같은 파일
        String tail = base.replaceFirst("^(\\.\\./)+", "");
        for (NodeModule module : modules.values()) {
            String path = module.file.filePath.replaceAll("\\.(js|mjs|cjs|ts|mts|cts)$", "");
            if (path.endsWith("/" + tail) || path.endsWith("/" + tail + "/index")) {
                return module;
            }
        }
        return null;
    }

    /** 모듈 안 이름이 가리키는 것 (함수, 클래스, import, 최상위 변수) */
    Target resolveName(NodeModule module, String name, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        NodeFunction fn = module.functions.get(name);
        if (fn != null) {
            return new FnTarget(fn);
        }
        NodeClass cls = module.classes.get(name);
        if (cls != null) {
            return new ClassTarget(cls);
        }
        Binding binding = module.imports.get(name);
        if (binding != null) {
            return resolveBinding(module.file, binding, depth + 1);
        }
        TSNode value = module.values.get(name);
        if (value != null) {
            return evaluate(module, module.body, value, Map.of(), depth + 1);
        }
        return null;
    }

    Target resolveBinding(NodeFile from, Binding binding, int depth) {
        NodeModule target = resolveModule(from, binding.spec());
        if (target == null) {
            String spec = binding.spec().replaceFirst("^node:", "").replace("fs/promises", "fs.promises");
            return new External(binding.imported().equals("*") || binding.imported().equals("default")
                    ? spec : spec + "." + binding.imported());
        }
        if (binding.imported().equals("*")) {
            return new ModuleTarget(target);
        }
        return exportOf(target, binding.imported(), depth + 1);
    }

    /** 모듈의 export (이름, "default", "*") */
    Target exportOf(NodeModule module, String name, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        String local = module.exportNames.get(name);
        if (local != null) {
            return resolveName(module, local, depth + 1);
        }
        TSNode value = module.exportValues.get(name);
        if (value != null) {
            return evaluate(module, module.body, value, Map.of(), depth + 1);
        }
        // CommonJS: module.exports = 객체·인스턴스 의 멤버, 또는 import x from (module.exports 전체)
        TSNode whole = module.exportValues.get("*");
        if (whole != null) {
            Target all = evaluate(module, module.body, whole, Map.of(), depth + 1);
            return name.equals("default") ? all : member(all, name, depth + 1);
        }
        return name.equals("default") ? new ModuleTarget(module) : null;
    }

    /** a.b 의 b */
    Target member(Target target, String name, int depth) {
        if (target == null || depth > MAX_DEPTH) {
            return null;
        }
        if (target instanceof ModuleTarget m) {
            return exportOf(m.module(), name, depth + 1);
        }
        if (target instanceof External e) {
            return new External(e.name() + "." + name);
        }
        if (target instanceof Instance i) {
            return instanceMember(i.cls(), name, depth + 1);
        }
        if (target instanceof ClassTarget c) {
            NodeFunction method = findMethod(c.cls(), name);
            return method == null ? null : new FnTarget(method);
        }
        if (target instanceof ObjectLiteral o) {
            return objectMember(o, name, depth + 1);
        }
        return null;
    }

    private Target instanceMember(NodeClass cls, String name, int depth) {
        NodeFunction method = findMethod(cls, name);
        if (method != null) {
            return new FnTarget(method);
        }
        for (NodeClass c = cls; c != null; c = superClass(c)) {
            String type = c.fieldTypes.get(name);
            if (type != null) {
                Target t = resolveName(c.module, type.contains(".") ? type.substring(0, type.indexOf('.')) : type, depth + 1);
                if (t instanceof ClassTarget ct) {
                    return new Instance(ct.cls());
                }
                if (t instanceof External e) {
                    return new External("new " + e.name());
                }
                return null;
            }
            TSNode value = c.fieldValues.get(name);
            if (value != null) {
                return evaluate(c.module, c.module.body, value, Map.of(), depth + 1);
            }
        }
        return null;
    }

    private Target objectMember(ObjectLiteral object, String name, int depth) {
        NodeFile file = object.module().file;
        for (TSNode member : namedChildren(object.node())) {
            if (member.getType().equals("method_definition") && propertyName(file, field(member, "name")).equals(name)) {
                return new FnTarget(functionFor(file, member, name));
            }
            if (member.getType().equals("pair") && propertyName(file, field(member, "key")).equals(name)) {
                TSNode value = field(member, "value");
                return evaluate(object.module(), object.module().body, value, Map.of(), depth + 1);
            }
            if (member.getType().equals("shorthand_property_identifier") && file.text(member).equals(name)) {
                return resolveName(object.module(), name, depth + 1);
            }
        }
        return null;
    }

    NodeFunction findMethod(NodeClass cls, String name) {
        Set<String> seen = new HashSet<>();
        for (NodeClass c = cls; c != null && seen.add(c.key); c = superClass(c)) {
            NodeFunction method = c.methods.get(name);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    NodeClass superClass(NodeClass cls) {
        if (cls.superName == null) {
            return null;
        }
        Target t = resolveName(cls.module, cls.superName, 1);
        return t instanceof ClassTarget ct && ct.cls() != cls ? ct.cls() : null;
    }

    /** 이름으로 클래스 찾기 (같은 모듈 → import) */
    NodeClass classNamed(NodeModule module, String name) {
        Target t = resolveName(module, name, 1);
        return t instanceof ClassTarget ct ? ct.cls() : null;
    }

    /**
     * 식이 가리키는 것. 모르면 null.
     *
     * @param locals 함수 안 지역 이름 → 값 식 (파라미터는 null 값, 타입은 context.paramTypes)
     */
    Target evaluate(NodeModule module, NodeFunction context, TSNode expr, Map<String, TSNode> locals, int depth) {
        if (isNull(expr) || depth > MAX_DEPTH) {
            return null;
        }
        NodeFile file = module.file;
        switch (expr.getType()) {
            case "identifier", "shorthand_property_identifier" -> {
                String name = file.text(expr);
                if (context != null && context.paramTypes().containsKey(name)) {
                    String type = context.paramTypes().get(name);
                    NodeClass cls = type == null ? null : classNamed(module, type);
                    return cls == null ? null : new Instance(cls);
                }
                if (locals.containsKey(name)) {
                    TSNode value = locals.get(name);
                    return value == null ? null : evaluate(module, context, value, Map.of(), depth + 1);
                }
                Target t = resolveName(module, name, depth + 1);
                return t;
            }
            case "this" -> {
                return context != null && context.owner() != null ? new Instance(context.owner()) : null;
            }
            case "member_expression" -> {
                TSNode property = field(expr, "property");
                Target object = evaluate(module, context, field(expr, "object"), locals, depth + 1);
                return property == null ? null : member(object, file.text(property), depth + 1);
            }
            case "call_expression" -> {
                Binding required = requireBinding(file, expr);
                if (required != null) {
                    return resolveBinding(file, required, depth + 1);
                }
                Target callee = evaluate(module, context, field(expr, "function"), locals, depth + 1);
                if (callee instanceof External e) {
                    return new External(e.name() + "()");
                }
                if (callee instanceof FnTarget f && f.fn().owner() == null) {
                    // 팩토리 함수: return new X() / return 객체 리터럴
                    for (TSNode ret : NodeFile.descendants(f.fn().node(), Set.of("return_statement"), FUNCTION_TYPES)) {
                        TSNode value = firstNamedChild(ret);
                        if (value != null && (value.getType().equals("new_expression") || value.getType().equals("object"))) {
                            return evaluate(module(f.fn().file()), f.fn(), value, Map.of(), depth + 1);
                        }
                    }
                }
                return null;
            }
            case "new_expression" -> {
                Target constructor = evaluate(module, context, field(expr, "constructor"), locals, depth + 1);
                if (constructor instanceof ClassTarget c) {
                    return new Instance(c.cls());
                }
                if (constructor instanceof External e) {
                    return new External("new " + e.name());
                }
                return null;
            }
            case "await_expression", "parenthesized_expression", "non_null_expression", "as_expression", "satisfies_expression" -> {
                return evaluate(module, context, firstNamedChild(expr), locals, depth + 1);
            }
            case "object" -> {
                return new ObjectLiteral(module, expr);
            }
            case "arrow_function", "function_expression", "function" -> {
                return new FnTarget(functionFor(file, expr, "<anonymous>@L" + NodeFile.line(expr)));
            }
            case "class" -> {
                NodeClass cls = module.classes.values().stream().filter(c -> c.node.equals(expr)).findFirst().orElse(null);
                return cls == null ? null : new ClassTarget(cls);
            }
            default -> {
                return null;
            }
        }
    }

    static final Set<String> FUNCTION_TYPES = Set.of("function_declaration", "generator_function_declaration", "arrow_function",
            "function_expression", "function", "generator_function", "method_definition", "class_declaration", "class");

    /** 함수마다 지역 이름 표를 한 번만 만든다 (라우트가 수백 개인 큰 함수에서 호출마다 다시 훑으면 느리다) */
    private final Map<String, Map<String, TSNode>> localsCache = new HashMap<>();

    /** 함수 안에서 const/let/var 로 선언한 지역 이름 → 값 식 (중첩 함수 안은 제외). 고칠 수 없는 표를 돌려준다 */
    Map<String, TSNode> locals(NodeFunction fn) {
        String key = fn.file().filePath + ":" + fn.node().getStartByte() + ":" + fn.node().getEndByte();
        Map<String, TSNode> cached = localsCache.get(key);
        if (cached == null) {
            cached = java.util.Collections.unmodifiableMap(buildLocals(fn));
            localsCache.put(key, cached);
        }
        return cached;
    }

    private Map<String, TSNode> buildLocals(NodeFunction fn) {
        Map<String, TSNode> locals = new HashMap<>();
        NodeFile file = fn.file();
        fn.paramTypes().keySet().forEach(p -> locals.put(p, null));
        TSNode body = fn.node().getType().equals("program") ? fn.node() : field(fn.node(), "body");
        for (TSNode declarator : NodeFile.descendants(body, Set.of("variable_declarator"), FUNCTION_TYPES)) {
            TSNode name = field(declarator, "name");
            TSNode value = field(declarator, "value");
            if (name != null && name.getType().equals("identifier")) {
                locals.putIfAbsent(file.text(name), value);
            } else if (name != null && name.getType().equals("object_pattern") && (value == null || requireBinding(file, value) == null)) {
                // const { Router } = require('express') 는 import 로 색인했으니 지역 이름으로 가리지 않는다
                // const { id } = req.params 처럼 구조 분해한 이름은 값을 모른다
                for (TSNode id : NodeFile.descendants(name, Set.of("shorthand_property_identifier_pattern", "identifier"), Set.of())) {
                    locals.putIfAbsent(file.text(id), null);
                }
            }
        }
        return locals;
    }
}
