package com.codeagent.core.python;

import com.codeagent.core.LanguageParser;
import com.codeagent.core.SourceFiles;
import com.codeagent.core.model.CodeGraphDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Python 파서 (tree-sitter). FastAPI 진입점과 Python 위험 지점을 그래프에 더한다.
 * 테스트 코드(tests/, test_*.py, *_test.py, conftest.py)와 가상환경·캐시 폴더는 건너뛴다.
 * tree-sitter 네이티브 라이브러리를 불러오지 못하는 OS/CPU 에서는 파이썬 파일을 건너뛰고 parseErrors 에 남긴다.
 */
public final class PythonLanguage implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(PythonLanguage.class);

    static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "__pycache__", ".venv", "venv", "env", ".env",
            ".tox", ".nox", ".mypy_cache", ".pytest_cache", ".ruff_cache", "site-packages", "dist", "build",
            ".eggs", "tests", "test", "testing");
    private static final long MAX_FILE_BYTES = 1_000_000;

    @Override
    public String language() {
        return "python";
    }

    @Override
    public boolean detect(Path root) {
        return SourceFiles.any(root, SKIP_DIRS, PythonLanguage::isSource);
    }

    static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".py") && !name.startsWith("test_") && !name.endsWith("_test.py") && !name.equals("conftest.py");
    }

    @Override
    public void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors) {
        TSLanguage language;
        TSParser parser;
        try {
            language = new TreeSitterPython();
            parser = new TSParser();
            parser.setLanguage(language);
        } catch (LinkageError | RuntimeException e) {
            parseErrors.add("(python): 파이썬 파서를 불러오지 못해 .py 파일을 건너뜀 (지원하지 않는 OS/CPU 일 수 있음: "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + ") " + e);
            return;
        }

        List<PythonModule> modules = new ArrayList<>();
        for (Path file : SourceFiles.find(root, SKIP_DIRS, MAX_FILE_BYTES, PythonLanguage::isSource)) {
            String relative = SourceFiles.relative(root, file);
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                parseErrors.add(relative + ": 읽지 못함 (" + e.getMessage() + ")");
                continue;
            }
            String source = new String(bytes, StandardCharsets.UTF_8);
            // tree-sitter 의 위치는 UTF-8 바이트 기준이라, 디코딩한 문자열을 다시 인코딩한 바이트로 자른다
            byte[] utf8 = source.getBytes(StandardCharsets.UTF_8);
            try {
                TSTree tree = parser.parseString(null, source);
                TSNode rootNode = tree.getRootNode();
                if (rootNode.hasError()) {
                    parseErrors.add(relative + ": 문법 오류가 있는 부분만 건너뛰고 분석함 (" + firstErrorLine(rootNode) + "번째 줄 부근)");
                }
                modules.add(new PythonModule(moduleName(relative), relative, relative.endsWith("__init__.py"), utf8, tree));
            } catch (RuntimeException e) {
                parseErrors.add(relative + ": 파싱 실패 (" + e.getMessage() + ")");
                log.debug("파이썬 파싱 실패: {}", relative, e);
            }
        }
        new PythonGraphBuilder(modules, draft).build();
    }

    /** app/api/scan.py → app.api.scan, app/__init__.py → app */
    static String moduleName(String relativePath) {
        String withoutExt = relativePath.substring(0, relativePath.length() - ".py".length());
        if (withoutExt.endsWith("/__init__")) {
            withoutExt = withoutExt.substring(0, withoutExt.length() - "/__init__".length());
        } else if (withoutExt.equals("__init__")) {
            withoutExt = "";
        }
        return withoutExt.replace('/', '.');
    }

    private static int firstErrorLine(TSNode node) {
        if (node.isError() || node.isMissing()) {
            return node.getStartPoint().getRow() + 1;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child.hasError() || child.isMissing()) {
                return firstErrorLine(child);
            }
        }
        return node.getStartPoint().getRow() + 1;
    }
}
