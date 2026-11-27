package com.codeagent.core.csharp;

import org.treesitter.TSNode;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 파싱한 C# 파일 하나. tree-sitter 노드는 트리가 살아 있을 때만 쓸 수 있으므로 트리를 같이 들고 있는다.
 */
public final class CsFile {

    final String filePath;
    final byte[] source;
    final TSTree tree;
    final TSNode root;
    /** 파일의 namespace (블록 namespace 가 여럿이면 첫 것) */
    String namespace = "";
    /** using 별칭 → 이름 (using Db = Shop.Data.AppDbContext) */
    final Map<String, String> aliases = new LinkedHashMap<>();
    /** using 한 namespace 들 */
    final List<String> usings = new ArrayList<>();

    CsFile(String filePath, byte[] source, TSTree tree) {
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

    /** "x" · @"x" · """x""" 의 내용. 보간 문자열이나 그 밖은 null */
    String stringValue(TSNode node) {
        node = unwrap(node);
        if (isNull(node)) {
            return null;
        }
        if (node.getType().equals("string_literal") || node.getType().equals("verbatim_string_literal")
                || node.getType().equals("raw_string_literal")) {
            String t = text(node);
            if (node.getType().equals("string_literal")) {
                StringBuilder sb = new StringBuilder();
                namedChildren(node).forEach(c -> sb.append(text(c)));
                return sb.toString();
            }
            return t.replaceAll("^@?\"+", "").replaceAll("\"+$", "");
        }
        return null;
    }

    /** 괄호·argument 를 벗긴 식 */
    static TSNode unwrap(TSNode node) {
        TSNode current = node;
        while (!isNull(current) && (current.getType().equals("parenthesized_expression") || current.getType().equals("argument"))) {
            List<TSNode> inner = namedChildren(current);
            current = inner.isEmpty() ? null : inner.get(inner.size() - 1);
        }
        return current;
    }

    /** 호출 인자 식 (이름 붙인 인자도 값만) */
    static List<TSNode> arguments(TSNode call) {
        TSNode list = field(call, "arguments");
        if (list == null) {
            list = firstChild(call, "argument_list");
        }
        List<TSNode> result = new ArrayList<>();
        for (TSNode arg : children(list, "argument")) {
            TSNode inner = unwrap(arg);
            if (inner != null) {
                result.add(inner);
            }
        }
        return result;
    }

    /** 특성 [Name(args)] 들 (특성 이름은 Attribute 접미사를 뗀다) */
    static List<TSNode> attributes(TSNode declaration) {
        List<TSNode> result = new ArrayList<>();
        for (TSNode list : children(declaration, "attribute_list")) {
            result.addAll(children(list, "attribute"));
        }
        return result;
    }

    String attributeName(TSNode attribute) {
        String name = text(field(attribute, "name"));
        name = name.substring(name.lastIndexOf('.') + 1);
        return name.endsWith("Attribute") ? name.substring(0, name.length() - "Attribute".length()) : name;
    }

    /** 특성의 위치 인자 (Name = value 는 뺀다) */
    List<TSNode> attributePositional(TSNode attribute) {
        List<TSNode> result = new ArrayList<>();
        for (TSNode arg : descendants(attribute, Set.of("attribute_argument"), Set.of("attribute_argument"))) {
            List<TSNode> inner = namedChildren(arg);
            if (inner.size() == 1 && !inner.get(0).getType().equals("assignment_expression")) {
                result.add(inner.get(0));
            }
        }
        return result;
    }

    /** 특성의 이름 인자 (Roles = "Admin") */
    TSNode attributeNamed(TSNode attribute, String name) {
        for (TSNode arg : descendants(attribute, Set.of("attribute_argument"), Set.of("attribute_argument"))) {
            List<TSNode> inner = namedChildren(arg);
            if (inner.size() == 1 && inner.get(0).getType().equals("assignment_expression")
                    && text(field(inner.get(0), "left")).equals(name)) {
                return field(inner.get(0), "right");
            }
            // Name: value 형태
            if (inner.size() == 2 && text(inner.get(0)).equals(name)) {
                return inner.get(1);
            }
        }
        return null;
    }
}
