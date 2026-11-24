# core-code-agent

be-code-agent(Spring 서버)와 mcp-code-agent(MCP 서버)가 같이 쓰는 코드 그래프 엔진 라이브러리. 소스코드를 정적 파싱해 파일·클래스·메서드·진입점·위험 지점과 그 관계를 만든다. LLM·DB·네트워크를 쓰지 않는다.

## 명령

```bash
./gradlew test               # 단위 테스트 (DB·네트워크·API 키 불필요)
./gradlew publishToMavenLocal  # 로컬 확인용
```

- Windows PowerShell에서는 `.\gradlew.bat`.
- 빌드는 JDK 21 toolchain, 바이트코드는 `release 17`. mcp 플러그인 서버가 Java 17 에서도 돌아야 하므로 Java 21 전용 API(`getFirst`, `addFirst` 등)와 Lombok 을 쓰지 않는다. 로그는 slf4j-api.
- 배포: `build.gradle` 의 `version` 을 올리고 `v{version}` 태그를 푸시하면 GitHub Actions 가 GitHub Packages 에 올린다. be·mcp 의 `build.gradle` 의존 버전과 be `Dockerfile` 의 `CORE_CODE_AGENT_REF` 를 같이 올린다.
- be·mcp 에서 이 저장소를 소스로 바로 쓰려면 그쪽에서 `-PcoreCodeAgentDir=../core-code-agent`(composite build).

## 구조

| 패키지 | 역할 |
|---|---|
| `com.codeagent.core` | `CodeGraphEngine.analyze(root)` 입구. 결과는 `Result(draft, parseErrors)` |
| `model` | `CodeNodeKind`, `CodeEdgeKind`, `CodeGraphDraft`(저장 전 그래프). 공개 출력 모델 |
| `rules` | `SinkRule`, `SinkRules`(위험 지점 규칙, 분류 우선순위 `priority`/`CATEGORY_ORDER`) |
| `java` | Java/Spring: `JavaProject`(파싱·색인), `TypeResolver`, `EntryPointDetector`, `CodeGraphBuilder` |
| `frontend` | `FrontendScanner`: JS/TS/Vue/HTML 줄 단위 정규식 위험 패턴 (비밀값은 snippet 에서 가린다) |

- be·mcp 는 `CodeGraphDraft` 를 각자 모델로 옮긴다(be: `CodeGraphIndexer` → DB, mcp: `CodeGraph` → 메모리 색인). 노드 kind 문자열과 props 키(`category`, `cwe`, `api`, `method`, `snippet`, `frontend`, `language`, `httpMethod`, `path`, `handler`)는 두 소비자가 그대로 쓰므로 바꾸면 양쪽을 같이 고친다. be 는 kind 를 DB 에 문자열로 저장한다.
- 위험 지점 규칙을 바꾸면 be `BenchmarkSinkTest`, mcp `BenchmarkTest` 가 정답 목록과 정확히 같은지 본다. 두 저장소에서 확인한다.

## 규칙

- 주석, 로그, 예외 메시지, 테스트 메서드 이름은 한국어.
- DTO와 값 객체는 record.
- 분석 대상 코드는 읽기만 한다. 빌드하거나 실행하지 않는다.
- ARTEX(AGPL-3.0)의 코드나 프롬프트는 복사하지 않는다.
- 비밀값(토큰, 비밀번호)은 코드나 설정 파일에 쓰지 않는다. 배포 토큰은 `~/.gradle/gradle.properties` 의 `gpr.user`/`gpr.key` 또는 환경 변수.
- README는 영어 먼저, `---` 아래에 한국어. 둘 다 같이 고친다.
- 사용자에게 설명과 요약은 한국어로 한다.
