package com.codeagent.core.objc;

import org.treesitter.TSNode;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 파싱한 Objective-C 파일 하나 (.m, .mm, @interface 가 있는 .h).
 * tree-sitter 노드는 트리가 살아 있을 때만 쓸 수 있으므로 트리를 같이 들고 있는다.
 */
public final class ObjcFile {

    final String filePath;
    final byte[] source;
    final TSTree tree;
    final TSNode root;

    ObjcFile(String filePath, byte[] source, TSTree tree) {
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

    /** 자손 중 종류가 같은 것 (자기 자신 제외). stopAt 종류 안으로는 들어가지 않는다 */
    static List<TSNode> descendants(TSNode node, String type, String stopAt) {
        List<TSNode> result = new ArrayList<>();
        for (TSNode child : namedChildren(node)) {
            if (child.getType().equals(type)) {
                result.add(child);
            }
            if (stopAt == null || !child.getType().equals(stopAt)) {
                result.addAll(descendants(child, type, stopAt));
            }
        }
        return result;
    }

    /** 이름 없는 자식(키워드) 중에 keyword 가 있는지  */
    static boolean hasKeyword(TSNode node, String keyword) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i).getType().equals(keyword)) {
                return true;
            }
        }
        return false;
    }

    static int line(TSNode node) {
        return node.getStartPoint().getRow() + 1;
    }

    static int endLine(TSNode node) {
        return node.getEndPoint().getRow() + 1;
    }
}
