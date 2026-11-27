package com.codeagent.core.php;

import org.treesitter.TSNode;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 파싱한 PHP 파일 하나. tree-sitter 노드는 트리가 살아 있을 때만 쓸 수 있으므로 트리를 같이 들고 있는다.
 */
public final class PhpFile {

    final String filePath;
    final byte[] source;
    final TSTree tree;
    final TSNode root;
    /** 파일의 namespace ("" 이면 전역) */
    String namespace = "";
    /** use 별칭 → 전체 이름 (Request → Illuminate\Http\Request) */
    final Map<String, String> uses = new LinkedHashMap<>();

    PhpFile(String filePath, byte[] source, TSTree tree) {
        this.filePath = filePath;
        this.source = source;
        this.tree = tree;
        this.root = tree.getRootNode();
    }

    public String filePath() {
        return filePath;
    }

    String text(TSNode node) {
        if (isNull(node)) {
            return "";
        }
        int start = node.getStartByte();
        int end = Math.min(node.getEndByte(), source.length);
        return start >= end ? "" : new String(source, start, end - start, StandardCharsets.UTF_8);
    }

    static boolean isNull(TSNode node) {
        return node == null || node.isNull();
    }

    static List<TSNode> namedChildren(TSNode node) {
        List<TSNode> result = new ArrayList<>();
        if (isNull(node)) {
            return result;
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            result.add(node.getNamedChild(i));
        }
        return result;
    }

    static TSNode firstChild(TSNode node, String type) {
        for (TSNode child : namedChildren(node)) {
            if (child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }

    static List<TSNode> children(TSNode node, String type) {
        return namedChildren(node).stream().filter(c -> c.getType().equals(type)).toList();
    }

    static TSNode field(TSNode node, String name) {
        if (isNull(node)) {
            return null;
        }
        TSNode child = node.getChildByFieldName(name);
        return isNull(child) ? null : child;
    }

    /** 자손 중 종류가 맞는 것 (자기 자신 제외). stop 에 든 종류 안으로는 들어가지 않는다 */
    static List<TSNode> descendants(TSNode node, Set<String> types, Set<String> stop) {
        List<TSNode> result = new ArrayList<>();
        for (TSNode child : namedChildren(node)) {
            if (types.contains(child.getType())) {
                result.add(child);
            }
            if (!stop.contains(child.getType())) {
                result.addAll(descendants(child, types, stop));
            }
        }
        return result;
    }

    static int line(TSNode node) {
        return node.getStartPoint().getRow() + 1;
    }

    static int endLine(TSNode node) {
        return node.getEndPoint().getRow() + 1;
    }

    /** 'x' · "x" (변수가 없는 문자열) 의 내용. 그 밖은 null */
    String stringValue(TSNode node) {
        node = unwrap(node);
        if (isNull(node)) {
            return null;
        }
        String type = node.getType();
        if (type.equals("string") || type.equals("encapsed_string") && interpolationFree(node)) {
            StringBuilder sb = new StringBuilder();
            for (TSNode part : namedChildren(node)) {
                sb.append(part.getType().equals("escape_sequence") ? unescape(text(part)) : text(part));
            }
            return sb.toString();
        }
        return null;
    }

    private static String unescape(String escape) {
        return switch (escape) {
            case "\\n" -> "\n";
            case "\\t" -> "\t";
            default -> escape.length() > 1 ? escape.substring(1) : escape;
        };
    }

    /** 변수·식이 끼어 있지 않은 문자열 */
    static boolean interpolationFree(TSNode node) {
        for (TSNode part : namedChildren(node)) {
            if (!part.getType().equals("string_content") && !part.getType().equals("escape_sequence")
                    && !part.getType().equals("string_value")) {
                return false;
            }
        }
        return true;
    }

    /** argument · 괄호를 벗긴 식 */
    static TSNode unwrap(TSNode node) {
        TSNode current = node;
        while (!isNull(current) && (current.getType().equals("parenthesized_expression") || current.getType().equals("argument"))) {
            List<TSNode> inner = namedChildren(current);
            current = inner.isEmpty() ? null : inner.get(inner.size() - 1);
        }
        return current;
    }

    /** 호출 인자 식 목록 (argument 를 벗긴다) */
    static List<TSNode> arguments(TSNode call) {
        TSNode args = field(call, "arguments");
        if (args == null) {
            args = firstChild(call, "arguments");
        }
        List<TSNode> result = new ArrayList<>();
        for (TSNode arg : namedChildren(args)) {
            TSNode inner = unwrap(arg);
            if (inner != null) {
                result.add(inner);
            }
        }
        return result;
    }

    /** 이름 있는 인자 (methods: ['GET']) */
    TSNode namedArgument(TSNode call, String name) {
        TSNode args = field(call, "arguments");
        if (args == null) {
            args = firstChild(call, "arguments");
        }
        if (args == null) {
            args = field(call, "parameters");
        }
        for (TSNode arg : namedChildren(args)) {
            TSNode argName = field(arg, "name");
            if (argName != null && text(argName).equals(name)) {
                return unwrap(arg);
            }
        }
        return null;
    }

    /** 위치 인자 (이름 있는 인자는 뺀다) */
    List<TSNode> positionalArguments(TSNode argumentsNode) {
        List<TSNode> result = new ArrayList<>();
        for (TSNode arg : namedChildren(argumentsNode)) {
            if (field(arg, "name") == null) {
                TSNode inner = unwrap(arg);
                if (inner != null) {
                    result.add(inner);
                }
            }
        }
        return result;
    }
}
