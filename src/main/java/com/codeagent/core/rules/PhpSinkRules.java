package com.codeagent.core.rules;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PHP 위험 지점 규칙. 이름은 소문자로 맞춘다.
 * 함수 "exec", 정적 호출 "illuminate\support\facades\db::select", 인스턴스 메서드 "pdo->query", 생성 "new symfony\...\redirectresponse".
 * 받는 쪽 타입을 모르는 메서드 호출은 {@link #method(String)} 로 이름만 보고 맞춘다 ($query->whereRaw).
 */
public final class PhpSinkRules {

    public enum Condition {
        /** 항상 */
        ALWAYS,
        /** {@link PhpRule#argument()} 번째 인자가 상수가 아니다 */
        NOT_CONSTANT,
        /** 인자 중 하나가 문자열 보간·. ·sprintf 로 조립한 문자열이다 */
        FORMATTED,
        /** 인자 중 하나가 요청 값이다 */
        REQUEST_VALUE,
        /** 첫 인자가 'md5'·'sha1' 같은 약한 알고리즘 이름이다 (hash('md5', ...)) */
        WEAK_ALGORITHM,
        /** 인자에 LIBXML_NOENT (외부 엔티티 치환) 가 있다 */
        XML_ENTITIES,
        /** header("Location: " . $x) */
        LOCATION_HEADER,
        /** curl_setopt($ch, CURLOPT_URL, $x) */
        CURL_URL
    }

    public record PhpRule(String category, String cwe, Condition condition, int argument) {
    }

    private static final Map<String, PhpRule> NAMED = new HashMap<>();
    private static final Map<String, PhpRule> METHODS = new HashMap<>();
    private static final Set<String> WEAK_ALGORITHMS = Set.of("md5", "md4", "md2", "sha1", "crc32", "crc32b", "adler32");

    private static void named(PhpRule rule, String... names) {
        for (String name : names) {
            NAMED.put(name.toLowerCase(Locale.ROOT), rule);
        }
    }

    private static PhpRule rule(String category, String cwe, Condition condition, int argument) {
        return new PhpRule(category, cwe, condition, argument);
    }

    private static PhpRule rule(String category, String cwe, Condition condition) {
        return new PhpRule(category, cwe, condition, 0);
    }

    static {
        // 명령 실행
        named(rule("COMMAND", "CWE-78", Condition.NOT_CONSTANT), "exec", "shell_exec", "system", "passthru", "popen", "proc_open",
                "pcntl_exec", "symfony\\component\\process\\process::fromshellcommandline", "illuminate\\support\\facades\\process::run");

        // 코드 실행
        named(rule("CODE_INJECTION", "CWE-95", Condition.NOT_CONSTANT), "eval", "assert");
        named(rule("CODE_INJECTION", "CWE-95", Condition.NOT_CONSTANT, 1), "create_function");

        // SQL
        PhpRule sql = rule("SQL", "CWE-89", Condition.FORMATTED);
        named(sql, "mysqli_query", "mysqli_multi_query", "mysqli_real_query", "mysql_query", "pg_query", "pg_send_query", "sqlite_query",
                "pdo->query", "pdo->exec", "pdo->prepare", "mysqli->query", "mysqli->multi_query", "mysqli->real_query", "mysqli->prepare");
        for (String m : List.of("select", "selectone", "insert", "update", "delete", "statement", "unprepared", "raw", "affectingstatement")) {
            named(sql, "illuminate\\support\\facades\\db::" + m);
        }
        for (String m : List.of("whereRaw", "orWhereRaw", "havingRaw", "orHavingRaw", "orderByRaw", "selectRaw", "groupByRaw", "fromRaw",
                "joinRaw", "statement", "unprepared", "query", "exec", "multi_query", "real_query", "executeQuery", "executeStatement",
                "createQuery", "createNativeQuery")) {
            METHODS.put(m.toLowerCase(Locale.ROOT), sql);
        }

        // 경로
        PhpRule path = rule("PATH", "CWE-22", Condition.NOT_CONSTANT);
        named(path, "file_get_contents", "file_put_contents", "fopen", "readfile", "file", "unlink", "copy", "rename", "mkdir", "rmdir",
                "scandir", "opendir", "parse_ini_file", "highlight_file", "show_source", "fpassthru",
                "illuminate\\support\\facades\\storage::get", "illuminate\\support\\facades\\storage::download",
                "illuminate\\support\\facades\\storage::delete", "illuminate\\support\\facades\\file::get",
                "new symfony\\component\\httpfoundation\\binaryfileresponse");
        named(rule("PATH", "CWE-22", Condition.NOT_CONSTANT, 1), "move_uploaded_file");
        METHODS.put("download", path);

        // 파일 포함 (include $x)
        named(rule("CODE_INJECTION", "CWE-98", Condition.NOT_CONSTANT), "include", "include_once", "require", "require_once");

        // SSRF
        PhpRule ssrf = rule("SSRF", "CWE-918", Condition.NOT_CONSTANT);
        named(ssrf, "curl_init", "fsockopen", "get_headers", "illuminate\\support\\facades\\http::get", "illuminate\\support\\facades\\http::post",
                "illuminate\\support\\facades\\http::put", "illuminate\\support\\facades\\http::head", "illuminate\\support\\facades\\http::delete",
                "illuminate\\support\\facades\\http::patch", "guzzlehttp\\client->get", "guzzlehttp\\client->post", "guzzlehttp\\client->head",
                "guzzlehttp\\client->put", "guzzlehttp\\client->delete");
        named(rule("SSRF", "CWE-918", Condition.NOT_CONSTANT, 1), "guzzlehttp\\client->request", "guzzlehttp\\client->requestasync");
        named(rule("SSRF", "CWE-918", Condition.CURL_URL), "curl_setopt");

        // 역직렬화
        named(rule("DESERIALIZATION", "CWE-502", Condition.NOT_CONSTANT), "unserialize");

        // 오픈 리다이렉트
        PhpRule redirect = rule("OPEN_REDIRECT", "CWE-601", Condition.NOT_CONSTANT);
        named(redirect, "redirect", "illuminate\\support\\facades\\redirect::to", "illuminate\\support\\facades\\redirect::away",
                "new symfony\\component\\httpfoundation\\redirectresponse", "new illuminate\\http\\redirectresponse");
        named(rule("OPEN_REDIRECT", "CWE-601", Condition.LOCATION_HEADER), "header");
        METHODS.put("away", redirect);

        // XSS: 요청 값을 응답 본문으로
        PhpRule reflect = rule("XSS", "CWE-79", Condition.REQUEST_VALUE);
        named(reflect, "echo", "print", "printf", "response", "new symfony\\component\\httpfoundation\\response",
                "new illuminate\\http\\response");

        // XXE
        PhpRule xxe = rule("XXE", "CWE-611", Condition.XML_ENTITIES);
        named(xxe, "simplexml_load_string", "simplexml_load_file", "domdocument->loadxml", "domdocument->load", "xmlreader->xml",
                "xmlreader->open");

        // 약한 암호·해시·난수
        named(rule("WEAK_CRYPTO", "CWE-328", Condition.ALWAYS), "md5", "sha1", "md5_file", "sha1_file", "crc32");
        named(rule("WEAK_CRYPTO", "CWE-328", Condition.WEAK_ALGORITHM), "hash", "hash_file", "hash_init");
        named(rule("WEAK_CRYPTO", "CWE-327", Condition.ALWAYS), "mcrypt_encrypt", "mcrypt_decrypt");
        named(rule("WEAK_RANDOM", "CWE-338", Condition.ALWAYS), "rand", "mt_rand", "uniqid", "lcg_value", "str_shuffle", "array_rand",
                "shuffle");
    }

    private PhpSinkRules() {
    }

    public static Optional<PhpRule> named(String name) {
        return Optional.ofNullable(NAMED.get(name.toLowerCase(Locale.ROOT)));
    }

    public static Optional<PhpRule> method(String name) {
        return Optional.ofNullable(METHODS.get(name.toLowerCase(Locale.ROOT)));
    }

    public static boolean weakAlgorithm(String value) {
        return value != null && WEAK_ALGORITHMS.contains(value.toLowerCase(Locale.ROOT));
    }
}
