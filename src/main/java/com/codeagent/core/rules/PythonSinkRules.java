package com.codeagent.core.rules;

import java.util.List;
import java.util.Set;

/**
 * Python 위험 지점 규칙. 호출 대상을 import 기준 정규화 이름(예: subprocess.run, httpx.AsyncClient.get)으로 맞춘다.
 * 생성자 호출은 클래스 이름 그대로(예: pathlib.Path) 적는다.
 * SQL 은 라이브러리마다 연결 객체 타입을 알기 어려워서, 호출 이름 대신 "문자열을 조립해서 execute 에 넘기는지"로 따로 본다
 * ({@link #isSqlExecuteMethod}).
 */
public final class PythonSinkRules {

    private static final String[] HTTP_METHODS = {
            "get", "post", "put", "patch", "delete", "head", "options", "request", "stream", "send"
    };

    private static final List<SinkRule> RULES = List.of(
            // 명령 실행
            rule("subprocess", "COMMAND", "CWE-78", "run", "call", "check_call", "check_output", "Popen",
                    "getoutput", "getstatusoutput"),
            rule("os", "COMMAND", "CWE-78", "system", "popen", "execl", "execle", "execlp", "execlpe", "execv", "execve",
                    "execvp", "execvpe", "spawnl", "spawnle", "spawnlp", "spawnlpe", "spawnv", "spawnve", "spawnvp", "spawnvpe",
                    "posix_spawn", "posix_spawnp"),
            rule("asyncio", "COMMAND", "CWE-78", "create_subprocess_shell", "create_subprocess_exec"),
            rule("asyncio.subprocess", "COMMAND", "CWE-78", "create_subprocess_shell", "create_subprocess_exec"),
            rule("pty", "COMMAND", "CWE-78", "spawn"),
            // python-nmap 은 인자 문자열을 나눠 nmap 명령줄에 그대로 붙인다 (인자 주입)
            rule("nmap.PortScanner", "COMMAND", "CWE-88", "scan"),
            rule("nmap.PortScannerAsync", "COMMAND", "CWE-88", "scan"),

            // 코드 실행
            rule("builtins", "CODE_INJECTION", "CWE-94", "eval", "exec", "compile", "__import__"),
            rule("importlib", "CODE_INJECTION", "CWE-94", "import_module"),

            // SSRF
            rule("requests", "SSRF", "CWE-918", HTTP_METHODS),
            rule("requests.Session", "SSRF", "CWE-918", HTTP_METHODS),
            rule("requests.sessions.Session", "SSRF", "CWE-918", HTTP_METHODS),
            rule("httpx", "SSRF", "CWE-918", HTTP_METHODS),
            rule("httpx.Client", "SSRF", "CWE-918", HTTP_METHODS),
            rule("httpx.AsyncClient", "SSRF", "CWE-918", HTTP_METHODS),
            rule("aiohttp.ClientSession", "SSRF", "CWE-918", "get", "post", "put", "patch", "delete", "head", "options",
                    "request", "ws_connect"),
            rule("aiohttp", "SSRF", "CWE-918", "request"),
            rule("urllib.request", "SSRF", "CWE-918", "urlopen", "urlretrieve", "Request"),
            rule("urllib3", "SSRF", "CWE-918", "request"),
            rule("urllib3.PoolManager", "SSRF", "CWE-918", "request", "urlopen"),
            rule("http.client", "SSRF", "CWE-918", "HTTPConnection", "HTTPSConnection"),
            rule("socket", "SSRF", "CWE-918", "create_connection"),

            // 경로 조작
            rule("builtins", "PATH", "CWE-22", "open"),
            rule("io", "PATH", "CWE-22", "open"),
            rule("codecs", "PATH", "CWE-22", "open"),
            rule("aiofiles", "PATH", "CWE-22", "open"),
            rule("pathlib", "PATH", "CWE-22", "Path", "PurePath", "PosixPath", "WindowsPath"),
            rule("os", "PATH", "CWE-22", "open", "remove", "unlink", "rmdir", "removedirs", "makedirs", "mkdir", "listdir",
                    "rename", "replace", "scandir"),
            rule("shutil", "PATH", "CWE-22", "copy", "copy2", "copyfile", "copytree", "move", "rmtree", "unpack_archive"),
            rule("fastapi.responses", "PATH", "CWE-22", "FileResponse"),
            rule("starlette.responses", "PATH", "CWE-22", "FileResponse"),
            rule("flask", "PATH", "CWE-22", "send_file", "send_from_directory"),
            rule("tarfile.TarFile", "PATH", "CWE-22", "extractall", "extract"),
            rule("zipfile.ZipFile", "PATH", "CWE-22", "extractall", "extract"),

            // SQL: 문자열 그대로 쿼리로 만드는 API (조립 여부는 호출 지점에서 따로 본다)
            rule("sqlalchemy", "SQL", "CWE-89", "text"),
            rule("sqlalchemy.sql", "SQL", "CWE-89", "text"),
            rule("sqlalchemy.sql.expression", "SQL", "CWE-89", "text"),

            // 역직렬화
            rule("pickle", "DESERIALIZATION", "CWE-502", "load", "loads", "Unpickler"),
            rule("_pickle", "DESERIALIZATION", "CWE-502", "load", "loads"),
            rule("cPickle", "DESERIALIZATION", "CWE-502", "load", "loads"),
            rule("dill", "DESERIALIZATION", "CWE-502", "load", "loads"),
            rule("marshal", "DESERIALIZATION", "CWE-502", "load", "loads"),
            rule("shelve", "DESERIALIZATION", "CWE-502", "open"),
            rule("jsonpickle", "DESERIALIZATION", "CWE-502", "decode", "loads"),
            rule("joblib", "DESERIALIZATION", "CWE-502", "load"),
            rule("yaml", "DESERIALIZATION", "CWE-502", "load", "load_all", "unsafe_load", "unsafe_load_all"),

            // XXE: 표준 xml 모듈 대신 defusedxml 을 써야 한다. lxml 은 외부 엔티티를 기본으로 푼다
            rule("lxml.etree", "XXE", "CWE-611", "parse", "fromstring", "XML", "XMLParser"),
            rule("xml.dom.minidom", "XXE", "CWE-611", "parse", "parseString"),
            rule("xml.sax", "XXE", "CWE-611", "parse", "parseString"),

            // 오픈 리다이렉트
            rule("fastapi.responses", "OPEN_REDIRECT", "CWE-601", "RedirectResponse"),
            rule("starlette.responses", "OPEN_REDIRECT", "CWE-601", "RedirectResponse"),
            rule("flask", "OPEN_REDIRECT", "CWE-601", "redirect"),
            rule("django.shortcuts", "OPEN_REDIRECT", "CWE-601", "redirect"),
            rule("django.http", "OPEN_REDIRECT", "CWE-601", "HttpResponseRedirect", "HttpResponsePermanentRedirect"),

            // 서버 측 템플릿 주입: 사용자 값을 템플릿 원문으로
            rule("flask", "CODE_INJECTION", "CWE-1336", "render_template_string"),

            // Django: 조립한 SQL 조각 (Model.objects.raw / cursor.execute 는 조립한 문자열 검사가 따로 본다)
            rule("django.db.models.expressions", "SQL", "CWE-89", "RawSQL"),
            rule("django.db.models", "SQL", "CWE-89", "RawSQL"),
            rule("django.http", "PATH", "CWE-22", "FileResponse"),

            // XSS: 사용자 값을 HTML 응답에 그대로
            rule("fastapi.responses", "XSS", "CWE-79", "HTMLResponse"),
            rule("starlette.responses", "XSS", "CWE-79", "HTMLResponse"),
            rule("markupsafe", "XSS", "CWE-79", "Markup"),
            rule("flask", "XSS", "CWE-79", "Markup"),
            rule("django.utils.safestring", "XSS", "CWE-79", "mark_safe"),
            rule("jinja2", "XSS", "CWE-79", "Template"),

            // 약한 암호화·난수
            rule("hashlib", "WEAK_CRYPTO", "CWE-328", "md5", "sha1"),
            rule("random", "WEAK_RANDOM", "CWE-330", "random", "randint", "randrange", "choice", "choices", "getrandbits")
    );

    /** 데이터베이스 드라이버의 쿼리 실행 메서드 이름 (sqlite3, psycopg, pymysql, asyncpg, SQLAlchemy, Django) */
    private static final Set<String> SQL_EXECUTE_METHODS = Set.of(
            "execute", "executemany", "executescript", "exec_driver_sql", "raw", "fetch", "fetchrow", "fetchval", "mogrify");

    /** 첫 인자가 고정 문자열이어도 위험한 분류 (어떤 값을 넘기든 쓰임새를 봐야 함) */
    private static final Set<String> RISKY_EVEN_WITH_LITERAL = Set.of("DESERIALIZATION", "WEAK_CRYPTO", "WEAK_RANDOM", "XXE");

    private PythonSinkRules() {
    }

    private static SinkRule rule(String module, String category, String cwe, String... names) {
        return new SinkRule(module, Set.of(names), category, cwe);
    }

    /** 정규화 이름(예: subprocess.run)에 맞는 규칙. 한 모듈이 여러 분류에 걸리기도 해서(fastapi.responses) 모두 돌려준다 */
    public static List<SinkRule> matchAll(String qualifiedCallee) {
        if (qualifiedCallee == null) {
            return List.of();
        }
        int dot = qualifiedCallee.lastIndexOf('.');
        if (dot < 0) {
            return List.of();
        }
        String module = qualifiedCallee.substring(0, dot);
        String name = qualifiedCallee.substring(dot + 1);
        return RULES.stream().filter(r -> r.matches(module, name)).toList();
    }

    /** 고정 문자열 인자만 넘기면 위험 지점으로 보지 않아도 되는 분류인지 */
    public static boolean safeWithLiteralArgument(String category) {
        return !RISKY_EVEN_WITH_LITERAL.contains(category);
    }

    public static boolean isSqlExecuteMethod(String methodName) {
        return SQL_EXECUTE_METHODS.contains(methodName);
    }
}
