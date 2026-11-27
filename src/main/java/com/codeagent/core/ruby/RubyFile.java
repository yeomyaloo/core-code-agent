package com.codeagent.core.ruby;

import org.treesitter.TSNode;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 파싱한 Ruby 파일 하나. tree-sitter 노드는 트리가 살아 있을 때만 쓸 수 있으므로 트리를 같이 들고 있는다.
 */
public final class RubyFile {

    final String filePath;
    final byte[] source;
    final TSTree tree;
    final TSNode root;

    RubyFile(String filePath, byte[] source, TSTree tree) {
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

    /** 'x' · "x" (보간 없음) · :x · x: 의 내용. 그 밖은 null */
    String stringValue(TSNode node) {
        node = unwrap(node);
        if (isNull(node)) {
            return null;
        }
        switch (node.getType()) {
            case "string" -> {
                if (!interpolationFree(node)) {
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                namedChildren(node).forEach(c -> sb.append(text(c)));
                return sb.toString();
            }
            case "simple_symbol" -> {
                return text(node).substring(1);
            }
            case "hash_key_symbol" -> {
                return text(node);
            }
            case "delimited_symbol" -> {
                String t = text(node);
                return t.length() > 3 ? t.substring(2, t.length() - 1) : null;
            }
            default -> {
                return null;
            }
        }
    }

    static boolean interpolationFree(TSNode node) {
        return firstChild(node, "interpolation") == null;
    }

    static TSNode unwrap(TSNode node) {
        TSNode current = node;
        while (!isNull(current) && (current.getType().equals("parenthesized_statements") || current.getType().equals("begin_block"))) {
            List<TSNode> inner = namedChildren(current);
            current = inner.isEmpty() ? null : inner.get(inner.size() - 1);
        }
        return current;
    }

    /** 호출의 위치 인자 (키워드 인자·블록 인자 제외) */
    static List<TSNode> positional(TSNode call) {
        List<TSNode> result = new ArrayList<>();
        for (TSNode arg : namedChildren(field(call, "arguments"))) {
            if (!arg.getType().equals("pair") && !arg.getType().equals("block_argument") && !arg.getType().equals("hash_splat_argument")) {
                result.add(arg);
            }
        }
        return result;
    }

    /** 모든 인자 식 (키워드 인자는 값) */
    static List<TSNode> argumentValues(TSNode call) {
        List<TSNode> result = new ArrayList<>();
        for (TSNode arg : namedChildren(field(call, "arguments"))) {
            if (arg.getType().equals("pair")) {
                TSNode value = field(arg, "value");
                if (value != null) {
                    result.add(value);
                }
            } else if (!arg.getType().equals("block_argument")) {
                result.add(arg);
            }
        }
        return result;
    }

    /** 키워드 인자 값 (to: 'x', only: [...], 'path' => 'c#a' 의 키도) */
    TSNode keyword(TSNode call, String key) {
        for (TSNode arg : namedChildren(field(call, "arguments"))) {
            if (arg.getType().equals("pair") && key.equals(stringValue(field(arg, "key")))) {
                return field(arg, "value");
            }
        }
        return null;
    }

    /** 'path' => 'c#a' 형태의 첫 쌍 */
    TSNode[] rocketPair(TSNode call) {
        for (TSNode arg : namedChildren(field(call, "arguments"))) {
            TSNode key = field(arg, "key");
            if (arg.getType().equals("pair") && key != null && key.getType().equals("string")) {
                return new TSNode[]{key, field(arg, "value")};
            }
        }
        return null;
    }

    /** 'x' 나 :x 나 [...] 의 문자열 목록 */
    List<String> strings(TSNode node) {
        node = unwrap(node);
        List<String> result = new ArrayList<>();
        if (isNull(node)) {
            return result;
        }
        String single = stringValue(node);
        if (single != null) {
            result.add(single);
        } else if (node.getType().equals("array")) {
            for (TSNode item : namedChildren(node)) {
                String value = stringValue(item);
                if (value != null) {
                    result.add(value);
                }
            }
        }
        return result;
    }
}
