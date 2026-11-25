package com.codeagent.core.kotlin;

import com.codeagent.core.SourceFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterKotlin;

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
import java.util.concurrent.ConcurrentHashMap;

import static com.codeagent.core.kotlin.KotlinFile.children;
import static com.codeagent.core.kotlin.KotlinFile.firstChild;
import static com.codeagent.core.kotlin.KotlinFile.namedChildren;

/**
 * 저장소의 Kotlin 소스를 tree-sitter 로 파싱해서 타입·함수 색인을 만든다. 라이브러리 jar 없이 import 문으로 타입 이름을 푼다.
 * 타입 추론은 하지 않는다: 선언에 적힌 타입, 생성자 호출(Foo(...)), 알려진 프레임워크 반환 타입만 따라간다.
 */
public final class KotlinProject {

    private static final Logger log = LoggerFactory.getLogger(KotlinProject.class);

    public static final Set<String> SKIP_DIRS = Set.of(".git", ".gradle", ".idea", "build", "out", "target", "node_modules",
            "generated", "intermediates", "test", "androidTest", "testDebug", "testRelease");
    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final Map<String, Boolean> JDK_TYPE_CACHE = new ConcurrentHashMap<>();

    /**
     * Kotlin 클래스·object·interface. 파일 최상위 함수는 Kotlin 관례대로 "패키지.파일이름Kt" 타입에 넣는다.
     *
     * @param declaration class, interface, object, file
     */
    public record KotlinType(String fqn, String simpleName, String declaration, KotlinFile file, int startLine, int endLine,
                             List<String> superTypes, List<KotlinFunction> functions, Map<String, String> propertyTypes,
                             List<String> annotations) {

        public String filePath() {
            return file.filePath;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof KotlinType other && fqn.equals(other.fqn);
        }

        @Override
        public int hashCode() {
            return fqn.hashCode();
        }

        @Override
        public String toString() {
            return fqn;
        }
    }

    /**
     * @param key        Class#name(Type,Type) (Java 와 같은 모양, 파라미터 타입은 소스에 적힌 짧은 이름)
     * @param paramTypes 파라미터 이름 → 타입 FQN (모르면 짧은 이름)
     * @param annotations 애너테이션 이름 (FQN 으로 풀리면 FQN)
     */
    public record KotlinFunction(String key, String name, Map<String, String> paramTypes, int startLine, int endLine,
                                 TSNode node, KotlinType owner, List<String> annotations) {

        @Override
        public boolean equals(Object o) {
            return o instanceof KotlinFunction other && key.equals(other.key);
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
    private final List<KotlinFile> files = new ArrayList<>();
    private final Map<String, KotlinType> types = new LinkedHashMap<>();
    /** 같은 앱의 다른 언어(Java) 타입: 같은 패키지·와일드카드 import 로 쓴 이름을 풀 때 쓴다 */
    private final Set<String> externalTypes = new java.util.HashSet<>();

    private KotlinProject(Path root) {
        this.root = root;
    }

    /** 이 OS/CPU 에서 Kotlin 파서(네이티브 라이브러리)를 쓸 수 있는지 */
    public static boolean available() {
        try {
            new TSParser().setLanguage(new TreeSitterKotlin());
            return true;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    public static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".kt") && !name.endsWith("Test.kt") && !name.endsWith("Tests.kt");
    }

    /**
     * 저장소의 .kt 파일을 읽는다. 파서를 불러오지 못하면 빈 프로젝트를 돌려주고 parseErrors 에 남긴다.
     */
    public static KotlinProject load(Path root, List<String> parseErrors) {
        KotlinProject project = new KotlinProject(root.toAbsolutePath().normalize());
        TSParser parser;
        try {
            parser = new TSParser();
            parser.setLanguage(new TreeSitterKotlin());
        } catch (LinkageError | RuntimeException e) {
            parseErrors.add("(kotlin): Kotlin 파서를 불러오지 못해 .kt 파일을 건너뜀 (지원하지 않는 OS/CPU 일 수 있음: "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + ") " + e);
            return project;
        }
        for (Path file : SourceFiles.find(project.root, SKIP_DIRS, MAX_FILE_BYTES, KotlinProject::isSource)) {
            String relative = SourceFiles.relative(project.root, file);
            try {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                TSTree tree = parser.parseString(null, text);
                KotlinFile kf = new KotlinFile(relative, text.getBytes(StandardCharsets.UTF_8), tree);
                if (kf.root.hasError()) {
                    parseErrors.add(relative + ": 문법 오류가 있는 부분만 건너뛰고 분석함");
                }
                project.files.add(kf);
            } catch (IOException | RuntimeException e) {
                parseErrors.add(relative + ": 파싱 실패 (" + e.getMessage() + ")");
                log.debug("Kotlin 파싱 실패: {}", relative, e);
            }
        }
        // 1단계: 타입 이름 (같은 패키지 이름 풀기에 쓴다), 2단계: 함수·프로퍼티 색인
        project.files.forEach(f -> project.collectTypes(f, f.root, f.packageName, true));
        project.files.forEach(f -> project.collectTypes(f, f.root, f.packageName, false));
        return project;
    }

    public Path root() {
        return root;
    }

    public List<KotlinFile> files() {
        return files;
    }

    public Collection<KotlinType> types() {
        return types.values();
    }

    public Optional<KotlinType> type(String fqn) {
        return Optional.ofNullable(fqn).map(types::get);
    }

    public boolean isProjectType(String fqn) {
        return fqn != null && types.containsKey(fqn);
    }

    /** 프로젝트 안에 있는 상위 타입 (자기 자신 제외, 가까운 순) */
    public List<KotlinType> ancestors(KotlinType type) {
        List<KotlinType> result = new ArrayList<>();
        Set<String> visited = new HashSet<>(Set.of(type.fqn()));
        Deque<String> queue = new ArrayDeque<>(type.superTypes());
        while (!queue.isEmpty()) {
            String fqn = queue.poll();
            if (!visited.add(fqn)) {
                continue;
            }
            KotlinType ancestor = types.get(fqn);
            if (ancestor != null) {
                result.add(ancestor);
                queue.addAll(ancestor.superTypes());
            }
        }
        return result;
    }

    /** 타입과 프로젝트 안의 상위 타입들이 상속·구현하는 모든 타입 이름 (프로젝트 밖 프레임워크 타입 포함) */
    public Set<String> lineage(KotlinType type) {
        Set<String> result = new LinkedHashSet<>(type.superTypes());
        ancestors(type).forEach(a -> result.addAll(a.superTypes()));
        return result;
    }

    /** 타입과 상위 타입에서 이름·인자 수가 맞는 함수 (가장 가까운 타입에서 찾은 것만) */
    public List<KotlinFunction> findFunctions(String typeFqn, String name, int argCount) {
        KotlinType type = types.get(typeFqn);
        if (type == null) {
            return List.of();
        }
        List<KotlinType> candidates = new ArrayList<>();
        candidates.add(type);
        candidates.addAll(ancestors(type));
        for (KotlinType candidate : candidates) {
            List<KotlinFunction> found = candidate.functions().stream()
                    .filter(f -> f.name().equals(name) && (argCount < 0 || f.paramTypes().size() == argCount))
                    .toList();
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    /** 타입(과 상위 타입)에 선언된 프로퍼티의 타입 */
    public String propertyType(KotlinType type, String name) {
        List<KotlinType> candidates = new ArrayList<>();
        candidates.add(type);
        candidates.addAll(ancestors(type));
        for (KotlinType candidate : candidates) {
            String t = candidate.propertyTypes().get(name);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /** 이 파일에 적힌 짧은 타입 이름을 FQN 으로. 모르면 null */
    /** 같은 앱의 Java 타입 이름을 더한다 (import 없이 같은 패키지에서 쓴 Java 클래스를 풀기 위해) */
    public void addExternalTypes(Collection<String> fqns) {
        externalTypes.addAll(fqns);
    }

    private boolean known(String fqn) {
        return types.containsKey(fqn) || externalTypes.contains(fqn);
    }

    public String resolve(KotlinFile file, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String clean = name.replaceAll("<.*", "").replace("?", "").strip();
        int dot = clean.indexOf('.');
        String head = dot < 0 ? clean : clean.substring(0, dot);
        String tail = dot < 0 ? "" : clean.substring(dot);
        String imported = file.imports.get(head);
        if (imported != null) {
            return imported + tail;
        }
        String samePackage = file.packageName.isEmpty() ? head : file.packageName + "." + head;
        if (known(samePackage + tail)) {
            return samePackage + tail;
        }
        if (known(samePackage)) {
            return samePackage + tail;
        }
        for (String pkg : file.wildcardImports) {
            String candidate = pkg + "." + head;
            if (known(candidate) || isJdkType(candidate)) {
                return candidate + tail;
            }
        }
        if (isJdkType("java.lang." + head)) {
            return "java.lang." + head + tail;
        }
        // 소문자로 시작하면 패키지가 붙은 이름으로 본다 (java.io.File)
        return dot > 0 && Character.isLowerCase(head.charAt(0)) ? clean : null;
    }

    // ---- 색인 ----

    private void collectTypes(KotlinFile file, TSNode container, String scope, boolean namesOnly) {
        List<TSNode> topLevelFunctions = new ArrayList<>();
        List<TSNode> topLevelProperties = new ArrayList<>();
        for (TSNode child : namedChildren(container)) {
            switch (child.getType()) {
                case "class_declaration", "object_declaration" -> addType(file, child, scope, namesOnly);
                case "function_declaration" -> topLevelFunctions.add(child);
                case "property_declaration" -> topLevelProperties.add(child);
                default -> {
                }
            }
        }
        if (container == file.root && (!topLevelFunctions.isEmpty() || !topLevelProperties.isEmpty())) {
            String fileName = file.filePath.substring(file.filePath.lastIndexOf('/') + 1).replace(".kt", "");
            String fqn = (scope.isEmpty() ? "" : scope + ".") + Character.toUpperCase(fileName.charAt(0)) + fileName.substring(1) + "Kt";
            if (namesOnly) {
                types.putIfAbsent(fqn, null);
                return;
            }
            KotlinType type = new KotlinType(fqn, fqn.substring(fqn.lastIndexOf('.') + 1), "file", file, 1,
                    KotlinFile.endLine(file.root), List.of(), new ArrayList<>(), new LinkedHashMap<>(), List.of());
            topLevelProperties.forEach(p -> addProperty(file, type, p));
            topLevelFunctions.forEach(f -> type.functions().add(function(file, type, f)));
            types.put(fqn, type);
        }
    }

    private void addType(KotlinFile file, TSNode decl, String scope, boolean namesOnly) {
        TSNode nameNode = firstChild(decl, "type_identifier");
        if (nameNode == null) {
            return;
        }
        String simple = file.text(nameNode);
        String fqn = scope.isEmpty() ? simple : scope + "." + simple;
        TSNode body = firstChild(decl, "class_body");
        if (body == null) {
            body = firstChild(decl, "enum_class_body");
        }
        if (namesOnly) {
            types.putIfAbsent(fqn, null);
            if (body != null) {
                for (TSNode member : namedChildren(body)) {
                    if (member.getType().equals("class_declaration") || member.getType().equals("object_declaration")) {
                        addType(file, member, fqn, true);
                    }
                }
            }
            return;
        }
        String declaration = decl.getType().equals("object_declaration") ? "object" : hasKeyword(decl, "interface") ? "interface" : "class";
        List<String> superTypes = new ArrayList<>();
        for (TSNode spec : children(decl, "delegation_specifier")) {
            TSNode userType = firstChild(spec, "user_type");
            if (userType == null) {
                TSNode invocation = firstChild(spec, "constructor_invocation");
                userType = invocation == null ? null : firstChild(invocation, "user_type");
            }
            if (userType != null) {
                String resolved = resolve(file, file.text(userType));
                superTypes.add(resolved != null ? resolved : file.text(userType).replaceAll("<.*", ""));
            }
        }
        KotlinType type = new KotlinType(fqn, simple, declaration, file, KotlinFile.line(decl), KotlinFile.endLine(decl),
                List.copyOf(superTypes), new ArrayList<>(), new LinkedHashMap<>(), annotations(file, decl));
        types.put(fqn, type);
        // 주 생성자의 val/var 파라미터는 프로퍼티다
        TSNode primary = firstChild(decl, "primary_constructor");
        if (primary != null) {
            TSNode params = firstChild(primary, "class_parameters");
            for (TSNode param : children(params == null ? primary : params, "class_parameter")) {
                TSNode name = firstChild(param, "simple_identifier");
                if (name != null) {
                    type.propertyTypes().put(file.text(name), typeOf(file, param));
                }
            }
        }
        if (body == null) {
            return;
        }
        for (TSNode member : namedChildren(body)) {
            switch (member.getType()) {
                case "function_declaration" -> type.functions().add(function(file, type, member));
                case "property_declaration" -> addProperty(file, type, member);
                case "class_declaration", "object_declaration" -> addType(file, member, fqn, false);
                case "companion_object" -> {
                    // companion 의 함수·프로퍼티는 바깥 클래스 이름으로 부른다 (Foo.make())
                    TSNode companionBody = firstChild(member, "class_body");
                    for (TSNode m : namedChildren(companionBody)) {
                        if (m.getType().equals("function_declaration")) {
                            type.functions().add(function(file, type, m));
                        } else if (m.getType().equals("property_declaration")) {
                            addProperty(file, type, m);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private void addProperty(KotlinFile file, KotlinType type, TSNode property) {
        TSNode variable = firstChild(property, "variable_declaration");
        TSNode name = variable == null ? null : firstChild(variable, "simple_identifier");
        if (name == null) {
            return;
        }
        String declared = typeOf(file, variable);
        if (declared == null) {
            declared = constructedType(file, firstChild(property, "call_expression"));
        }
        if (declared != null) {
            type.propertyTypes().put(file.text(name), declared);
        }
    }

    private KotlinFunction function(KotlinFile file, KotlinType owner, TSNode decl) {
        TSNode nameNode = firstChild(decl, "simple_identifier");
        String name = nameNode == null ? "<anonymous>" : file.text(nameNode);
        Map<String, String> params = new LinkedHashMap<>();
        List<String> shortTypes = new ArrayList<>();
        TSNode paramList = firstChild(decl, "function_value_parameters");
        for (TSNode param : children(paramList, "parameter")) {
            TSNode pName = firstChild(param, "simple_identifier");
            String written = writtenType(file, param);
            shortTypes.add(written == null ? "?" : written.substring(written.lastIndexOf('.') + 1));
            if (pName != null) {
                String resolved = written == null ? null : resolve(file, written);
                params.put(file.text(pName), resolved != null ? resolved : written);
            }
        }
        String key = owner.fqn() + "#" + name + "(" + String.join(",", shortTypes) + ")";
        return new KotlinFunction(key, name, params, KotlinFile.line(decl), KotlinFile.endLine(decl), decl, owner,
                annotations(file, decl));
    }

    /** 선언에 적힌 타입 (user_type 또는 nullable_type). 제네릭·? 는 뗀다 */
    static String writtenType(KotlinFile file, TSNode holder) {
        TSNode t = firstChild(holder, "user_type");
        if (t == null) {
            TSNode nullable = firstChild(holder, "nullable_type");
            t = nullable == null ? null : firstChild(nullable, "user_type");
        }
        return t == null ? null : file.text(t).replaceAll("<.*", "").replace("?", "").strip();
    }

    /** 선언에 적힌 타입을 FQN 으로 */
    String typeOf(KotlinFile file, TSNode holder) {
        String written = writtenType(file, holder);
        if (written == null) {
            return null;
        }
        String resolved = resolve(file, written);
        return resolved != null ? resolved : written;
    }

    /** Foo(...) 처럼 대문자 이름을 부르는 생성자 호출이면 그 타입 */
    String constructedType(KotlinFile file, TSNode call) {
        if (KotlinFile.isNull(call) || call == null) {
            return null;
        }
        TSNode callee = namedChildren(call).isEmpty() ? null : namedChildren(call).get(0);
        if (callee != null && callee.getType().equals("simple_identifier")) {
            String name = file.text(callee);
            if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                return resolve(file, name);
            }
        }
        return null;
    }

    private List<String> annotations(KotlinFile file, TSNode decl) {
        TSNode modifiers = firstChild(decl, "modifiers");
        List<String> result = new ArrayList<>();
        for (TSNode annotation : children(modifiers, "annotation")) {
            TSNode userType = KotlinFile.descendants(annotation, "user_type", null).stream().findFirst().orElse(null);
            if (userType != null) {
                String written = file.text(userType);
                String resolved = resolve(file, written);
                result.add(resolved != null ? resolved : written);
            }
        }
        return List.copyOf(result);
    }

    /** 이름 없는 자식(키워드) 중에 keyword 가 있는지 (class_declaration 의 interface 등) */
    private static boolean hasKeyword(TSNode node, String keyword) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i).getType().equals(keyword)) {
                return true;
            }
        }
        return false;
    }

    static boolean isJdkType(String fqn) {
        return JDK_TYPE_CACHE.computeIfAbsent(fqn, n -> {
            try {
                Class.forName(n, false, ClassLoader.getPlatformClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        });
    }
}
