package com.codeagent.core.rules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * C# 위험 지점 규칙. 이름은 "타입.메서드" (타입은 이름만: SqlCommand, File, HttpClient) 또는 "new 타입",
 * 받는 쪽 없는 호출(컨트롤러의 Redirect·Content·PhysicalFile)은 메서드 이름 그대로.
 * 받는 쪽 타입을 모르는 메서드(Dapper 의 Query, EF 의 FromSqlRaw)는 {@link #method(String)} 로 이름만 보고 맞춘다.
 */
public final class CsSinkRules {

    public enum Condition {
        /** 항상 */
        ALWAYS,
        /** {@link CsRule#argument()} 번째 인자가 상수가 아니다 */
        NOT_CONSTANT,
        /** 인자 중 하나라도 상수가 아니다 */
        ANY_NOT_CONSTANT,
        /** 인자 중 하나가 보간·+·string.Format 으로 조립한 문자열이다 */
        FORMATTED,
        /** 인자 중 하나가 요청 값이다 */
        REQUEST_VALUE,
        /** 요청 값이고 Content-Type 인자가 HTML 이다 (Content(x, "text/html")) */
        HTML_REQUEST_VALUE,
        /** 인자나 그 설정에 TypeNameHandling(None 이 아닌) 이 있다 */
        TYPE_NAME_HANDLING,
        /** 같은 메서드에서 XmlUrlResolver·DtdProcessing.Parse 를 켰다 */
        XML_RESOLVER
    }

    public record CsRule(String category, String cwe, Condition condition, int argument) {
    }

    private static final Map<String, CsRule> NAMED = new HashMap<>();
    private static final Map<String, CsRule> METHODS = new HashMap<>();

    private static void named(CsRule rule, String... names) {
        for (String name : names) {
            NAMED.put(name, rule);
        }
    }

    private static CsRule rule(String category, String cwe, Condition condition) {
        return new CsRule(category, cwe, condition, 0);
    }

    private static CsRule rule(String category, String cwe, Condition condition, int argument) {
        return new CsRule(category, cwe, condition, argument);
    }

    static {
        // 명령 실행
        named(rule("COMMAND", "CWE-78", Condition.ANY_NOT_CONSTANT), "Process.Start", "new ProcessStartInfo");

        // 코드 실행 (Roslyn 스크립트)
        named(rule("CODE_INJECTION", "CWE-95", Condition.NOT_CONSTANT), "CSharpScript.EvaluateAsync", "CSharpScript.RunAsync",
                "CSharpScript.Create");

        // SQL: 쿼리 문자열을 조립해서 넘김 (FromSqlInterpolated·FromSql·ExecuteSqlInterpolated 는 매개변수로 바꿔서 안전)
        CsRule sql = rule("SQL", "CWE-89", Condition.FORMATTED);
        named(sql, "new SqlCommand", "new NpgsqlCommand", "new MySqlCommand", "new SqliteCommand", "new OracleCommand", "new SqlDataAdapter");
        for (String m : List.of("FromSqlRaw", "ExecuteSqlRaw", "ExecuteSqlRawAsync", "SqlQueryRaw", "Query", "QueryAsync", "QueryFirst",
                "QueryFirstAsync", "QueryFirstOrDefault", "QueryFirstOrDefaultAsync", "QuerySingle", "QuerySingleAsync",
                "QuerySingleOrDefault", "QuerySingleOrDefaultAsync", "QueryMultiple", "QueryMultipleAsync", "Execute", "ExecuteAsync",
                "ExecuteScalar", "ExecuteScalarAsync", "ExecuteReader", "ExecuteReaderAsync")) {
            METHODS.put(m, sql);
        }

        // 경로
        CsRule path = rule("PATH", "CWE-22", Condition.NOT_CONSTANT);
        for (String m : List.of("ReadAllText", "ReadAllTextAsync", "ReadAllBytes", "ReadAllBytesAsync", "ReadAllLines", "ReadAllLinesAsync",
                "OpenRead", "Open", "OpenText", "WriteAllText", "WriteAllTextAsync", "WriteAllBytes", "WriteAllBytesAsync", "Delete",
                "Copy", "Move", "Create", "AppendAllText")) {
            named(path, "File." + m);
        }
        named(path, "Directory.Delete", "Directory.GetFiles", "Directory.EnumerateFiles", "new FileStream", "PhysicalFile", "Results.File", "TypedResults.PhysicalFile", "Results.PhysicalFile");

        // SSRF
        CsRule ssrf = rule("SSRF", "CWE-918", Condition.NOT_CONSTANT);
        for (String m : List.of("GetAsync", "GetStringAsync", "GetByteArrayAsync", "GetStreamAsync", "PostAsync", "PutAsync",
                "DeleteAsync", "GetFromJsonAsync", "PostAsJsonAsync")) {
            named(ssrf, "HttpClient." + m);
        }
        named(ssrf, "WebClient.DownloadString", "WebClient.DownloadData", "WebClient.DownloadFile", "WebRequest.Create",
                "WebRequest.CreateHttp");
        named(rule("SSRF", "CWE-918", Condition.NOT_CONSTANT, 1), "new HttpRequestMessage");

        // 역직렬화
        CsRule deser = rule("DESERIALIZATION", "CWE-502", Condition.ALWAYS);
        for (String type : List.of("BinaryFormatter", "SoapFormatter", "NetDataContractSerializer", "LosFormatter", "ObjectStateFormatter")) {
            named(deser, type + ".Deserialize");
        }
        named(rule("DESERIALIZATION", "CWE-502", Condition.TYPE_NAME_HANDLING), "JsonConvert.DeserializeObject",
                "JsonSerializer.Deserialize");

        // XXE (.NET Core 기본은 안전: XmlResolver 를 켰을 때만)
        CsRule xxe = rule("XXE", "CWE-611", Condition.XML_RESOLVER);
        named(xxe, "XmlDocument.Load", "XmlDocument.LoadXml", "XmlReader.Create", "XDocument.Load", "XElement.Load");

        // 오픈 리다이렉트 (LocalRedirect·LocalRedirectPermanent 는 안전)
        named(rule("OPEN_REDIRECT", "CWE-601", Condition.REQUEST_VALUE), "Redirect", "RedirectPermanent", "Results.Redirect",
                "TypedResults.Redirect", "Response.Redirect");

        // XSS: 요청 값을 HTML 응답으로
        named(rule("XSS", "CWE-79", Condition.HTML_REQUEST_VALUE), "Content", "Results.Content", "TypedResults.Content");
        named(rule("XSS", "CWE-79", Condition.REQUEST_VALUE), "new HtmlString", "Html.Raw");

        // 약한 암호·난수
        CsRule weakHash = rule("WEAK_CRYPTO", "CWE-328", Condition.ALWAYS);
        named(weakHash, "MD5.Create", "SHA1.Create", "MD5.HashData", "SHA1.HashData", "new MD5CryptoServiceProvider",
                "new SHA1CryptoServiceProvider", "new SHA1Managed");
        named(rule("WEAK_CRYPTO", "CWE-327", Condition.ALWAYS), "DES.Create", "TripleDES.Create", "RC2.Create",
                "new DESCryptoServiceProvider");
        CsRule weakRandom = rule("WEAK_RANDOM", "CWE-338", Condition.ALWAYS);
        named(weakRandom, "new Random", "Random.Shared.Next", "Random.Shared.NextInt64", "Random.Shared.NextBytes", "Random.Shared.NextDouble");
    }

    private CsSinkRules() {
    }

    public static Optional<CsRule> named(String name) {
        return Optional.ofNullable(NAMED.get(name));
    }

    public static Optional<CsRule> method(String name) {
        return Optional.ofNullable(METHODS.get(name));
    }
}
