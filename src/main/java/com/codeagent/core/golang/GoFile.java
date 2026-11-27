package com.codeagent.core.golang;

import org.treesitter.TSNode;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 파싱한 Go 파일 하나. tree-sitter 노드는 트리가 살아 있을 때만 쓸 수 있으므로 트리를 같이 들고 있는다.
 */
public final class GoFile {

    final String filePath;
    final byte[] source;
    final TSTree tree;
    final TSNode root;
    /** 패키지 import 경로 (go.mod 의 module + 폴더) */
    String packagePath;
    /** 저장소 기준 폴더 ("" 이면 루트) */
    final String dir;
    /** 이 파일의 import 이름 → import 경로 (gin → github.com/gin-gonic/gin) */
    final Map<String, String> imports = new LinkedHashMap<>();

    GoFile(String filePath, byte[] source, TSTree tree) {
        this.filePath = filePath;
        this.source = source;
        this.tree = tree;
        this.root = tree.getRootNode();
        int slash = filePath.lastIndexOf('/');
        this.dir = slash < 0 ? "" : filePath.substring(0, slash);
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

    /** 같은 이름 필드가 여럿일 때 (parameter_declaration 의 name 들, var_spec 의 name 들) */
    static List<TSNode> fields(TSNode node, String name) {
        List<TSNode> result = new ArrayList<>();
        if (isNull(node)) {
            return result;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String fieldName = node.getFieldNameForChild(i);
            if (name.equals(fieldName)) {
                result.add(node.getChild(i));
            }
        }
        return result;
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

    /** "x" · `x` 의 내용. 그 밖은 null */
    String stringValue(TSNode node) {
        if (isNull(node)) {
            return null;
        }
        String type = node.getType();
        if (type.equals("interpreted_string_literal")) {
            String t = text(node);
            return t.length() >= 2 ? t.substring(1, t.length() - 1).replace("\\\"", "\"") : "";
        }
        if (type.equals("raw_string_literal")) {
            String t = text(node);
            return t.length() >= 2 ? t.substring(1, t.length() - 1) : "";
        }
        return null;
    }

    static boolean isString(TSNode node) {
        return !isNull(node) && (node.getType().equals("interpreted_string_literal") || node.getType().equals("raw_string_literal"));
    }

    /** 괄호를 벗긴 식 */
    static TSNode unwrap(TSNode node) {
        TSNode current = node;
        while (!isNull(current) && current.getType().equals("parenthesized_expression")) {
            List<TSNode> inner = namedChildren(current);
            current = inner.isEmpty() ? null : inner.get(0);
        }
        return current;
    }
}
