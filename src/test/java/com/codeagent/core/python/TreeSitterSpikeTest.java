package com.codeagent.core.python;

import org.junit.jupiter.api.Test;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tree-sitter-ng 로 Python 을 파싱할 수 있는지 확인하는 시험 테스트.
 * 네이티브 라이브러리 로드, 한글이 섞인 소스의 위치 계산, 문법 오류가 있을 때 나머지 파싱, 쿼리 사용을 본다.
 */
class TreeSitterSpikeTest {

    private static final String SOURCE = """
            # 장비 스캔 API (한글 주석: 바이트 위치 계산 확인용)
            import subprocess
            from fastapi import APIRouter, Depends
            import httpx as hx

            router = APIRouter(prefix="/scan")

            @router.post("/nmap")
            async def run_scan(target: str, user=Depends(current_user)):
                cmd = f"nmap -sV {target}"
                return subprocess.run(cmd, shell=True)

            def broken(:
                pass

            @router.get("/fetch")
            def fetch(url: str):
                return hx.get(url)
            """;

    @Test
    void 파이썬_소스를_파싱하고_오류가_있어도_나머지를_읽는다() {
        byte[] bytes = SOURCE.getBytes(StandardCharsets.UTF_8);
        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterPython());
        TSTree tree = parser.parseString(null, SOURCE);
        TSNode root = tree.getRootNode();

        assertThat(root.getType()).isEqualTo("module");
        assertThat(root.hasError()).isTrue(); // broken( 때문에

        TSQuery query = new TSQuery(new TreeSitterPython(), """
                (decorated_definition
                  (decorator (call function: (attribute object: (identifier) @obj attribute: (identifier) @verb)
                                   arguments: (argument_list (string) @path)))
                  definition: (function_definition name: (identifier) @fn))
                """);
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(query, root);
        List<String> routes = new ArrayList<>();
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            StringBuilder sb = new StringBuilder();
            for (TSQueryCapture c : match.getCaptures()) {
                sb.append(query.getCaptureNameForId(c.getIndex())).append('=').append(text(bytes, c.getNode())).append(' ');
            }
            routes.add(sb.toString().strip());
        }
        assertThat(routes).containsExactly(
                "obj=router verb=post path=\"/nmap\" fn=run_scan",
                "obj=router verb=get path=\"/fetch\" fn=fetch");

        List<String> calls = new ArrayList<>();
        collectCalls(root, bytes, calls);
        assertThat(calls).contains("subprocess.run@11", "hx.get@18", "Depends@9");
    }

    private static void collectCalls(TSNode node, byte[] bytes, List<String> out) {
        if (node.getType().equals("call")) {
            out.add(text(bytes, node.getChildByFieldName("function")) + "@" + (node.getStartPoint().getRow() + 1));
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            collectCalls(node.getNamedChild(i), bytes, out);
        }
    }

    private static String text(byte[] bytes, TSNode node) {
        return new String(bytes, node.getStartByte(), node.getEndByte() - node.getStartByte(), StandardCharsets.UTF_8);
    }
}
