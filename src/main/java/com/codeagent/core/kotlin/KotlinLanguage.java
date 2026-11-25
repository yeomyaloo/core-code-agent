package com.codeagent.core.kotlin;

import com.codeagent.core.LanguageParser;
import com.codeagent.core.SourceFiles;
import com.codeagent.core.android.AndroidDetector;
import com.codeagent.core.android.AndroidManifest;
import com.codeagent.core.android.AndroidSinkRules;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.rules.SinkRule;
import com.codeagent.core.rules.SinkRules;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Kotlin 파서 (tree-sitter). 구조·호출 관계와 서버 위험 지점 규칙(SinkRules)으로 그래프를 더한다.
 * Spring 컨트롤러(@RestController·@GetMapping 등)를 진입점으로 찾는다({@link KotlinEntryPointDetector}).
 * 저장소에 AndroidManifest.xml 이 있으면 안드로이드 앱으로 보고 모바일 진입점·규칙({@link AndroidDetector}, {@link AndroidSinkRules})도 함께 적용한다.
 */
public final class KotlinLanguage implements LanguageParser {

    /** 라이브러리 jar 없이 반환 타입을 아는 JDK 메서드 */
    private static final Map<String, String> JDK_RETURN_TYPES = Map.of("getRuntime", "java.lang.Runtime");

    @Override
    public String language() {
        return "kotlin";
    }

    @Override
    public boolean detect(Path root) {
        return SourceFiles.any(root, KotlinProject.SKIP_DIRS, KotlinProject::isSource);
    }

    /** Java 파서가 Kotlin 까지 같이 만든 저장소 (다시 만들지 않는다) */
    private Path builtWithJava;

    @Override
    public void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors) {
        if (root.equals(builtWithJava)) {
            return;
        }
        KotlinProject project = KotlinProject.load(root, parseErrors);
        build(project, support(project, draft));
    }

    /** 서버 규칙으로 Kotlin 그래프를 만드는 부품 (호출을 잇기 전에 Java 와 연결할 수 있게 따로 꺼낸다) */
    public KotlinGraphSupport support(KotlinProject project, CodeGraphDraft draft) {
        return new KotlinGraphSupport(project, draft, JDK_RETURN_TYPES);
    }

    public void build(KotlinProject project, KotlinGraphSupport support) {
        // AndroidManifest.xml 이 있으면 안드로이드 앱으로 보고 모바일 진입점·규칙을 함께 적용한다
        Optional<AndroidManifest> manifest = AndroidManifest.parse(project.root(), new ArrayList<>());
        boolean android = manifest.isPresent();
        BiFunction<String, String, Optional<SinkRule>> rules = android
                ? (type, method) -> {
                    Optional<SinkRule> server = SinkRules.match(type, method);
                    return server.isPresent() ? server : AndroidSinkRules.match(type, method);
                }
                : SinkRules::match;

        project.types().forEach(support::addStructure);
        project.types().forEach(support::addOverrides);
        KotlinEntryPointDetector detector = new KotlinEntryPointDetector();
        KotlinRouterDetector routerDetector = new KotlinRouterDetector();
        CodeGraphDraft draft = support.draft();
        for (KotlinProject.KotlinType type : project.types()) {
            for (KotlinEntryPointDetector.KotlinEntryPoint entry : detector.detect(project, type)) {
                KotlinProject.KotlinFunction handler = entry.handler();
                String entryKey = draft.addNode(new CodeGraphDraft.NodeDraft(CodeNodeKind.ENTRY_POINT, entry.qualifiedName(),
                        type.filePath(), handler.startLine(), handler.endLine(), Map.of(
                        "httpMethod", entry.httpMethod(),
                        "path", entry.path(),
                        "handler", handler.key(),
                        "language", "kotlin")));
                draft.addEdge(entryKey, KotlinGraphSupport.methodKey(handler), CodeEdgeKind.ROUTES_TO);
            }
            // WebFlux 라우터 DSL (router { } / coRouter { })
            for (KotlinRouterDetector.RouterEntry entry : routerDetector.detect(project, type)) {
                KotlinProject.KotlinFunction target = entry.target();
                String entryKey = draft.addNode(new CodeGraphDraft.NodeDraft(CodeNodeKind.ENTRY_POINT, entry.qualifiedName(),
                        target.owner().filePath(), target.startLine(), target.endLine(), Map.of(
                        "httpMethod", entry.httpMethod(),
                        "path", entry.path(),
                        "handler", target.key(),
                        "router", entry.router().key(),
                        "language", "kotlin")));
                draft.addEdge(entryKey, KotlinGraphSupport.methodKey(target), CodeEdgeKind.ROUTES_TO);
            }
        }
        project.types().forEach(t -> t.functions().forEach(f -> support.addCalls(f, rules, Map.of())));

        // 안드로이드 외부 공격면: 소켓 진입점, 매니페스트 exported/딥링크/평문, 인증 없는 소켓 핸드셰이크, 토큰 저장
        manifest.ifPresent(m -> new AndroidDetector(project, support).contribute(m));
    }

    /** Java 파서가 이 저장소의 Kotlin 을 이미 만들었다 */
    public void builtWithJava(Path root) {
        this.builtWithJava = root;
    }
}
