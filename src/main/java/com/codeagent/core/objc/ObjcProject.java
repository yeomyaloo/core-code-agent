package com.codeagent.core.objc;

import com.codeagent.core.SourceFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterObjc;

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

import static com.codeagent.core.objc.ObjcFile.children;
import static com.codeagent.core.objc.ObjcFile.field;
import static com.codeagent.core.objc.ObjcFile.firstChild;
import static com.codeagent.core.objc.ObjcFile.namedChildren;

/**
 * 저장소의 Objective-C 소스(.m, .mm, @interface·@protocol 이 있는 .h)를 tree-sitter 로 파싱해서 클래스·메서드 색인을 만든다.
 * 헤더의 @interface(상위 클래스·프로토콜·프로퍼티)와 .m 의 @implementation(메서드 본문), 카테고리를 클래스 이름 하나로 합친다.
 * 메서드 이름은 셀렉터(application:openURL:options:)이고, 파일 최상위 C 함수는 파일마다 만든 가짜 타입(이름Objc) 아래에 둔다.
 */
public final class ObjcProject {

    private static final Logger log = LoggerFactory.getLogger(ObjcProject.class);

    /** Pods·Carthage·빌드 결과물은 남의 코드다 */
    public static final Set<String> SKIP_DIRS = Set.of(".git", "Pods", "Carthage", "DerivedData", "build", ".build",
            "node_modules", "fastlane", "vendor", "checkouts");
    private static final long MAX_FILE_BYTES = 1_000_000;

    /**
     * @param declaration class, protocol, file(파일 최상위 C 함수)
     * @param filePath    @implementation 이 있는 파일 (없으면 처음 본 선언)
     */
    public record ObjcType(String name, String declaration, String filePath, int startLine, int endLine,
                           List<String> superTypes, List<ObjcMethod> methods, Map<String, String> propertyTypes) {

        @Override
        public boolean equals(Object o) {
            return o instanceof ObjcType other && name.equals(other.name);
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
     * @param key        Type#selector (C 함수는 FileObjc#name)
     * @param selector   application:openURL:options: / helper / C 함수 이름
     * @param paramTypes 파라미터 이름 → 타입
     * @param returnType 적힌 반환 타입 (instancetype 은 자기 타입, 모르면 null)
     * @param node       본문이 있는 정의 (헤더의 선언만 있으면 null)
     */
    public record ObjcMethod(String key, String selector, boolean classMethod, Map<String, String> paramTypes,
                             String returnType, int startLine, int endLine, TSNode node, ObjcType owner, ObjcFile file) {

        @Override
        public boolean equals(Object o) {
            return o instanceof ObjcMethod other && key.equals(other.key);
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
    private final List<ObjcFile> files = new ArrayList<>();
    private final Map<String, ObjcType> types = new LinkedHashMap<>();

    private ObjcProject(Path root) {
        this.root = root;
    }

    /** 이 OS/CPU 에서 Objective-C 파서(네이티브 라이브러리)를 쓸 수 있는지 */
    public static boolean available() {
        try {
            new TSParser().setLanguage(new TreeSitterObjc());
            return true;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    public static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return (name.endsWith(".m") || name.endsWith(".mm") || name.endsWith(".h")) && !name.endsWith("Tests.m");
    }

    public static ObjcProject load(Path root, List<String> parseErrors) {
        ObjcProject project = new ObjcProject(root.toAbsolutePath().normalize());
        TSParser parser;
        try {
            parser = new TSParser();
            parser.setLanguage(new TreeSitterObjc());
        } catch (LinkageError | RuntimeException e) {
            parseErrors.add("(objc): Objective-C 파서를 불러오지 못해 .m 파일을 건너뜀 (지원하지 않는 OS/CPU 일 수 있음: "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + ") " + e);
            return project;
        }
        for (Path file : SourceFiles.find(project.root, SKIP_DIRS, MAX_FILE_BYTES,
                p -> isSource(p) && !inTestBundle(project.root.relativize(p)))) {
            String relative = SourceFiles.relative(project.root, file);
            try {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                // C·C++ 헤더는 건너뛴다 (Objective-C 선언이 있는 헤더만)
                if (relative.endsWith(".h") && !text.contains("@interface") && !text.contains("@protocol")) {
                    continue;
                }
                TSTree tree = parser.parseString(null, text);
                ObjcFile of = new ObjcFile(relative, text.getBytes(StandardCharsets.UTF_8), tree);
                if (of.root.hasError()) {
                    parseErrors.add(relative + ": 문법 오류가 있는 부분만 건너뛰고 분석함");
                }
                project.files.add(of);
            } catch (IOException | RuntimeException e) {
                parseErrors.add(relative + ": 파싱 실패 (" + e.getMessage() + ")");
                log.debug("Objective-C 파싱 실패: {}", relative, e);
            }
        }
        // 헤더(선언)를 먼저, 구현을 나중에 본다: 구현의 메서드가 선언을 대신한다
        project.files.stream().filter(f -> f.filePath.endsWith(".h")).forEach(project::collect);
        project.files.stream().filter(f -> !f.filePath.endsWith(".h")).forEach(project::collect);
        return project;
    }

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

    public List<ObjcFile> files() {
        return files;
    }

    public Collection<ObjcType> types() {
        return types.values();
    }

    public Optional<ObjcType> type(String name) {
        return Optional.ofNullable(name).map(types::get);
    }

    public boolean isProjectType(String name) {
        return name != null && types.containsKey(name);
    }

    public List<ObjcType> ancestors(ObjcType type) {
        List<ObjcType> result = new ArrayList<>();
        Set<String> visited = new HashSet<>(Set.of(type.name()));
        Deque<String> queue = new ArrayDeque<>(type.superTypes());
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!visited.add(name)) {
                continue;
            }
            ObjcType ancestor = types.get(name);
            if (ancestor != null) {
                result.add(ancestor);
                queue.addAll(ancestor.superTypes());
            }
        }
        return result;
    }

    /** 상위 클래스와 채택한 프로토콜 이름 전부 (UIKit·WebKit 것 포함) */
    public Set<String> lineage(ObjcType type) {
        Set<String> result = new LinkedHashSet<>(type.superTypes());
        ancestors(type).forEach(a -> result.addAll(a.superTypes()));
        return result;
    }

    /** 타입과 상위 타입에서 셀렉터가 같은 메서드 (가장 가까운 타입에서 찾은 것) */
    public Optional<ObjcMethod> findMethod(String typeName, String selector) {
        ObjcType type = types.get(typeName);
        if (type == null) {
            return Optional.empty();
        }
        List<ObjcType> candidates = new ArrayList<>();
        candidates.add(type);
        candidates.addAll(ancestors(type));
        for (ObjcType candidate : candidates) {
            Optional<ObjcMethod> found = candidate.methods().stream().filter(m -> m.selector().equals(selector)).findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** 프로젝트 안의 C 함수 */
    public Optional<ObjcMethod> findFunction(String name) {
        return types.values().stream()
                .filter(t -> t.declaration().equals("file"))
                .flatMap(t -> t.methods().stream())
                .filter(m -> m.selector().equals(name))
                .findFirst();
    }

    public String propertyType(ObjcType type, String name) {
        List<ObjcType> candidates = new ArrayList<>();
        candidates.add(type);
        candidates.addAll(ancestors(type));
        for (ObjcType candidate : candidates) {
            String t = candidate.propertyTypes().get(name);
            if (t == null && name.startsWith("_")) {
                // 프로퍼티의 인스턴스 변수 (_db)
                t = candidate.propertyTypes().get(name.substring(1));
            }
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /** 파일·줄을 감싼 가장 좁은 메서드·함수 (본문이 있는 것만) */
    public Optional<ObjcMethod> methodAt(String filePath, int line) {
        return types.values().stream()
                .flatMap(t -> t.methods().stream())
                .filter(m -> m.node() != null && m.file().filePath.equals(filePath) && m.startLine() <= line && m.endLine() >= line)
                .min((a, b) -> Integer.compare(a.endLine() - a.startLine(), b.endLine() - b.startLine()));
    }

    // ---- 색인 ----

    private void collect(ObjcFile file) {
        List<TSNode> functions = new ArrayList<>();
        for (TSNode child : namedChildren(file.root)) {
            switch (child.getType()) {
                case "class_interface", "category_interface", "class_implementation", "category_implementation" ->
                        addClass(file, child);
                case "protocol_declaration" -> addProtocol(file, child);
                case "function_definition" -> functions.add(child);
                default -> {
                }
            }
        }
        if (functions.isEmpty()) {
            return;
        }
        String stem = file.filePath.substring(file.filePath.lastIndexOf('/') + 1).replaceAll("\\.[^.]+$", "");
        ObjcType type = types.computeIfAbsent(stem + "Objc", n -> new ObjcType(n, "file", file.filePath, 1,
                ObjcFile.endLine(file.root), List.of(), new ArrayList<>(), new LinkedHashMap<>()));
        for (TSNode function : functions) {
            TSNode declarator = field(function, "declarator");
            TSNode nameNode = declarator == null ? null : field(declarator, "declarator");
            if (nameNode == null || !nameNode.getType().equals("identifier")) {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (TSNode param : children(field(declarator, "parameters"), "parameter_declaration")) {
                String name = declaredName(file, field(param, "declarator"));
                if (name != null) {
                    params.put(name, typeName(file, field(param, "type")));
                }
            }
            String name = file.text(nameNode);
            type.methods().add(new ObjcMethod(type.name() + "#" + name, name, false, params,
                    typeName(file, field(function, "type")), ObjcFile.line(function), ObjcFile.endLine(function), function, type, file));
        }
    }

    private ObjcType typeFor(ObjcFile file, TSNode decl, String name, String declaration) {
        ObjcType type = types.get(name);
        if (type == null) {
            type = new ObjcType(name, declaration, file.filePath, ObjcFile.line(decl), ObjcFile.endLine(decl),
                    new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>());
            types.put(name, type);
        } else if (decl.getType().equals("class_implementation") && type.filePath().endsWith(".h")) {
            // 위치는 구현 파일로 옮긴다
            ObjcType moved = new ObjcType(name, type.declaration(), file.filePath, ObjcFile.line(decl), ObjcFile.endLine(decl),
                    type.superTypes(), type.methods(), type.propertyTypes());
            types.put(name, moved);
            type = moved;
        }
        return type;
    }

    private void addClass(ObjcFile file, TSNode decl) {
        TSNode nameNode = field(decl, "name");
        if (nameNode == null) {
            return;
        }
        ObjcType type = typeFor(file, decl, file.text(nameNode), "class");
        TSNode superclass = firstChild(decl, "superclass_reference");
        if (superclass != null) {
            addSuper(type, file.text(field(superclass, "name")));
        }
        for (TSNode protocols : children(decl, "protocol_qualifiers")) {
            for (TSNode p : namedChildren(protocols)) {
                addSuper(type, file.text(p));
            }
        }
        for (TSNode member : namedChildren(decl)) {
            switch (member.getType()) {
                case "property_declaration" -> addProperty(file, type, member);
                case "method_declaration", "method_definition" -> addMethod(file, type, member);
                case "instance_variables" -> {
                    for (TSNode ivar : ObjcFile.descendants(member, "field_declaration", null)) {
                        addProperty(file, type, ivar);
                    }
                }
                default -> {
                }
            }
        }
    }

    private void addProtocol(ObjcFile file, TSNode decl) {
        TSNode nameNode = field(decl, "name");
        if (nameNode == null) {
            return;
        }
        ObjcType type = typeFor(file, decl, file.text(nameNode), "protocol");
        for (TSNode protocols : children(decl, "protocol_reference_list")) {
            for (TSNode p : namedChildren(protocols)) {
                addSuper(type, file.text(p));
            }
        }
        for (TSNode member : ObjcFile.descendants(decl, "method_declaration", null)) {
            addMethod(file, type, member);
        }
    }

    private static void addSuper(ObjcType type, String name) {
        String clean = name.strip();
        if (!clean.isEmpty() && !type.superTypes().contains(clean)) {
            type.superTypes().add(clean);
        }
    }

    private void addProperty(ObjcFile file, ObjcType type, TSNode property) {
        String t = typeName(file, field(property, "type"));
        TSNode declarator = field(property, "declarator");
        String name = declaredName(file, declarator);
        if (t != null && name != null) {
            type.propertyTypes().put(name, t);
        }
    }

    private void addMethod(ObjcFile file, ObjcType type, TSNode decl) {
        TSNode selectorNode = field(decl, "selector");
        if (selectorNode == null) {
            return;
        }
        StringBuilder selector = new StringBuilder();
        Map<String, String> params = new LinkedHashMap<>();
        if (selectorNode.getType().equals("keyword_selector")) {
            for (TSNode keyword : children(selectorNode, "keyword_declarator")) {
                TSNode kw = field(keyword, "keyword");
                selector.append(kw == null ? "" : file.text(kw)).append(':');
                TSNode name = field(keyword, "name");
                if (name != null) {
                    params.put(file.text(name), keywordType(file, keyword));
                }
            }
        } else {
            selector.append(file.text(selectorNode));
        }
        boolean classMethod = firstChild(decl, "class_scope") != null;
        String returnType = returnType(file, decl);
        if ("instancetype".equals(returnType)) {
            returnType = type.name();
        }
        boolean definition = decl.getType().equals("method_definition");
        String sel = selector.toString();
        ObjcMethod method = new ObjcMethod(type.name() + "#" + sel, sel, classMethod, params, returnType,
                ObjcFile.line(decl), ObjcFile.endLine(decl), definition ? decl : null, type, file);
        int existing = -1;
        for (int i = 0; i < type.methods().size(); i++) {
            if (type.methods().get(i).selector().equals(sel)) {
                existing = i;
            }
        }
        if (existing < 0) {
            type.methods().add(method);
        } else if (definition) {
            // 헤더 선언을 구현으로 바꾼다
            type.methods().set(existing, method);
        }
    }

    /** NSString * / PayeeDatabase * / id / BOOL 의 타입 이름. 포인터·제네릭은 뗀다 */
    String typeName(ObjcFile file, TSNode type) {
        if (type == null) {
            return null;
        }
        TSNode t = type;
        if (t.getType().equals("type_name") || t.getType().equals("method_type")) {
            TSNode inner = firstChild(t, "type_identifier");
            if (inner == null) {
                List<TSNode> named = namedChildren(t);
                inner = named.isEmpty() ? null : named.get(0);
            }
            t = inner;
        }
        if (t == null) {
            return null;
        }
        String text = file.text(t).replaceAll("<.*", "").replace("*", "").strip();
        return text.isEmpty() ? null : text;
    }

    /** (NSURL *)url 의 NSURL. 문법이 괄호도 type 필드로 달아서 이름 있는 자식에서 고른다 */
    private String keywordType(ObjcFile file, TSNode keyword) {
        List<TSNode> named = namedChildren(keyword);
        for (int i = 1; i < named.size() - 1; i++) {
            TSNode child = named.get(i);
            if (!child.getType().endsWith("declarator")) {
                return typeName(file, child);
            }
        }
        return null;
    }

    /** - (BOOL)... 의 반환 타입: 범위(-/+) 다음, 셀렉터 앞의 이름 있는 자식 */
    private String returnType(ObjcFile file, TSNode decl) {
        TSNode selector = field(decl, "selector");
        for (TSNode child : namedChildren(decl)) {
            if (child.getType().endsWith("_scope")) {
                continue;
            }
            if (selector != null && child.getStartByte() >= selector.getStartByte()) {
                return null;
            }
            return typeName(file, child);
        }
        return null;
    }

    /** *name, name, name = ... 선언의 이름 */
    static String declaredName(ObjcFile file, TSNode declarator) {
        TSNode d = declarator;
        while (d != null && !d.getType().equals("identifier") && !d.getType().equals("field_identifier")) {
            TSNode inner = field(d, "declarator");
            if (inner == null) {
                List<TSNode> named = namedChildren(d);
                inner = named.isEmpty() ? null : named.get(0);
            }
            d = inner;
        }
        return d == null ? null : file.text(d);
    }
}
