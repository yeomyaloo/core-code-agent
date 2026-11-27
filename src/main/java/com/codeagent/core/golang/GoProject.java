package com.codeagent.core.golang;

import com.codeagent.core.SourceFiles;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterGo;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.codeagent.core.golang.GoFile.field;
import static com.codeagent.core.golang.GoFile.fields;
import static com.codeagent.core.golang.GoFile.isNull;
import static com.codeagent.core.golang.GoFile.namedChildren;

/**
 * Go 저장소 색인: go.mod 의 module 로 패키지 import 경로를 정하고, 패키지마다 함수·메서드·타입(구조체 필드, 인터페이스)·
 * 패키지 변수·상수를 모은다. 식의 타입은 Go 에 적힌 타입(파라미터, 리시버, 구조체 필드, var, :=, 생성자 반환 타입)으로 알아낸다.
 * 타입 이름은 "import경로.이름" 이고 포인터는 뗀다 (*sql.DB → database/sql.DB).
 */
final class GoProject {

    static final Set<String> SKIP_DIRS = Set.of(".git", "vendor", "node_modules", "testdata", "third_party", "dist", "build",
            "bin", ".idea", ".vscode");
    private static final long MAX_FILE_BYTES = 2_000_000;
    private static final Pattern MODULE = Pattern.compile("(?m)^\\s*module\\s+(\\S+)");
    static final Set<String> BUILTIN_TYPES = Set.of("string", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16",
            "uint32", "uint64", "uintptr", "byte", "rune", "bool", "float32", "float64", "complex64", "complex128", "error", "any");
    static final Set<String> FUNCTION_TYPES = Set.of("function_declaration", "method_declaration", "func_literal");

    /**
     * 외부 함수·메서드의 반환 타입 (첫 값). "타입.*" 은 그 타입의 모든 메서드 (gorm 의 체인).
     * 라우터와 위험 지점 판단에 필요한 것만 둔다.
     */
    private static final Map<String, String> RETURN_TYPES = Map.ofEntries(
            Map.entry("database/sql.Open", "database/sql.DB"),
            Map.entry("database/sql.OpenDB", "database/sql.DB"),
            Map.entry("database/sql.DB.Begin", "database/sql.Tx"),
            Map.entry("database/sql.DB.BeginTx", "database/sql.Tx"),
            Map.entry("database/sql.DB.Prepare", "database/sql.Stmt"),
            Map.entry("github.com/jmoiron/sqlx.Open", "github.com/jmoiron/sqlx.DB"),
            Map.entry("github.com/jmoiron/sqlx.Connect", "github.com/jmoiron/sqlx.DB"),
            Map.entry("github.com/jmoiron/sqlx.MustConnect", "github.com/jmoiron/sqlx.DB"),
            Map.entry("github.com/jmoiron/sqlx.MustOpen", "github.com/jmoiron/sqlx.DB"),
            Map.entry("github.com/jmoiron/sqlx.NewDb", "github.com/jmoiron/sqlx.DB"),
            Map.entry("gorm.io/gorm.Open", "gorm.io/gorm.DB"),
            Map.entry("gorm.io/gorm.DB.*", "gorm.io/gorm.DB"),
            Map.entry("github.com/jackc/pgx/v5/pgxpool.New", "github.com/jackc/pgx/v5/pgxpool.Pool"),
            Map.entry("github.com/jackc/pgx/v5.Connect", "github.com/jackc/pgx/v5.Conn"),
            Map.entry("github.com/gin-gonic/gin.Default", "github.com/gin-gonic/gin.Engine"),
            Map.entry("github.com/gin-gonic/gin.New", "github.com/gin-gonic/gin.Engine"),
            Map.entry("github.com/gin-gonic/gin.Engine.Group", "github.com/gin-gonic/gin.RouterGroup"),
            Map.entry("github.com/gin-gonic/gin.RouterGroup.Group", "github.com/gin-gonic/gin.RouterGroup"),
            Map.entry("github.com/labstack/echo/v4.New", "github.com/labstack/echo/v4.Echo"),
            Map.entry("github.com/labstack/echo/v4.Echo.Group", "github.com/labstack/echo/v4.Group"),
            Map.entry("github.com/labstack/echo/v4.Group.Group", "github.com/labstack/echo/v4.Group"),
            Map.entry("github.com/go-chi/chi/v5.NewRouter", "github.com/go-chi/chi/v5.Mux"),
            Map.entry("github.com/go-chi/chi.NewRouter", "github.com/go-chi/chi.Mux"),
            Map.entry("github.com/gorilla/mux.NewRouter", "github.com/gorilla/mux.Router"),
            Map.entry("github.com/gorilla/mux.Router.PathPrefix", "github.com/gorilla/mux.Route"),
            Map.entry("github.com/gorilla/mux.Route.Subrouter", "github.com/gorilla/mux.Router"),
            Map.entry("github.com/gofiber/fiber/v2.New", "github.com/gofiber/fiber/v2.App"),
            Map.entry("net/http.NewServeMux", "net/http.ServeMux"),
            Map.entry("net/http.NewRequest", "net/http.Request"),
            Map.entry("net/http.NewRequestWithContext", "net/http.Request"),
            Map.entry("net/http.Get", "net/http.Response"),
            Map.entry("net/http.Post", "net/http.Response"),
            Map.entry("net/http.Client.Do", "net/http.Response"),
            Map.entry("net/http.Client.Get", "net/http.Response"),
            Map.entry("os/exec.Command", "os/exec.Cmd"),
            Map.entry("os/exec.CommandContext", "os/exec.Cmd"),
            Map.entry("html/template.New", "html/template.Template"),
            Map.entry("html/template.Template.New", "html/template.Template"),
            Map.entry("html/template.Template.Parse", "html/template.Template"),
            Map.entry("html/template.Template.Funcs", "html/template.Template"),
            Map.entry("html/template.ParseFiles", "html/template.Template"),
            Map.entry("html/template.ParseGlob", "html/template.Template"),
            Map.entry("text/template.New", "text/template.Template"),
            Map.entry("text/template.Template.New", "text/template.Template"),
            Map.entry("text/template.Template.Parse", "text/template.Template"),
            Map.entry("text/template.Template.Funcs", "text/template.Template"),
            Map.entry("text/template.ParseFiles", "text/template.Template"),
            Map.entry("encoding/json.NewDecoder", "encoding/json.Decoder"),
            Map.entry("encoding/gob.NewDecoder", "encoding/gob.Decoder"),
            Map.entry("crypto/md5.New", "hash.Hash"),
            Map.entry("crypto/sha1.New", "hash.Hash"),
            Map.entry("math/rand.New", "math/rand.Rand"),
            Map.entry("go.mongodb.org/mongo-driver/mongo.Connect", "go.mongodb.org/mongo-driver/mongo.Client"),
            Map.entry("go.mongodb.org/mongo-driver/mongo.Client.Database", "go.mongodb.org/mongo-driver/mongo.Database"),
            Map.entry("go.mongodb.org/mongo-driver/mongo.Database.Collection", "go.mongodb.org/mongo-driver/mongo.Collection"));
    /** 외부 패키지 변수의 타입 */
    private static final Map<String, String> VAR_TYPES = Map.of(
            "net/http.DefaultClient", "net/http.Client",
            "net/http.DefaultServeMux", "net/http.ServeMux");

    // ---- 모델 ----

    /** 함수·메서드·함수 리터럴. parent 는 함수 리터럴을 감싼 함수 (클로저 변수 찾기) */
    record GoFunc(String key, String name, GoFile file, TSNode node, GoType receiver, GoFunc parent) {
    }

    static final class GoType {
        final String qualified;
        final String name;
        final GoPackage pkg;
        final GoFile file;
        final TSNode node;
        final boolean isInterface;
        final Map<String, String> fieldTypes = new LinkedHashMap<>();
        final List<String> embedded = new ArrayList<>();
        final Map<String, GoFunc> methods = new LinkedHashMap<>();
        /** 인터페이스 메서드 이름 → 파라미터 타입들 */
        final Map<String, List<String>> interfaceMethods = new HashMap<>();

        GoType(String qualified, String name, GoPackage pkg, GoFile file, TSNode node, boolean isInterface) {
            this.qualified = qualified;
            this.name = name;
            this.pkg = pkg;
            this.file = file;
            this.node = node;
            this.isInterface = isInterface;
        }
    }

    static final class GoPackage {
        final String path;
        final String dir;
        final List<GoFile> files = new ArrayList<>();
        final Map<String, GoFunc> functions = new LinkedHashMap<>();
        final Map<String, GoType> types = new LinkedHashMap<>();
        /** 패키지 변수 이름 → (적힌 타입, 값 식) */
        final Map<String, Local> vars = new LinkedHashMap<>();
        final Map<String, TSNode> consts = new LinkedHashMap<>();

        GoPackage(String path, String dir) {
            this.path = path;
            this.dir = dir;
        }
    }

    /** 이름 하나의 선언: 적힌 타입(없으면 null), 값 식(없으면 null), 여러 값을 받는 식에서 몇 번째인지 */
    record Local(String declaredType, TSNode value, int index, GoFile file) {
    }

    /** 호출 대상: 프로젝트 함수들 (인터페이스면 구현 여럿), 외부 이름, 또는 받는 쪽을 모르는 메서드 이름 */
    record Callee(List<GoFunc> functions, String external, String unknownMethod) {
        static Callee none() {
            return new Callee(List.of(), null, null);
        }
    }

    final Path root;
    private final List<GoFile> files = new ArrayList<>();
    private final Map<String, GoPackage> packages = new LinkedHashMap<>();
    private final Map<String, GoType> types = new HashMap<>();
    private final Map<String, GoFunc> byNode = new HashMap<>();
    private final List<GoFunc> functions = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private final Map<String, Map<String, Local>> localsCache = new HashMap<>();

    private GoProject(Path root) {
        this.root = root;
    }

    // ---- 불러오기 ----

    static GoProject load(Path root, List<String> parseErrors) {
        GoProject project = new GoProject(root.toAbsolutePath().normalize());
        TSParser parser;
        try {
            parser = parser(new TreeSitterGo());
        } catch (LinkageError | RuntimeException e) {
            parseErrors.add("(go): Go 파서를 불러오지 못해 Go 파일을 건너뜀 (지원하지 않는 OS/CPU 일 수 있음: " + e.getMessage() + ")");
            return project;
        }
        Map<String, String> modules = project.modules();
        for (Path file : SourceFiles.find(project.root, SKIP_DIRS, MAX_FILE_BYTES, GoProject::isSource)) {
            String relative = SourceFiles.relative(project.root, file);
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                TSTree tree = parser.parseString(null, text);
                GoFile gf = new GoFile(relative, text.getBytes(StandardCharsets.UTF_8), tree);
                if (gf.root.hasError()) {
                    parseErrors.add(relative + ": 문법 오류가 있는 부분은 건너뜀");
                }
                gf.packagePath = packagePath(modules, gf);
                project.files.add(gf);
            } catch (IOException | RuntimeException e) {
                parseErrors.add(relative + ": " + e.getMessage());
            }
        }
        project.files.forEach(project::indexImports);
        project.files.forEach(project::indexDeclarations);
        project.files.forEach(project::indexMethods);
        return project;
    }

    static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".go") && !name.endsWith("_test.go");
    }

    private static TSParser parser(TSLanguage language) {
        TSParser parser = new TSParser();
        parser.setLanguage(language);
        return parser;
    }

    /** go.mod 폴더(저장소 기준, 루트는 "") → module 경로 */
    private Map<String, String> modules() {
        Map<String, String> result = new HashMap<>();
        for (Path mod : SourceFiles.find(root, SKIP_DIRS, MAX_FILE_BYTES, p -> p.getFileName().toString().equals("go.mod"))) {
            try {
                Matcher m = MODULE.matcher(Files.readString(mod, StandardCharsets.UTF_8));
                if (m.find()) {
                    String rel = SourceFiles.relative(root, mod);
                    int slash = rel.lastIndexOf('/');
                    result.put(slash < 0 ? "" : rel.substring(0, slash), m.group(1));
                }
            } catch (IOException e) {
                // 못 읽는 go.mod 는 건너뛴다
            }
        }
        return result;
    }

    /** 가장 가까운 go.mod 의 module + 그 아래 폴더. go.mod 가 없으면 폴더 경로 */
    private static String packagePath(Map<String, String> modules, GoFile file) {
        String dir = file.dir;
        String current = dir;
        while (true) {
            String module = modules.get(current);
            if (module != null) {
                String rest = current.isEmpty() ? dir : dir.length() > current.length() ? dir.substring(current.length() + 1) : "";
                return rest.isEmpty() ? module : module + "/" + rest;
            }
            if (current.isEmpty()) {
                return dir.isEmpty() ? "main" : dir;
            }
            int slash = current.lastIndexOf('/');
            current = slash < 0 ? "" : current.substring(0, slash);
        }
    }

    private GoPackage pkg(GoFile file) {
        return packages.computeIfAbsent(file.packagePath, p -> new GoPackage(p, file.dir));
    }

    private void indexImports(GoFile file) {
        pkg(file).files.add(file);
        for (TSNode spec : GoFile.descendants(file.root, Set.of("import_spec"), Set.of("function_declaration", "method_declaration"))) {
            String path = file.stringValue(field(spec, "path"));
            if (path == null) {
                continue;
            }
            TSNode name = field(spec, "name");
            String alias = name == null ? defaultAlias(path) : file.text(name);
            if (!alias.equals("_") && !alias.equals(".")) {
                file.imports.put(alias, path);
            }
        }
    }

    /** github.com/labstack/echo/v4 → echo, gopkg.in/yaml.v3 → yaml, github.com/go-chi/chi/v5 → chi */
    static String defaultAlias(String path) {
        String[] parts = path.split("/");
        String last = parts[parts.length - 1];
        if (last.matches("v\\d+") && parts.length > 1) {
            last = parts[parts.length - 2];
        }
        last = last.replaceAll("\\.v\\d+$", "");
        if (last.startsWith("go-")) {
            last = last.substring(3);
        }
        return last.replace("-", "").replace(".", "");
    }

    private void indexDeclarations(GoFile file) {
        GoPackage pkg = pkg(file);
        for (TSNode decl : namedChildren(file.root)) {
            switch (decl.getType()) {
                case "function_declaration" -> {
                    String name = file.text(field(decl, "name"));
                    pkg.functions.put(name, register(file, decl, name, null, null));
                }
                case "type_declaration" -> {
                    for (TSNode spec : GoFile.children(decl, "type_spec")) {
                        indexType(file, pkg, spec);
                    }
                }
                case "var_declaration" -> {
                    for (TSNode spec : GoFile.descendants(decl, Set.of("var_spec"), Set.of())) {
                        addSpec(file, spec, pkg.vars);
                    }
                }
                case "const_declaration" -> {
                    for (TSNode spec : GoFile.descendants(decl, Set.of("const_spec"), Set.of())) {
                        List<TSNode> values = namedChildren(field(spec, "value"));
                        List<TSNode> names = fields(spec, "name");
                        for (int i = 0; i < names.size(); i++) {
                            pkg.consts.put(file.text(names.get(i)), i < values.size() ? values.get(i) : null);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private void indexType(GoFile file, GoPackage pkg, TSNode spec) {
        String name = file.text(field(spec, "name"));
        TSNode type = field(spec, "type");
        boolean isInterface = type != null && type.getType().equals("interface_type");
        GoType goType = new GoType(pkg.path + "." + name, name, pkg, file, spec, isInterface);
        if (type != null && type.getType().equals("struct_type")) {
            for (TSNode decl : GoFile.descendants(type, Set.of("field_declaration"), Set.of("struct_type"))) {
                String fieldType = typeName(file, field(decl, "type"));
                List<TSNode> names = fields(decl, "name");
                if (names.isEmpty()) {
                    if (fieldType != null) {
                        goType.embedded.add(fieldType);
                        goType.fieldTypes.put(fieldType.substring(fieldType.lastIndexOf('.') + 1), fieldType);
                    }
                } else {
                    names.forEach(n -> goType.fieldTypes.put(file.text(n), fieldType));
                }
            }
        }
        if (isInterface) {
            for (TSNode elem : namedChildren(type)) {
                if (elem.getType().equals("method_elem") || elem.getType().equals("method_spec")) {
                    goType.interfaceMethods.put(file.text(field(elem, "name")), parameterTypes(file, field(elem, "parameters")));
                }
            }
        }
        pkg.types.put(name, goType);
        types.put(goType.qualified, goType);
    }

    private void indexMethods(GoFile file) {
        for (TSNode decl : GoFile.children(file.root, "method_declaration")) {
            TSNode receiver = field(decl, "receiver");
            TSNode param = GoFile.firstChild(receiver, "parameter_declaration");
            String typeName = typeName(file, field(param, "type"));
            GoType owner = typeName == null ? null : types.get(typeName);
            String name = file.text(field(decl, "name"));
            String simple = typeName == null ? "?" : typeName.substring(typeName.lastIndexOf('.') + 1);
            GoFunc fn = register(file, decl, simple + "." + name, owner, null);
            if (owner != null) {
                owner.methods.put(name, fn);
            }
        }
    }

    private void addSpec(GoFile file, TSNode spec, Map<String, Local> into) {
        String declared = typeName(file, field(spec, "type"));
        List<TSNode> values = namedChildren(field(spec, "value"));
        List<TSNode> names = fields(spec, "name");
        for (int i = 0; i < names.size(); i++) {
            TSNode value = values.size() == names.size() ? values.get(i) : values.isEmpty() ? null : values.get(0);
            int index = values.size() == names.size() ? 0 : i;
            into.putIfAbsent(file.text(names.get(i)), new Local(declared, value, index, file));
        }
    }

    // ---- 함수 ----

    private String packagePrefix(GoFile file) {
        return file.dir.isEmpty() ? "." : file.dir;
    }

    /** 함수 노드를 색인한다 (이미 있으면 그대로). 이름 없는 함수 리터럴은 name 으로 이름 붙인다 */
    GoFunc register(GoFile file, TSNode node, String name, GoType receiver, GoFunc parent) {
        String nodeKey = file.filePath + "@" + node.getStartByte();
        GoFunc existing = byNode.get(nodeKey);
        if (existing != null) {
            return existing;
        }
        String key = packagePrefix(file) + "#" + name;
        if (!keys.add(key)) {
            key = key + "@L" + GoFile.line(node);
            keys.add(key);
        }
        GoFunc fn = new GoFunc(key, name, file, node, receiver, parent);
        byNode.put(nodeKey, fn);
        functions.add(fn);
        return fn;
    }

    boolean isRegistered(GoFile file, TSNode node) {
        return byNode.containsKey(file.filePath + "@" + node.getStartByte());
    }

    GoFunc registered(GoFile file, TSNode node) {
        return byNode.get(file.filePath + "@" + node.getStartByte());
    }

    List<GoFunc> functions() {
        return List.copyOf(functions);
    }

    List<GoFile> files() {
        return files;
    }

    GoPackage packageOf(GoFile file) {
        return packages.get(file.packagePath);
    }

    boolean isProjectPackage(String path) {
        return packages.containsKey(path);
    }

    GoType type(String qualified) {
        return qualified == null ? null : types.get(qualified);
    }

    /** 파일·줄을 감싼 함수 (색인한 것 중 가장 안쪽) */
    Optional<GoFunc> functionAt(String filePath, int line) {
        GoFunc best = null;
        for (GoFunc fn : functions) {
            if (fn.file().filePath.equals(filePath) && GoFile.line(fn.node()) <= line && line <= GoFile.endLine(fn.node())
                    && (best == null || fn.node().getStartByte() > best.node().getStartByte())) {
                best = fn;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 노드를 감싼 색인된 함수 (색인하지 않은 함수 리터럴은 건너뛴다) */
    GoFunc enclosing(GoFile file, TSNode node) {
        for (TSNode current = node.getParent(); !isNull(current); current = current.getParent()) {
            if (FUNCTION_TYPES.contains(current.getType())) {
                GoFunc fn = registered(file, current);
                if (fn != null) {
                    return fn;
                }
            }
        }
        return null;
    }

    /** 첫 반환 타입 */
    String resultType(GoFunc fn) {
        TSNode result = field(fn.node(), "result");
        if (result == null) {
            return null;
        }
        if (result.getType().equals("parameter_list")) {
            TSNode first = GoFile.firstChild(result, "parameter_declaration");
            return first == null ? null : typeName(fn.file(), field(first, "type"));
        }
        return typeName(fn.file(), result);
    }

    /** 파라미터 타입들 (이름 없는 파라미터도 하나로 센다) */
    private List<String> parameterTypes(GoFile file, TSNode parameters) {
        List<String> result = new ArrayList<>();
        for (TSNode param : namedChildren(parameters)) {
            String type = typeName(file, field(param, "type"));
            for (int i = 0; i < Math.max(1, fields(param, "name").size()); i++) {
                result.add(type);
            }
        }
        return result;
    }

    /** 개수가 같고, 둘 다 아는 타입끼리는 같다 */
    private static boolean sameTypes(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != null && b.get(i) != null && !a.get(i).equals(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 파라미터 이름 → 타입 (리시버 포함) */
    Map<String, String> parameters(GoFunc fn) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String fieldName : List.of("receiver", "parameters")) {
            for (TSNode param : namedChildren(field(fn.node(), fieldName))) {
                String type = typeName(fn.file(), field(param, "type"));
                for (TSNode name : fields(param, "name")) {
                    result.put(fn.file().text(name), type);
                }
            }
        }
        return result;
    }

    /** n 번째 파라미터 이름 (리시버 제외). 없으면 null */
    String parameterName(GoFunc fn, int index) {
        int i = 0;
        for (TSNode param : namedChildren(field(fn.node(), "parameters"))) {
            List<TSNode> names = fields(param, "name");
            if (names.isEmpty()) {
                i++;
                continue;
            }
            for (TSNode name : names) {
                if (i++ == index) {
                    return fn.file().text(name);
                }
            }
        }
        return null;
    }

    // ---- 지역 이름 ----

    /** 함수 안의 지역 이름 (파라미터, var, :=). 따로 색인한 안쪽 함수 리터럴 안은 제외 */
    Map<String, Local> locals(GoFunc fn) {
        return localsCache.computeIfAbsent(fn.key(), k -> {
            Map<String, Local> locals = new LinkedHashMap<>();
            GoFile file = fn.file();
            parameters(fn).forEach((name, type) -> locals.put(name, new Local(type, null, 0, file)));
            collectLocals(fn, field(fn.node(), "body"), locals);
            return locals;
        });
    }

    private void collectLocals(GoFunc fn, TSNode node, Map<String, Local> locals) {
        GoFile file = fn.file();
        for (TSNode child : namedChildren(node)) {
            switch (child.getType()) {
                case "func_literal" -> {
                    if (isRegistered(file, child)) {
                        continue;
                    }
                    for (TSNode param : namedChildren(field(child, "parameters"))) {
                        String type = typeName(file, field(param, "type"));
                        fields(param, "name").forEach(n -> locals.putIfAbsent(file.text(n), new Local(type, null, 0, file)));
                    }
                }
                case "short_var_declaration" -> {
                    List<TSNode> left = namedChildren(field(child, "left"));
                    List<TSNode> right = namedChildren(field(child, "right"));
                    for (int i = 0; i < left.size(); i++) {
                        TSNode value = right.size() == left.size() ? right.get(i) : right.isEmpty() ? null : right.get(0);
                        int index = right.size() == left.size() ? 0 : i;
                        locals.putIfAbsent(file.text(left.get(i)), new Local(null, value, index, file));
                    }
                }
                case "var_spec" -> addSpec(file, child, locals);
                case "const_spec" -> {
                    List<TSNode> values = namedChildren(field(child, "value"));
                    List<TSNode> names = fields(child, "name");
                    for (int i = 0; i < names.size(); i++) {
                        locals.putIfAbsent(file.text(names.get(i)), new Local("const", i < values.size() ? values.get(i) : null, 0, file));
                    }
                }
                case "range_clause" -> namedChildren(field(child, "left"))
                        .forEach(n -> locals.putIfAbsent(file.text(n), new Local(null, null, 0, file)));
                default -> {
                }
            }
            collectLocals(fn, child, locals);
        }
    }

    /** 이름의 선언을 함수 → 감싼 함수 순으로 찾는다 */
    Local local(GoFunc fn, String name) {
        for (GoFunc current = fn; current != null; current = current.parent()) {
            Local local = locals(current).get(name);
            if (local != null) {
                return local;
            }
        }
        return null;
    }

    /** 식의 import 경로 (지역 이름에 가려지지 않은 import 이름이면) */
    String importOf(GoFunc fn, TSNode expr) {
        if (expr == null || !expr.getType().equals("identifier") && !expr.getType().equals("package_identifier")) {
            return null;
        }
        String name = fn.file().text(expr);
        if (local(fn, name) != null) {
            return null;
        }
        return fn.file().imports.get(name);
    }

    // ---- 타입 ----

    /** 타입 식 → "import경로.이름" (포인터는 뗀다). 기본 타입은 이름 그대로, 슬라이스는 "[]" + 원소 */
    String typeName(GoFile file, TSNode type) {
        if (isNull(type)) {
            return null;
        }
        return switch (type.getType()) {
            case "pointer_type", "parenthesized_type" -> {
                List<TSNode> inner = namedChildren(type);
                yield inner.isEmpty() ? null : typeName(file, inner.get(inner.size() - 1));
            }
            case "qualified_type" -> {
                String alias = file.text(field(type, "package"));
                String path = file.imports.getOrDefault(alias, alias);
                yield path + "." + file.text(field(type, "name"));
            }
            case "type_identifier" -> {
                String name = file.text(type);
                yield BUILTIN_TYPES.contains(name) ? name : file.packagePath + "." + name;
            }
            case "generic_type" -> typeName(file, field(type, "type"));
            case "slice_type", "array_type" -> {
                String element = typeName(file, field(type, "element"));
                yield element == null ? null : "[]" + element;
            }
            case "function_type" -> "func";
            case "map_type" -> "map";
            default -> null;
        };
    }

    /** 식의 타입. 모르면 null */
    String typeOf(GoFunc fn, TSNode expr) {
        return typeOf(fn, expr, 0, 0);
    }

    private String typeOf(GoFunc fn, TSNode expr, int index, int depth) {
        expr = GoFile.unwrap(expr);
        if (isNull(expr) || depth > 8) {
            return null;
        }
        GoFile file = fn.file();
        switch (expr.getType()) {
            case "identifier" -> {
                String name = file.text(expr);
                Local local = local(fn, name);
                if (local != null) {
                    if (local.declaredType() != null && !local.declaredType().equals("const")) {
                        return local.declaredType();
                    }
                    return local.value() == null ? null : typeOf(fn, local.value(), local.index(), depth + 1);
                }
                GoPackage pkg = packageOf(file);
                Local var = pkg == null ? null : pkg.vars.get(name);
                if (var != null) {
                    if (var.declaredType() != null) {
                        return var.declaredType();
                    }
                    GoFunc init = packageInit(var.file());
                    return var.value() == null ? null : typeOf(init, var.value(), var.index(), depth + 1);
                }
                return null;
            }
            case "selector_expression" -> {
                TSNode operand = field(expr, "operand");
                String fieldName = file.text(field(expr, "field"));
                String importPath = importOf(fn, operand);
                if (importPath != null) {
                    GoPackage pkg = packages.get(importPath);
                    if (pkg != null) {
                        Local var = pkg.vars.get(fieldName);
                        if (var != null) {
                            return var.declaredType() != null ? var.declaredType()
                                    : var.value() == null ? null : typeOf(packageInit(var.file()), var.value(), var.index(), depth + 1);
                        }
                        return null;
                    }
                    return VAR_TYPES.get(importPath + "." + fieldName);
                }
                return fieldType(typeOf(fn, operand, 0, depth + 1), fieldName, 0);
            }
            case "call_expression" -> {
                return callResultType(fn, expr, index, depth);
            }
            case "composite_literal" -> {
                return typeName(file, field(expr, "type"));
            }
            case "unary_expression" -> {
                return typeOf(fn, field(expr, "operand"), 0, depth + 1);
            }
            case "type_assertion_expression" -> {
                return typeName(file, field(expr, "type"));
            }
            default -> {
                return null;
            }
        }
    }

    /** 패키지 변수 초기값을 볼 때 쓰는 가짜 함수 (그 파일의 import 를 쓴다) */
    private GoFunc packageInit(GoFile file) {
        return new GoFunc(packagePrefix(file) + "#<init>", "<init>", file, file.root, null, null);
    }

    private String callResultType(GoFunc fn, TSNode call, int index, int depth) {
        TSNode function = GoFile.unwrap(field(call, "function"));
        GoFile file = fn.file();
        if (function == null) {
            return null;
        }
        if (function.getType().equals("identifier")) {
            String name = file.text(function);
            GoPackage pkg = packageOf(file);
            if (local(fn, name) == null && pkg != null) {
                GoFunc target = pkg.functions.get(name);
                if (target != null) {
                    return resultAt(target, index);
                }
                if (pkg.types.containsKey(name)) {
                    return pkg.types.get(name).qualified;
                }
            }
            return null;
        }
        if (!function.getType().equals("selector_expression")) {
            return null;
        }
        TSNode operand = field(function, "operand");
        String name = file.text(field(function, "field"));
        String importPath = importOf(fn, operand);
        if (importPath != null) {
            GoPackage pkg = packages.get(importPath);
            if (pkg != null) {
                GoFunc target = pkg.functions.get(name);
                return target != null ? resultAt(target, index) : pkg.types.containsKey(name) ? pkg.types.get(name).qualified : null;
            }
            if (name.equals("Must")) {
                List<TSNode> args = namedChildren(field(call, "arguments"));
                return args.isEmpty() ? null : typeOf(fn, args.get(0), 0, depth + 1);
            }
            return index == 0 ? RETURN_TYPES.get(importPath + "." + name) : null;
        }
        String receiver = typeOf(fn, operand, 0, depth + 1);
        if (receiver == null) {
            return null;
        }
        GoType type = types.get(receiver);
        if (type != null) {
            GoFunc method = findMethod(type, name, 0);
            return method == null ? null : resultAt(method, index);
        }
        if (index != 0) {
            return null;
        }
        String exact = RETURN_TYPES.get(receiver + "." + name);
        return exact != null ? exact : RETURN_TYPES.get(receiver + ".*");
    }

    private String resultAt(GoFunc fn, int index) {
        TSNode result = field(fn.node(), "result");
        if (result == null) {
            return null;
        }
        if (!result.getType().equals("parameter_list")) {
            return index == 0 ? typeName(fn.file(), result) : null;
        }
        List<TSNode> params = GoFile.children(result, "parameter_declaration");
        return index < params.size() ? typeName(fn.file(), field(params.get(index), "type")) : null;
    }

    /** 구조체 필드의 타입 (끼워 넣은 구조체 안도 본다) */
    String fieldType(String typeName, String fieldName, int depth) {
        GoType type = types.get(typeName);
        if (type == null || depth > 4) {
            return null;
        }
        String direct = type.fieldTypes.get(fieldName);
        if (direct != null) {
            return direct;
        }
        for (String embedded : type.embedded) {
            String inner = fieldType(embedded, fieldName, depth + 1);
            if (inner != null) {
                return inner;
            }
        }
        return null;
    }

    /** 타입의 메서드 (끼워 넣은 구조체 안도 본다) */
    GoFunc findMethod(GoType type, String name, int depth) {
        if (type == null || depth > 4) {
            return null;
        }
        GoFunc method = type.methods.get(name);
        if (method != null) {
            return method;
        }
        for (String embedded : type.embedded) {
            GoFunc inner = findMethod(types.get(embedded), name, depth + 1);
            if (inner != null) {
                return inner;
            }
        }
        return null;
    }

    /** 인터페이스 메서드 → 그 인터페이스의 메서드를 모두 가진 프로젝트 타입의 같은 이름 메서드 */
    List<GoFunc> implementations(GoType iface, String name) {
        List<GoFunc> result = new ArrayList<>();
        for (GoType type : types.values()) {
            if (type.isInterface) {
                continue;
            }
            if (iface.interfaceMethods.entrySet().stream().allMatch(m -> {
                GoFunc method = findMethod(type, m.getKey(), 0);
                return method != null && sameTypes(parameterTypes(method.file(), field(method.node(), "parameters")), m.getValue());
            })) {
                GoFunc method = findMethod(type, name, 0);
                if (method != null) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    // ---- 호출 대상 ----

    Callee callee(GoFunc fn, TSNode call) {
        TSNode function = GoFile.unwrap(field(call, "function"));
        GoFile file = fn.file();
        if (function == null) {
            return Callee.none();
        }
        if (function.getType().equals("identifier")) {
            String name = file.text(function);
            if (local(fn, name) != null) {
                return Callee.none();
            }
            GoPackage pkg = packageOf(file);
            GoFunc target = pkg == null ? null : pkg.functions.get(name);
            return target != null ? new Callee(List.of(target), null, null) : Callee.none();
        }
        if (!function.getType().equals("selector_expression")) {
            return Callee.none();
        }
        TSNode operand = field(function, "operand");
        String name = file.text(field(function, "field"));
        String importPath = importOf(fn, operand);
        if (importPath != null) {
            GoPackage pkg = packages.get(importPath);
            if (pkg != null) {
                GoFunc target = pkg.functions.get(name);
                return target != null ? new Callee(List.of(target), null, null) : Callee.none();
            }
            return new Callee(List.of(), importPath + "." + name, null);
        }
        String receiver = typeOf(fn, operand);
        GoType type = types.get(receiver);
        if (type != null) {
            if (type.isInterface) {
                return new Callee(implementations(type, name), null, null);
            }
            GoFunc method = findMethod(type, name, 0);
            return method != null ? new Callee(List.of(method), null, null) : Callee.none();
        }
        if (receiver != null && receiver.contains(".") && !receiver.startsWith("[]")) {
            return new Callee(List.of(), receiver + "." + name, name);
        }
        return new Callee(List.of(), null, name);
    }

    /** 식이 가리키는 프로젝트 함수 (함수 이름, pkg.Func, h.Method 값). 없으면 null */
    GoFunc functionValue(GoFunc fn, TSNode expr) {
        expr = GoFile.unwrap(expr);
        if (expr == null) {
            return null;
        }
        GoFile file = fn.file();
        if (expr.getType().equals("identifier")) {
            String name = file.text(expr);
            if (local(fn, name) != null) {
                return null;
            }
            GoPackage pkg = packageOf(file);
            return pkg == null ? null : pkg.functions.get(name);
        }
        if (expr.getType().equals("selector_expression")) {
            TSNode operand = field(expr, "operand");
            String name = file.text(field(expr, "field"));
            String importPath = importOf(fn, operand);
            if (importPath != null) {
                GoPackage pkg = packages.get(importPath);
                return pkg == null ? null : pkg.functions.get(name);
            }
            GoType type = types.get(typeOf(fn, operand));
            return type == null ? null : findMethod(type, name, 0);
        }
        return null;
    }

    /** 상수로 보는 식: 리터럴, const, 상수끼리 + */
    boolean constant(GoFunc fn, TSNode expr, int depth) {
        expr = GoFile.unwrap(expr);
        if (expr == null || depth > 6) {
            return false;
        }
        GoFile file = fn.file();
        switch (expr.getType()) {
            case "interpreted_string_literal", "raw_string_literal", "int_literal", "float_literal", "true", "false", "nil",
                 "rune_literal", "imaginary_literal" -> {
                return true;
            }
            case "identifier" -> {
                String name = file.text(expr);
                Local local = local(fn, name);
                if (local != null) {
                    return "const".equals(local.declaredType());
                }
                GoPackage pkg = packageOf(file);
                return pkg != null && pkg.consts.containsKey(name);
            }
            case "selector_expression" -> {
                String importPath = importOf(fn, field(expr, "operand"));
                GoPackage pkg = importPath == null ? null : packages.get(importPath);
                return pkg != null && pkg.consts.containsKey(file.text(field(expr, "field")));
            }
            case "binary_expression" -> {
                return constant(fn, field(expr, "left"), depth + 1) && constant(fn, field(expr, "right"), depth + 1);
            }
            case "composite_literal" -> {
                for (TSNode element : GoFile.descendants(field(expr, "body"), Set.of("literal_element"), Set.of("literal_element"))) {
                    List<TSNode> inner = namedChildren(element);
                    if (!inner.isEmpty() && !constant(fn, inner.get(0), depth + 1)) {
                        return false;
                    }
                }
                return true;
            }
            case "call_expression" -> {
                // []string{...} 변환이나 filepath.Join("a", "b") 처럼 인자가 모두 상수
                List<TSNode> args = namedChildren(field(expr, "arguments"));
                return !args.isEmpty() && args.stream().allMatch(a -> constant(fn, a, depth + 1))
                        && Set.of("filepath.Join", "path.Join", "fmt.Sprintf", "string", "strings.Join")
                        .contains(file.text(field(expr, "function")));
            }
            default -> {
                return false;
            }
        }
    }
}
