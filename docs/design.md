# 공용 코드 그래프 엔진 설계

## 1. 배경: 두 사본의 상태 (추출 전, 2026-11)

be-code-agent(`com.codeagent.codegraph.ingest`)와 mcp-code-agent(`com.codeagent.mcp.codegraph`)에 같은 엔진이 복사돼 있었다. 패키지 이름을 맞춰 비교한 결과:

| 파일 | 차이 |
|---|---|
| CodeGraphDraft, EntryPointDetector, FrontendScanner, SinkRule, SinkRules, TypeResolver, CodeNodeKind, CodeEdgeKind | 같음 |
| CodeGraphBuilder | 로그 선언(be: Lombok `@Slf4j`, mcp: slf4j `LoggerFactory`), `getFirst()`(Java 21) ↔ `get(0)` 2곳 |
| JavaProject | `getFirst()` ↔ `get(0)` 1곳 |

로직은 완전히 같았다. 차이는 mcp 가 Java 17 바이트코드(`release 17`)로 빌드하기 때문에 생긴 문법 차이뿐이다. 그래서 공용 엔진은 **Java 17 대상, Lombok 없이 slf4j-api** 로 만들었다(mcp 사본을 기준으로 옮김).

## 2. 공유 방식

| 안 | 장점 | 단점 |
|---|---|---|
| **(a) 별도 레포 + 버전드 아티팩트 (채택)** | 레포 3개 구조와 레포별 PR 을 그대로 유지. 버전 고정으로 회귀 통제. mcp 플러그인은 `server/lib` 에 jar 하나만 늘어남 | GitHub Packages(Maven)는 공개 패키지도 읽을 때 토큰 필요 |
| (b) 모노레포·멀티모듈 | 한 번 수정으로 모두 반영 | 레포 분리 원칙이 깨지고 플러그인 배포 레포가 무거워짐 |
| (c) git submodule | 설정이 단순해 보임 | 소스를 늘 같이 빌드해야 하고 포인터 관리 실수가 잦음 |

보완:
- 로컬 개발: 소비 레포에서 `-PcoreCodeAgentDir=../core-code-agent` 를 주면 `includeBuild` 로 소스를 바로 대체한다(composite build). 의존 좌표 `com.codeagent:core-code-agent` 가 이 빌드로 바뀐다.
- be Docker 이미지: 빌드 안에서 토큰을 쓰지 않도록 태그(`CORE_CODE_AGENT_REF`)의 소스를 `ADD <git>` 으로 받아 composite build 로 묶는다(core 레포가 공개여야 함).
- CI: be·mcp 워크플로는 `permissions: packages: read` 와 `GITHUB_TOKEN` 으로 받는다. 다른 레포의 패키지라서 core 패키지 설정의 "Manage Actions access" 에 be·mcp 레포를 추가해야 한다.

## 3. 공개 API 경계

```
입력  Path root (저장소 폴더)
출력  CodeGraphEngine.Result(CodeGraphDraft draft, List<String> parseErrors)
       draft: NodeDraft(kind, qualifiedName, filePath, startLine, endLine, props)
              EdgeDraft(srcKey, dstKey, kind)
```

- 노드 종류와 props 키를 그대로 유지했다. 그래서 be 의 DB 스키마(kind 문자열)와 mcp 의 도구 응답 형식이 바뀌지 않는다.
- 어댑터 지점
  - be: `codegraph/ingest/CodeGraphIndexer` (Draft → JDBC 배치 upsert), `codegraph/domain/CodeNode` 는 core 의 `CodeNodeKind` 를 그대로 쓴다. `AnalysisService` 는 `SinkRules.priority` 로 조사 순서를 정한다.
  - mcp: `codegraph/CodeGraph.build` (Draft → 번호 붙은 메모리 색인, BFS 경로 탐색).
- `java` 패키지(JavaParser 타입이 드러나는 `JavaProject` 등)는 공개돼 있지만 소비자는 쓰지 않는다. 테스트와 이후 플러그인 구현용이다.

## 4. 다음 단계: 언어 플러그인

```java
public interface LanguageParser {
    Language language();
    boolean detect(Path root);                 // 확장자·매니페스트(pom.xml, build.gradle, pyproject.toml, requirements.txt…)
    void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors);
}
```

- Java 파서는 지금의 `JavaProject` + `CodeGraphBuilder` 를 그대로 감싼다. 프론트엔드 스캐너도 별도 플러그인으로 분리한다.
- 노드 props 에 `language` 를 넣어 소비자(UI 뱃지 등)가 언어를 구분한다.
- Python 파싱 전략(추천): **tree-sitter-ng** (`io.github.bonede`, JNI, Java 8+).
  - 깨진 코드도 끝까지 파싱한다(실제 저장소에 중요). Python 과 TypeScript 문법을 같은 방식으로 쓸 수 있어 TS 확장이 그대로 이어진다.
  - 위험(추측, 첫 작업으로 시험 구현해 확인): 네이티브 라이브러리가 Windows/macOS/Linux, x64/arm64 에서 플러그인 `server/lib` 로 로드되는지.
  - 공식 `jtreesitter` 는 FFM 때문에 Java 22 이상이 필요해서(추측: 현재 버전 기준) mcp 의 Java 17 제약과 맞지 않는다.
  - 대안: ANTLR grammars-v4 Python 문법(순수 JVM, 오류에 약함). 파이썬 사이드카는 사용자 PC 에 Python 이 필요해서 제외.
- 호출 해석: 모듈별 import 표(`import subprocess`, `from x import y as z`)로 `module.function` 을 정규화한 뒤 규칙과 맞춘다. 해석하지 못한 호출은 `unresolvedCalls` 로 센다.

## 5. 다른 사본: phonetective (Sherlock Phones)

`com.sherlockphones.codegraph` 는 mcp 엔진을 안드로이드용으로 바꾼 사본이다. EntryPointDetector 대신 Component/AppScan/LineScanner 가 있다. 하지만 `JavaProject`·`TypeResolver`·`CodeGraphDraft` 는 같은 계열이라, 진입점 탐지기와 규칙 세트를 주입하는 SPI 를 열면 나중에 이 엔진 위에 안드로이드 규칙만 얹는 형태로 옮길 수 있다(추측, 이번 범위 밖).
