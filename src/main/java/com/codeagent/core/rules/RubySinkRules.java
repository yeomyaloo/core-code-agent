package com.codeagent.core.rules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ruby 위험 지점 규칙. 이름은 받는 쪽이 없는 호출 "system", 클래스 메서드 "File.read"·"Net::HTTP.get".
 * 받는 쪽 타입을 모르는 메서드(User.where, relation.order)는 {@link #method(String)} 로 이름만 보고 맞춘다.
 */
public final class RubySinkRules {

    public enum Condition {
        /** 항상 */
        ALWAYS,
        /** {@link RubyRule#argument()} 번째 인자가 상수가 아니다 */
        NOT_CONSTANT,
        /** 인자 중 하나가 보간·+ 로 조립한 문자열이다 */
        FORMATTED,
        /** 인자 중 하나가 요청 값이다 */
        REQUEST_VALUE,
        /** 받는 쪽이 요청 값이다 (params[:q].html_safe) */
        RECEIVER_REQUEST
    }

    public record RubyRule(String category, String cwe, Condition condition, int argument) {
    }

    private static final Map<String, RubyRule> NAMED = new HashMap<>();
    private static final Map<String, RubyRule> METHODS = new HashMap<>();

    private static void named(RubyRule rule, String... names) {
        for (String name : names) {
            NAMED.put(name, rule);
        }
    }

    private static RubyRule rule(String category, String cwe, Condition condition) {
        return new RubyRule(category, cwe, condition, 0);
    }

    static {
        // 명령 실행 (open("|cmd") 도 명령)
        named(rule("COMMAND", "CWE-78", Condition.NOT_CONSTANT), "system", "exec", "spawn", "open", "Kernel.system", "Kernel.exec",
                "Kernel.spawn", "Kernel.open", "IO.popen", "Open3.capture2", "Open3.capture2e", "Open3.capture3", "Open3.popen2",
                "Open3.popen2e", "Open3.popen3", "Open3.pipeline", "Process.spawn", "PTY.spawn");

        // 코드 실행·템플릿 주입
        named(rule("CODE_INJECTION", "CWE-95", Condition.NOT_CONSTANT), "eval", "instance_eval", "class_eval", "module_eval",
                "Kernel.eval");
        named(rule("CODE_INJECTION", "CWE-1336", Condition.NOT_CONSTANT), "ERB.new", "Liquid::Template.parse", "Haml::Engine.new",
                "Slim::Template.new");

        // SQL (ActiveRecord·연결 직접 실행)
        RubyRule sql = rule("SQL", "CWE-89", Condition.FORMATTED);
        for (String m : List.of("where", "not", "rewhere", "find_by_sql", "count_by_sql", "exists?", "having", "joins", "from", "lock",
                "delete_all", "update_all", "destroy_by", "delete_by", "find_by", "execute", "exec_query", "select_all", "select_one",
                "select_value", "select_values", "select_rows", "exec_update", "exec_delete", "exec_insert", "run")) {
            METHODS.put(m, sql);
        }
        // order(params[:sort]) 처럼 SQL 조각을 그대로 받는 메서드: 상수가 아니면 (기호·해시 형태는 상수)
        RubyRule sqlRaw = rule("SQL", "CWE-89", Condition.NOT_CONSTANT);
        for (String m : List.of("order", "reorder", "group", "pluck", "select", "calculate", "minimum", "maximum", "sum", "average")) {
            METHODS.put(m, sqlRaw);
        }

        // 경로
        RubyRule path = rule("PATH", "CWE-22", Condition.NOT_CONSTANT);
        named(path, "File.read", "File.binread", "File.readlines", "File.open", "File.new", "File.write", "File.binwrite", "File.delete",
                "File.unlink", "File.rename", "File.foreach", "IO.read", "IO.binread", "IO.readlines", "IO.foreach", "FileUtils.rm",
                "FileUtils.rm_f", "FileUtils.rm_r", "FileUtils.rm_rf", "FileUtils.cp", "FileUtils.mv", "FileUtils.mkdir_p", "Dir.glob",
                "Dir.entries", "Pathname.new", "send_file");

        // SSRF
        RubyRule ssrf = rule("SSRF", "CWE-918", Condition.NOT_CONSTANT);
        named(ssrf, "Net::HTTP.get", "Net::HTTP.get_response", "Net::HTTP.start", "Net::HTTP.new", "Net::HTTP.post", "Net::HTTP.post_form",
                "URI.open", "HTTParty.get", "HTTParty.post", "HTTParty.put", "HTTParty.delete", "Faraday.get", "Faraday.post", "Faraday.new",
                "RestClient.get", "RestClient.post", "RestClient.put", "RestClient.delete", "Excon.get", "Excon.post", "HTTP.get",
                "HTTP.post", "Typhoeus.get", "Typhoeus.post");

        // 역직렬화 (Psych 4 의 YAML.load 는 safe_load 라서 뺀다)
        named(rule("DESERIALIZATION", "CWE-502", Condition.NOT_CONSTANT), "Marshal.load", "Marshal.restore", "YAML.unsafe_load",
                "Psych.unsafe_load", "Oj.load", "Oj.object_load");

        // 오픈 리다이렉트: 요청 값일 때만 (redirect_to root_path 같은 경로 헬퍼는 상수가 아니어도 안전하다)
        named(rule("OPEN_REDIRECT", "CWE-601", Condition.REQUEST_VALUE), "redirect_to", "redirect", "redirect_back");

        // XSS: 이스케이프를 끄는 것
        named(rule("XSS", "CWE-79", Condition.REQUEST_VALUE), "raw");
        METHODS.put("html_safe", rule("XSS", "CWE-79", Condition.RECEIVER_REQUEST));

        // 약한 암호·난수
        RubyRule weakHash = rule("WEAK_CRYPTO", "CWE-328", Condition.ALWAYS);
        for (String digest : List.of("Digest::MD5", "Digest::SHA1", "OpenSSL::Digest::MD5", "OpenSSL::Digest::SHA1")) {
            for (String m : List.of("hexdigest", "digest", "base64digest", "new", "file")) {
                named(weakHash, digest + "." + m);
            }
        }
        named(rule("WEAK_RANDOM", "CWE-338", Condition.ALWAYS), "rand", "srand", "Random.rand", "Random.new", "Kernel.rand");
    }

    private RubySinkRules() {
    }

    public static Optional<RubyRule> named(String name) {
        return Optional.ofNullable(NAMED.get(name));
    }

    public static Optional<RubyRule> method(String name) {
        return Optional.ofNullable(METHODS.get(name));
    }
}
