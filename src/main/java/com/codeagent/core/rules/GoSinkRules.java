package com.codeagent.core.rules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Go 위험 지점 규칙. 이름은 "import경로.함수" 또는 "import경로.타입.메서드" (포인터는 뗀다).
 * 받는 쪽 타입을 모르는 메서드 호출(db 가 무엇인지 모르는 db.Query)은 {@link #method(String)} 로 이름만 보고 맞춘다.
 */
public final class GoSinkRules {

    /** 인자 조건 */
    public enum Condition {
        /** 항상 */
        ALWAYS,
        /** 인자 중 하나라도 상수가 아니다 */
        ANY_NOT_CONSTANT,
        /** {@link GoRule#argument()} 번째 인자가 상수가 아니다 */
        NOT_CONSTANT,
        /** 인자 중 하나가 fmt.Sprintf·+ 로 조립한 문자열이다 (SQL 문자열) */
        FORMATTED,
        /** 인자 중 하나가 요청 값이다 (응답에 그대로 쓰기) */
        REQUEST_VALUE
    }

    public record GoRule(String category, String cwe, Condition condition, int argument) {
    }

    private static final Map<String, GoRule> NAMED = new HashMap<>();
    private static final Map<String, GoRule> METHODS = new HashMap<>();

    private static void named(GoRule rule, String... names) {
        for (String name : names) {
            NAMED.put(name, rule);
        }
    }

    private static GoRule rule(String category, String cwe, Condition condition) {
        return new GoRule(category, cwe, condition, 0);
    }

    private static GoRule rule(String category, String cwe, Condition condition, int argument) {
        return new GoRule(category, cwe, condition, argument);
    }

    static {
        // 명령 실행
        named(rule("COMMAND", "CWE-78", Condition.ANY_NOT_CONSTANT), "os/exec.Command", "os/exec.CommandContext", "syscall.Exec",
                "os.StartProcess");

        // SQL: 쿼리 문자열을 조립해서 넘김
        GoRule sql = rule("SQL", "CWE-89", Condition.FORMATTED);
        for (String type : List.of("database/sql.DB", "database/sql.Tx", "database/sql.Conn", "github.com/jmoiron/sqlx.DB",
                "github.com/jmoiron/sqlx.Tx", "github.com/jackc/pgx/v5/pgxpool.Pool", "github.com/jackc/pgx/v5.Conn")) {
            for (String m : List.of("Query", "QueryRow", "Exec", "QueryContext", "QueryRowContext", "ExecContext", "Prepare",
                    "PrepareContext", "Get", "Select", "Queryx", "QueryRowx", "MustExec", "NamedExec", "NamedQuery", "GetContext",
                    "SelectContext")) {
                named(sql, type + "." + m);
            }
        }
        for (String m : List.of("Raw", "Exec", "Where", "Or", "Not", "Order", "Group", "Having", "Joins", "Select", "Pluck")) {
            named(sql, "gorm.io/gorm.DB." + m);
        }

        // 경로
        GoRule path = rule("PATH", "CWE-22", Condition.NOT_CONSTANT);
        named(path, "os.Open", "os.OpenFile", "os.ReadFile", "os.WriteFile", "os.Create", "os.Remove", "os.RemoveAll", "os.Mkdir",
                "os.MkdirAll", "os.Rename", "os.ReadDir", "io/ioutil.ReadFile", "io/ioutil.WriteFile", "io/ioutil.ReadDir",
                "github.com/gin-gonic/gin.Context.File", "github.com/gin-gonic/gin.Context.FileAttachment",
                "github.com/labstack/echo/v4.Context.File", "github.com/labstack/echo/v4.Context.Attachment",
                "github.com/gofiber/fiber/v2.Ctx.SendFile", "github.com/gofiber/fiber/v2.Ctx.Download");
        named(rule("PATH", "CWE-22", Condition.NOT_CONSTANT, 2), "net/http.ServeFile");

        // SSRF
        GoRule ssrf = rule("SSRF", "CWE-918", Condition.NOT_CONSTANT);
        named(ssrf, "net/http.Get", "net/http.Head", "net/http.Post", "net/http.PostForm", "net/http.Client.Get", "net/http.Client.Head",
                "net/http.Client.Post", "net/http.Client.PostForm", "net.Dial", "net.DialTimeout");
        named(rule("SSRF", "CWE-918", Condition.NOT_CONSTANT, 1), "net/http.NewRequest");
        named(rule("SSRF", "CWE-918", Condition.NOT_CONSTANT, 2), "net/http.NewRequestWithContext");

        // 오픈 리다이렉트
        named(rule("OPEN_REDIRECT", "CWE-601", Condition.NOT_CONSTANT, 2), "net/http.Redirect");
        named(rule("OPEN_REDIRECT", "CWE-601", Condition.NOT_CONSTANT, 1), "github.com/gin-gonic/gin.Context.Redirect",
                "github.com/labstack/echo/v4.Context.Redirect");
        named(rule("OPEN_REDIRECT", "CWE-601", Condition.NOT_CONSTANT), "github.com/gofiber/fiber/v2.Ctx.Redirect");

        // XSS: 이스케이프를 끄는 변환, 요청 값을 응답에 그대로 쓰기
        named(rule("XSS", "CWE-79", Condition.NOT_CONSTANT), "html/template.HTML", "html/template.JS", "html/template.HTMLAttr",
                "html/template.URL");
        GoRule reflect = rule("XSS", "CWE-79", Condition.REQUEST_VALUE);
        named(reflect, "net/http.ResponseWriter.Write", "io.WriteString", "fmt.Fprintf", "fmt.Fprint", "fmt.Fprintln",
                "github.com/gin-gonic/gin.Context.Data", "github.com/labstack/echo/v4.Context.HTML",
                "github.com/labstack/echo/v4.Context.HTMLBlob", "github.com/gofiber/fiber/v2.Ctx.SendString");

        // 템플릿 주입: 템플릿 문장 자체를 요청 값으로 만든다
        named(rule("CODE_INJECTION", "CWE-1336", Condition.NOT_CONSTANT), "html/template.Template.Parse",
                "text/template.Template.Parse");

        // 역직렬화: gob 은 타입이 정해져 있어 덜 위험하지만 신뢰할 수 없는 입력이면 후보
        named(rule("DESERIALIZATION", "CWE-502", Condition.ALWAYS), "encoding/gob.Decoder.Decode");

        // 약한 암호·해시·난수
        GoRule weakHash = rule("WEAK_CRYPTO", "CWE-328", Condition.ALWAYS);
        named(weakHash, "crypto/md5.New", "crypto/md5.Sum", "crypto/sha1.New", "crypto/sha1.Sum");
        GoRule weakCipher = rule("WEAK_CRYPTO", "CWE-327", Condition.ALWAYS);
        named(weakCipher, "crypto/des.NewCipher", "crypto/des.NewTripleDESCipher", "crypto/rc4.NewCipher");
        GoRule weakRandom = rule("WEAK_RANDOM", "CWE-338", Condition.ALWAYS);
        for (String pkg : List.of("math/rand", "math/rand/v2")) {
            for (String m : List.of("Int", "Intn", "Int31", "Int31n", "Int63", "Int63n", "IntN", "Int64", "Int64N", "Uint32", "Uint64",
                    "Float32", "Float64", "Read", "Perm", "Shuffle", "N")) {
                named(weakRandom, pkg + "." + m);
            }
        }

        // 받는 쪽을 모를 때 이름으로 보는 SQL 메서드
        for (String m : List.of("Query", "QueryRow", "Exec", "QueryContext", "QueryRowContext", "ExecContext", "Raw", "Queryx",
                "QueryRowx", "MustExec")) {
            METHODS.put(m, sql);
        }
    }

    private GoSinkRules() {
    }

    public static Optional<GoRule> named(String qualified) {
        return Optional.ofNullable(NAMED.get(qualified));
    }

    public static Optional<GoRule> method(String name) {
        return Optional.ofNullable(METHODS.get(name));
    }
}
