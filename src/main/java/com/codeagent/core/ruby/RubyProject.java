package com.codeagent.core.ruby;

import com.codeagent.core.SourceFiles;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterRuby;

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

import static com.codeagent.core.ruby.RubyFile.field;
import static com.codeagent.core.ruby.RubyFile.isNull;
import static com.codeagent.core.ruby.RubyFile.namedChildren;

/**
 * Ruby 저장소 색인: module·class 중첩으로 전체 이름(Admin::ReportsController)을 정하고, 클래스(부모, 인스턴스·클래스 메서드,
 * before_action, 인스턴스 변수 타입)와 최상위 def 를 모은다. Ruby 는 타입을 적지 않으므로 X.new 로 만든 값,
 * @x = X.new·@x ||= X.new 로 채운 인스턴스 변수, 그런 값을 돌려주는 메서드로만 타입을 안다.
 */
final class RubyProject {

    static final Set<String> SKIP_DIRS = Set.of(".git", "vendor", "node_modules", "tmp", "log", "coverage", "spec", "test", "public",
            "storage", ".bundle", "db", "bin");
    private static final long MAX_FILE_BYTES = 2_000_000;
    static final Set<String> SCOPES = Set.of("method", "singleton_method", "class", "module", "do_block", "block", "lambda");

    record RubyFunc(String key, String name, RubyFile file, TSNode node, RubyClass owner, RubyFunc parent) {
    }

    static final class RubyClass {
        final String fullName;
        final RubyFile file;
        final TSNode node;
        final List<String> nesting;
        String superName;
        final Map<String, RubyFunc> methods = new LinkedHashMap<>();
        final Map<String, RubyFunc> classMethods = new LinkedHashMap<>();
        final Map<String, String> ivarTypes = new HashMap<>();
        /** before_action 들: [이름, only 목록(쉼표), except 목록(쉼표), skip 이면 "skip"] */
        final List<String[]> filters = new ArrayList<>();

        RubyClass(String fullName, RubyFile file, TSNode node, List<String> nesting) {
            this.fullName = fullName;
            this.file = file;
            this.node = node;
            this.nesting = nesting;
        }
    }

    record Callee(List<RubyFunc> functions, String external, String unknownMethod) {
        static Callee none() {
            return new Callee(List.of(), null, null);
        }
    }

    final Path root;
    private final List<RubyFile> files = new ArrayList<>();
    private final Map<String, RubyClass> classes = new LinkedHashMap<>();
    private final Map<String, RubyFunc> topLevel = new HashMap<>();
    private final Map<String, RubyFunc> byNode = new HashMap<>();
    private final Map<String, RubyClass> classByNode = new HashMap<>();
    private final List<RubyFunc> functions = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private final Map<String, Map<String, TSNode>> localsCache = new HashMap<>();

    private RubyProject(Path root) {
        this.root = root;
    }

    // ---- 불러오기 ----

    static RubyProject load(Path root, List<String> parseErrors) {
        RubyProject project = new RubyProject(root.toAbsolutePath().normalize());
        TSParser parser;
        try {
            parser = parser(new TreeSitterRuby());
        } catch (LinkageError | RuntimeException e) {
            parseErrors.add("(ruby): Ruby 파서를 불러오지 못해 Ruby 파일을 건너뜀 (지원하지 않는 OS/CPU 일 수 있음: " + e.getMessage() + ")");
            return project;
        }
        for (Path file : SourceFiles.find(project.root, SKIP_DIRS, MAX_FILE_BYTES, RubyProject::isSource)) {
            String relative = SourceFiles.relative(project.root, file);
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                TSTree tree = parser.parseString(null, text);
                RubyFile rf = new RubyFile(relative, text.getBytes(StandardCharsets.UTF_8), tree);
                if (rf.root.hasError()) {
                    parseErrors.add(relative + ": 문법 오류가 있는 부분은 건너뜀");
                }
                project.files.add(rf);
            } catch (IOException | RuntimeException e) {
                parseErrors.add(relative + ": " + e.getMessage());
            }
        }
        project.files.forEach(f -> project.indexScope(f, f.root, new ArrayList<>(), null));
        project.classes.values().forEach(project::indexClassBody);
        return project;
    }

    static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return (name.endsWith(".rb") || name.equals("config.ru")) && !name.endsWith("_spec.rb") && !name.endsWith("_test.rb");
    }

    private static TSParser parser(TSLanguage language) {
        TSParser parser = new TSParser();
        parser.setLanguage(language);
        return parser;
    }

    /** module·class 를 따라 내려가며 클래스와 메서드를 색인한다 */
    private void indexScope(RubyFile file, TSNode node, List<String> nesting, RubyClass owner) {
        for (TSNode child : namedChildren(node)) {
            switch (child.getType()) {
                case "module", "class" -> {
                    String name = file.text(field(child, "name"));
                    List<String> inner = new ArrayList<>(nesting);
                    inner.add(name);
                    String fullName = String.join("::", inner);
                    RubyClass cls = classes.get(fullName);
                    if (cls == null) {
                        cls = new RubyClass(fullName, file, child, List.copyOf(inner));
                        classes.put(fullName, cls);
                    }
                    TSNode superclass = field(child, "superclass");
                    if (superclass != null) {
                        List<TSNode> parts = namedChildren(superclass);
                        cls.superName = parts.isEmpty() ? null : file.text(parts.get(0));
                    }
                    classByNode.put(file.filePath + "@" + child.getStartByte(), cls);
                    indexScope(file, field(child, "body"), inner, cls);
                }
                case "method" -> {
                    String name = file.text(field(child, "name"));
                    if (owner != null) {
                        owner.methods.putIfAbsent(name, register(file, child, owner.fullName + "#" + name, name, owner, null));
                    } else {
                        topLevel.putIfAbsent(name, register(file, child, file.filePath + "#" + name, name, null, null));
                    }
                }
                case "singleton_method" -> {
                    String name = file.text(field(child, "name"));
                    if (owner != null) {
                        owner.classMethods.putIfAbsent(name, register(file, child, owner.fullName + "#self." + name, name, owner, null));
                    }
                }
                case "singleton_class" -> {
                    // class << self
                    if (owner != null) {
                        for (TSNode method : RubyFile.descendants(child, Set.of("method"), Set.of("class", "module"))) {
                            String name = file.text(field(method, "name"));
                            owner.classMethods.putIfAbsent(name, register(file, method, owner.fullName + "#self." + name, name, owner, null));
                        }
                    }
                }
                case "body_statement" -> indexScope(file, child, nesting, owner);
                default -> {
                }
            }
        }
    }

    /** before_action 과 인스턴스 변수 타입 */
    private void indexClassBody(RubyClass cls) {
        RubyFile file = cls.file;
        for (TSNode call : RubyFile.children(field(cls.node, "body"), "call")) {
            String method = file.text(field(call, "method"));
            if (!method.matches("(skip_|prepend_)?before_(action|filter)")) {
                continue;
            }
            String only = String.join(",", file.strings(file.keyword(call, "only")));
            String except = String.join(",", file.strings(file.keyword(call, "except")));
            for (TSNode arg : RubyFile.positional(call)) {
                String name = file.stringValue(arg);
                if (name != null) {
                    cls.filters.add(new String[]{name, only, except, method.startsWith("skip") ? "skip" : ""});
                }
            }
        }
        for (RubyFunc method : cls.methods.values()) {
            for (TSNode assign : RubyFile.descendants(method.node(), Set.of("assignment", "operator_assignment"), Set.of("class", "module"))) {
                TSNode left = field(assign, "left");
                if (left != null && left.getType().equals("instance_variable")) {
                    String type = typeOf(method, field(assign, "right"), 0);
                    if (type != null) {
                        cls.ivarTypes.putIfAbsent(file.text(left), type);
                    }
                }
            }
        }
    }

    // ---- 함수 ----

    RubyFunc register(RubyFile file, TSNode node, String key, String name, RubyClass owner, RubyFunc parent) {
        String nodeKey = file.filePath + "@" + node.getStartByte();
        RubyFunc existing = byNode.get(nodeKey);
        if (existing != null) {
            return existing;
        }
        if (!keys.add(key)) {
            key = key + "@L" + RubyFile.line(node);
            keys.add(key);
        }
        RubyFunc fn = new RubyFunc(key, name, file, node, owner, parent);
        byNode.put(nodeKey, fn);
        functions.add(fn);
        return fn;
    }

    /** 블록 처리기 (Sinatra get '/x' do ... end) */
    RubyFunc registerBlock(RubyFile file, TSNode block, String name) {
        return register(file, block, file.filePath + "#" + name, name, enclosingClass(file, block), null);
    }

    boolean isRegistered(RubyFile file, TSNode node) {
        return byNode.containsKey(file.filePath + "@" + node.getStartByte());
    }

    List<RubyFunc> functions() {
        return List.copyOf(functions);
    }

    List<RubyFile> files() {
        return files;
    }

    RubyClass cls(String fullName) {
        return fullName == null ? null : classes.get(fullName);
    }

    java.util.Collection<RubyClass> classes() {
        return classes.values();
    }

    RubyClass superClass(RubyClass cls) {
        return cls == null || cls.superName == null ? null : resolveConstant(cls.superName, cls.nesting.subList(0, cls.nesting.size() - 1));
    }

    /** 노드를 감싼 클래스 */
    RubyClass enclosingClass(RubyFile file, TSNode node) {
        for (TSNode current = node.getParent(); !isNull(current); current = current.getParent()) {
            if (current.getType().equals("class") || current.getType().equals("module")) {
                return classByNode.get(file.filePath + "@" + current.getStartByte());
            }
        }
        return null;
    }

    Optional<RubyFunc> functionAt(String filePath, int line) {
        RubyFunc best = null;
        for (RubyFunc fn : functions) {
            if (fn.file().filePath.equals(filePath) && RubyFile.line(fn.node()) <= line && line <= RubyFile.endLine(fn.node())
                    && (best == null || fn.node().getStartByte() > best.node().getStartByte())) {
                best = fn;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 상수 이름 → 클래스 (안쪽 중첩부터 바깥으로 찾는다) */
    RubyClass resolveConstant(String name, List<String> nesting) {
        if (name == null) {
            return null;
        }
        String clean = name.startsWith("::") ? name.substring(2) : name;
        for (int i = nesting.size(); i >= 0; i--) {
            String prefix = String.join("::", nesting.subList(0, i));
            RubyClass cls = classes.get(prefix.isEmpty() ? clean : prefix + "::" + clean);
            if (cls != null) {
                return cls;
            }
        }
        return null;
    }

    /** 클래스와 부모에서 인스턴스 메서드 */
    RubyFunc findMethod(RubyClass cls, String name, boolean classMethod, int depth) {
        if (cls == null || depth > 8) {
            return null;
        }
        RubyFunc fn = (classMethod ? cls.classMethods : cls.methods).get(name);
        return fn != null ? fn : findMethod(superClass(cls), name, classMethod, depth + 1);
    }

    /** 이 클래스가 ActiveRecord 모델인가 (부모를 따라 ApplicationRecord·ActiveRecord::Base) */
    boolean isModel(RubyClass cls) {
        for (int i = 0; i < 8 && cls != null; i++) {
            if (cls.superName != null && (cls.superName.endsWith("ApplicationRecord") || cls.superName.endsWith("ActiveRecord::Base"))) {
                return true;
            }
            cls = superClass(cls);
        }
        return false;
    }

    // ---- 지역 이름 ----

    /** 지역 변수 → 첫 대입 값 (블록 파라미터는 null). 따로 색인한 블록 안은 제외 */
    Map<String, TSNode> locals(RubyFunc fn) {
        return localsCache.computeIfAbsent(fn.key(), k -> {
            Map<String, TSNode> locals = new LinkedHashMap<>();
            collectLocals(fn, fn.node(), locals, true);
            return locals;
        });
    }

    private void collectLocals(RubyFunc fn, TSNode node, Map<String, TSNode> locals, boolean top) {
        RubyFile file = fn.file();
        for (TSNode child : namedChildren(node)) {
            String type = child.getType();
            if (!top && (type.equals("method") || type.equals("singleton_method") || type.equals("class") || type.equals("module"))) {
                continue;
            }
            if ((type.equals("do_block") || type.equals("block")) && isRegistered(file, child) && child.getStartByte() != fn.node().getStartByte()) {
                continue;
            }
            switch (type) {
                case "assignment", "operator_assignment" -> {
                    TSNode left = field(child, "left");
                    if (left != null && left.getType().equals("identifier")) {
                        locals.putIfAbsent(file.text(left), field(child, "right"));
                    }
                }
                case "method_parameters", "block_parameters", "lambda_parameters" -> {
                    for (TSNode param : namedChildren(child)) {
                        TSNode name = param.getType().equals("identifier") ? param : field(param, "name");
                        if (name != null) {
                            locals.putIfAbsent(file.text(name), null);
                        }
                    }
                }
                default -> {
                }
            }
            collectLocals(fn, child, locals, false);
        }
    }

    boolean isLocal(RubyFunc fn, String name) {
        for (RubyFunc current = fn; current != null; current = current.parent()) {
            if (locals(current).containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    TSNode localValue(RubyFunc fn, String name) {
        for (RubyFunc current = fn; current != null; current = current.parent()) {
            Map<String, TSNode> locals = locals(current);
            if (locals.containsKey(name)) {
                return locals.get(name);
            }
        }
        return null;
    }

    // ---- 타입 ----

    String typeOf(RubyFunc fn, TSNode expr) {
        return typeOf(fn, expr, 0);
    }

    private String typeOf(RubyFunc fn, TSNode expr, int depth) {
        expr = RubyFile.unwrap(expr);
        if (isNull(expr) || depth > 8) {
            return null;
        }
        RubyFile file = fn.file();
        switch (expr.getType()) {
            case "identifier" -> {
                String name = file.text(expr);
                if (isLocal(fn, name)) {
                    return typeOf(fn, localValue(fn, name), depth + 1);
                }
                // 인자 없는 메서드 호출 (def repo; @repo ||= Repo.new; end)
                RubyFunc method = fn.owner() == null ? null : findMethod(fn.owner(), name, false, 0);
                return method == null ? null : returnType(method, depth + 1);
            }
            case "instance_variable" -> {
                return fn.owner() == null ? null : fn.owner().ivarTypes.get(file.text(expr));
            }
            case "self" -> {
                return fn.owner() == null ? null : fn.owner().fullName;
            }
            case "call" -> {
                TSNode receiver = field(expr, "receiver");
                String method = file.text(field(expr, "method"));
                if (method.equals("new") && receiver != null && (receiver.getType().equals("constant") || receiver.getType().equals("scope_resolution"))) {
                    RubyClass cls = resolveConstant(file.text(receiver), nesting(fn));
                    return cls == null ? null : cls.fullName;
                }
                for (RubyFunc target : callee(fn, expr).functions()) {
                    String type = returnType(target, depth + 1);
                    if (type != null) {
                        return type;
                    }
                }
                return null;
            }
            case "operator_assignment", "assignment" -> {
                return typeOf(fn, field(expr, "right"), depth + 1);
            }
            default -> {
                return null;
            }
        }
    }

    /** 메서드 본문 마지막 식의 타입 */
    private String returnType(RubyFunc fn, int depth) {
        TSNode body = field(fn.node(), "body");
        List<TSNode> statements = namedChildren(body);
        return statements.isEmpty() ? null : typeOf(fn, statements.get(statements.size() - 1), depth);
    }

    List<String> nesting(RubyFunc fn) {
        return fn.owner() == null ? List.of() : fn.owner().nesting;
    }

    // ---- 호출 대상 ----

    Callee callee(RubyFunc fn, TSNode call) {
        RubyFile file = fn.file();
        String method = file.text(field(call, "method"));
        TSNode receiver = RubyFile.unwrap(field(call, "receiver"));
        if (receiver == null) {
            RubyFunc target = fn.owner() == null ? null : findMethod(fn.owner(), method, false, 0);
            if (target == null && fn.owner() != null) {
                target = findMethod(fn.owner(), method, true, 0);
            }
            if (target == null) {
                target = topLevel.get(method);
            }
            return target != null ? new Callee(List.of(target), null, null) : new Callee(List.of(), method, null);
        }
        if (receiver.getType().equals("constant") || receiver.getType().equals("scope_resolution")) {
            String name = file.text(receiver);
            RubyClass cls = resolveConstant(name, nesting(fn));
            if (cls != null) {
                RubyFunc target = method.equals("new") ? findMethod(cls, "initialize", false, 0) : findMethod(cls, method, true, 0);
                if (target != null) {
                    return new Callee(List.of(target), null, null);
                }
                // User.where(...): 모델의 ActiveRecord 메서드
                return new Callee(List.of(), null, method);
            }
            return new Callee(List.of(), name.replaceFirst("^::", "") + "." + method, method);
        }
        String type = typeOf(fn, receiver);
        RubyClass cls = cls(type);
        if (cls != null) {
            RubyFunc target = findMethod(cls, method, false, 0);
            return target != null ? new Callee(List.of(target), null, null) : new Callee(List.of(), null, method);
        }
        return new Callee(List.of(), null, method);
    }

    /** 상수로 보는 식: 리터럴, 상수, 상수끼리 + */
    boolean constant(RubyFunc fn, TSNode expr, int depth) {
        expr = RubyFile.unwrap(expr);
        if (expr == null || depth > 6) {
            return false;
        }
        switch (expr.getType()) {
            case "string" -> {
                return RubyFile.interpolationFree(expr);
            }
            case "integer", "float", "true", "false", "nil", "simple_symbol", "constant", "scope_resolution", "hash_key_symbol",
                 "delimited_symbol" -> {
                return true;
            }
            case "binary" -> {
                return constant(fn, field(expr, "left"), depth + 1) && constant(fn, field(expr, "right"), depth + 1);
            }
            case "array" -> {
                return namedChildren(expr).stream().allMatch(e -> constant(fn, e, depth + 1));
            }
            case "hash" -> {
                // order(created_at: :desc)
                for (TSNode pair : namedChildren(expr)) {
                    if (!pair.getType().equals("pair") || !constant(fn, field(pair, "value"), depth + 1)) {
                        return false;
                    }
                }
                return true;
            }
            case "pair" -> {
                return constant(fn, field(expr, "value"), depth + 1);
            }
            case "call" -> {
                // Rails.root.join('x') 처럼 상수만 넘기는 경로 조립
                String text = fn.file().text(field(expr, "receiver"));
                String method = fn.file().text(field(expr, "method"));
                return (text.equals("Rails.root") || text.equals("File")) && Set.of("join", "expand_path").contains(method)
                        && RubyFile.argumentValues(expr).stream().allMatch(a -> constant(fn, a, depth + 1));
            }
            default -> {
                return false;
            }
        }
    }
}
