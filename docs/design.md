# 공용 코드 그래프 엔진 설계

## 1. 배경: 두 사본의 상태 (추출 전, 2026-11)

cotcha-net(`com.codeagent.codegraph.ingest`)와 cotcha-pocket(`com.codeagent.mcp.codegraph`)에 같은 엔진이 복사돼 있었다. 패키지 이름을 맞춰 비교한 결과:

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
- 로컬 개발: 소비 레포에서 `-PcoreCodeAgentDir=../cotcha-trail` 를 주면 `includeBuild` 로 소스를 바로 대체한다(composite build). 의존 좌표 `com.codeagent:core-code-agent` 가 이 빌드로 바뀐다.
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

## 5. 2단계 결과: Python 파서 (v0.2.0)

시험 구현(spike) 결과 tree-sitter-ng 로 확정했다.

| 확인 항목 | 결과 |
|---|---|
| 좌표 | `io.github.bonede:tree-sitter:0.26.6`, `tree-sitter-python:0.25.0` (MIT, Java 11 바이트코드) |
| 네이티브 | jar 안에 Linux x64/arm64, macOS x64/arm64, Windows x64. **Windows arm64 없음** |
| 실행 확인 | Java 17 로 Linux x64·arm64, macOS arm64, Windows x64 CI 통과. 로컬 Windows Java 17 실행 확인 |
| 오류 내성 | 문법 오류 파일도 나머지 함수·호출을 그대로 읽음 |
| 위치 | 한글이 섞인 소스에서도 UTF-8 바이트 기준 위치가 맞음 |

구현:
- `LanguageParser` 로 Java(`JavaLanguage`), Python(`PythonLanguage`), 프론트엔드(`FrontendGraph`)를 감쌌다. 엔진은 확장자로 언어를 판별해 해당 파서만 돌리고 `Result.languages` 로 알려준다. Java 저장소의 결과는 예전과 노드·연결선 순서까지 같다(`CodeGraphEngineTest`).
- 이름 해석: 파일마다 import 표(절대·상대 import, 별칭)를 만들고, 호출 대상을 `subprocess.run`, `app.services.scanner.run_nmap` 같은 정규화 이름으로 바꾼다. 저장소 폴더 구조와 import 경로가 다를 수 있어 모듈은 이름 접미사로 찾는다. `self.`, `변수 = 호출()`, `with … as 변수` 만 따라가고 타입 추론은 하지 않는다.
- FastAPI: 모듈 최상위의 `FastAPI()`/`APIRouter(prefix=…)` 를 라우터로 모으고 `include_router(prefix=…)` 를 합쳐 전체 경로를 만든다. 파라미터·데코레이터·라우터의 `Depends(...)` 는 진입점 props `dependencies` 에 남기고, 의존 함수는 핸들러가 부르는 것으로 본다(CALLS). 인증 누락 판단(Planner)에 쓸 수 있다.
- 위험 지점: 인자가 모두 상수면 제외해 오탐을 줄였다. SQL 은 연결 객체 타입을 모르는 경우가 많아 "조립한 문자열을 execute 류에 넘김"으로 잡는다. python-nmap 의 `PortScanner.scan` 은 인자 주입(CWE-88)으로 분류했다.
- 남은 한계(다음 개선 후보): 파라미터 타입 힌트로 객체 타입 추론, Flask/Django 진입점, 클래스 기반 의존성(`Depends(SomeClass)`), 데코레이터 기반 인증 표시.

## 6. 다른 사본: phonetective (Sherlock Phones)

`com.sherlockphones.codegraph` 는 mcp 엔진을 안드로이드용으로 바꾼 사본이었다. EntryPointDetector 대신 Component/AppScan/LineScanner 가 있었고, `JavaProject`·`TypeResolver`·`CodeGraphDraft` 와 호출 연결 코드는 같았다.

v0.3.0 에서 옮겼다:
- core 의 Spring `CodeGraphBuilder` 에서 규칙과 상관없는 부분(구조, 상속 연결, 호출 대상 타입, 프로젝트 호출 잇기, 위험 지점 노드)을 공개 클래스 `JavaGraphSupport` 로 꺼냈다. Spring 빌더는 진입점 탐지와 규칙 판단만 남기고 이것을 쓴다. be·mcp 테스트를 새 core 소스(composite build)로 돌려 결과가 같음을 확인했다.
- 프레임워크 메서드 반환 타입 표(`knownReturnTypes`)를 받게 했다. Spring 은 빈 표라서 동작이 같고, 안드로이드는 `getIntent` → `android.content.Intent` 같은 표를 넘긴다.
- Sherlock Phones 는 `JavaProject`·`TypeResolver`·그래프 모델 사본을 지우고, 안드로이드 빌더(외부에 열린 컴포넌트·JS 브리지 진입점, 안드로이드 규칙, 아무 인증서나 믿는 TLS 구현, 줄 단위·설정 위험 지점)만 남겼다. 벤치마크 34곳은 옮기기 전과 같다.
- 다음: Kotlin·Swift 를 tree-sitter `LanguageParser` 로 core 에 더하면 서버(Kotlin Spring)와 모바일이 같이 쓴다.
