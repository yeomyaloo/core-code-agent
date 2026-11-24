# core-code-agent

The shared code graph engine behind [be-code-agent](https://github.com/yeomyaloo/be-code-agent) (Spring server with an agent loop) and [mcp-code-agent](https://github.com/yeomyaloo/mcp-code-agent) (MCP server / Claude Code plugin).

It parses source code statically (no build, no execution) and produces a code graph: files, classes, methods, HTTP entry points, sinks (risky API calls), and the edges between them. Both servers then search entry point → sink paths and let Claude investigate them. The engine itself uses no LLM, database or network.

## Contents

- [Why a separate library](#why-a-separate-library)
- [Usage](#usage)
- [Public API](#public-api)
- [Supported languages](#supported-languages)
- [Progress](#progress)
- [Development](#development)
- [Directory structure](#directory-structure)
- [License](#license)

## Why a separate library

The engine used to be copied into both servers with only the package name changed, so every rule fix had to be made twice. It now lives here once and is published as a versioned Maven artifact. Each server keeps only a thin adapter that moves the result into its own model (be: PostgreSQL, mcp: in-memory index). See [docs/design.md](docs/design.md) (Korean) for the comparison of sharing options and the plan for more languages.

## Usage

```gradle
repositories {
    mavenCentral()
    maven {
        url = uri('https://maven.pkg.github.com/yeomyaloo/core-code-agent')
        credentials {   // GitHub Packages needs a token (read:packages) even for public packages
            username = providers.gradleProperty('gpr.user').orElse(providers.environmentVariable('GITHUB_ACTOR')).orNull
            password = providers.gradleProperty('gpr.key').orElse(providers.environmentVariable('GITHUB_TOKEN')).orNull
        }
        content { includeGroup 'com.codeagent' }
    }
}

dependencies {
    implementation 'com.codeagent:core-code-agent:0.1.0'
}
```

To build a consumer against a local checkout instead (no token), both servers accept `-PcoreCodeAgentDir=../core-code-agent`, which turns on a Gradle composite build.

```java
CodeGraphEngine.Result result = CodeGraphEngine.analyze(Path.of("/path/to/repo"));
CodeGraphDraft graph = result.draft();          // nodes and edges
List<String> errors = result.parseErrors();      // files that could not be parsed
```

## Public API

| Type | Role |
|---|---|
| `CodeGraphEngine.analyze(Path)` | Entry point. Reads the repository and returns `Result(draft, parseErrors)` |
| `model.CodeGraphDraft` | Graph before saving. Nodes are keyed by (kind, qualified name), duplicates are dropped |
| `model.CodeNodeKind` | `MODULE`, `FILE`, `CLASS`, `METHOD`, `ENTRY_POINT`, `SINK` |
| `model.CodeEdgeKind` | `CONTAINS`, `CALLS`, `ROUTES_TO`, `OVERRIDDEN_BY` |
| `rules.SinkRules` | Sink rules and category priority (`priority`) used to order investigations |

Node props shared with both servers: sinks have `category`, `cwe`, `api`, `method`, `snippet` (and `frontend=true` for JS/TS); entry points have `httpMethod`, `path`, `handler`.

## Supported languages

| Language | Entry points | Sinks | Call edges |
|---|---|---|---|
| Java / Spring | Spring MVC mappings | 54 rules (SQL, command, path, SSRF, XXE, deserialization, XSS, crypto, IDOR candidates…) | type-resolved (imports, declarations, symbol solver) |
| JS / TS / Vue / HTML | – | 27 line-based patterns (XSS, eval, open redirect, token storage, secrets…) | – |
| Python / FastAPI | planned | planned | planned |

## Progress

| Step | Description | Status |
|---|---|---|
| 1 | Extract the engine from be-code-agent and mcp-code-agent into this library (no behavior change) | ✅ Done |
| 2 | Language plugins (`LanguageParser`), Python/FastAPI parser | ⏳ Planned |
| 3 | TypeScript backend parser on the same plugin interface | ⏳ Planned |

## Development

```bash
./gradlew test                 # unit tests (no DB, network or API key)
./gradlew publishToMavenLocal  # local check
```

- Built with a JDK 21 toolchain but compiled with `release 17`, because the MCP plugin server must run on Java 17. No Java 21-only APIs, no Lombok.
- Release: bump `version` in `build.gradle`, push a `v{version}` tag. GitHub Actions runs the tests and publishes to GitHub Packages. Then bump the dependency in both servers (and `CORE_CODE_AGENT_REF` in be-code-agent's `Dockerfile`).
- When you change sink rules, run both servers' benchmark tests (`BenchmarkSinkTest`, `BenchmarkTest`); they must still match the answer key exactly.

## Directory structure

```
src/main/java/com/codeagent/core/
├── CodeGraphEngine.java      entry point
├── model/                    CodeNodeKind, CodeEdgeKind, CodeGraphDraft (output model)
├── rules/                    SinkRule, SinkRules
├── java/                     JavaProject, TypeResolver, EntryPointDetector, CodeGraphBuilder
└── frontend/                 FrontendScanner (JS/TS/Vue/HTML)
src/test/                     unit tests and sample apps (vulnerable-app, idor-app, owasp-app)
docs/design.md                design notes (Korean)
```

## License

Apache-2.0. Ideas from ARTEX were used as reference only; no code or prompts were copied.

---

# core-code-agent (한국어)

[be-code-agent](https://github.com/yeomyaloo/be-code-agent)(에이전트 루프가 있는 Spring 서버)와 [mcp-code-agent](https://github.com/yeomyaloo/mcp-code-agent)(MCP 서버 / Claude Code 플러그인)가 같이 쓰는 코드 그래프 엔진이다.

소스코드를 정적으로 파싱해서(빌드·실행하지 않음) 파일·클래스·메서드·HTTP 진입점·위험 지점(위험한 API 호출)과 그 관계를 코드 그래프로 만든다. 두 서버는 이 그래프에서 진입점 → 위험 지점 경로를 찾아 Claude 가 조사하게 한다. 엔진 자체는 LLM·DB·네트워크를 쓰지 않는다.

## 왜 별도 라이브러리인가

전에는 두 서버에 엔진이 패키지 이름만 다르게 복사돼 있어서 규칙 하나를 고칠 때마다 두 번 고쳐야 했다. 이제 엔진은 여기 한 곳에만 있고 버전이 붙은 Maven 아티팩트로 배포된다. 각 서버에는 결과를 자기 모델로 옮기는 얇은 어댑터만 남는다(be: PostgreSQL, mcp: 메모리 색인). 공유 방식 비교와 다른 언어 추가 계획은 [docs/design.md](docs/design.md)에 있다.

## 사용법

위 영어 절의 Gradle 설정을 그대로 쓴다. GitHub Packages 는 공개 패키지여도 읽을 때 토큰(`read:packages`)이 필요하다. `~/.gradle/gradle.properties` 에 `gpr.user`/`gpr.key` 를 두거나 `GITHUB_ACTOR`/`GITHUB_TOKEN` 환경 변수를 쓴다. 토큰 없이 로컬 소스로 빌드하려면 두 서버 모두 `-PcoreCodeAgentDir=../core-code-agent`(Gradle composite build)를 받는다.

```java
CodeGraphEngine.Result result = CodeGraphEngine.analyze(Path.of("/path/to/repo"));
CodeGraphDraft graph = result.draft();          // 노드와 연결선
List<String> errors = result.parseErrors();      // 파싱하지 못한 파일
```

## 공개 API

| 타입 | 역할 |
|---|---|
| `CodeGraphEngine.analyze(Path)` | 입구. 저장소를 읽어 `Result(draft, parseErrors)` 를 돌려준다 |
| `model.CodeGraphDraft` | 저장 전 그래프. 노드는 (종류, 정규화된 이름)으로 식별하고 중복은 버린다 |
| `model.CodeNodeKind` | `MODULE`, `FILE`, `CLASS`, `METHOD`, `ENTRY_POINT`, `SINK` |
| `model.CodeEdgeKind` | `CONTAINS`, `CALLS`, `ROUTES_TO`, `OVERRIDDEN_BY` |
| `rules.SinkRules` | 위험 지점 규칙과 분류 우선순위(`priority`, 조사 순서에 씀) |

두 서버가 같이 쓰는 노드 props: 위험 지점은 `category`, `cwe`, `api`, `method`, `snippet`(JS/TS 는 `frontend=true`), 진입점은 `httpMethod`, `path`, `handler`.

## 지원 언어

| 언어 | 진입점 | 위험 지점 | 호출 관계 |
|---|---|---|---|
| Java / Spring | Spring MVC 매핑 | 규칙 54개 (SQL, 명령, 경로, SSRF, XXE, 역직렬화, XSS, 암호, IDOR 후보…) | 타입 해석 (import, 선언, symbol solver) |
| JS / TS / Vue / HTML | – | 줄 단위 패턴 27개 (XSS, eval, 오픈 리다이렉트, 토큰 저장, 비밀값…) | – |
| Python / FastAPI | 예정 | 예정 | 예정 |

## 진행 상황

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | be-code-agent·mcp-code-agent 의 엔진을 이 라이브러리로 추출 (동작 변화 없음) | ✅ 완료 |
| 2 | 언어 플러그인(`LanguageParser`), Python/FastAPI 파서 | ⏳ 예정 |
| 3 | 같은 플러그인 구조로 TypeScript 백엔드 파서 | ⏳ 예정 |

## 개발

- JDK 21 toolchain 으로 빌드하지만 바이트코드는 `release 17` 이다. MCP 플러그인 서버가 Java 17 에서도 돌아야 하기 때문이다. Java 21 전용 API 와 Lombok 은 쓰지 않는다.
- 배포: `build.gradle` 의 `version` 을 올리고 `v{version}` 태그를 푸시하면 GitHub Actions 가 테스트 후 GitHub Packages 에 올린다. 그다음 두 서버의 의존 버전(be 는 `Dockerfile` 의 `CORE_CODE_AGENT_REF` 도)을 올린다.
- 위험 지점 규칙을 바꾸면 두 서버의 벤치마크 테스트(`BenchmarkSinkTest`, `BenchmarkTest`)가 정답과 정확히 같은지 확인한다.

## 디렉터리 구조

```
src/main/java/com/codeagent/core/
├── CodeGraphEngine.java      입구
├── model/                    CodeNodeKind, CodeEdgeKind, CodeGraphDraft (출력 모델)
├── rules/                    SinkRule, SinkRules
├── java/                     JavaProject, TypeResolver, EntryPointDetector, CodeGraphBuilder
└── frontend/                 FrontendScanner (JS/TS/Vue/HTML)
src/test/                     단위 테스트와 예제 앱 (vulnerable-app, idor-app, owasp-app)
docs/design.md                설계 문서
```

## 라이선스

Apache-2.0. ARTEX 는 아이디어만 참고했고 코드나 프롬프트는 복사하지 않았다.
