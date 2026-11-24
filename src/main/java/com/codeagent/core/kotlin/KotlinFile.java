package com.codeagent.core.kotlin;

import org.treesitter.TSNode;
import org.treesitter.TSTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 파싱한 Kotlin 파일 하나. 패키지, import 표, tree-sitter 트리를 가진다.
 * tree-sitter 노드는 트리가 살아 있을 때만 쓸 수 있으므로 트리를 같이 들고 있는다.
 */
public final class KotlinFile {

    final String filePath;
    final String packageName;
    /** 이 파일에서 쓰는 이름 → FQN (import a.b.C → C: a.b.C, import a.b.C as D → D: a.b.C) */
    final Map<String, String> imports = new LinkedHashMap<>();
    /** import a.b.* 의 a.b */
    final List<String> wildcardImports = new ArrayList<>();
    final byte[] source;
    final TSTree tree;
    final TSNode root;

    KotlinFile(String filePath, byte[] source, TSTree tree) {
        this.filePath = filePath;
        this.source = source;
        this.tree = tree;
        this.root = tree.getRootNode();
        String pkg = "";
        for (TSNode child : namedChildren(root)) {
            if (child.getType().equals("package_header")) {
                TSNode id = firstChild(child, "identifier");
                pkg = id == null ? "" : compact(text(id));
            } else if (child.getType().equals("import_list")) {
                for (TSNode header : namedChildren(child)) {
                    if (header.getType().equals("import_header")) {
                        addImport(header);
                    }
                }
            } else if (child.getType().equals("import_header")) {
                addImport(child);
            }
        }
        this.packageName = pkg;
    }

    public String filePath() {
        return filePath;
    }

    public String packageName() {
        return packageName;
    }

    private void addImport(TSNode header) {
        TSNode id = firstChild(header, "identifier");
        if (id == null) {
            return;
        }
        String name = compact(text(id));
        String all = compact(text(header));
        if (all.endsWith(".*")) {
            wildcardImports.add(name);
            return;
        }
        TSNode alias = firstChild(header, "import_alias");
        String simple = alias != null ? compact(text(alias)).replaceFirst("^as", "") : name.substring(name.lastIndexOf('.') + 1);
        imports.put(simple, name);
    }

    // ---- 노드 헬퍼 ----

    /** 노드의 원문. 위치가 UTF-8 바이트 기준이라 바이트로 자른다 */
    String text(TSNode node) {
        if (isNull(node)) {
            return "";
        }
        int start = node.getStartByte();
        int end = Math.min(node.getEndByte(), source.length);
        return start >= end ? "" : new String(source, start, end - start, java.nio.charset.StandardCharsets.UTF_8);
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

    /** 자손 전체에서 종류가 같은 것 (자기 자신 제외, 앞에서부터). stopAt 종류 안으로는 들어가지 않는다 */
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

    static int line(TSNode node) {
        return node.getStartPoint().getRow() + 1;
    }

    static int endLine(TSNode node) {
        return node.getEndPoint().getRow() + 1;
    }

    /** 공백·줄바꿈을 지운다 (패키지·import 이름) */
    static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }
}
