package com.codeagent.core.python;

import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.python.PythonModule.ClassInfo;
import com.codeagent.core.python.PythonModule.FunctionInfo;
import com.codeagent.core.rules.PythonSinkRules;
import com.codeagent.core.rules.SinkRule;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codeagent.core.python.PythonModule.firstNamedChild;
import static com.codeagent.core.python.PythonModule.isNull;
import static com.codeagent.core.python.PythonModule.namedChildren;

/**
 * 파싱한 Python 모듈들로 코드 그래프를 만든다.
 * <ul>
 *   <li>구조: FILE → CLASS → METHOD, FILE → METHOD(최상위 함수)</li>
 *   <li>진입점: FastAPI 라우터 데코레이터(@app.get, @router.post …) → ROUTES_TO, Depends(...) 의존 함수는 CALLS</li>
 *   <li>호출: import 표로 이름을 정규화해 프로젝트 함수면 CALLS, 위험 API 면 SINK</li>
 * </ul>
 * 타입 추론은 하지 않고 "변수 = 생성자/함수 호출" 대입과 with ... as 만 따라간다.
 */
final class PythonGraphBuilder {

    private static final int SNIPPET_MAX_LENGTH = 300;

    private static final Set<String> FASTAPI_APP_TYPES = Set.of(
            "fastapi.FastAPI", "fastapi.APIRouter", "fastapi.routing.APIRouter", "fastapi.applications.FastAPI",
            "starlette.applications.Starlette", "starlette.routing.Router");
    private static final Map<String, String> ROUTE_VERBS = Map.of(
            "get", "GET", "post", "POST", "put", "PUT", "delete", "DELETE", "patch", "PATCH",
            "options", "OPTIONS", "head", "HEAD", "api_route", "", "websocket", "WEBSOCKET", "route", "");
    private static final Set<String> DEPENDS = Set.of(
            "fastapi.Depends", "fastapi.Security", "fastapi.params.Depends", "fastapi.params.Security");

    /** import 하지 않아도 쓸 수 있는 이름. 위험 규칙(eval, open 등)과 맞추려고 builtins. 를 붙인다 */
    private static final Set<String> BUILTINS = Set.of(
            "open", "eval", "exec", "compile", "__import__", "print", "len", "range", "str", "int", "float", "bool",
            "list", "dict", "set", "tuple", "isinstance", "issubclass", "getattr", "setattr", "hasattr", "super",
            "enumerate", "zip", "map", "filter", "sorted", "min", "max", "sum", "any", "all", "abs", "round",
            "repr", "type", "id", "iter", "next", "vars", "dir", "format", "hash", "hex", "ord", "chr", "input",
            "bytes", "bytearray", "frozenset", "object", "property", "staticmethod", "classmethod", "reversed",
            "divmod", "pow", "callable", "delattr", "globals", "locals", "memoryview", "slice", "ascii", "bin", "oct",
            "breakpoint", "aiter", "anext", "exit", "quit", "NotImplementedError", "ValueError", "TypeError",
            "KeyError", "RuntimeError", "Exception", "IndexError", "AttributeError", "OSError", "IOError",
            "PermissionError", "FileNotFoundError", "StopIteration", "AssertionError", "ImportError", "TimeoutError",
            "ConnectionError", "UnicodeDecodeError", "LookupError", "ArithmeticError", "ZeroDivisionError");

    private final List<PythonModule> modules;
    private final CodeGraphDraft draft;

    /** 모듈 이름의 모든 접미사 → 모듈 (backend.app.main 은 app.main, main 으로도 찾음). 겹치면 null */
    private final Map<String, PythonModule> moduleBySuffix = new HashMap<>();
    private final Map<String, FunctionInfo> functions = new LinkedHashMap<>();
    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    /** FastAPI 앱·라우터 변수(모듈.변수) → 그 라우터의 prefix 들 (include_router 로 붙은 경로 포함) */
    private final Map<String, Router> routers = new LinkedHashMap<>();
    private final Set<String> createdBodies = new HashSet<>();

    private record Router(String key, String ownPrefix, List<String> includedPrefixes, List<String> dependencies) {
    }

    PythonGraphBuilder(List<PythonModule> modules, CodeGraphDraft draft) {
        this.modules = modules;
        this.draft = draft;
        Set<String> ambiguous = new HashSet<>();
        for (PythonModule module : modules) {
            String[] parts = module.name.split("\\.");
            for (int i = 0; i < parts.length; i++) {
                String suffix = String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length));
                if (moduleBySuffix.containsKey(suffix) && moduleBySuffix.get(suffix) != module) {
                    ambiguous.add(suffix);
                }
                moduleBySuffix.putIfAbsent(suffix, module);
            }
            functions.putAll(module.functions);
            classes.putAll(module.classes);
        }
        // 이름 전체가 같은 모듈은 그대로 두고, 접미사만 겹치는 것은 지운다
        for (String suffix : ambiguous) {
            PythonModule exact = modules.stream().filter(m -> m.name.equals(suffix)).findFirst().orElse(null);
            if (exact != null) {
                moduleBySuffix.put(suffix, exact);
            } else {
                moduleBySuffix.remove(suffix);
            }
        }
    }

    void build() {
        modules.forEach(this::addStructure);
        classes.values().forEach(this::addOverrides);
        modules.forEach(this::collectRouters);
        modules.forEach(this::collectIncludes);
        for (FunctionInfo fn : functions.values()) {
            addEntryPoints(fn);
        }
        for (PythonModule module : modules) {
            addCalls(module.body);
        }
        for (FunctionInfo fn : functions.values()) {
            addCalls(fn);
        }
    }

    // ---- 구조 ----

    private void addStructure(PythonModule module) {
        String fileKey = draft.addNode(new NodeDraft(CodeNodeKind.FILE, module.filePath, module.filePath, null, null,
                Map.of("language", "python", "module", module.name)));
        for (ClassInfo cls : module.classes.values()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("declaration", "class");
            props.put("annotations", cls.decorators());
            props.put("superTypes", cls.bases().stream().map(b -> {
                String q = qualify(b, module.body, Map.of());
                return q == null ? b : q;
            }).toList());
            props.put("language", "python");
            String classKey = draft.addNode(node(CodeNodeKind.CLASS, cls.qualifiedName(), module, cls.outer(), props));
            String parent = parentClassKey(cls.qualifiedName());
            draft.addEdge(parent != null ? parent : fileKey, classKey, CodeEdgeKind.CONTAINS);
        }
        for (FunctionInfo fn : module.functions.values()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("name", fn.name());
            props.put("annotations", fn.decorators());
            props.put("language", "python");
            String methodKey = draft.addNode(node(CodeNodeKind.METHOD, fn.qualifiedName(), module, fn.outer(), props));
            String owner = fn.owner() != null ? CodeGraphDraft.key(CodeNodeKind.CLASS, fn.owner().qualifiedName()) : null;
            draft.addEdge(owner != null ? owner : fileKey, methodKey, CodeEdgeKind.CONTAINS);
        }
    }

    private String parentClassKey(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        String parent = dot < 0 ? null : qualifiedName.substring(0, dot);
        return parent != null && classes.containsKey(parent) ? CodeGraphDraft.key(CodeNodeKind.CLASS, parent) : null;
    }

    /** 부모 클래스의 같은 이름 메서드 → 자식 메서드 */
    private void addOverrides(ClassInfo cls) {
        for (ClassInfo ancestor : ancestors(cls)) {
            for (FunctionInfo method : cls.methods().values()) {
                FunctionInfo base = ancestor.methods().get(method.name());
                if (base != null) {
                    draft.addEdge(methodKey(base), methodKey(method), CodeEdgeKind.OVERRIDDEN_BY);
                }
            }
        }
    }

    private List<ClassInfo> ancestors(ClassInfo cls) {
        List<ClassInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ClassInfo> queue = new ArrayList<>(List.of(cls));
        while (!queue.isEmpty()) {
            ClassInfo current = queue.remove(0);
            for (String base : current.bases()) {
                ClassInfo resolved = projectClass(qualify(base, current.module().body, Map.of()));
                if (resolved != null && seen.add(resolved.qualifiedName())) {
                    result.add(resolved);
                    queue.add(resolved);
                }
            }
        }
        return result;
    }

    // ---- FastAPI 진입점 ----

    /** 모듈 최상위의 app = FastAPI(...), router = APIRouter(prefix=...) */
    private void collectRouters(PythonModule module) {
        module.moduleAssignments.forEach((variable, value) -> {
            if (!value.getType().equals("call")) {
                return;
            }
            String callee = qualify(module.text(value.getChildByFieldName("function")), module.body, Map.of());
            if (callee != null && FASTAPI_APP_TYPES.contains(callee)) {
                TSNode args = value.getChildByFieldName("arguments");
                String prefix = stringValue(module, keywordArgument(module, args, "prefix"));
                routers.put(module.name + "." + variable, new Router(module.name + "." + variable,
                        prefix == null ? "" : prefix, new ArrayList<>(), dependencyNames(module, keywordArgument(module, args, "dependencies"))));
            }
        });
    }

    /** app.include_router(users.router, prefix="/api") 로 붙은 경로 */
    private void collectIncludes(PythonModule module) {
        for (TSNode call : calls(module.root, false)) {
            TSNode function = call.getChildByFieldName("function");
            if (!function.getType().equals("attribute")
                    || !module.text(function.getChildByFieldName("attribute")).equals("include_router")) {
                continue;
            }
            Router parent = router(qualify(module.text(function.getChildByFieldName("object")), module.body, Map.of()));
            TSNode args = call.getChildByFieldName("arguments");
            TSNode first = firstPositional(args);
            if (first == null) {
                continue;
            }
            Router child = router(qualify(module.text(first), module.body, Map.of()));
            if (child == null) {
                continue;
            }
            String prefix = stringValue(module, keywordArgument(module, args, "prefix"));
            String parentPrefix = parent == null ? "" : parent.ownPrefix();
            child.includedPrefixes().add(joinPath(parentPrefix, prefix == null ? "" : prefix));
            child.dependencies().addAll(dependencyNames(module, keywordArgument(module, args, "dependencies")));
        }
    }

    /** 정규화 이름 → 라우터. 모듈 이름은 접미사로 맞춘다 (app.api.scan.router ↔ backend.app.api.scan.router) */
    private Router router(String qualified) {
        if (qualified == null) {
            return null;
        }
        Router exact = routers.get(qualified);
        int dot = qualified.lastIndexOf('.');
        if (exact != null || dot < 0) {
            return exact;
        }
        PythonModule module = moduleBySuffix.get(qualified.substring(0, dot));
        return module == null ? null : routers.get(module.name + qualified.substring(dot));
    }

    private void addEntryPoints(FunctionInfo fn) {
        if (fn.outer() == null || !fn.outer().getType().equals("decorated_definition")) {
            return;
        }
        PythonModule module = fn.module();
        for (TSNode decorator : namedChildren(fn.outer())) {
            if (!decorator.getType().equals("decorator")) {
                continue;
            }
            TSNode call = firstNamedChild(decorator);
            if (isNull(call) || !call.getType().equals("call")) {
                continue;
            }
            TSNode function = call.getChildByFieldName("function");
            if (!function.getType().equals("attribute")) {
                continue;
            }
            String verb = module.text(function.getChildByFieldName("attribute"));
            String httpMethod = ROUTE_VERBS.get(verb);
            Router router = router(qualify(module.text(function.getChildByFieldName("object")), module.body, Map.of()));
            if (httpMethod == null || router == null) {
                continue;
            }
            TSNode args = call.getChildByFieldName("arguments");
            String path = stringValue(module, firstPositional(args));
            if (path == null) {
                path = stringValue(module, keywordArgument(module, args, "path"));
            }
            if (path == null) {
                path = "";
            }
            if (httpMethod.isEmpty()) {
                httpMethod = methodsArgument(module, keywordArgument(module, args, "methods"));
            }

            Set<String> dependencies = new LinkedHashSet<>(router.dependencies());
            dependencies.addAll(dependencyNames(module, keywordArgument(module, args, "dependencies")));
            dependencies.addAll(parameterDependencies(fn));

            List<String> prefixes = router.includedPrefixes().isEmpty() ? List.of("") : router.includedPrefixes();
            for (String included : prefixes) {
                String fullPath = joinPath(joinPath(included, router.ownPrefix()), path);
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("httpMethod", httpMethod);
                props.put("path", fullPath);
                props.put("handler", fn.qualifiedName());
                props.put("framework", "fastapi");
                props.put("dependencies", List.copyOf(dependencies));
                props.put("language", "python");
                String entryKey = draft.addNode(new NodeDraft(CodeNodeKind.ENTRY_POINT,
                        httpMethod + " " + fullPath + " -> " + fn.qualifiedName(), module.filePath,
                        PythonModule.line(fn.outer()), PythonModule.endLine(fn.outer()), props));
                draft.addEdge(entryKey, methodKey(fn), CodeEdgeKind.ROUTES_TO);
            }
            // Depends 로 요청마다 실행되는 함수는 핸들러가 부르는 것으로 본다 (인증·DB 세션 등)
            for (String dependency : dependencies) {
                FunctionInfo target = projectFunction(dependency);
                if (target != null) {
                    draft.addEdge(methodKey(fn), methodKey(target), CodeEdgeKind.CALLS);
                }
            }
        }
    }

    /** 파라미터 기본값·Annotated 안의 Depends(get_user) */
    private List<String> parameterDependencies(FunctionInfo fn) {
        List<String> result = new ArrayList<>();
        TSNode parameters = fn.node().getChildByFieldName("parameters");
        for (TSNode call : calls(parameters, true)) {
            String callee = qualify(fn.module().text(call.getChildByFieldName("function")), fn, Map.of());
            if (callee != null && DEPENDS.contains(callee)) {
                TSNode first = firstPositional(call.getChildByFieldName("arguments"));
                if (first != null) {
                    String target = qualify(fn.module().text(first), fn, Map.of());
                    result.add(target != null ? target : fn.module().text(first));
                }
            }
        }
        return result;
    }

    /** dependencies=[Depends(verify_token), ...] */
    private List<String> dependencyNames(PythonModule module, TSNode list) {
        List<String> result = new ArrayList<>();
        if (list == null) {
            return result;
        }
        for (TSNode call : calls(list, true)) {
            String callee = qualify(module.text(call.getChildByFieldName("function")), module.body, Map.of());
            if (callee != null && DEPENDS.contains(callee)) {
                TSNode first = firstPositional(call.getChildByFieldName("arguments"));
                if (first != null) {
                    String target = qualify(module.text(first), module.body, Map.of());
                    result.add(target != null ? target : module.text(first));
                }
            }
        }
        return result;
    }

    private String methodsArgument(PythonModule module, TSNode list) {
        if (list == null) {
            return "GET";
        }
        List<String> methods = new ArrayList<>();
        for (TSNode item : namedChildren(list)) {
            String value = stringValue(module, item);
            if (value != null) {
                methods.add(value.toUpperCase());
            }
        }
        return methods.isEmpty() ? "GET" : String.join(",", methods);
    }

    private static String joinPath(String a, String b) {
        String joined = (a == null ? "" : a) + "/" + (b == null ? "" : b);
        joined = joined.replaceAll("/+", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined;
    }

    // ---- 호출과 위험 지점 ----

    private void addCalls(FunctionInfo fn) {
        PythonModule module = fn.module();
        TSNode scope = fn.isModuleBody() ? module.root : fn.node().getChildByFieldName("body");
        Map<String, String> localTypes = new HashMap<>();
        Set<String> formattedStrings = new HashSet<>();
        collectLocals(module, fn, scope, localTypes, formattedStrings);

        for (TSNode call : calls(scope, false)) {
            TSNode function = call.getChildByFieldName("function");
            TSNode args = call.getChildByFieldName("arguments");
            String callee = qualifyExpression(module, function, fn, localTypes);

            FunctionInfo target = projectFunction(callee);
            if (target == null && callee != null) {
                ClassInfo cls = projectClass(callee);
                if (cls != null) {
                    target = cls.methods().get("__init__");
                    if (target == null) {
                        continue;
                    }
                }
            }
            if (target != null) {
                draft.addEdge(methodKey(fn), methodKey(target), CodeEdgeKind.CALLS);
                continue;
            }

            boolean matched = false;
            for (SinkRule rule : PythonSinkRules.matchAll(callee)) {
                if (isSafeCall(module, rule, callee, args, formattedStrings)) {
                    continue;
                }
                addSink(fn, call, rule, callee);
                matched = true;
                break;
            }
            if (!matched && function.getType().equals("attribute")) {
                String method = module.text(function.getChildByFieldName("attribute"));
                // 연결 객체 타입을 몰라도, 문자열을 조립해 execute 에 넘기면 SQL 인젝션 후보
                if (PythonSinkRules.isSqlExecuteMethod(method) && isFormatted(module, firstPositional(args), formattedStrings)) {
                    // 받는 객체 타입을 모르므로 코드에 쓴 그대로 (cursor.execute, self.conn.execute)
                    String api = module.text(function);
                    addSink(fn, call, new SinkRule(api.substring(0, Math.max(0, api.lastIndexOf('.'))), Set.of(method), "SQL", "CWE-89"), api);
                    matched = true;
                }
            }
            if (!matched && callee == null) {
                draft.countUnresolvedCall();
            }
        }
    }

    /** 고정 값만 넘기는 호출은 건너뛴다 (open("config.json"), subprocess.run(["ls"]), yaml.load(f, Loader=SafeLoader)) */
    private boolean isSafeCall(PythonModule module, SinkRule rule, String callee, TSNode args, Set<String> formattedStrings) {
        if (callee.startsWith("yaml.load") && !callee.contains("unsafe")) {
            TSNode loader = keywordArgument(module, args, "Loader");
            if (loader != null && module.text(loader).matches(".*\\b(C?SafeLoader|BaseLoader)\\b.*")) {
                return true;
            }
        }
        if (rule.category().equals("SQL")) {
            TSNode first = firstPositional(args);
            return first == null || !(isFormatted(module, first, formattedStrings) || isVariable(first));
        }
        if (!PythonSinkRules.safeWithLiteralArgument(rule.category())) {
            return false;
        }
        // 키워드 인자까지 모든 인자가 상수여야 안전하다 (create_subprocess_exec("nmap", target) 는 위험)
        for (TSNode arg : namedChildren(args)) {
            TSNode value = arg.getType().equals("keyword_argument") ? arg.getChildByFieldName("value") : arg;
            if (arg.getType().equals("comment")) {
                continue;
            }
            if (isNull(value) || !isConstant(module, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isVariable(TSNode node) {
        return node.getType().equals("identifier") || node.getType().equals("attribute");
    }

    /** 문자열·숫자 상수, 또는 상수만 든 리스트·튜플 */
    private boolean isConstant(PythonModule module, TSNode node) {
        switch (node.getType()) {
            case "string" -> {
                return namedChildren(node).stream().noneMatch(c -> c.getType().equals("interpolation"));
            }
            case "concatenated_string" -> {
                return namedChildren(node).stream().allMatch(c -> isConstant(module, c));
            }
            case "integer", "float", "true", "false", "none" -> {
                return true;
            }
            case "list", "tuple" -> {
                return namedChildren(node).stream().allMatch(c -> isConstant(module, c));
            }
            default -> {
                return false;
            }
        }
    }

    /** 문자열을 조립했는지: f-string 에 값 넣기, % 포매팅, .format(), + 이어붙이기, 또는 그렇게 만든 변수 */
    private boolean isFormatted(PythonModule module, TSNode node, Set<String> formattedStrings) {
        if (node == null) {
            return false;
        }
        switch (node.getType()) {
            case "string" -> {
                return namedChildren(node).stream().anyMatch(c -> c.getType().equals("interpolation"));
            }
            case "concatenated_string" -> {
                return namedChildren(node).stream().anyMatch(c -> isFormatted(module, c, formattedStrings));
            }
            case "binary_operator" -> {
                TSNode operator = node.getChildByFieldName("operator");
                String op = module.text(operator);
                return (op.equals("%") || op.equals("+")) && (containsString(node) || isFormatted(module,
                        node.getChildByFieldName("left"), formattedStrings));
            }
            case "call" -> {
                TSNode function = node.getChildByFieldName("function");
                return function.getType().equals("attribute")
                        && module.text(function.getChildByFieldName("attribute")).equals("format");
            }
            case "identifier" -> {
                return formattedStrings.contains(module.text(node));
            }
            case "parenthesized_expression" -> {
                return isFormatted(module, firstNamedChild(node), formattedStrings);
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean containsString(TSNode node) {
        if (node.getType().equals("string")) {
            return true;
        }
        for (TSNode child : namedChildren(node)) {
            if (containsString(child)) {
                return true;
            }
        }
        return false;
    }

    /** 함수 안의 "변수 = 호출" 대입과 with ... as 변수, 그리고 조립한 문자열 변수 */
    private void collectLocals(PythonModule module, FunctionInfo fn, TSNode node, Map<String, String> types, Set<String> formatted) {
        if (isNull(node)) {
            return;
        }
        String type = node.getType();
        if (!TSNode.eq(node, scopeOf(fn)) && (type.equals("function_definition") || type.equals("class_definition"))) {
            return;
        }
        if (type.equals("assignment")) {
            TSNode left = node.getChildByFieldName("left");
            TSNode right = node.getChildByFieldName("right");
            if (!isNull(left) && left.getType().equals("identifier") && !isNull(right)) {
                String name = module.text(left);
                TSNode value = unwrapAwait(right);
                if (value.getType().equals("call")) {
                    String callee = qualifyExpression(module, value.getChildByFieldName("function"), fn, types);
                    if (callee != null) {
                        types.put(name, callee);
                    }
                }
                if (isFormatted(module, right, formatted)) {
                    formatted.add(name);
                }
            }
        } else if (type.equals("as_pattern")) {
            TSNode value = unwrapAwait(firstNamedChild(node));
            TSNode alias = node.getChildByFieldName("alias");
            TSNode aliasName = isNull(alias) ? null : (alias.getType().equals("identifier") ? alias : firstNamedChild(alias));
            if (!isNull(value) && value.getType().equals("call") && !isNull(aliasName) && aliasName.getType().equals("identifier")) {
                String callee = qualifyExpression(module, value.getChildByFieldName("function"), fn, types);
                if (callee != null) {
                    types.put(module.text(aliasName), callee);
                }
            }
        }
        for (TSNode child : namedChildren(node)) {
            collectLocals(module, fn, child, types, formatted);
        }
    }

    private static TSNode scopeOf(FunctionInfo fn) {
        return fn.isModuleBody() ? fn.module().root : fn.node();
    }

    private static TSNode unwrapAwait(TSNode node) {
        if (!isNull(node) && node.getType().equals("await")) {
            TSNode inner = firstNamedChild(node);
            return isNull(inner) ? node : inner;
        }
        return node;
    }

    private void addSink(FunctionInfo caller, TSNode call, SinkRule rule, String api) {
        PythonModule module = caller.module();
        int line = PythonModule.line(call);
        int column = PythonModule.column(call);
        String snippet = module.text(call);
        if (snippet.length() > SNIPPET_MAX_LENGTH) {
            snippet = snippet.substring(0, SNIPPET_MAX_LENGTH) + "...";
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("category", rule.category());
        props.put("cwe", rule.cwe());
        props.put("api", api);
        props.put("method", caller.qualifiedName());
        props.put("snippet", snippet);
        props.put("language", "python");

        String callerKey = methodKey(caller);
        if (caller.isModuleBody() && createdBodies.add(caller.qualifiedName())) {
            // 모듈 최상위 코드는 위험 지점이 있을 때만 노드로 만든다
            String fileKey = CodeGraphDraft.key(CodeNodeKind.FILE, module.filePath);
            draft.addNode(new NodeDraft(CodeNodeKind.METHOD, caller.qualifiedName(), module.filePath, 1, null,
                    Map.of("name", "<module>", "annotations", List.of(), "language", "python")));
            draft.addEdge(fileKey, callerKey, CodeEdgeKind.CONTAINS);
        }
        String sinkKey = draft.addNode(new NodeDraft(CodeNodeKind.SINK,
                caller.qualifiedName() + "@L" + line + ":" + column + " " + api,
                module.filePath, line, PythonModule.endLine(call), props));
        draft.addEdge(callerKey, sinkKey, CodeEdgeKind.CALLS);
    }

    // ---- 이름 정규화 ----

    /** 호출 대상 식 → 정규화 이름. 모르면 null */
    private String qualifyExpression(PythonModule module, TSNode expr, FunctionInfo context, Map<String, String> localTypes) {
        if (isNull(expr)) {
            return null;
        }
        switch (expr.getType()) {
            case "identifier" -> {
                return qualify(module.text(expr), context, localTypes);
            }
            case "attribute" -> {
                String object = qualifyExpression(module, expr.getChildByFieldName("object"), context, localTypes);
                return object == null ? null : object + "." + module.text(expr.getChildByFieldName("attribute"));
            }
            case "call" -> {
                // httpx.Client().get(...) 처럼 만들자마자 부르는 호출: 만든 타입 이름
                return qualifyExpression(module, expr.getChildByFieldName("function"), context, localTypes);
            }
            case "await", "parenthesized_expression" -> {
                return qualifyExpression(module, firstNamedChild(expr), context, localTypes);
            }
            default -> {
                return null;
            }
        }
    }

    /** 점으로 이은 이름(a.b.c)의 첫 부분을 지역 변수·self·모듈 정의·import 로 푼다 */
    private String qualify(String dotted, FunctionInfo context, Map<String, String> localTypes) {
        return qualify(dotted, context, localTypes, 0);
    }

    private String qualify(String dotted, FunctionInfo context, Map<String, String> localTypes, int depth) {
        if (depth > 5 || dotted == null || dotted.isEmpty() || !dotted.matches("[\\w.]+")) {
            return null;
        }
        PythonModule module = context.module();
        int dot = dotted.indexOf('.');
        String head = dot < 0 ? dotted : dotted.substring(0, dot);
        String rest = dot < 0 ? "" : dotted.substring(dot);

        if ((head.equals("self") || head.equals("cls")) && context.owner() != null) {
            return context.owner().qualifiedName() + rest;
        }
        if (localTypes.containsKey(head)) {
            return localTypes.get(head) + rest;
        }
        // 같은 모듈의 함수·클래스 (중첩 함수는 바깥 함수 이름 아래에 있다)
        if (!context.isModuleBody() && functions.containsKey(context.qualifiedName() + "." + head)) {
            return context.qualifiedName() + "." + head + rest;
        }
        if (module.functions.containsKey(module.name + "." + head) || module.classes.containsKey(module.name + "." + head)) {
            return module.name + "." + head + rest;
        }
        if (module.imports.containsKey(head)) {
            return module.imports.get(head) + rest;
        }
        if (module.moduleAssignments.containsKey(head)) {
            TSNode value = module.moduleAssignments.get(head);
            if (value.getType().equals("call")) {
                String callee = qualify(module.text(value.getChildByFieldName("function")), module.body, Map.of(), depth + 1);
                // 라우터·앱 변수는 변수 자체를 가리키고, 그 밖의 객체는 만든 타입을 가리킨다
                if (callee != null && FASTAPI_APP_TYPES.contains(callee)) {
                    return module.name + "." + head + rest;
                }
                return callee == null ? null : callee + rest;
            }
            return module.name + "." + head + rest;
        }
        if (dot < 0 && BUILTINS.contains(head)) {
            return "builtins." + head;
        }
        return null;
    }

    /** 정규화 이름 → 프로젝트 함수. 모듈 이름은 저장소 폴더 구조와 import 가 달라도 접미사로 맞춘다 */
    private FunctionInfo projectFunction(String qualified) {
        if (qualified == null) {
            return null;
        }
        FunctionInfo exact = functions.get(qualified);
        if (exact != null) {
            return exact;
        }
        String[] parts = qualified.split("\\.");
        for (int i = parts.length - 1; i >= 1; i--) {
            PythonModule module = moduleBySuffix.get(String.join(".", java.util.Arrays.copyOfRange(parts, 0, i)));
            if (module == null) {
                continue;
            }
            String inModule = module.name + "." + String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length));
            FunctionInfo fn = functions.get(inModule);
            if (fn != null) {
                return fn;
            }
            // 클래스.메서드인데 부모 클래스에 정의된 경우
            int lastDot = inModule.lastIndexOf('.');
            ClassInfo cls = classes.get(inModule.substring(0, lastDot));
            if (cls != null) {
                return inheritedMethod(cls, inModule.substring(lastDot + 1));
            }
            // from pkg import module 처럼 모듈 자체를 가져온 경우는 다음 접두사로
        }
        int lastDot = qualified.lastIndexOf('.');
        if (lastDot > 0) {
            ClassInfo cls = classes.get(qualified.substring(0, lastDot));
            if (cls != null) {
                return inheritedMethod(cls, qualified.substring(lastDot + 1));
            }
        }
        return null;
    }

    private FunctionInfo inheritedMethod(ClassInfo cls, String name) {
        FunctionInfo own = cls.methods().get(name);
        if (own != null) {
            return own;
        }
        for (ClassInfo ancestor : ancestors(cls)) {
            FunctionInfo inherited = ancestor.methods().get(name);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private ClassInfo projectClass(String qualified) {
        if (qualified == null) {
            return null;
        }
        ClassInfo exact = classes.get(qualified);
        if (exact != null) {
            return exact;
        }
        String[] parts = qualified.split("\\.");
        for (int i = parts.length - 1; i >= 1; i--) {
            PythonModule module = moduleBySuffix.get(String.join(".", java.util.Arrays.copyOfRange(parts, 0, i)));
            if (module != null) {
                ClassInfo cls = classes.get(module.name + "." + String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length)));
                if (cls != null) {
                    return cls;
                }
            }
        }
        return null;
    }

    // ---- 트리 도우미 ----

    /** 노드 아래의 호출. 중첩 함수·클래스 정의 안은 그 함수 몫이라 들어가지 않는다 (includeNested 면 들어감) */
    private static List<TSNode> calls(TSNode node, boolean includeNested) {
        List<TSNode> result = new ArrayList<>();
        collectCalls(node, includeNested, result, true);
        return result;
    }

    private static void collectCalls(TSNode node, boolean includeNested, List<TSNode> out, boolean top) {
        if (isNull(node)) {
            return;
        }
        String type = node.getType();
        if (!top && !includeNested && (type.equals("function_definition") || type.equals("class_definition")
                || type.equals("decorated_definition"))) {
            return;
        }
        if (type.equals("call")) {
            out.add(node);
        }
        for (TSNode child : namedChildren(node)) {
            collectCalls(child, includeNested, out, false);
        }
    }

    private static TSNode firstPositional(TSNode args) {
        if (isNull(args)) {
            return null;
        }
        for (TSNode child : namedChildren(args)) {
            String type = child.getType();
            if (!type.equals("keyword_argument") && !type.equals("list_splat") && !type.equals("dictionary_splat")
                    && !type.equals("comment")) {
                return child;
            }
        }
        return null;
    }

    private static TSNode keywordArgument(PythonModule module, TSNode args, String name) {
        if (isNull(args)) {
            return null;
        }
        for (TSNode child : namedChildren(args)) {
            if (child.getType().equals("keyword_argument")) {
                TSNode key = child.getChildByFieldName("name");
                if (!isNull(key) && module.text(key).equals(name)) {
                    TSNode value = child.getChildByFieldName("value");
                    return isNull(value) ? null : value;
                }
            }
        }
        return null;
    }

    /** 고정 문자열 값. f-string 등 값을 모르면 null */
    private static String stringValue(PythonModule module, TSNode node) {
        if (node == null || !node.getType().equals("string")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (TSNode child : namedChildren(node)) {
            switch (child.getType()) {
                case "string_content" -> sb.append(module.text(child));
                case "interpolation" -> sb.append("{").append(module.text(firstNamedChild(child))).append("}");
                default -> {
                }
            }
        }
        return sb.toString();
    }

    private static String methodKey(FunctionInfo fn) {
        return CodeGraphDraft.key(CodeNodeKind.METHOD, fn.qualifiedName());
    }

    private static NodeDraft node(CodeNodeKind kind, String qualifiedName, PythonModule module, TSNode astNode,
                                  Map<String, Object> props) {
        Integer begin = astNode == null ? null : PythonModule.line(astNode);
        Integer end = astNode == null ? null : PythonModule.endLine(astNode);
        return new NodeDraft(kind, qualifiedName, module.filePath, begin, end, props);
    }
}
