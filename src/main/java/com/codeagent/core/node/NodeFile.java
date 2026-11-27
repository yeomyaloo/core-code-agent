package com.codeagent.core.node;

import org.treesitter.TSNode;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 파싱한 JavaScript·TypeScript 파일 하나. tree-sitter 노드는 트리가 살아 있을 때만 쓸 수 있으므로 트리를 같이 들고 있는다.
 */
public final class NodeFile {

    final String filePath;
    final byte[] source;
    final TSTree tree;
    final TSNode root;
    final boolean typeScript;

    NodeFile(String filePath, byte[] source, TSTree tree, boolean typeScript) {
        this.filePath = filePath;
        this.source = source;
        this.tree = tree;
        this.root = tree.getRootNode();
        this.typeScript = typeScript;
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
    static List<TSNode> descendants(TSNode node, java.util.Set<String> types, java.util.Set<String> stop) {
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

    /** 'x' · "x" · `x` (치환 없는 템플릿) 의 내용. 그 밖은 null */
    String stringValue(TSNode node) {
        if (isNull(node)) {
            return null;
        }
        if (node.getType().equals("string")) {
            StringBuilder sb = new StringBuilder();
            namedChildren(node).forEach(c -> sb.append(text(c)));
            return sb.toString();
        }
        if (node.getType().equals("template_string") && firstChild(node, "template_substitution") == null) {
            String t = text(node);
            return t.length() >= 2 ? t.substring(1, t.length() - 1) : "";
        }
        return null;
    }
}
