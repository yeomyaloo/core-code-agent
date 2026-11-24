package com.codeagent.core.swift;

import com.codeagent.core.SourceFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterSwift;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.codeagent.core.swift.SwiftFile.children;
import static com.codeagent.core.swift.SwiftFile.field;
import static com.codeagent.core.swift.SwiftFile.firstChild;
import static com.codeagent.core.swift.SwiftFile.namedChildren;

/**
 * 저장소의 Swift 소스를 tree-sitter 로 파싱해서 타입·함수 색인을 만든다.
 * Swift 는 모듈 안에서 타입 이름이 하나뿐이라 짧은 이름(AppDelegate, UserDefaults)을 그대로 타입 이름으로 쓴다.
 * extension 의 함수는 원래 타입에 합친다. 타입 추론은 하지 않는다: 적힌 타입, 생성자 호출(Foo(...)), 싱글턴(Foo.shared)만 따라간다.
 */
public final class SwiftProject {

    private static final Logger log = LoggerFactory.getLogger(SwiftProject.class);

    /** Pods·Carthage·빌드 결과물·SwiftPM 체크아웃은 남의 코드이고, …Tests 는 테스트 번들이다 */
    public static final Set<String> SKIP_DIRS = Set.of(".git", "Pods", "Carthage", "DerivedData", "build", ".build",
            "node_modules", "fastlane", "vendor", "checkouts");
    private static final long MAX_FILE_BYTES = 1_000_000;

    /**
     * @param declaration class, struct, enum, actor, protocol, extension(원래 타입이 프로젝트 밖일 때), file(파일 최상위 함수)
     */
    public record SwiftType(String name, String declaration, String filePath, int startLine, int endLine,
                            List<String> superTypes, List<SwiftFunction> functions, Map<String, String> propertyTypes) {

        @Override
        public boolean equals(Object o) {
            return o instanceof SwiftType other && name.equals(other.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * @param key        Type#name(label:label:) (Swift 식 이름. 레이블이 없으면 _)
     * @param paramTypes 파라미터 이름 → 타입
     */
    public record SwiftFunction(String key, String name, Map<String, String> paramTypes, int startLine, int endLine,
                                TSNode node, SwiftType owner, SwiftFile file, List<String> attributes) {

        @Override
        public boolean equals(Object o) {
            return o instanceof SwiftFunction other && key.equals(other.key);
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

    private final Path root;
    private final List<SwiftFile> files = new ArrayList<>();
    private final Map<String, SwiftType> types = new LinkedHashMap<>();

    private SwiftProject(Path root) {
        this.root = root;
    }

    /** 이 OS/CPU 에서 Swift 파서(네이티브 라이브러리)를 쓸 수 있는지 */
    public static boolean available() {
        try {
            new TSParser().setLanguage(new TreeSitterSwift());
            return true;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    public static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".swift") && !name.endsWith("Tests.swift") && !name.equals("Package.swift");
    }

    public static SwiftProject load(Path root, List<String> parseErrors) {
        SwiftProject project = new SwiftProject(root.toAbsolutePath().normalize());
        TSParser parser;
        try {
            parser = new TSParser();
            parser.setLanguage(new TreeSitterSwift());
        } catch (LinkageError | RuntimeException e) {
            parseErrors.add("(swift): Swift 파서를 불러오지 못해 .swift 파일을 건너뜀 (지원하지 않는 OS/CPU 일 수 있음: "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + ") " + e);
            return project;
        }
        for (Path file : SourceFiles.find(project.root, SKIP_DIRS, MAX_FILE_BYTES,
                p -> isSource(p) && !inTestBundle(project.root.relativize(p)))) {
            String relative = SourceFiles.relative(project.root, file);
            try {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                TSTree tree = parser.parseString(null, text);
                SwiftFile sf = new SwiftFile(relative, text.getBytes(StandardCharsets.UTF_8), tree);
                if (sf.root.hasError()) {
                    parseErrors.add(relative + ": 문법 오류가 있는 부분만 건너뛰고 분석함");
                }
                project.files.add(sf);
            } catch (IOException | RuntimeException e) {
                parseErrors.add(relative + ": 파싱 실패 (" + e.getMessage() + ")");
                log.debug("Swift 파싱 실패: {}", relative, e);
            }
        }
        // 1단계: 타입 이름 (extension 은 원래 타입이 프로젝트에 있으면 거기 합친다), 2단계: 함수·프로퍼티
        project.files.forEach(f -> project.collect(f, f.root, null, true));
        project.files.forEach(f -> project.collect(f, f.root, null, false));
        return project;
    }

    /** BankTests/, BankUITests/ 같은 테스트 번들 폴더 */
    private static boolean inTestBundle(Path relative) {
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (relative.getName(i).toString().endsWith("Tests")) {
                return true;
            }
        }
        return false;
    }

    public Path root() {
        return root;
    }

    public List<SwiftFile> files() {
        return files;
    }

    public Collection<SwiftType> types() {
        return types.values().stream().filter(t -> t != null).toList();
    }

    public Optional<SwiftType> type(String name) {
        return Optional.ofNullable(name).map(types::get);
    }

    public boolean isProjectType(String name) {
        return name != null && types.get(name) != null;
    }

    public List<SwiftType> ancestors(SwiftType type) {
        List<SwiftType> result = new ArrayList<>();
        Set<String> visited = new HashSet<>(Set.of(type.name()));
        Deque<String> queue = new ArrayDeque<>(type.superTypes());
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!visited.add(name)) {
                continue;
            }
            SwiftType ancestor = types.get(name);
            if (ancestor != null) {
                result.add(ancestor);
                queue.addAll(ancestor.superTypes());
            }
        }
        return result;
    }

    /** 타입과 프로젝트 안의 상위 타입들이 상속·채택하는 모든 타입 이름 (UIKit·WebKit 프로토콜 포함) */
    public Set<String> lineage(SwiftType type) {
        Set<String> result = new LinkedHashSet<>(type.superTypes());
        ancestors(type).forEach(a -> result.addAll(a.superTypes()));
        return result;
    }

    /** 타입과 상위 타입에서 이름·인자 수가 맞는 함수 (가장 가까운 타입에서 찾은 것만) */
    public List<SwiftFunction> findFunctions(String typeName, String name, int argCount) {
        SwiftType type = types.get(typeName);
        if (type == null) {
            return List.of();
        }
        List<SwiftType> candidates = new ArrayList<>();
        candidates.add(type);
        candidates.addAll(ancestors(type));
        for (SwiftType candidate : candidates) {
            List<SwiftFunction> found = candidate.functions().stream()
                    .filter(f -> f.name().equals(name) && (argCount < 0 || f.paramTypes().size() == argCount))
                    .toList();
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    public String propertyType(SwiftType type, String name) {
        List<SwiftType> candidates = new ArrayList<>();
        candidates.add(type);
        candidates.addAll(ancestors(type));
        for (SwiftType candidate : candidates) {
            String t = candidate.propertyTypes().get(name);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /** 파일·줄을 감싼 가장 좁은 함수 */
    public Optional<SwiftFunction> functionAt(String filePath, int line) {
        return types().stream()
                .flatMap(t -> t.functions().stream())
                .filter(f -> f.file().filePath.equals(filePath) && f.startLine() <= line && f.endLine() >= line)
                .min((a, b) -> Integer.compare(a.endLine() - a.startLine(), b.endLine() - b.startLine()));
    }

    // ---- 색인 ----

    private void collect(SwiftFile file, TSNode container, String outer, boolean namesOnly) {
        List<TSNode> topFunctions = new ArrayList<>();
        for (TSNode child : namedChildren(container)) {
            switch (child.getType()) {
                case "class_declaration", "protocol_declaration" -> addType(file, child, outer, namesOnly);
                case "function_declaration" -> topFunctions.add(child);
                default -> {
                }
            }
        }
        if (outer == null && !topFunctions.isEmpty()) {
            String stem = file.filePath.substring(file.filePath.lastIndexOf('/') + 1).replace(".swift", "");
            String name = stem + "Swift";
            if (namesOnly) {
                types.putIfAbsent(name, null);
                return;
            }
            SwiftType type = new SwiftType(name, "file", file.filePath, 1, SwiftFile.endLine(file.root), List.of(),
                    new ArrayList<>(), new LinkedHashMap<>());
            topFunctions.forEach(f -> type.functions().add(function(file, type, f)));
            types.put(name, type);
        }
    }

    private void addType(SwiftFile file, TSNode decl, String outer, boolean namesOnly) {
        TSNode nameNode = field(decl, "name");
        if (nameNode == null) {
            return;
        }
        String written = file.text(nameNode).replaceAll("<.*", "").strip();
        boolean extension = SwiftFile.hasKeyword(decl, "extension");
        String name = extension || outer == null ? written : outer + "." + written;
        TSNode body = field(decl, "body");
        if (namesOnly) {
            if (!extension) {
                types.put(name, null);
            }
            for (TSNode member : namedChildren(body)) {
                if (member.getType().equals("class_declaration") || member.getType().equals("protocol_declaration")) {
                    addType(file, member, name, true);
                }
            }
            return;
        }
        SwiftType type = types.get(name);
        if (type == null) {
            String declaration = decl.getType().equals("protocol_declaration") ? "protocol"
                    : extension ? "extension"
                    : SwiftFile.hasKeyword(decl, "struct") ? "struct"
                    : SwiftFile.hasKeyword(decl, "enum") ? "enum"
                    : SwiftFile.hasKeyword(decl, "actor") ? "actor" : "class";
            type = new SwiftType(name, declaration, file.filePath, SwiftFile.line(decl), SwiftFile.endLine(decl),
                    new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>());
            types.put(name, type);
        }
        for (TSNode spec : children(decl, "inheritance_specifier")) {
            TSNode t = field(spec, "inherits_from");
            String inherits = t == null ? file.text(spec) : file.text(t);
            String clean = inherits.replaceAll("<.*", "").strip();
            if (!clean.isEmpty() && !type.superTypes().contains(clean)) {
                type.superTypes().add(clean);
            }
        }
        for (TSNode member : namedChildren(body)) {
            switch (member.getType()) {
                case "function_declaration", "protocol_function_declaration", "init_declaration" ->
                        type.functions().add(function(file, type, member));
                case "property_declaration", "protocol_property_declaration" -> addProperty(file, type, member);
                case "class_declaration", "protocol_declaration" -> addType(file, member, name, false);
                default -> {
                }
            }
        }
    }

    private void addProperty(SwiftFile file, SwiftType type, TSNode property) {
        TSNode pattern = field(property, "name");
        TSNode bound = pattern == null ? null : field(pattern, "bound_identifier");
        if (bound == null) {
            bound = pattern == null ? null : SwiftFile.descendants(pattern, "simple_identifier", null).stream().findFirst().orElse(null);
        }
        if (bound == null) {
            return;
        }
        String declared = annotatedType(file, firstChild(property, "type_annotation"));
        if (declared == null) {
            declared = constructedType(file, field(property, "value"));
        }
        if (declared != null) {
            type.propertyTypes().put(file.text(bound), declared);
        }
    }

    private SwiftFunction function(SwiftFile file, SwiftType owner, TSNode decl) {
        String name = decl.getType().equals("init_declaration") ? "init" : file.text(field(decl, "name"));
        Map<String, String> params = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        for (TSNode param : children(decl, "parameter")) {
            List<TSNode> ids = children(param, "simple_identifier");
            TSNode external = field(param, "external_name");
            String local = ids.isEmpty() ? "_" : file.text(ids.get(ids.size() - 1));
            labels.add(external != null ? file.text(external) : local);
            params.put(local, typeText(file, param));
        }
        String key = owner.name() + "#" + name + "(" + labels.stream().map(l -> l + ":").reduce("", String::concat) + ")";
        List<String> attributes = new ArrayList<>();
        TSNode modifiers = firstChild(decl, "modifiers");
        for (TSNode attribute : children(modifiers, "attribute")) {
            attributes.add(file.text(attribute).replaceFirst("^@", "").replaceAll("\\(.*", "").strip());
        }
        return new SwiftFunction(key, name, params, SwiftFile.line(decl), SwiftFile.endLine(decl), decl, owner, file,
                List.copyOf(attributes));
    }

    /** 파라미터에 적힌 타입 (Foo, Foo?, [Foo] → Foo / 컬렉션은 원문) */
    private static String typeText(SwiftFile file, TSNode param) {
        for (TSNode child : namedChildren(param)) {
            switch (child.getType()) {
                case "user_type" -> {
                    return file.text(child).replaceAll("<.*", "").strip();
                }
                case "optional_type", "implicitly_unwrapped_type" -> {
                    TSNode wrapped = field(child, "wrapped");
                    return wrapped == null ? file.text(child).replace("?", "").replace("!", "") : file.text(wrapped).replaceAll("<.*", "");
                }
                case "array_type", "dictionary_type", "function_type", "tuple_type" -> {
                    return file.text(child).replaceAll("\\s+", "");
                }
                default -> {
                }
            }
        }
        return "?";
    }

    /** : Foo / : Foo? 의 타입 */
    String annotatedType(SwiftFile file, TSNode annotation) {
        if (annotation == null) {
            return null;
        }
        TSNode t = field(annotation, "name");
        if (t == null) {
            return null;
        }
        if (t.getType().equals("optional_type") || t.getType().equals("implicitly_unwrapped_type")) {
            TSNode wrapped = field(t, "wrapped");
            return wrapped == null ? null : file.text(wrapped).replaceAll("<.*", "").strip();
        }
        return t.getType().equals("user_type") ? file.text(t).replaceAll("<.*", "").strip() : null;
    }

    /** Foo(...) 처럼 대문자 이름을 부르는 생성자 호출이면 그 타입 (프로젝트 밖 타입도 이름 그대로) */
    String constructedType(SwiftFile file, TSNode expr) {
        if (expr == null || SwiftFile.isNull(expr) || !expr.getType().equals("call_expression")) {
            return null;
        }
        List<TSNode> parts = namedChildren(expr);
        if (parts.isEmpty() || !parts.get(0).getType().equals("simple_identifier")) {
            return null;
        }
        String name = file.text(parts.get(0));
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0)) ? name : null;
    }
}
