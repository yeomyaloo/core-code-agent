<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/cotcha-logo-dark.svg">
    <img src="docs/images/cotcha-logo.svg" alt="Cotcha" width="260">
  </picture>
</p>

# cotcha-trail

> **Cotcha** = *"Caught ya!"* Cotcha follows untrusted input from where it enters your code to where it can do damage, catches the security bugs on the way, and keeps them in a jar.
> *Every bug ends up in the jar.*

| Repository | The name means | Role |
|---|---|---|
| [cotcha-trail (this repo)](https://github.com/yeomyaloo/cotcha-trail) | the trail a bug leaves behind | Shared engine: parses code into a graph of entry points, sinks and the calls between them |
| [cotcha-net](https://github.com/yeomyaloo/cotcha-net) | the bug-catching net | Server: Planner, Worker and Verifier agents sweep the code and double-check every catch (REST API, DB) |
| [cotcha-pocket](https://github.com/yeomyaloo/cotcha-pocket) | the bug box in your pocket | Claude Code plugin / MCP server: run the audit right from chat |
| [cotcha-jar](https://github.com/yeomyaloo/cotcha-jar) | the jar where caught bugs go | Web UI: paths, findings and approvals at a glance |
| [cotcha-pinch](https://github.com/yeomyaloo/cotcha-pinch) | the touchscreen pinch, and catching a bug between two fingers | Mobile: Claude Code plugin / MCP server for Android (soon iOS) apps |

The shared code graph engine behind [cotcha-net](https://github.com/yeomyaloo/cotcha-net) (Spring server with an agent loop) and [cotcha-pocket](https://github.com/yeomyaloo/cotcha-pocket) (MCP server / Claude Code plugin). [cotcha-pinch](https://github.com/yeomyaloo/cotcha-pinch) (mobile app audits) builds its Android graph on the same Java building blocks.

It parses source code statically (no build, no execution) and produces a code graph: files, classes, methods, HTTP entry points, sinks (risky API calls), and the edges between them. Both servers then search entry point → sink paths and let Claude investigate them. The engine itself uses no LLM, database or network.

## Contents

- [Why a separate library](#why-a-separate-library)
- [How this library was built](#how-this-library-was-built)
- [Usage](#usage)
- [How consumers use it](#how-consumers-use-it)
- [Public API](#public-api)
- [Supported languages](#supported-languages)
- [Progress](#progress)
- [Development](#development)
- [Directory structure](#directory-structure)
- [License](#license)

## Why a separate library

The engine used to be copied into both servers with only the package name changed, so every rule fix had to be made twice. It now lives here once and is published as a versioned Maven artifact. Each server keeps only a thin adapter that moves the result into its own model (be: PostgreSQL, mcp: in-memory index). See [docs/design.md](docs/design.md) (Korean) for the comparison of sharing options and the plan for more languages.

## How this library was built

1. **Compare the two copies.** The engine lived in cotcha-net (`com.codeagent.codegraph.ingest`) and cotcha-pocket (`com.codeagent.mcp.codegraph`). After normalizing package names, a diff showed the logic was identical. The only differences were Java 17 vs 21 syntax (`get(0)` vs `getFirst()`) and logging (slf4j `LoggerFactory` vs Lombok `@Slf4j`), because mcp compiles to Java 17 bytecode.
2. **Move, don't rewrite.** The mcp copy (already Java 17, no Lombok) was moved as is into packages split by role: `model` (output types), `rules` (sink rules), `java` (Java/Spring parsing), `frontend` (JS/TS scanner). Only package and import lines changed.
3. **One entry point.** `CodeGraphEngine.analyze(root)` wraps the two calls both servers used to make (`JavaProject.load` → `CodeGraphBuilder.build`) and returns the graph plus parse errors.
4. **Move the tests with the code.** be's engine unit tests (`CodeGraphBuilderTest`, `IdorSinkTest`, `OwaspSinkTest`) and their sample apps moved here, plus `CodeGraphEngineTest`, which checks that the facade gives exactly the same nodes and edges as calling the builder directly.
5. **Gradle library build.** `java-library` + `maven-publish`, a JDK 21 toolchain with `release 17` bytecode, JavaParser as an `api` dependency.
6. **Publish.** Pushing a `v*` tag runs `.github/workflows/test.yml`: tests, then `./gradlew publish` to GitHub Packages with the workflow's `GITHUB_TOKEN`.
7. **Switch the consumers.** be and mcp deleted their copies, depend on `com.codeagent:core-code-agent`, and keep only an adapter (be: `CodeGraphIndexer` → PostgreSQL, mcp: `CodeGraph` → in-memory index). Their test results were the same before and after (mcp 27/27; be the same except the 15 tests that moved here), and both CI runs passed against the published package.

## Usage

```gradle
repositories {
    mavenCentral()
    // Look up com.codeagent only here. Without exclusiveContent Gradle asks Maven Central first,
    // and a 429 (Too Many Requests) from Central fails the build.
    exclusiveContent {
        forRepository {
            maven {
                url = uri('https://maven.pkg.github.com/yeomyaloo/cotcha-trail')
                credentials {   // GitHub Packages needs a token (read:packages) even for public packages
                    username = providers.gradleProperty('gpr.user').orElse(providers.environmentVariable('GITHUB_ACTOR')).orNull
                    password = providers.gradleProperty('gpr.key').orElse(providers.environmentVariable('GITHUB_TOKEN')).orNull
                }
            }
        }
        filter { includeGroup 'com.codeagent' }
    }
}

dependencies {
    implementation 'com.codeagent:core-code-agent:0.6.0'   // bump the number to upgrade
}
```

To build a consumer against a local checkout instead (no token), both servers accept `-PcoreCodeAgentDir=../cotcha-trail`, which turns on a Gradle composite build.

```java
CodeGraphEngine.Result result = CodeGraphEngine.analyze(Path.of("/path/to/repo"));
CodeGraphDraft graph = result.draft();          // nodes and edges
List<String> errors = result.parseErrors();      // files that could not be (fully) parsed
Set<String> languages = result.languages();      // e.g. [java, javascript] or [python]
```

## How consumers use it

### A library project, not an application

This repository is a **library project** (`java-library`): it has no `main`, no server and nothing to run. It builds a jar plus a pom (its dependency list) that other projects depend on. The products are applications that depend on it:

```
cotcha-trail  [engine, library]  <── depends on ── cotcha-net     [Spring Boot server]
                                  <── depends on ── cotcha-pocket  [MCP server / Claude Code plugin]
                                  <── depends on ── cotcha-pinch   [mobile audits, Java building blocks]
```

Only the shared engine was pulled out, because a Spring Boot application cannot simply be turned into a library: `bootJar` builds an executable fat jar meant to be run, not depended on. The same recipe works for any code several projects copy (utilities, auth, shared DTOs): move just that part into a `java-library` project, publish it, and depend on it.

### Release → consume

```
cotcha-trail                                         consumers
bump version in build.gradle
git tag v0.6.0 && git push origin v0.6.0
   │ GitHub Actions: tests on 4 OSes, then ./gradlew publish
   ▼
GitHub Packages  com.codeagent:core-code-agent:0.6.0  ◀── implementation '…:0.6.0'
                                                          Gradle downloads it at build time
```

`from components.java` in `publishing` uploads the pom with the jar, so transitive dependencies (JavaParser, tree-sitter…) come along without consumers listing them.

### Developing against local source (composite build)

Publishing for every change is slow, so each consumer's `settings.gradle` can swap the published artifact for a local checkout:

```groovy
def coreCodeAgentDir = providers.gradleProperty('coreCodeAgentDir').orNull
if (coreCodeAgentDir) {
    includeBuild(coreCodeAgentDir)   // replaces com.codeagent:core-code-agent with this source build
}
```

```bash
./gradlew test -PcoreCodeAgentDir=../cotcha-trail
```

Gradle matches the coordinates (`group` + `rootProject.name`), builds this repository from source and uses it instead of the published version. No token is needed. The Python parser was tested in cotcha-net and cotcha-pocket this way before it was released.

### Two special consumers

- **Docker image (cotcha-net)**: to avoid putting a token into the image build, the Dockerfile fetches the tagged source with `ADD https://github.com/yeomyaloo/cotcha-trail.git#${CORE_CODE_AGENT_REF}` and builds with `-PcoreCodeAgentDir` pointing at it. Bump `CORE_CODE_AGENT_REF` together with the dependency version.
- **Claude Code plugin (cotcha-pocket)**: plugins are not built on install, so `./gradlew pluginServer` copies the engine jar and every runtime dependency into `server/lib`, which is committed.

### Compared with attaching a jar to a GitHub Release

| | Jar attached to a Release | Maven repository (this repo) |
|---|---|---|
| Getting it | download the jar, drop it into `libs/`, depend on the file | one coordinate line, Gradle downloads it |
| Transitive dependencies | you collect them yourself | listed in the pom, pulled in automatically |
| Upgrading | download a new jar and replace it | change the version number |
| Publishing | build and upload by hand | push a tag, CI tests and publishes |
| Downside | manual work | GitHub Packages needs a token even to read |

Alternatives: **JitPack** builds from a Git tag and needs no token; **Maven Central** is the most standard but has a longer registration process.

## Public API

| Type | Role |
|---|---|
| `CodeGraphEngine.analyze(Path)` | Entry point. Detects the languages in the repository (by file extension), runs the matching parsers and returns `Result(draft, parseErrors, languages)` |
| `LanguageParser` | One per language: `language()`, `detect(root)`, `contribute(root, draft, parseErrors)`. Java, Python and frontend are implementations |
| `model.CodeGraphDraft` | Graph before saving. Nodes are keyed by (kind, qualified name), duplicates are dropped |
| `model.CodeNodeKind` | `MODULE`, `FILE`, `CLASS`, `METHOD`, `ENTRY_POINT`, `SINK` |
| `model.CodeEdgeKind` | `CONTAINS`, `CALLS`, `ROUTES_TO`, `OVERRIDDEN_BY` |
| `rules.SinkRules` | Sink rules and category priority (`priority`) used to order investigations |
| `java.JavaGraphSupport` | Java building blocks with no rules attached: structure (FILE → CLASS → METHOD), override edges, call target type resolution (with optional known framework return types such as `getIntent` → `android.content.Intent`), project call linking, sink nodes. The Spring `CodeGraphBuilder` uses it; other products plug in their own entry points and rules (cotcha-pinch: exported Android components, JS bridges, Android sinks) |

Node props shared with both servers: sinks have `category`, `cwe`, `api`, `method`, `snippet` (and `frontend=true` for JS/TS); entry points have `httpMethod`, `path`, `handler`. Python nodes also carry `language=python`; entry points add `framework=fastapi` and `dependencies` (the `Depends(...)` functions, including router-level ones). Nodes without `language` are Java.

## Supported languages

| Language | Entry points | Sinks | Call edges |
|---|---|---|---|
| Java / Spring | Spring MVC mappings | 54 rules (SQL, command, path, SSRF, XXE, deserialization, XSS, crypto, IDOR candidates…) | type-resolved (imports, declarations, symbol solver) |
| JS / TS / Vue / HTML | – | 27 line-based patterns (XSS, eval, open redirect, token storage, secrets…) | – |
| Kotlin | – (products plug in their own: cotcha-pinch wires exported Android components to Kotlin handlers) | the same Java rules (`SinkRules`), matched on Kotlin calls and constructor calls | name-resolved: imports and aliases, declared parameter/property/local types, `Foo(...)` constructor calls, `this`/`super`, companion functions, top-level functions (`FileNameKt`), optional known framework return types |
| Python / FastAPI | FastAPI routes (`@app.get`, `@router.post`, `api_route`, `websocket`), `APIRouter(prefix=)` + `include_router(prefix=)`, `Depends`/`Security` | ~200 APIs: COMMAND (subprocess, os.system/popen, asyncio subprocess, python-nmap), SSRF (requests, httpx, aiohttp, urllib), PATH (open, pathlib, FileResponse, shutil), SQL (string-built `execute`, `sqlalchemy.text`), DESERIALIZATION (pickle, yaml.load, marshal…), code injection, XXE, redirect, XSS, weak crypto/random | name-resolved through the import table (absolute and relative imports, `self.`, `var = Call()`, `with … as`) |

Kotlin notes:
- Parsed with tree-sitter (`tree-sitter-kotlin`, MIT), same native-library platforms as Python. Nodes carry `language=kotlin`.
- `KotlinProject` (index) and `KotlinGraphSupport` (structure, overrides, call linking, sinks) are public so a product can add its own entry points and rules, like `JavaGraphSupport`.
- `SwiftProject` and `SwiftGraphSupport` do the same for Swift (tree-sitter): classes, structs, enums, protocols and extensions (merged into the extended type), functions keyed Swift-style as `Type#name(label:label:)`, overrides, call linking through declared types, constructors and singletons (`UserDefaults.standard`), and sinks. Swift is not in the default parsers, so the server's analysis is unchanged.
- `ObjcProject` and `ObjcGraphSupport` do the same for Objective-C (tree-sitter): `@interface` in headers and `@implementation` / categories in `.m` files merge into one class, methods are keyed by selector (`AppDelegate#application:openURL:options:`), and messages (`[receiver selector:arg]`) and C function calls are linked through declared types, `alloc`/`init`, `instancetype` returns and class singletons (`[NSUserDefaults standardUserDefaults]`). Also not in the default parsers.
- No type inference: values of unknown type (chained calls, lambdas, generics) are not followed. Calls in property initializers outside functions are not scanned yet. Java ↔ Kotlin calls are not linked yet.

Python notes:
- Parsed with [tree-sitter](https://tree-sitter.github.io/) through the [tree-sitter-ng](https://github.com/bonede/tree-sitter-ng) JNI binding (MIT). Files with syntax errors are still analyzed; the broken part is skipped and reported in `parseErrors`.
- Native libraries ship inside the jar for Linux x64/arm64, macOS x64/arm64 and Windows x64 (checked in CI with Java 17). **Windows on ARM is not supported**: Python files are skipped there with a message in `parseErrors`.
- Calls whose arguments are all constants (`open("config.json")`, `subprocess.run(["uptime"])`, `yaml.load(f, Loader=SafeLoader)`) are not sinks. SQL is flagged when the query string is built (f-string, `%`, `.format`, `+`) and passed to `execute`-like methods, because the driver type is usually unknown statically.
- Test code (`tests/`, `test_*.py`, `*_test.py`, `conftest.py`) and virtualenv/cache folders are skipped. No type inference: parameters of unknown type are not followed.

## Progress

| Step | Description | Status |
|---|---|---|
| 1 | Extract the engine from cotcha-net and cotcha-pocket into this library (no behavior change) | ✅ Done |
| 2 | Language plugins (`LanguageParser`), language detection, Python/FastAPI parser (tree-sitter) | ✅ Done (v0.2.0) |
| 2-2 | Kotlin parser (tree-sitter): structure, overrides, call linking, sinks with the Java rules; public `KotlinProject`/`KotlinGraphSupport` (cotcha-pinch uses them for Android) | ✅ Done (v0.4.0) |
| 2-3 | Swift parser (tree-sitter): public `SwiftProject`/`SwiftGraphSupport` for iOS call paths (cotcha-pinch); not in the default parsers | ✅ Done (v0.5.0) |
| 2-4 | Objective-C parser (tree-sitter): public `ObjcProject`/`ObjcGraphSupport`, selectors, headers + implementations + categories; not in the default parsers | ✅ Done (v0.6.0) |
| 2-1 | `JavaGraphSupport`: Java building blocks as public API, so other products (cotcha-pinch for Android) reuse parsing and call linking instead of copying them. No change in be/mcp output | ✅ Done (v0.3.0) |
| 3 | TypeScript backend parser on the same plugin interface | ⏳ Planned |

## Development

```bash
./gradlew test                 # unit tests (no DB, network or API key)
./gradlew publishToMavenLocal  # local check
```

- Built with a JDK 21 toolchain but compiled with `release 17`, because the MCP plugin server must run on Java 17. No Java 21-only APIs, no Lombok.
- Release: bump `version` in `build.gradle`, push a `v{version}` tag. GitHub Actions runs the tests and publishes to GitHub Packages. Then bump the dependency in both servers (and `CORE_CODE_AGENT_REF` in cotcha-net's `Dockerfile`).
- When you change sink rules, run both servers' benchmark tests (`BenchmarkSinkTest`, `BenchmarkTest`); they must still match the answer key exactly.

## Directory structure

```
src/main/java/com/codeagent/core/
├── CodeGraphEngine.java      entry point, language detection
├── LanguageParser.java       per-language parser interface
├── SourceFiles.java          source file discovery (skips build/venv folders, symlinks)
├── model/                    CodeNodeKind, CodeEdgeKind, CodeGraphDraft (output model)
├── rules/                    SinkRule, SinkRules (Java), PythonSinkRules
├── java/                     JavaLanguage, JavaProject, TypeResolver, EntryPointDetector, CodeGraphBuilder
├── python/                   PythonLanguage (tree-sitter), PythonModule (imports, definitions), PythonGraphBuilder (FastAPI, calls, sinks)
└── frontend/                 FrontendGraph, FrontendScanner (JS/TS/Vue/HTML)
src/test/                     unit tests and sample apps (vulnerable-app, idor-app, owasp-app, fastapi-app)
docs/design.md                design notes (Korean)
```

## License

Apache-2.0. Ideas from ARTEX were used as reference only; no code or prompts were copied.

---

# cotcha-trail (한국어)

> **Cotcha(코챠)** = *"Caught ya!"*(잡았다!)를 귀엽게 줄인 말. 외부 입력이 코드에 들어온 곳부터 위험한 곳까지 따라가며 보안 버그를 잡아 유리병에 담는다.
> *Every bug ends up in the jar.* (잡은 버그는 전부 병 속으로)

| 레포 | 이름 뜻 | 역할 |
|---|---|---|
| [cotcha-trail (이 레포)](https://github.com/yeomyaloo/cotcha-trail) | 벌레가 지나간 발자국 | 공용 엔진: 코드를 파싱해 진입점 → 위험 지점 경로(호출 그래프)를 그린다 |
| [cotcha-net](https://github.com/yeomyaloo/cotcha-net) | 벌레 잡는 잠자리채 | 서버: Planner·Worker·Verifier 에이전트 팀이 코드를 훑고, 잡은 것을 다시 확인한다 (REST API, DB) |
| [cotcha-pocket](https://github.com/yeomyaloo/cotcha-pocket) | 주머니 속 채집통 | Claude Code 플러그인 / MCP 서버: 채팅에서 바로 분석한다 |
| [cotcha-jar](https://github.com/yeomyaloo/cotcha-jar) | 잡은 벌레를 모아 두는 유리병 | 웹 화면: 경로·발견·승인을 한눈에 본다 |
| [cotcha-pinch](https://github.com/yeomyaloo/cotcha-pinch) | 화면을 집는 손가락 제스처, 손가락으로 벌레를 집어 잡기 | 모바일: 안드로이드(곧 iOS) 앱용 Claude Code 플러그인 / MCP 서버 |

[cotcha-net](https://github.com/yeomyaloo/cotcha-net)(에이전트 루프가 있는 Spring 서버)와 [cotcha-pocket](https://github.com/yeomyaloo/cotcha-pocket)(MCP 서버 / Claude Code 플러그인)가 같이 쓰는 코드 그래프 엔진이다. 모바일 앱 검사 도구 [cotcha-pinch](https://github.com/yeomyaloo/cotcha-pinch)도 같은 Java 부품 위에 안드로이드 그래프를 만든다.

소스코드를 정적으로 파싱해서(빌드·실행하지 않음) 파일·클래스·메서드·HTTP 진입점·위험 지점(위험한 API 호출)과 그 관계를 코드 그래프로 만든다. 두 서버는 이 그래프에서 진입점 → 위험 지점 경로를 찾아 Claude 가 조사하게 한다. 엔진 자체는 LLM·DB·네트워크를 쓰지 않는다.

## 왜 별도 라이브러리인가

전에는 두 서버에 엔진이 패키지 이름만 다르게 복사돼 있어서 규칙 하나를 고칠 때마다 두 번 고쳐야 했다. 이제 엔진은 여기 한 곳에만 있고 버전이 붙은 Maven 아티팩트로 배포된다. 각 서버에는 결과를 자기 모델로 옮기는 얇은 어댑터만 남는다(be: PostgreSQL, mcp: 메모리 색인). 공유 방식 비교와 다른 언어 추가 계획은 [docs/design.md](docs/design.md)에 있다.

## 이 라이브러리를 만든 과정

1. **두 사본 비교.** 엔진은 cotcha-net(`com.codeagent.codegraph.ingest`)와 cotcha-pocket(`com.codeagent.mcp.codegraph`)에 있었다. 패키지 이름을 맞추고 diff 해 보니 로직은 완전히 같았다. 차이는 Java 17/21 문법(`get(0)` ↔ `getFirst()`)과 로그 선언(slf4j `LoggerFactory` ↔ Lombok `@Slf4j`)뿐이었다. mcp 가 Java 17 바이트코드로 빌드하기 때문이다.
2. **다시 짜지 않고 옮기기.** 이미 Java 17·Lombok 없음인 mcp 사본을 그대로 옮기고, 역할별 패키지로 나눴다: `model`(출력 타입), `rules`(위험 지점 규칙), `java`(Java/Spring 파싱), `frontend`(JS/TS 스캐너). 바뀐 것은 package·import 줄뿐이다.
3. **입구 하나.** 두 서버가 각자 부르던 `JavaProject.load` → `CodeGraphBuilder.build` 를 `CodeGraphEngine.analyze(root)` 하나로 묶고, 그래프와 파싱 오류를 같이 돌려준다.
4. **테스트도 같이 옮기기.** be 의 엔진 단위 테스트(`CodeGraphBuilderTest`, `IdorSinkTest`, `OwaspSinkTest`)와 예제 앱을 옮겼다. 입구가 빌더를 직접 부른 것과 노드·연결선이 정확히 같은지 보는 `CodeGraphEngineTest` 를 더했다.
5. **Gradle 라이브러리 빌드.** `java-library` + `maven-publish`, JDK 21 toolchain 에 바이트코드는 `release 17`, JavaParser 는 `api` 의존성.
6. **배포.** `v*` 태그를 푸시하면 `.github/workflows/test.yml` 이 테스트 후 `./gradlew publish` 로 GitHub Packages 에 올린다(워크플로의 `GITHUB_TOKEN` 사용).
7. **소비자 교체.** be·mcp 는 사본을 지우고 `com.codeagent:core-code-agent` 에 의존하며 어댑터만 남겼다(be: `CodeGraphIndexer` → PostgreSQL, mcp: `CodeGraph` → 메모리 색인). 테스트 결과는 교체 전후가 같았고(mcp 27/27, be 는 여기로 옮긴 15개를 빼면 같음), 배포된 패키지로 두 저장소 CI 가 모두 통과했다.

## 사용법

위 영어 절의 Gradle 설정을 그대로 쓴다. GitHub Packages 는 공개 패키지여도 읽을 때 토큰(`read:packages`)이 필요하다. `~/.gradle/gradle.properties` 에 `gpr.user`/`gpr.key` 를 두거나 `GITHUB_ACTOR`/`GITHUB_TOKEN` 환경 변수를 쓴다. 토큰 없이 로컬 소스로 빌드하려면 두 서버 모두 `-PcoreCodeAgentDir=../cotcha-trail`(Gradle composite build)를 받는다.

```java
CodeGraphEngine.Result result = CodeGraphEngine.analyze(Path.of("/path/to/repo"));
CodeGraphDraft graph = result.draft();          // 노드와 연결선
List<String> errors = result.parseErrors();      // 파싱하지 못했거나 일부만 읽은 파일
Set<String> languages = result.languages();      // 예: [java, javascript] 또는 [python]
```

## 쓰는 쪽에서 쓰는 법

### 애플리케이션이 아니라 라이브러리 프로젝트

이 레포는 **라이브러리 프로젝트**(`java-library`)다. `main` 도 서버도 없고 혼자 실행되지 않는다. 빌드하면 다른 프로젝트가 의존하는 jar 와 pom(의존성 목록)이 나온다. 제품들은 이걸 가져다 쓰는 애플리케이션이다.

```
cotcha-trail  [엔진, 라이브러리]  <── 의존 ── cotcha-net     [Spring Boot 서버]
                                   <── 의존 ── cotcha-pocket  [MCP 서버 / Claude Code 플러그인]
                                   <── 의존 ── cotcha-pinch   [모바일 점검, Java 부품]
```

같이 쓰는 엔진만 뺀 이유: Spring Boot 애플리케이션은 그대로 라이브러리로 만들 수 없다. `bootJar` 는 실행용으로 의존성을 다 묶은 jar 라서 다른 프로젝트가 의존하기에 맞지 않는다. 여러 프로젝트가 복사해 쓰는 코드(유틸, 인증, 공통 DTO 등)가 있으면 같은 방법을 쓸 수 있다. 그 부분만 `java-library` 프로젝트로 옮겨 배포하고 의존하게 하면 된다.

### 배포 → 사용

```
cotcha-trail                                         쓰는 쪽
build.gradle 의 version 올리기
git tag v0.6.0 && git push origin v0.6.0
   │ GitHub Actions: 4개 OS 테스트 → ./gradlew publish
   ▼
GitHub Packages  com.codeagent:core-code-agent:0.6.0  ◀── implementation '…:0.6.0'
                                                          빌드할 때 Gradle 이 받아 옴
```

`publishing` 의 `from components.java` 가 jar 와 함께 pom 을 올리므로, 하위 의존성(JavaParser, tree-sitter 등)은 쓰는 쪽이 적지 않아도 따라온다.

### 로컬 소스로 개발하기 (composite build)

고칠 때마다 배포하면 느려서, 쓰는 쪽 `settings.gradle` 에 배포본 대신 로컬 소스를 쓰는 옵션을 넣었다.

```groovy
def coreCodeAgentDir = providers.gradleProperty('coreCodeAgentDir').orNull
if (coreCodeAgentDir) {
    includeBuild(coreCodeAgentDir)   // com.codeagent:core-code-agent 를 이 소스 빌드로 바꿔 끼움
}
```

```bash
./gradlew test -PcoreCodeAgentDir=../cotcha-trail
```

Gradle 이 좌표(`group` + `rootProject.name`)를 맞춰 보고 이 레포를 소스에서 빌드해 배포본 대신 쓴다. 토큰이 필요 없다. Python 파서도 배포하기 전에 이 방법으로 cotcha-net·cotcha-pocket 테스트를 먼저 돌렸다.

### 특수한 두 곳

- **Docker 이미지(cotcha-net)**: 이미지 빌드에 토큰을 넣지 않으려고, Dockerfile 이 `ADD https://github.com/yeomyaloo/cotcha-trail.git#${CORE_CODE_AGENT_REF}` 로 태그 소스를 받아 `-PcoreCodeAgentDir` 로 묶어 빌드한다. 의존 버전을 올릴 때 `CORE_CODE_AGENT_REF` 도 같이 올린다.
- **Claude Code 플러그인(cotcha-pocket)**: 플러그인은 설치할 때 빌드하지 않으므로 `./gradlew pluginServer` 가 엔진 jar 와 모든 런타임 의존 jar 를 `server/lib` 에 모아 커밋한다.

### 릴리즈에 jar 를 첨부하는 방식과 비교

| | 릴리즈에 jar 첨부 | Maven 저장소 배포 (이 레포) |
|---|---|---|
| 받는 법 | jar 를 받아 `libs/` 에 넣고 파일로 의존 | 좌표 한 줄, Gradle 이 자동으로 받음 |
| 하위 의존성 | 직접 챙겨야 함 | pom 에 적혀 있어 자동으로 따라옴 |
| 버전 올리기 | 새 jar 를 받아 교체 | 버전 숫자만 변경 |
| 배포 | 직접 빌드해서 업로드 | 태그 push → CI 가 테스트 후 자동 배포 |
| 단점 | 손이 많이 감 | GitHub Packages 는 읽을 때도 토큰 필요 |

다른 선택지: **JitPack**(Git 태그로 빌드, 토큰 불필요), **Maven Central**(가장 표준이지만 등록 절차가 길다).

## 공개 API

| 타입 | 역할 |
|---|---|
| `CodeGraphEngine.analyze(Path)` | 입구. 저장소의 언어를 확장자로 판별해 알맞은 파서만 돌리고 `Result(draft, parseErrors, languages)` 를 돌려준다 |
| `LanguageParser` | 언어별 파서: `language()`, `detect(root)`, `contribute(root, draft, parseErrors)`. Java·Python·프론트엔드가 구현체 |
| `model.CodeGraphDraft` | 저장 전 그래프. 노드는 (종류, 정규화된 이름)으로 식별하고 중복은 버린다 |
| `model.CodeNodeKind` | `MODULE`, `FILE`, `CLASS`, `METHOD`, `ENTRY_POINT`, `SINK` |
| `model.CodeEdgeKind` | `CONTAINS`, `CALLS`, `ROUTES_TO`, `OVERRIDDEN_BY` |
| `rules.SinkRules` | 위험 지점 규칙과 분류 우선순위(`priority`, 조사 순서에 씀) |
| `java.JavaGraphSupport` | 규칙이 붙지 않은 Java 부품: 구조(FILE → CLASS → METHOD), 상속 연결, 호출 대상 타입 알아내기(라이브러리 jar 없이 아는 프레임워크 반환 타입, 예: `getIntent` → `android.content.Intent`), 프로젝트 호출 잇기, 위험 지점 노드 만들기. Spring 용 `CodeGraphBuilder` 가 쓰고, 다른 제품은 자기 진입점·규칙만 얹는다(cotcha-pinch: 외부에 열린 안드로이드 컴포넌트, JS 브리지, 안드로이드 위험 지점) |

두 서버가 같이 쓰는 노드 props: 위험 지점은 `category`, `cwe`, `api`, `method`, `snippet`(JS/TS 는 `frontend=true`), 진입점은 `httpMethod`, `path`, `handler`. Python 노드는 `language=python` 이 붙고, 진입점은 `framework=fastapi` 와 `dependencies`(라우터에 건 것까지 포함한 `Depends(...)` 함수)가 더 있다. `language` 가 없으면 Java 다.

## 지원 언어

| 언어 | 진입점 | 위험 지점 | 호출 관계 |
|---|---|---|---|
| Java / Spring | Spring MVC 매핑 | 규칙 54개 (SQL, 명령, 경로, SSRF, XXE, 역직렬화, XSS, 암호, IDOR 후보…) | 타입 해석 (import, 선언, symbol solver) |
| JS / TS / Vue / HTML | – | 줄 단위 패턴 27개 (XSS, eval, 오픈 리다이렉트, 토큰 저장, 비밀값…) | – |
| Kotlin | – (제품이 직접 붙인다: cotcha-pinch 는 외부에 열린 안드로이드 컴포넌트를 Kotlin 핸들러로 잇는다) | Java 규칙(`SinkRules`)을 Kotlin 호출·생성자 호출에 그대로 적용 | 이름 해석: import·별칭, 파라미터·프로퍼티·지역 변수에 적힌 타입, `Foo(...)` 생성자 호출, `this`/`super`, companion 함수, 파일 최상위 함수(`파일이름Kt`), 넘겨받은 프레임워크 반환 타입 |
| Python / FastAPI | FastAPI 라우트(`@app.get`, `@router.post`, `api_route`, `websocket`), `APIRouter(prefix=)` + `include_router(prefix=)`, `Depends`/`Security` | 약 200개 API: COMMAND(subprocess, os.system/popen, asyncio subprocess, python-nmap), SSRF(requests, httpx, aiohttp, urllib), PATH(open, pathlib, FileResponse, shutil), SQL(문자열을 조립해 넘기는 `execute`, `sqlalchemy.text`), DESERIALIZATION(pickle, yaml.load, marshal…), 코드 실행, XXE, 리다이렉트, XSS, 약한 암호·난수 | import 표로 이름 해석 (절대·상대 import, `self.`, `변수 = 호출()`, `with … as`) |

Kotlin 참고:
- tree-sitter(`tree-sitter-kotlin`, MIT)로 파싱한다. 네이티브 라이브러리 지원 플랫폼은 Python 과 같다. 노드에 `language=kotlin` 이 붙는다.
- `KotlinProject`(색인)와 `KotlinGraphSupport`(구조, 상속, 호출 잇기, 위험 지점)는 공개라서 `JavaGraphSupport` 처럼 제품이 자기 진입점·규칙을 얹을 수 있다.
- `SwiftProject`·`SwiftGraphSupport` 는 Swift(tree-sitter) 판이다. class·struct·enum·protocol·extension(원래 타입에 합침), `Type#name(label:label:)` 꼴 함수 이름, 상속, 적힌 타입·생성자·싱글턴(`UserDefaults.standard`)으로 호출 잇기, 위험 지점. 기본 파서에는 넣지 않아서 서버 분석은 그대로다.
- `ObjcProject`·`ObjcGraphSupport` 는 Objective-C(tree-sitter) 판이다. 헤더의 `@interface` 와 `.m` 의 `@implementation`·카테고리를 클래스 하나로 합치고, 메서드는 셀렉터(`AppDelegate#application:openURL:options:`)로 부른다. 메시지(`[받는쪽 셀렉터:인자]`)와 C 함수 호출을 적힌 타입, `alloc`/`init`, `instancetype` 반환, 클래스 싱글턴(`[NSUserDefaults standardUserDefaults]`)으로 잇는다. 이것도 기본 파서에는 넣지 않는다.
- 타입 추론은 하지 않는다: 타입을 모르는 값(이어진 호출, 람다, 제네릭)은 따라가지 않는다. 함수 밖 프로퍼티 초기값의 호출은 아직 보지 않는다. Java ↔ Kotlin 호출은 아직 잇지 않는다.

Python 참고:
- [tree-sitter](https://tree-sitter.github.io/) 를 [tree-sitter-ng](https://github.com/bonede/tree-sitter-ng) JNI 바인딩(MIT)으로 쓴다. 문법 오류가 있는 파일도 오류 부분만 건너뛰고 분석하고 `parseErrors` 에 남긴다.
- 네이티브 라이브러리는 jar 안에 Linux x64/arm64, macOS x64/arm64, Windows x64 용이 들어 있다(CI 에서 Java 17 로 확인). **Windows ARM 은 지원하지 않는다**: 그 환경에서는 파이썬 파일을 건너뛰고 `parseErrors` 에 이유를 남긴다.
- 인자가 모두 상수인 호출(`open("config.json")`, `subprocess.run(["uptime"])`, `yaml.load(f, Loader=SafeLoader)`)은 위험 지점이 아니다. SQL 은 드라이버 타입을 정적으로 알기 어려워서, 쿼리 문자열을 조립(f-string, `%`, `.format`, `+`)해 `execute` 류에 넘기면 잡는다.
- 테스트 코드(`tests/`, `test_*.py`, `*_test.py`, `conftest.py`)와 가상환경·캐시 폴더는 건너뛴다. 타입 추론은 하지 않아서 타입을 모르는 파라미터의 메서드 호출은 따라가지 않는다.

## 진행 상황

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | cotcha-net·cotcha-pocket 의 엔진을 이 라이브러리로 추출 (동작 변화 없음) | ✅ 완료 |
| 2 | 언어 플러그인(`LanguageParser`), 언어 자동 판별, Python/FastAPI 파서 (tree-sitter) | ✅ 완료 (v0.2.0) |
| 2-2 | Kotlin 파서(tree-sitter): 구조, 상속, 호출 잇기, Java 규칙으로 위험 지점. `KotlinProject`/`KotlinGraphSupport` 공개 (cotcha-pinch 가 안드로이드에 씀) | ✅ 완료 (v0.4.0) |
| 2-3 | Swift 파서(tree-sitter): iOS 호출 경로용 공개 `SwiftProject`/`SwiftGraphSupport` (cotcha-pinch). 기본 파서에는 넣지 않음 | ✅ 완료 (v0.5.0) |
| 2-4 | Objective-C 파서(tree-sitter): 공개 `ObjcProject`/`ObjcGraphSupport`, 셀렉터 이름, 헤더·구현·카테고리 합치기. 기본 파서에는 넣지 않음 | ✅ 완료 (v0.6.0) |
| 2-1 | `JavaGraphSupport`: 규칙이 붙지 않은 Java 부품을 공개 API 로. 다른 제품(안드로이드용 cotcha-pinch)이 파싱·호출 연결을 복사하지 않고 쓴다. be·mcp 결과는 그대로 | ✅ 완료 (v0.3.0) |
| 3 | 같은 플러그인 구조로 TypeScript 백엔드 파서 | ⏳ 예정 |

## 개발

- JDK 21 toolchain 으로 빌드하지만 바이트코드는 `release 17` 이다. MCP 플러그인 서버가 Java 17 에서도 돌아야 하기 때문이다. Java 21 전용 API 와 Lombok 은 쓰지 않는다.
- 배포: `build.gradle` 의 `version` 을 올리고 `v{version}` 태그를 푸시하면 GitHub Actions 가 테스트 후 GitHub Packages 에 올린다. 그다음 두 서버의 의존 버전(be 는 `Dockerfile` 의 `CORE_CODE_AGENT_REF` 도)을 올린다.
- 위험 지점 규칙을 바꾸면 두 서버의 벤치마크 테스트(`BenchmarkSinkTest`, `BenchmarkTest`)가 정답과 정확히 같은지 확인한다.

## 디렉터리 구조

```
src/main/java/com/codeagent/core/
├── CodeGraphEngine.java      입구, 언어 판별
├── LanguageParser.java       언어별 파서 인터페이스
├── SourceFiles.java          소스 파일 찾기 (빌드·가상환경 폴더, 심볼릭 링크 제외)
├── model/                    CodeNodeKind, CodeEdgeKind, CodeGraphDraft (출력 모델)
├── rules/                    SinkRule, SinkRules (Java), PythonSinkRules
├── java/                     JavaLanguage, JavaProject, TypeResolver, EntryPointDetector, CodeGraphBuilder
├── python/                   PythonLanguage (tree-sitter), PythonModule (import·정의 색인), PythonGraphBuilder (FastAPI, 호출, 위험 지점)
└── frontend/                 FrontendGraph, FrontendScanner (JS/TS/Vue/HTML)
src/test/                     단위 테스트와 예제 앱 (vulnerable-app, idor-app, owasp-app, fastapi-app)
docs/design.md                설계 문서
```

## 라이선스

Apache-2.0. ARTEX 는 아이디어만 참고했고 코드나 프롬프트는 복사하지 않았다.
