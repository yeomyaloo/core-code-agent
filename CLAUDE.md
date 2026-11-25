# cotcha-trail

cotcha-net(Spring 서버)와 cotcha-pocket(MCP 서버)가 같이 쓰는 코드 그래프 엔진 라이브러리. cotcha-pinch(`../phonetective`, 모바일 앱 검사, 예전 이름 Sherlock Phones)도 `JavaGraphSupport` 위에 안드로이드 그래프를 만든다. 소스코드를 정적 파싱해 파일·클래스·메서드·진입점·위험 지점과 그 관계를 만든다. LLM·DB·네트워크를 쓰지 않는다.

## 명령

```bash
./gradlew test               # 단위 테스트 (DB·네트워크·API 키 불필요)
./gradlew publishToMavenLocal  # 로컬 확인용
```

- Windows PowerShell에서는 `.\gradlew.bat`.
- 빌드는 JDK 21 toolchain, 바이트코드는 `release 17`. mcp 플러그인 서버가 Java 17 에서도 돌아야 하므로 Java 21 전용 API(`getFirst`, `addFirst` 등)와 Lombok 을 쓰지 않는다. 로그는 slf4j-api.
- 배포: `build.gradle` 의 `version` 을 올리고 `v{version}` 태그를 푸시하면 GitHub Actions 가 GitHub Packages 에 올린다. be·mcp 의 `build.gradle` 의존 버전과 be `Dockerfile` 의 `CORE_CODE_AGENT_REF` 를 같이 올린다.
- be·mcp 에서 이 저장소를 소스로 바로 쓰려면 그쪽에서 `-PcoreCodeAgentDir=../cotcha-trail`(composite build).

## 구조

| 패키지 | 역할 |
|---|---|
| `com.codeagent.core` | `CodeGraphEngine.analyze(root)` 입구(언어 판별 후 `LanguageParser` 들을 차례로 실행). 결과는 `Result(draft, parseErrors, languages)`. `SourceFiles` 는 파일 찾기 |
| `model` | `CodeNodeKind`, `CodeEdgeKind`, `CodeGraphDraft`(저장 전 그래프). 공개 출력 모델 |
| `rules` | `SinkRule`, `SinkRules`(Java 위험 지점 규칙, 분류 우선순위 `priority`/`CATEGORY_ORDER`), `PythonSinkRules`(정규화 이름 기준) |
| `java` | Java/Spring: `JavaProject`(파싱·색인), `TypeResolver`, `EntryPointDetector`(Spring MVC), `WebFluxRouteDetector`(WebFlux 함수형 라우터), `JavaConstants`(경로 상수 값: 리터럴·+·필드·static import), `CodeGraphBuilder`(Spring 진입점·규칙). `JavaGraphSupport` 는 규칙 없는 공용 부품(구조, 상속, 호출 대상 타입, 호출 잇기, 위험 지점 노드)이라 Spring 빌더와 cotcha-pinch 가 같이 쓴다 |
| `kotlin` | Kotlin: `KotlinProject`(tree-sitter 로 파싱, import·타입·함수 색인), `KotlinGraphSupport`(구조, 상속, 호출 대상 타입, 호출 잇기, 위험 지점; 규칙은 쓰는 쪽이 넘긴다), `KotlinLanguage`(기본 파서: 서버 규칙 `SinkRules`, Spring 진입점 `KotlinEntryPointDetector`, WebFlux 라우터 DSL `KotlinRouterDetector`). 경로 상수는 `KotlinProject.constantValue`·`templateValue`(val·const val 초기값, 템플릿, +). 애너테이션 인자는 `KotlinProject.annotationDetails`(문자열은 내용, 다른 식은 `${식}`). tree-sitter-kotlin 이 파일 최상위 애너테이션 줄을 클래스와 떼어 prefix_expression 으로 읽을 때가 있어 앞 형제도 본다 |
| `swift` | Swift: `SwiftProject`(tree-sitter, 타입·extension·함수 색인, 함수 이름 `Type#name(label:)`), `SwiftGraphSupport`(구조, 상속, 호출 잇기, 위험 지점; C 함수는 타입 "" 으로 묻는다). 기본 파서에 넣지 않는다 (모바일 전용) |
| `objc` | Objective-C: `ObjcProject`(tree-sitter, .m·.mm·@interface 있는 .h, 헤더·구현·카테고리를 클래스 하나로, 메서드 이름 `Type#selector:`), `ObjcGraphSupport`(메시지·C 함수 호출 잇기, 위험 지점; 규칙은 (타입, 셀렉터), C 함수는 타입 ""). 기본 파서에 넣지 않는다 (모바일 전용). 문법이 괄호에도 type 필드를 달아서 타입은 이름 있는 자식에서 고른다 |
| `interop` | `JavaKotlinInterop`: Java ↔ Kotlin 호출 잇기(이름 그대로, `FileKt`·`INSTANCE`·`Companion`·getter, 서로의 타입 이름을 `addExternalTypes` 로 알려 줌). 기본 엔진에서는 `JavaLanguage(kotlin)` 이 둘 다 있으면 Kotlin 까지 만들고 `KotlinLanguage` 는 그 저장소를 건너뛴다. `SwiftObjcInterop`: Swift ↔ Objective-C 호출 잇기. 셀렉터 ↔ Swift 이름 변환(deletePayee: ↔ deletePayee(_:), initWithName: ↔ init(name:), storeWithToken: ↔ store(token:)). `SwiftGraphSupport.foreignCalls`·`ObjcGraphSupport.foreignCalls` 에 단다 |
| `python` | Python: `PythonLanguage`(tree-sitter 로 파싱, 네이티브 로드 실패 시 건너뜀), `PythonModule`(import·정의 색인), `PythonGraphBuilder`(FastAPI 진입점·Depends, 호출, 위험 지점) |
| `frontend` | `FrontendGraph`(그래프에 더함), `FrontendScanner`: JS/TS/Vue/HTML 줄 단위 정규식 위험 패턴 (비밀값은 snippet 에서 가린다) |

- 새 언어는 `LanguageParser` 를 구현하고 `CodeGraphEngine.defaultParsers()` 에 넣는다. 순서가 결과 노드 순서이므로 Java 저장소 결과가 바뀌지 않게 프론트엔드를 마지막에 둔다.
- Python 은 tree-sitter-ng(JNI) 를 쓴다. 노드 위치는 UTF-8 바이트 기준이라 `PythonModule.text()` 로만 자른다. tree-sitter 노드는 트리가 살아 있을 때만 유효하므로 `PythonModule` 이 트리를 들고 있는다.
- 네이티브 라이브러리 때문에 CI 는 OS·CPU 별(Linux x64/arm64, macOS arm64, Windows x64)로 Java 17 에서 테스트한다(`-PtestJavaVersion=17`).

- be·mcp 는 `CodeGraphDraft` 를 각자 모델로 옮긴다(be: `CodeGraphIndexer` → DB, mcp: `CodeGraph` → 메모리 색인). 노드 kind 문자열과 props 키(`category`, `cwe`, `api`, `method`, `snippet`, `frontend`, `language`, `httpMethod`, `path`, `handler`)는 두 소비자가 그대로 쓰므로 바꾸면 양쪽을 같이 고친다. be 는 kind 를 DB 에 문자열로 저장한다.
- 위험 지점 규칙을 바꾸면 be `BenchmarkSinkTest`, mcp `BenchmarkTest` 가 정답 목록과 정확히 같은지 본다. 두 저장소에서 확인한다.
- `JavaGraphSupport` 를 바꾸면 cotcha-pinch 의 `BenchmarkTest` 도 돌린다(`-PcoreCodeAgentDir=../cotcha-trail`).

## 규칙

- 주석, 로그, 예외 메시지, 테스트 메서드 이름은 한국어.
- DTO와 값 객체는 record.
- 분석 대상 코드는 읽기만 한다. 빌드하거나 실행하지 않는다.
- ARTEX(AGPL-3.0)의 코드나 프롬프트는 복사하지 않는다.
- 비밀값(토큰, 비밀번호)은 코드나 설정 파일에 쓰지 않는다. 배포 토큰은 `~/.gradle/gradle.properties` 의 `gpr.user`/`gpr.key` 또는 환경 변수.
- README는 영어 먼저, `---` 아래에 한국어. 둘 다 같이 고친다.
- 사용자에게 설명과 요약은 한국어로 한다.
