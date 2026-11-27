package com.codeagent.core.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Node.js 위험 지점 규칙.
 * <ul>
 *   <li>이름 규칙: require·import 로 푼 이름(child_process.exec, axios.get, fs.promises.readFile, global.eval)</li>
 *   <li>메서드 규칙: 받는 쪽 타입을 모르는 호출(res.redirect, db.query, User.findOne)을 메서드 이름과 인자 모양으로 본다</li>
 * </ul>
 * 고정 문자열만 넘기는 호출은 위험 지점으로 보지 않는다 (약한 암호·난수는 예외).
 */
public final class NodeSinkRules {

    /** 인자 조건 */
    public enum Condition {
        /** 첫 인자가 고정 값이 아니면 */
        NOT_LITERAL,
        /** 첫 인자를 문자열 조립(템플릿 치환, +)으로 만들었으면 */
        FORMATTED,
        /** 인자에 요청 값(req.body·req.query·req.params, ctx.request.body …)이 그대로 들어가면 */
        REQUEST_VALUE,
        /** 항상 (약한 암호·난수처럼 쓰임새를 봐야 하는 것) */
        ALWAYS,
        /** 첫 인자가 약한 알고리즘 이름(md5, sha1, des, rc4, ecb)이면 */
        WEAK_ALGORITHM
    }

    public record NodeRule(String category, String cwe, Condition condition) {
    }

    private static final Map<String, NodeRule> NAMED = new LinkedHashMap<>();
    private static final Map<String, NodeRule> METHODS = new LinkedHashMap<>();

    private static final List<String> HTTP_VERBS = List.of("get", "post", "put", "patch", "delete", "head", "options", "request");

    static {
        // 명령 실행
        for (String m : List.of("exec", "execSync", "spawn", "spawnSync", "execFile", "execFileSync", "fork")) {
            named("child_process." + m, "COMMAND", "CWE-78", Condition.NOT_LITERAL);
        }
        for (String m : List.of("execa", "execa.command", "execa.commandSync", "execaSync", "shelljs.exec")) {
            named(m, "COMMAND", "CWE-78", Condition.NOT_LITERAL);
        }
        // 코드 실행
        named("global.eval", "CODE_INJECTION", "CWE-95", Condition.NOT_LITERAL);
        named("global.Function", "CODE_INJECTION", "CWE-95", Condition.NOT_LITERAL);
        named("new global.Function", "CODE_INJECTION", "CWE-95", Condition.NOT_LITERAL);
        named("global.setTimeout", "CODE_INJECTION", "CWE-95", Condition.FORMATTED);
        for (String m : List.of("runInNewContext", "runInContext", "runInThisContext", "compileFunction")) {
            named("vm." + m, "CODE_INJECTION", "CWE-95", Condition.NOT_LITERAL);
        }
        named("new vm.Script", "CODE_INJECTION", "CWE-95", Condition.NOT_LITERAL);
        // 서버 템플릿 주입: 사용자 값을 템플릿 원문으로
        for (String m : List.of("ejs.render", "pug.render", "pug.compile", "handlebars.compile", "Handlebars.compile",
                "nunjucks.renderString", "mustache.render", "lodash.template", "_.template")) {
            named(m, "CODE_INJECTION", "CWE-1336", Condition.NOT_LITERAL);
        }
        // 경로 조작
        for (String m : List.of("readFile", "readFileSync", "writeFile", "writeFileSync", "appendFile", "appendFileSync",
                "createReadStream", "createWriteStream", "unlink", "unlinkSync", "rm", "rmSync", "readdir", "readdirSync",
                "mkdir", "mkdirSync", "rename", "renameSync", "copyFile", "copyFileSync", "open", "openSync")) {
            named("fs." + m, "PATH", "CWE-22", Condition.NOT_LITERAL);
            named("fs.promises." + m, "PATH", "CWE-22", Condition.NOT_LITERAL);
            named("fs-extra." + m, "PATH", "CWE-22", Condition.NOT_LITERAL);
        }
        // SSRF
        named("global.fetch", "SSRF", "CWE-918", Condition.NOT_LITERAL);
        for (String lib : List.of("axios", "got", "node-fetch", "superagent", "needle", "request", "undici")) {
            named(lib, "SSRF", "CWE-918", Condition.NOT_LITERAL);
            HTTP_VERBS.forEach(v -> named(lib + "." + v, "SSRF", "CWE-918", Condition.NOT_LITERAL));
        }
        named("undici.fetch", "SSRF", "CWE-918", Condition.NOT_LITERAL);
        for (String lib : List.of("http", "https")) {
            named(lib + ".get", "SSRF", "CWE-918", Condition.NOT_LITERAL);
            named(lib + ".request", "SSRF", "CWE-918", Condition.NOT_LITERAL);
        }
        // 역직렬화
        named("node-serialize.unserialize", "DESERIALIZATION", "CWE-502", Condition.NOT_LITERAL);
        named("serialize-to-js.deserialize", "DESERIALIZATION", "CWE-502", Condition.NOT_LITERAL);
        named("funcster.deepDeserialize", "DESERIALIZATION", "CWE-502", Condition.NOT_LITERAL);
        // 프로토타입 오염: 요청 객체를 깊게 합침
        for (String m : List.of("merge", "mergeWith", "defaultsDeep", "set", "setWith", "zipObjectDeep")) {
            named("lodash." + m, "PROTOTYPE_POLLUTION", "CWE-1321", Condition.REQUEST_VALUE);
        }
        named("deep-extend", "PROTOTYPE_POLLUTION", "CWE-1321", Condition.REQUEST_VALUE);
        named("deepmerge", "PROTOTYPE_POLLUTION", "CWE-1321", Condition.REQUEST_VALUE);
        named("merge", "PROTOTYPE_POLLUTION", "CWE-1321", Condition.REQUEST_VALUE);
        // 약한 암호·난수
        named("crypto.createHash", "WEAK_CRYPTO", "CWE-328", Condition.WEAK_ALGORITHM);
        named("crypto.createCipheriv", "WEAK_CRYPTO", "CWE-327", Condition.WEAK_ALGORITHM);
        named("crypto.createCipher", "WEAK_CRYPTO", "CWE-327", Condition.ALWAYS);
        named("md5", "WEAK_CRYPTO", "CWE-328", Condition.ALWAYS);
        named("global.Math.random", "WEAK_RANDOM", "CWE-330", Condition.ALWAYS);
        // 오픈 리다이렉트
        named("global.Response.redirect", "OPEN_REDIRECT", "CWE-601", Condition.NOT_LITERAL);
        named("next/navigation.redirect", "OPEN_REDIRECT", "CWE-601", Condition.NOT_LITERAL);

        // 받는 쪽을 모르는 메서드 (Express res, Koa ctx, DB 연결·모델)
        method("redirect", "OPEN_REDIRECT", "CWE-601", Condition.NOT_LITERAL);
        method("sendFile", "PATH", "CWE-22", Condition.NOT_LITERAL);
        method("download", "PATH", "CWE-22", Condition.NOT_LITERAL);
        method("attachment", "PATH", "CWE-22", Condition.NOT_LITERAL);
        for (String m : List.of("send", "write", "end")) {
            method(m, "XSS", "CWE-79", Condition.REQUEST_VALUE);
        }
        for (String m : List.of("query", "execute", "raw", "whereRaw", "orderByRaw", "havingRaw", "joinRaw", "$queryRawUnsafe",
                "$executeRawUnsafe", "queryRawUnsafe", "literal", "exec")) {
            method(m, "SQL", "CWE-89", Condition.FORMATTED);
        }
        for (String m : List.of("find", "findOne", "findOneAndUpdate", "findOneAndDelete", "findOneAndRemove", "updateOne",
                "updateMany", "deleteOne", "deleteMany", "countDocuments", "where", "aggregate", "replaceOne", "remove")) {
            method(m, "NOSQL_INJECTION", "CWE-943", Condition.REQUEST_VALUE);
        }
    }

    private NodeSinkRules() {
    }

    private static void named(String name, String category, String cwe, Condition condition) {
        NAMED.put(name, new NodeRule(category, cwe, condition));
    }

    private static void method(String name, String category, String cwe, Condition condition) {
        METHODS.put(name, new NodeRule(category, cwe, condition));
    }

    /** 이름 규칙 (호출을 끝낸 "()" 은 떼고 맞춘다) */
    public static Optional<NodeRule> named(String qualifiedCallee) {
        if (qualifiedCallee == null) {
            return Optional.empty();
        }
        String name = qualifiedCallee.endsWith("()") ? qualifiedCallee.substring(0, qualifiedCallee.length() - 2) : qualifiedCallee;
        NodeRule rule = NAMED.get(name);
        if (rule == null && name.startsWith("lodash/")) {
            rule = NAMED.get("lodash." + name.substring("lodash/".length()));
        }
        return Optional.ofNullable(rule);
    }

    /** 받는 쪽을 모를 때 메서드 이름 규칙 */
    public static Optional<NodeRule> method(String methodName) {
        return Optional.ofNullable(METHODS.get(methodName));
    }

    /** 약한 알고리즘 이름 */
    public static boolean weakAlgorithm(String value) {
        if (value == null) {
            return false;
        }
        String v = value.toLowerCase();
        return Set.of("md5", "md4", "sha1", "sha-1", "des", "des-ede", "des-ede3", "rc4", "rc2", "bf").contains(v)
                || v.endsWith("-ecb") || v.startsWith("des-");
    }
}
