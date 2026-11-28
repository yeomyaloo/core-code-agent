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
    implementation 'com.codeagent:core-code-agent:0.25.0'   // bump the number to upgrade
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
git tag v0.25.0 && git push origin v0.25.0
   │ GitHub Actions: tests on 4 OSes, then ./gradlew publish
   ▼
GitHub Packages  com.codeagent:core-code-agent:0.25.0  ◀── implementation '…:0.25.0'
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
| Node.js (JavaScript / TypeScript) | Express (`Router`, `app.use('/x', …)` mounts, `route()` chains), Koa (`@koa/router` with `prefix`), Fastify (`register(…, {prefix})`, `route({…})`), Hono, Restify, NestJS (`@Controller` + `@Get`/`@Post`…, `setGlobalPrefix`), Next.js API routes (`pages/api/**`, `app/**/route.ts`). Middleware and `@UseGuards` are kept as `guards` | COMMAND (child_process, execa, shelljs), CODE_INJECTION (eval, `Function`, vm, template engines), PATH (fs, `res.sendFile`/`download`), SSRF (fetch, axios, got, http), SQL (string-built `query`/`execute`/`raw`/`$queryRawUnsafe`), NOSQL_INJECTION (request object passed to `find`/`findOne`/…), PROTOTYPE_POLLUTION (lodash `merge` family, deepmerge), DESERIALIZATION (node-serialize), OPEN_REDIRECT, XSS (`res.send` of request data), weak crypto/random | name-resolved: `require`/`import` (relative, `@/`, index files), `module.exports`/`exports.x`, classes and `this.x`, NestJS constructor injection, TS parameter types, factory functions |
| Go | net/http `ServeMux` (Go 1.22 `"GET /items/{id}"` patterns, `StripPrefix` mounts), gin, echo, chi (`Route`, `Group`, `Mount`, `With`), gorilla/mux (`PathPrefix().Subrouter()`, `.Methods()`), fiber; groups and routers passed into functions (`func (h *Handler) Register(g *gin.RouterGroup)`) get the caller's prefix; middleware is kept as `guards` | COMMAND (`os/exec`), SQL (string-built `Query`/`Exec` on database/sql, sqlx, pgx, gorm `Raw`/`Where`…), PATH (`os.Open`/`ReadFile`…, `http.ServeFile`, `c.File`), SSRF (`http.Get`, `NewRequest`, `http.Client`), OPEN_REDIRECT, XSS (`template.HTML`, request values written to the response, HTML `c.Data`), template injection (`template.Parse` of a request value), weak crypto (md5, sha1, des, rc4, `InsecureSkipVerify`), `math/rand` | type-resolved from Go's declared types: packages from `go.mod`, receivers, parameters, struct fields (embedded too), `:=` from constructors, interface calls to the project types that implement them (matched by name and parameter types) |
| PHP | Laravel (`Route::get`… with `prefix`/`middleware`/`controller`/`group` chains and `Route::group([...])`, `resource`/`apiResource`, `routes/api.php` under `/api`, controller-constructor `$this->middleware()`), Symfony (`#[Route]` attributes and `@Route` annotations with class prefixes, `IsGranted`/`Security` as guards), Slim (`$app->get`, `group(...)->add(M)`), CodeIgniter 4 (`$routes->get`, group `filter`), and plain `.php` scripts that read `$_GET`/`$_POST` | COMMAND (`exec`, `system`, backticks…), CODE_INJECTION (`eval`, `assert`, `include $x`), SQL (interpolated/concatenated strings to `mysqli_query`, PDO, `DB::select`, `whereRaw`…), PATH (`file_get_contents`, `fopen`, `unlink`…), SSRF (`curl`, `Http::get`, Guzzle), DESERIALIZATION (`unserialize`), XXE (`LIBXML_NOENT`), OPEN_REDIRECT (`redirect`, `header('Location: …')`), XSS (request values in `echo`/`response()`), weak crypto/random | type-resolved from PHP's declared types: `namespace`/`use`, typed parameters and properties, promoted constructor properties, `new`, return types, `$this`/`self`/`parent`, interface calls to implementing classes, Laravel facades (also `Http::timeout()->get()` chains) |
| Ruby | Rails `config/routes.rb` (verbs with `to:`/`=>`, `root`, `resources`/`resource` with `only`/`except`, `member`/`collection`, nesting, `namespace`/`scope`/`controller`, Devise `authenticate`, `draw(:x)` files) with controller `before_action` auth filters as guards (`only`/`except`, `skip_before_action`, parents); Sinatra (`get '/x' do`, `namespace`, `before` filters) | COMMAND (`system`, backticks, `Kernel#open`, `Open3`, `IO.popen`), CODE_INJECTION (`eval`, `ERB.new`, `render inline:`), SQL (interpolated `where`/`find_by_sql`/`execute`, non-constant `order`/`pluck`/`group`), PATH (`File.*`, `send_file`, `render file:`), SSRF (`Net::HTTP`, `URI.open`, HTTParty, Faraday), DESERIALIZATION (`Marshal.load`, `YAML.unsafe_load`), OPEN_REDIRECT (`redirect_to` with request values), XSS (`html_safe`/`raw` on request values), weak crypto/random | `X.new` values, instance variables set with `X.new` (also `@x ||=`), memoized helper methods, class methods, lexical constant lookup |
| C# | ASP.NET Core controllers (`[Route]`/`[HttpGet("{id}")]` attribute routing with `[controller]`/`[action]` tokens and `~/` overrides, conventional `/{controller}/{action}` for controllers without attributes), Minimal APIs (`MapGet`/`MapPost`/`MapMethods`, nested `MapGroup`); `[Authorize]`/`[AllowAnonymous]`, auth filter attributes and `RequireAuthorization()`/`AllowAnonymous()` as guards | COMMAND (`Process.Start`, `ProcessStartInfo`), CODE_INJECTION (`CSharpScript`), SQL (`FromSqlRaw`/`ExecuteSqlRaw`, Dapper, `SqlCommand`, `CommandText =` with built strings; `FromSqlInterpolated` is safe), PATH (`File.*`, `PhysicalFile`, `FileStream`), SSRF (`HttpClient`, `WebClient`), DESERIALIZATION (`BinaryFormatter`…, JSON.NET with `TypeNameHandling`), XXE (only with `XmlUrlResolver`/`DtdProcessing.Parse`), OPEN_REDIRECT (`Redirect` with request values; `LocalRedirect` is safe), XSS (`Content(x, "text/html")`, `HtmlString`), weak crypto/random | type-resolved from C#'s declared types: fields, properties, primary-constructor parameters, typed and `var` locals, return types, interface calls to implementing classes |
| JS / TS / Vue / HTML | – | 27 line-based patterns (XSS, eval, open redirect, token storage, secrets…) | – |
| Kotlin | – (products plug in their own: cotcha-pinch wires exported Android components to Kotlin handlers) | the same Java rules (`SinkRules`), matched on Kotlin calls and constructor calls | name-resolved: imports and aliases, declared parameter/property/local types, `Foo(...)` constructor calls, `this`/`super`, companion functions, top-level functions (`FileNameKt`), optional known framework return types |
| Python / FastAPI | FastAPI routes (`@app.get`, `@router.post`, `api_route`, `websocket`), `APIRouter(prefix=)` + `include_router(prefix=)`, `Depends`/`Security` | ~200 APIs: COMMAND (subprocess, os.system/popen, asyncio subprocess, python-nmap), SSRF (requests, httpx, aiohttp, urllib), PATH (open, pathlib, FileResponse, shutil), SQL (string-built `execute`, `sqlalchemy.text`), DESERIALIZATION (pickle, yaml.load, marshal…), code injection, XXE, redirect, XSS, weak crypto/random | name-resolved through the import table (absolute and relative imports, `self.`, `var = Call()`, `with … as`) |

Kotlin notes:
- Parsed with tree-sitter (`tree-sitter-kotlin`, MIT), same native-library platforms as Python. Nodes carry `language=kotlin`.
- `KotlinProject` (index) and `KotlinGraphSupport` (structure, overrides, call linking, sinks) are public so a product can add its own entry points and rules, like `JavaGraphSupport`.
- When the repository has an `AndroidManifest.xml`, the default engine treats it as an Android app and adds the mobile attack surface itself (`android` package): socket entry points, manifest `exported`/deep-link entry points, and the CLEARTEXT / MISSING_AUTH / WEBVIEW_JS / TOKEN_STORAGE sinks (see the `android` notes below). A plain Kotlin/Spring repository (no manifest) is unchanged. cotcha-pinch still drives Kotlin directly for its own Android graph.
- `SwiftProject` and `SwiftGraphSupport` do the same for Swift (tree-sitter): classes, structs, enums, protocols and extensions (merged into the extended type), functions keyed Swift-style as `Type#name(label:label:)`, overrides, call linking through declared types, constructors and singletons (`UserDefaults.standard`), and sinks. Swift is not in the default parsers, so the server's analysis is unchanged.
- `ObjcProject` and `ObjcGraphSupport` do the same for Objective-C (tree-sitter): `@interface` in headers and `@implementation` / categories in `.m` files merge into one class, methods are keyed by selector (`AppDelegate#application:openURL:options:`), and messages (`[receiver selector:arg]`) and C function calls are linked through declared types, `alloc`/`init`, `instancetype` returns and class singletons (`[NSUserDefaults standardUserDefaults]`). Also not in the default parsers.
- `SwiftObjcInterop` links calls across the two languages in one app with the compiler's naming rules: Swift `db.deletePayee(name)` → `-deletePayee:`, `PayeeDatabase(name:)` → `-initWithName:`, and Objective-C `[vault storeWithToken:x]` → Swift `store(token:)`. `connect(swiftSupport, objcSupport)` wires both directions, including Objective-C return types for Swift (`LegacyStore.shared().save(…)`). Explicit `@objc(name)` / `NS_SWIFT_NAME` renames and omitted needless words are not followed.
- `JavaKotlinInterop` does the same for Java and Kotlin: Java → Kotlin functions (also `FileNameKt.top()`, `Foo.INSTANCE.bar()`, `Foo.Companion.make()`, property getters), Kotlin → Java methods and constructors, and type names across the two even without imports (same package). The default engine uses it: when a repository has both, the Java parser builds the Kotlin part too, so a Java Spring controller that calls a Kotlin service has a path to the Kotlin sink. Java-only repositories are analyzed exactly as before. `@JvmName`, `@JvmOverloads` defaults and extension functions called from Java are not followed.
- No type inference: values of unknown type (chained calls, lambdas, generics) are not followed. Calls in property initializers outside functions are not scanned yet. Java ↔ Kotlin calls are not linked yet.

Android notes (`android` package, only when `AndroidManifest.xml` is present):
- `AndroidManifest` parses the manifest (XXE-safe), ignoring generated ones under `build/`: `usesCleartextTraffic` and `exported` components with their intent-filters and deep links. Launcher-only `MAIN`/`LAUNCHER` activities are not treated as external entry points.
- `AndroidDetector` adds, on the Kotlin graph: socket entry points (functions that call `ServerSocket.accept()`, props `kind=socket`, `transport=tcp|tls`), manifest `exported` component entry points (`kind=android-component`), a CLEARTEXT sink for `usesCleartextTraffic` (props `mobile=true`), a MISSING_AUTH sink when a WebSocket handshake (`101 Switching Protocols` / `Sec-WebSocket-Accept`) is written in a class with no token/PIN/authorization check, and a TOKEN_STORAGE sink for `SharedPreferences` `putString` of a token-like key. `AndroidSinkRules` adds the TLS-less `ServerSocket` (CLEARTEXT) and `WebView.addJavascriptInterface` (WEBVIEW_JS) rules, merged with `SinkRules`.
- The RFC6455 WebSocket handshake uses SHA-1 by design; `MessageDigest.getInstance("SHA-1")` next to a handshake signal is not a WEAK_CRYPTO sink. Other weak hashes are still flagged, and the algorithm is kept on the sink as `algorithm`.

Node.js notes:
- Detected without configuration: a `package.json` (outside `node_modules`, `dist`…) that depends on express, koa, fastify, @nestjs/core, hapi, restify, hono or next. Parsed with tree-sitter (`tree-sitter-javascript`, `tree-sitter-typescript`, MIT). Nodes carry `language=node`.
- Browser code folders (`public`, `static`, `client`, `frontend`, `web`), tests, `.d.ts` and minified files are skipped. The line-based frontend scanner still runs on backend files for secrets; its findings there get `frontend=false` and a CALLS edge from the enclosing Node function, so they have entry paths too.
- A call is a sink only when the argument can carry input: built strings (template with `${}`, `+`) for SQL/eval, request values (`req.body`, `req.query`, `ctx.request.body`, NestJS `@Body()`/`@Query()` parameters, and locals assigned from them) for NoSQL, prototype pollution and `res.send`, and any non-literal argument for command, path and SSRF. Methods like `query`, `findOne` and `sendFile` match by name when the receiver is not known (a DB pool or an Express response).
- Handler factories are followed (`app.post('/login', login())`, `asyncHandler(resetPassword())`: the factory that returns the handler is the handler). Path-scoped middleware (`app.use('/rest/basket', auth)`, also an array of paths) is kept as a guard on the routes under that path; security-header, logging, parser and upload middleware (helmet, cors, morgan, multer…) is not. Permission checks inside handlers are not kept as guards.
- Routes that libraries or `app.use` create are entry points too: finale-rest/epilogue `finale.resource({ endpoints })` (GET/POST on the list path, GET/PUT/PATCH/DELETE on `/:id`; a template path inside `for (const { name } of models)` is expanded per element), with an explicit `app.get('/api/Users', auth)` on the same path merged in as its guard; and a path-scoped `app.use` whose last argument serves files or responds (`app.use('/ftp', serveIndex(...))`, `express.static`, a proxy, a handler that calls `res.send`) is an `ANY /path` endpoint. Middleware that calls `next()` stays a guard. Middleware registered after a route in the same file is not that route's guard, and a parent's middleware registered after a router is mounted is not a guard on that router's routes (Express runs in registration order).

Go notes:
- Detected when the repository has `.go` files (not `vendor/`, `testdata/`, `_test.go`). Parsed with tree-sitter (`tree-sitter-go`, MIT). Nodes carry `language=go`; function keys are `dir#Func` and `dir#Type.Method`, and anonymous handlers are named after their route (`internal/handlers#GET /files/:name`).
- Calls with only constants (`exec.Command("uptime")`, `os.ReadFile("/etc/app.conf")`, `db.Query("... $1", id)`) are not sinks. SQL is flagged when the query is built with `fmt.Sprintf`, `+` or `+=`. `c.Data` is XSS only with an HTML (or non-constant) content type; `fmt.Fprintf`/`io.WriteString` only when writing to the `http.ResponseWriter`.
- Not followed: routers kept in struct fields and assigned elsewhere, function values stored in variables, generics. `app.Use("/x", fn)` path-scoped middleware is not kept as a guard.

PHP notes:
- Detected when the repository has `.php` files (not `vendor/`, `storage/`, `tests/`, `*.blade.php`, migrations). Parsed with tree-sitter (`tree-sitter-php`, MIT). Nodes carry `language=php`; methods are keyed `Fqcn#method` (`App\Http\Controllers\UserController#show`), top-level code `file.php#<main>`, and anonymous route handlers after their route.
- Request values: `$_GET`/`$_POST`/`$_REQUEST`/`$_COOKIE`/`$_FILES`/`$_SERVER`, `$request->input()`/`query()`/`getContent()`… on Request-typed parameters, Symfony request bags, `request()`, CodeIgniter `$this->request->getGet()`, and the scalar parameters of route handlers (path variables, Slim `$args`).
- Calls with only constants are not sinks. SQL is flagged when the query is built with interpolation, `.`, `.=` or `sprintf`; XXE only with `LIBXML_NOENT`/`LIBXML_DTDLOAD`; `header()` only for `Location`.
- Not followed: Blade templates (`{!! !!}`), dynamic calls (`$fn()`, `call_user_func`), Laravel container bindings (interface calls go to every implementing class instead), Eloquent models.

Ruby notes:
- Detected when the repository has `.rb` files (not `vendor/`, `spec/`, `test/`, `db/`, `tmp/`). Parsed with tree-sitter (`tree-sitter-ruby`, MIT). Nodes carry `language=ruby`; methods are keyed `Module::Class#method` (`Api::ProductsController#search`), class methods `Class#self.name`, Sinatra handlers after their route.
- Ruby has no declared types, so calls are followed through `X.new`, instance variables assigned from it, memoized helpers and class methods. Calls on ActiveRecord models and unknown receivers match by method name (`where`, `order`, `execute`…).
- Request values: `params`, `request.body`/`raw_post`/`headers`…, `cookies[...]`, Sinatra block parameters. `redirect_to` is a sink only with request values (route helpers like `root_path` are not). `YAML.load` is left out because Psych 4 makes it safe. `system('cmd', arg)` with a constant program (no shell) is not a sink.
- Not followed: `send`/`public_send`, metaprogramming (`define_method`), concerns mixed in with `include`, Grape/Hanami/Roda routes, ERB/Haml views.

C# notes:
- Detected when the repository has `.cs` files (not `bin/`, `obj/`, `Migrations/`, `*.Designer.cs`, tests). Parsed with tree-sitter (`tree-sitter-c-sharp`, MIT). Nodes carry `language=csharp`; methods are keyed `Namespace.Class#Method`, top-level statements `Program.cs#<main>`, Minimal API lambdas after their route.
- Request values: action parameters bound from the request (`[FromQuery]`/`[FromBody]`/`[FromRoute]`/`[FromForm]`/`[FromHeader]`, or any parameter that is not a service such as `I…`, `…Service`, `…Repository`, `CancellationToken`), Minimal API lambda parameters, `Request.Query`/`Form`/`Headers`/`Cookies`/`Body`.
- Safe-by-default APIs are not sinks: `FromSqlInterpolated`/`FromSql`, `LocalRedirect`, `XmlDocument` without `XmlUrlResolver`, JSON.NET without `TypeNameHandling`, `Process.Start("cmd")` with constants.
- Not followed: Razor views (`@Html.Raw`), reflection, source generators, DI registrations (interface calls go to every implementing class), global auth through a fallback policy.

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
| 2-5 | Swift ↔ Objective-C call links (`SwiftObjcInterop`) | ✅ Done (v0.7.0) |
| 2-6 | Java ↔ Kotlin call links (`JavaKotlinInterop`), also in the default engine | ✅ Done (v0.8.0) |
| 2-7 | Kotlin Spring entry points (`KotlinEntryPointDetector`): `@RestController`/`@Controller` with `@GetMapping`… and `@RequestMapping(value = […], method = […])`, same names and props as Java; also reads class annotations that tree-sitter-kotlin detaches from the class | ✅ Done (v0.9.0) |
| 2-8 | WebFlux functional routers as entry points: Java `RouterFunctions.route(GET("/x"), h::get)`, `.andRoute`, `nest(path(…), …)`, the `route().GET(…).path(…, b -> …)` builder (`WebFluxRouteDetector`), and the Kotlin `router { }` / `coRouter { }` DSL with `"/api".nest { }` (`KotlinRouterDetector`). Handlers are method references or the first call in a lambda; unresolved handlers route to the router function | ✅ Done (v0.10.0) |
| 2-9 | Constant paths: `@GetMapping(Paths.USERS)`, `GET(ApiPaths.ORDERS + "/x")`, static imports, Kotlin `const val` and string templates (`"$V2/users"`) resolve to their values in MVC mappings and routers (`JavaConstants`, `KotlinProject.constantValue`); unknown constants stay as `${expr}` | ✅ Done (v0.11.0) |
| 2-10 | Flask and Django entry points (`PythonWebFrameworks`): Flask apps and blueprints (`url_prefix`, nested `register_blueprint`), `@app.route`/`@bp.get`, `add_url_rule`, `MethodView`, Flask-RESTful/RESTX; Django `urlpatterns` with `include`, function and class views, DRF routers and `@action`. Entry points carry `framework` and `guards` (auth hints: decorators, `*Mixin`, `permission_classes`, `before_request`). New Python sinks: `render_template_string` (SSTI), Django `redirect`/`HttpResponseRedirect`, `mark_safe`, `RawSQL`, `FileResponse` | ✅ Done (v0.12.0) |
| 2-11 | Django `ROOT_URLCONF`: entry points start from the urls module named in settings (all settings variants), so urls files that nothing connects are left out; without it, every urls module that is not included elsewhere is a root | ✅ Done (v0.13.0) |
| 2-12 | `ROOT_URLCONF` from the environment: `os.environ.get`/`os.getenv` defaults, django-environ `env(…, default=)`, python-decouple `config(…, default=)`, `os.environ["X"]` looked up in `.env*` files, plus module variables, `a if DEBUG else b`, `+` and f-strings | ✅ Done (v0.14.0) |
| 2-13 | Environment values also from docker-compose (`environment:` list and map, `${X:-default}`, files named by `env_file:`) and Dockerfile `ENV` | ✅ Done (v0.15.0) |
| 2-14 | Environment values also from Kubernetes manifests (container `env`, including the one-line form; `configMapKeyRef`/`secretKeyRef` into ConfigMap `data` / Secret `stringData`; `envFrom` keys) and Kustomize `configMapGenerator` (`literals`, `envs` files); search depth 6 | ✅ Done (v0.16.0) |
| 2-1 | `JavaGraphSupport`: Java building blocks as public API, so other products (cotcha-pinch for Android) reuse parsing and call linking instead of copying them. No change in be/mcp output | ✅ Done (v0.3.0) |
| 2-15 | Android attack surface in the default engine (new `android` package), gated on `AndroidManifest.xml`: `ServerSocket.accept()` loops as socket entry points, manifest `exported` components / intent-filters / deep links as entry points, and mobile sinks — `usesCleartextTraffic` and TLS-less `ServerSocket` (CLEARTEXT), token/PIN-less socket (WebSocket) handshakes (MISSING_AUTH), `WebView.addJavascriptInterface` (WEBVIEW_JS), `SharedPreferences` token storage (TOKEN_STORAGE). RFC6455 WebSocket-handshake SHA-1 is no longer a WEAK_CRYPTO false positive | ✅ Done (v0.17.0) |
| 2-16 | Node.js backends in the default engine (new `node` package, JavaScript and TypeScript): Express, Koa, Fastify, Hono, Restify, NestJS and Next.js entry points with mount prefixes and `guards`; calls through `require`/`import`, classes and NestJS injection; Node sink rules including new NOSQL_INJECTION and PROTOTYPE_POLLUTION categories | ✅ Done (v0.18.0) |
| 2-17 | Go backends in the default engine (new `golang` package): net/http, gin, echo, chi, gorilla/mux and fiber entry points with group/mount prefixes, routers passed into functions and `guards`; calls through Go's declared types and interfaces; Go sink rules including template injection and `InsecureSkipVerify` | ✅ Done (v0.19.0) |
| 2-18 | PHP backends in the default engine (new `php` package): Laravel, Symfony, Slim, CodeIgniter 4 and plain-script entry points with prefixes and `guards`; calls through PHP's declared types, interfaces and Laravel facades; PHP sink rules including XXE, `unserialize` and file inclusion | ✅ Done (v0.20.0) |
| 2-19 | Ruby backends in the default engine (new `ruby` package): Rails routes DSL and Sinatra entry points with `before_action`/`authenticate`/`before` guards; calls through `X.new`, instance variables and class methods; Ruby sink rules including `Kernel#open`, `Marshal.load`, `ERB.new` and `html_safe` | ✅ Done (v0.21.0) |
| 2-20 | C# backends in the default engine (new `csharp` package): ASP.NET Core controllers (attribute and conventional routing) and Minimal APIs with `[Authorize]`/`RequireAuthorization` guards; calls through C#'s declared types and interfaces; C# sink rules that leave out safe-by-default APIs (`FromSqlInterpolated`, `LocalRedirect`, `XmlDocument` without a resolver) | ✅ Done (v0.22.0) |
| 2-21 | Node.js on a real app (OWASP Juice Shop): handler factories (`login()`, `asyncHandler(x())`), path-scoped and array-path `app.use` middleware as guards, infrastructure middleware left out of guards, NoSQL `update`/`count`/`distinct`. Juice Shop entry points 17 → 99 | ✅ Done (v0.23.0) |
| 2-22 | Node.js routes that Juice Shop runs exposed: finale-rest generated REST routes (template paths expanded from the model loop, explicit guard routes merged), path-scoped `app.use` that serves files or responds as `ANY` endpoints (`/ftp`, `/encryptionkeys`, `/support/logs`, `/metrics`), registration order for middleware. Juice Shop entry points 99 → 175 | ✅ Done (v0.24.0) |
| 2-23 | Node.js middleware order across mounts: a parent's `app.use` registered after `app.use('/x', router)` is not a guard on that router's routes (Juice Shop's trailing error handler and SPA fallback no longer show up as guards) | ✅ Done (v0.25.0) |

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
├── rules/                    SinkRule, SinkRules (Java), PythonSinkRules, NodeSinkRules, GoSinkRules, PhpSinkRules, RubySinkRules, CsSinkRules
├── java/                     JavaLanguage, JavaProject, TypeResolver, EntryPointDetector, CodeGraphBuilder
├── python/                   PythonLanguage (tree-sitter), PythonModule (imports, definitions), PythonGraphBuilder (FastAPI, calls, sinks)
├── csharp/                   CsLanguage (graph), CsProject (classes, members, types), CsRoutes (controllers, Minimal APIs)
├── ruby/                     RubyLanguage (graph), RubyProject (classes, ivars, types), RubyRoutes (Rails routes DSL, Sinatra)
├── php/                      PhpLanguage (graph), PhpProject (namespaces, classes, types), PhpRoutes (Laravel, Symfony, Slim, CodeIgniter, plain scripts)
├── golang/                   GoLanguage (graph), GoProject (packages, types, type inference), GoRoutes (net/http, gin, echo, chi, gorilla, fiber)
├── node/                     NodeLanguage (detection, graph), NodeProject (modules, imports, values), NodeRoutes (Express/Koa/Fastify/NestJS/Next.js)
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
git tag v0.25.0 && git push origin v0.25.0
   │ GitHub Actions: 4개 OS 테스트 → ./gradlew publish
   ▼
GitHub Packages  com.codeagent:core-code-agent:0.25.0  ◀── implementation '…:0.25.0'
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
| Node.js (JavaScript / TypeScript) | Express(`Router`, `app.use('/x', …)` 마운트, `route()` 체인), Koa(`@koa/router` 와 `prefix`), Fastify(`register(…, {prefix})`, `route({…})`), Hono, Restify, NestJS(`@Controller` + `@Get`·`@Post`…, `setGlobalPrefix`), Next.js API 라우트(`pages/api/**`, `app/**/route.ts`). 미들웨어와 `@UseGuards` 는 `guards` 로 남긴다 | COMMAND(child_process, execa, shelljs), CODE_INJECTION(eval, `Function`, vm, 템플릿 엔진), PATH(fs, `res.sendFile`·`download`), SSRF(fetch, axios, got, http), SQL(문자열을 조립해 넘기는 `query`·`execute`·`raw`·`$queryRawUnsafe`), NOSQL_INJECTION(요청 객체를 그대로 넘긴 `find`·`findOne`…), PROTOTYPE_POLLUTION(lodash `merge` 류, deepmerge), DESERIALIZATION(node-serialize), OPEN_REDIRECT, XSS(요청 값을 `res.send`), 약한 암호·난수 | 이름 해석: `require`·`import`(상대 경로, `@/`, index 파일), `module.exports`·`exports.x`, 클래스와 `this.x`, NestJS 생성자 주입, TS 파라미터 타입, 팩토리 함수 |
| Go | net/http `ServeMux`(Go 1.22 `"GET /items/{id}"` 패턴, `StripPrefix` 마운트), gin, echo, chi(`Route`, `Group`, `Mount`, `With`), gorilla/mux(`PathPrefix().Subrouter()`, `.Methods()`), fiber. 그룹과 함수로 넘긴 라우터(`func (h *Handler) Register(g *gin.RouterGroup)`)는 부르는 곳의 경로를 받는다. 미들웨어는 `guards` 로 남긴다 | COMMAND(`os/exec`), SQL(database/sql·sqlx·pgx 의 `Query`·`Exec` 에 조립한 문자열, gorm `Raw`·`Where`…), PATH(`os.Open`·`ReadFile`…, `http.ServeFile`, `c.File`), SSRF(`http.Get`, `NewRequest`, `http.Client`), OPEN_REDIRECT, XSS(`template.HTML`, 요청 값을 응답에 그대로 쓰기, HTML `c.Data`), 템플릿 주입(요청 값을 `template.Parse`), 약한 암호(md5, sha1, des, rc4, `InsecureSkipVerify`), `math/rand` | Go 에 적힌 타입으로 해석: `go.mod` 로 패키지, 리시버, 파라미터, 구조체 필드(끼워 넣은 것 포함), 생성자에서 받은 `:=`, 인터페이스 호출은 그것을 채우는 프로젝트 타입으로(이름과 파라미터 타입으로 맞춤) |
| PHP | Laravel(`Route::get`… 과 `prefix`·`middleware`·`controller`·`group` 체인, `Route::group([...])`, `resource`·`apiResource`, `routes/api.php` 는 `/api` 아래, 컨트롤러 생성자의 `$this->middleware()`), Symfony(`#[Route]` 속성과 `@Route` 주석, 클래스 접두사, `IsGranted`·`Security` 는 guards), Slim(`$app->get`, `group(...)->add(M)`), CodeIgniter 4(`$routes->get`, 그룹 `filter`), `$_GET`·`$_POST` 를 읽는 순수 `.php` 스크립트 | COMMAND(`exec`, `system`, 백틱…), CODE_INJECTION(`eval`, `assert`, `include $x`), SQL(보간·연결로 만든 문자열을 `mysqli_query`, PDO, `DB::select`, `whereRaw`… 에), PATH(`file_get_contents`, `fopen`, `unlink`…), SSRF(`curl`, `Http::get`, Guzzle), DESERIALIZATION(`unserialize`), XXE(`LIBXML_NOENT`), OPEN_REDIRECT(`redirect`, `header('Location: …')`), XSS(요청 값을 `echo`·`response()` 에), 약한 암호·난수 | PHP 에 적힌 타입으로 해석: `namespace`·`use`, 타입 있는 파라미터·프로퍼티, 생성자 승격 프로퍼티, `new`, 반환 타입, `$this`·`self`·`parent`, 인터페이스 호출은 구현 클래스로, Laravel 파사드(`Http::timeout()->get()` 체인 포함) |
| Ruby | Rails `config/routes.rb`(`to:`·`=>` 를 쓴 동사, `root`, `only`·`except`·`member`·`collection`·중첩을 쓴 `resources`·`resource`, `namespace`·`scope`·`controller`, Devise `authenticate`, `draw(:x)` 파일)와 컨트롤러의 인증 관련 `before_action`(`only`·`except`·`skip_before_action`·부모 포함)을 guards 로, Sinatra(`get '/x' do`, `namespace`, `before` 필터) | COMMAND(`system`, 백틱, `Kernel#open`, `Open3`, `IO.popen`), CODE_INJECTION(`eval`, `ERB.new`, `render inline:`), SQL(보간한 `where`·`find_by_sql`·`execute`, 상수가 아닌 `order`·`pluck`·`group`), PATH(`File.*`, `send_file`, `render file:`), SSRF(`Net::HTTP`, `URI.open`, HTTParty, Faraday), DESERIALIZATION(`Marshal.load`, `YAML.unsafe_load`), OPEN_REDIRECT(요청 값을 `redirect_to`), XSS(요청 값에 `html_safe`·`raw`), 약한 암호·난수 | `X.new` 값, `X.new` 로 채운 인스턴스 변수(`@x ||=` 포함), 메모이즈한 헬퍼 메서드, 클래스 메서드, 중첩을 따라 찾는 상수 |
| C# | ASP.NET Core 컨트롤러(`[Route]`·`[HttpGet("{id}")]` 특성 라우팅, `[controller]`·`[action]` 치환과 `~/`, 특성이 없는 컨트롤러는 기본 규칙 `/{controller}/{action}`), Minimal API(`MapGet`·`MapPost`·`MapMethods`, 여러 단계 `MapGroup`). `[Authorize]`·`[AllowAnonymous]`, 인증 필터 특성, `RequireAuthorization()`·`AllowAnonymous()` 를 guards 로 | COMMAND(`Process.Start`, `ProcessStartInfo`), CODE_INJECTION(`CSharpScript`), SQL(조립한 문자열을 `FromSqlRaw`·`ExecuteSqlRaw`·Dapper·`SqlCommand`·`CommandText =` 에. `FromSqlInterpolated` 는 안전), PATH(`File.*`, `PhysicalFile`, `FileStream`), SSRF(`HttpClient`, `WebClient`), DESERIALIZATION(`BinaryFormatter`…, `TypeNameHandling` 을 켠 JSON.NET), XXE(`XmlUrlResolver`·`DtdProcessing.Parse` 를 켰을 때만), OPEN_REDIRECT(요청 값을 `Redirect`. `LocalRedirect` 는 안전), XSS(`Content(x, "text/html")`, `HtmlString`), 약한 암호·난수 | C# 에 적힌 타입으로 해석: 필드, 속성, 기본 생성자 파라미터, 타입·`var` 지역 변수, 반환 타입, 인터페이스 호출은 구현 클래스로 |
| JS / TS / Vue / HTML | – | 줄 단위 패턴 27개 (XSS, eval, 오픈 리다이렉트, 토큰 저장, 비밀값…) | – |
| Kotlin | – (제품이 직접 붙인다: cotcha-pinch 는 외부에 열린 안드로이드 컴포넌트를 Kotlin 핸들러로 잇는다) | Java 규칙(`SinkRules`)을 Kotlin 호출·생성자 호출에 그대로 적용 | 이름 해석: import·별칭, 파라미터·프로퍼티·지역 변수에 적힌 타입, `Foo(...)` 생성자 호출, `this`/`super`, companion 함수, 파일 최상위 함수(`파일이름Kt`), 넘겨받은 프레임워크 반환 타입 |
| Python / FastAPI | FastAPI 라우트(`@app.get`, `@router.post`, `api_route`, `websocket`), `APIRouter(prefix=)` + `include_router(prefix=)`, `Depends`/`Security` | 약 200개 API: COMMAND(subprocess, os.system/popen, asyncio subprocess, python-nmap), SSRF(requests, httpx, aiohttp, urllib), PATH(open, pathlib, FileResponse, shutil), SQL(문자열을 조립해 넘기는 `execute`, `sqlalchemy.text`), DESERIALIZATION(pickle, yaml.load, marshal…), 코드 실행, XXE, 리다이렉트, XSS, 약한 암호·난수 | import 표로 이름 해석 (절대·상대 import, `self.`, `변수 = 호출()`, `with … as`) |

Kotlin 참고:
- tree-sitter(`tree-sitter-kotlin`, MIT)로 파싱한다. 네이티브 라이브러리 지원 플랫폼은 Python 과 같다. 노드에 `language=kotlin` 이 붙는다.
- `KotlinProject`(색인)와 `KotlinGraphSupport`(구조, 상속, 호출 잇기, 위험 지점)는 공개라서 `JavaGraphSupport` 처럼 제품이 자기 진입점·규칙을 얹을 수 있다.
- 저장소에 `AndroidManifest.xml` 이 있으면 기본 엔진이 안드로이드 앱으로 보고 모바일 공격면을 직접 더한다(`android` 패키지): 소켓 진입점, 매니페스트 `exported`·딥링크 진입점, CLEARTEXT / MISSING_AUTH / WEBVIEW_JS / TOKEN_STORAGE 위험 지점(아래 안드로이드 참고). 매니페스트가 없는 순수 Kotlin/Spring 저장소는 그대로다. cotcha-pinch 는 자기 안드로이드 그래프를 위해 여전히 Kotlin 을 직접 쓴다.
- `SwiftProject`·`SwiftGraphSupport` 는 Swift(tree-sitter) 판이다. class·struct·enum·protocol·extension(원래 타입에 합침), `Type#name(label:label:)` 꼴 함수 이름, 상속, 적힌 타입·생성자·싱글턴(`UserDefaults.standard`)으로 호출 잇기, 위험 지점. 기본 파서에는 넣지 않아서 서버 분석은 그대로다.
- `ObjcProject`·`ObjcGraphSupport` 는 Objective-C(tree-sitter) 판이다. 헤더의 `@interface` 와 `.m` 의 `@implementation`·카테고리를 클래스 하나로 합치고, 메서드는 셀렉터(`AppDelegate#application:openURL:options:`)로 부른다. 메시지(`[받는쪽 셀렉터:인자]`)와 C 함수 호출을 적힌 타입, `alloc`/`init`, `instancetype` 반환, 클래스 싱글턴(`[NSUserDefaults standardUserDefaults]`)으로 잇는다. 이것도 기본 파서에는 넣지 않는다.
- `SwiftObjcInterop` 은 한 앱 안의 두 언어 사이 호출을 컴파일러 이름 규칙대로 잇는다: Swift `db.deletePayee(name)` → `-deletePayee:`, `PayeeDatabase(name:)` → `-initWithName:`, Objective-C `[vault storeWithToken:x]` → Swift `store(token:)`. `connect(swiftSupport, objcSupport)` 가 양쪽을 다 달고, Swift 쪽에 Objective-C 반환 타입도 알려 준다(`LegacyStore.shared().save(…)`). 직접 적은 `@objc(이름)`·`NS_SWIFT_NAME`, 필요 없는 단어 빼기는 따르지 않는다.
- `JavaKotlinInterop` 은 Java 와 Kotlin 사이를 잇는다: Java → Kotlin 함수(`FileNameKt.top()`, `Foo.INSTANCE.bar()`, `Foo.Companion.make()`, 프로퍼티 getter 포함), Kotlin → Java 메서드·생성자, import 없이 같은 패키지에서 쓴 서로의 타입 이름. 기본 엔진도 쓴다: 저장소에 둘 다 있으면 Java 파서가 Kotlin 부분까지 만들어서, Kotlin 서비스를 부르는 Java Spring 컨트롤러에서 Kotlin 위험 지점까지 경로가 생긴다. Java 만 있는 저장소는 예전과 똑같다. `@JvmName`, `@JvmOverloads` 기본값, Java 에서 부른 확장 함수는 따르지 않는다.
- 타입 추론은 하지 않는다: 타입을 모르는 값(이어진 호출, 람다, 제네릭)은 따라가지 않는다. 함수 밖 프로퍼티 초기값의 호출은 아직 보지 않는다. Java ↔ Kotlin 호출은 아직 잇지 않는다.

안드로이드 참고(`android` 패키지, `AndroidManifest.xml` 이 있을 때만):
- `AndroidManifest` 가 매니페스트를 파싱한다(XXE 안전, `build/` 아래 생성본은 건너뜀): `usesCleartextTraffic`, `exported` 컴포넌트와 intent-filter·딥링크. `MAIN`/`LAUNCHER` 만 있는 런처 액티비티는 외부 진입점으로 보지 않는다.
- `AndroidDetector` 가 Kotlin 그래프에 더한다: 소켓 진입점(`ServerSocket.accept()` 를 부르는 함수, props `kind=socket`, `transport=tcp|tls`), 매니페스트 `exported` 컴포넌트 진입점(`kind=android-component`), `usesCleartextTraffic` 에 대한 CLEARTEXT 위험 지점(props `mobile=true`), 토큰/PIN/인증 검사가 클래스에 없는 WebSocket 핸드셰이크(`101 Switching Protocols`/`Sec-WebSocket-Accept`)에 대한 MISSING_AUTH, `SharedPreferences` 에 토큰류 키를 `putString` 하는 TOKEN_STORAGE. `AndroidSinkRules` 는 TLS 없는 `ServerSocket`(CLEARTEXT)·`WebView.addJavascriptInterface`(WEBVIEW_JS) 규칙을 `SinkRules` 와 합쳐 쓴다.
- RFC6455 WebSocket 핸드셰이크는 원래 SHA-1 을 쓴다. 핸드셰이크 신호 옆의 `MessageDigest.getInstance("SHA-1")` 은 WEAK_CRYPTO 로 보지 않는다. 다른 약한 해시는 그대로 잡고, 알고리즘은 위험 지점에 `algorithm` 으로 남긴다.

Node.js 참고:
- 설정 없이 판별한다: `node_modules`·`dist` 등 밖의 `package.json` 이 express, koa, fastify, @nestjs/core, hapi, restify, hono, next 중 하나에 의존하면 Node 백엔드로 본다. tree-sitter(`tree-sitter-javascript`, `tree-sitter-typescript`, MIT)로 파싱한다. 노드에 `language=node` 가 붙는다.
- 브라우저 코드 폴더(`public`, `static`, `client`, `frontend`, `web`), 테스트, `.d.ts`, 압축 파일은 건너뛴다. 줄 단위 프론트엔드 검사는 백엔드 파일에서도 비밀값 등을 찾고, 거기서 찾은 것은 `frontend=false` 에 감싼 Node 함수에서 CALLS 로 이어서 진입점 경로가 생긴다.
- 인자가 입력을 실을 수 있을 때만 위험 지점이다: SQL·eval 은 조립한 문자열(`${}` 템플릿, `+`), NoSQL·프로토타입 오염·`res.send` 는 요청 값(`req.body`, `req.query`, `ctx.request.body`, NestJS `@Body()`·`@Query()` 파라미터와 그것을 받은 지역 변수), 명령·경로·SSRF 는 리터럴이 아닌 인자. `query`·`findOne`·`sendFile` 같은 메서드는 받는 쪽(DB 풀, Express 응답)을 모르면 이름으로 맞춘다.
- 처리기를 만드는 함수도 따라간다(`app.post('/login', login())`, `asyncHandler(resetPassword())`: 처리기를 돌려주는 함수를 처리기로 본다). 경로를 준 미들웨어(`app.use('/rest/basket', auth)`, 경로 배열 포함)는 그 경로 아래 라우트의 guards 로 남기고, 보안 헤더·로깅·파서·업로드 미들웨어(helmet, cors, morgan, multer…)는 뺀다. 처리기 안에서 하는 권한 검사는 guards 로 남기지 않는다.
- 라이브러리나 `app.use` 가 만드는 라우트도 진입점이다: finale-rest·epilogue `finale.resource({ endpoints })`(목록 경로는 GET·POST, `/:id` 는 GET·PUT·PATCH·DELETE. `for (const { name } of models)` 안의 템플릿 경로는 원소마다 편다)는 같은 경로에 따로 단 `app.get('/api/Users', auth)` 를 그 guards 로 합친다. 경로를 준 `app.use` 의 마지막 인자가 파일을 내주거나 응답을 보내면(`app.use('/ftp', serveIndex(...))`, `express.static`, 프록시, `res.send` 를 부르는 처리기) `ANY /path` 끝점이다. `next()` 를 부르는 미들웨어는 guards 로 남는다. 같은 파일에서 라우트보다 뒤에 등록한 미들웨어는 그 라우트의 guards 가 아니고, 라우터를 마운트한 뒤에 등록한 바깥 미들웨어도 그 라우터 라우트의 guards 가 아니다(Express 는 등록 순서대로 돈다).

Go 참고:
- 저장소에 `.go` 파일(`vendor/`, `testdata/`, `_test.go` 제외)이 있으면 판별한다. tree-sitter(`tree-sitter-go`, MIT)로 파싱한다. 노드에 `language=go` 가 붙고, 함수 키는 `폴더#함수`, `폴더#타입.메서드` 이며 이름 없는 처리기는 라우트로 이름 붙인다(`internal/handlers#GET /files/:name`).
- 상수만 넘기는 호출(`exec.Command("uptime")`, `os.ReadFile("/etc/app.conf")`, `db.Query("... $1", id)`)은 위험 지점이 아니다. SQL 은 쿼리를 `fmt.Sprintf`·`+`·`+=` 로 조립했을 때 잡는다. `c.Data` 는 Content-Type 이 HTML(또는 상수가 아님)일 때만 XSS, `fmt.Fprintf`·`io.WriteString` 은 `http.ResponseWriter` 에 쓸 때만 본다.
- 따라가지 않는 것: 구조체 필드에 넣어 다른 곳에서 채운 라우터, 변수에 담은 함수 값, 제네릭. 경로를 준 `app.Use("/x", fn)` 미들웨어는 guards 로 남기지 않는다.

PHP 참고:
- 저장소에 `.php` 파일(`vendor/`, `storage/`, `tests/`, `*.blade.php`, 마이그레이션 제외)이 있으면 판별한다. tree-sitter(`tree-sitter-php`, MIT)로 파싱한다. 노드에 `language=php` 가 붙고, 메서드 키는 `전체클래스이름#메서드`(`App\Http\Controllers\UserController#show`), 파일 최상위 코드는 `file.php#<main>`, 익명 라우트 처리기는 라우트로 이름 붙인다.
- 요청 값: `$_GET`·`$_POST`·`$_REQUEST`·`$_COOKIE`·`$_FILES`·`$_SERVER`, Request 타입 파라미터의 `$request->input()`·`query()`·`getContent()`…, Symfony 요청 bag, `request()`, CodeIgniter `$this->request->getGet()`, 라우트 처리기의 스칼라 파라미터(경로 변수, Slim `$args`).
- 상수만 넘기는 호출은 위험 지점이 아니다. SQL 은 보간·`.`·`.=`·`sprintf` 로 만든 쿼리일 때, XXE 는 `LIBXML_NOENT`·`LIBXML_DTDLOAD` 가 있을 때, `header()` 는 `Location` 일 때만 본다.
- 따라가지 않는 것: Blade 템플릿(`{!! !!}`), 동적 호출(`$fn()`, `call_user_func`), Laravel 컨테이너 바인딩(인터페이스 호출은 구현 클래스 모두로 잇는다), Eloquent 모델.

Ruby 참고:
- 저장소에 `.rb` 파일(`vendor/`, `spec/`, `test/`, `db/`, `tmp/` 제외)이 있으면 판별한다. tree-sitter(`tree-sitter-ruby`, MIT)로 파싱한다. 노드에 `language=ruby` 가 붙고, 메서드 키는 `Module::Class#method`(`Api::ProductsController#search`), 클래스 메서드는 `Class#self.name`, Sinatra 처리기는 라우트로 이름 붙인다.
- Ruby 는 타입을 적지 않으므로 `X.new`, 그것으로 채운 인스턴스 변수, 메모이즈한 헬퍼, 클래스 메서드로 호출을 따라간다. ActiveRecord 모델과 받는 쪽을 모르는 호출은 메서드 이름(`where`, `order`, `execute`…)으로 맞춘다.
- 요청 값: `params`, `request.body`·`raw_post`·`headers`…, `cookies[...]`, Sinatra 블록 파라미터. `redirect_to` 는 요청 값일 때만 위험 지점이다(`root_path` 같은 경로 헬퍼는 아님). `YAML.load` 는 Psych 4 부터 안전해서 뺀다. 프로그램이 상수인 `system('cmd', arg)`(셸 없음)도 위험 지점이 아니다.
- 따라가지 않는 것: `send`·`public_send`, 메타프로그래밍(`define_method`), `include` 로 섞은 concern, Grape·Hanami·Roda 라우트, ERB·Haml 뷰.

C# 참고:
- 저장소에 `.cs` 파일(`bin/`, `obj/`, `Migrations/`, `*.Designer.cs`, 테스트 제외)이 있으면 판별한다. tree-sitter(`tree-sitter-c-sharp`, MIT)로 파싱한다. 노드에 `language=csharp` 가 붙고, 메서드 키는 `Namespace.Class#Method`, 최상위 문장은 `Program.cs#<main>`, Minimal API 람다는 라우트로 이름 붙인다.
- 요청 값: 요청에서 바인딩되는 액션 파라미터(`[FromQuery]`·`[FromBody]`·`[FromRoute]`·`[FromForm]`·`[FromHeader]`, 또는 `I…`·`…Service`·`…Repository`·`CancellationToken` 같은 서비스가 아닌 파라미터), Minimal API 람다 파라미터, `Request.Query`·`Form`·`Headers`·`Cookies`·`Body`.
- 기본으로 안전한 API 는 위험 지점이 아니다: `FromSqlInterpolated`·`FromSql`, `LocalRedirect`, `XmlUrlResolver` 를 켜지 않은 `XmlDocument`, `TypeNameHandling` 없는 JSON.NET, 상수만 넘긴 `Process.Start("cmd")`.
- 따라가지 않는 것: Razor 뷰(`@Html.Raw`), 리플렉션, 소스 생성기, DI 등록(인터페이스 호출은 구현 클래스 모두로 잇는다), 대체 정책으로 거는 전역 인증.

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
| 2-5 | Swift ↔ Objective-C 호출 연결 (`SwiftObjcInterop`) | ✅ 완료 (v0.7.0) |
| 2-6 | Java ↔ Kotlin 호출 연결 (`JavaKotlinInterop`), 기본 엔진에도 적용 | ✅ 완료 (v0.8.0) |
| 2-7 | Kotlin Spring 진입점(`KotlinEntryPointDetector`): `@RestController`·`@Controller` 의 `@GetMapping`… 과 `@RequestMapping(value = […], method = […])`, Java 와 같은 이름·props. tree-sitter-kotlin 이 클래스에서 떼어 읽은 애너테이션도 읽음 | ✅ 완료 (v0.9.0) |
| 2-8 | WebFlux 함수형 라우터를 진입점으로: Java `RouterFunctions.route(GET("/x"), h::get)`·`.andRoute`·`nest(path(…), …)`·`route().GET(…).path(…, b -> …)` 빌더(`WebFluxRouteDetector`), Kotlin `router { }`·`coRouter { }` DSL 과 `"/api".nest { }`(`KotlinRouterDetector`). 처리 함수는 메서드 참조나 람다 안 첫 호출, 못 찾으면 라우터 함수로 잇는다 | ✅ 완료 (v0.10.0) |
| 2-9 | 상수 경로: `@GetMapping(Paths.USERS)`, `GET(ApiPaths.ORDERS + "/x")`, static import, Kotlin `const val`·문자열 템플릿(`"$V2/users"`)을 MVC 매핑과 라우터에서 값으로 푼다(`JavaConstants`, `KotlinProject.constantValue`). 모르는 상수는 `${식}` 으로 남긴다 | ✅ 완료 (v0.11.0) |
| 2-10 | Flask·Django 진입점(`PythonWebFrameworks`): Flask 앱·블루프린트(`url_prefix`, 중첩 `register_blueprint`), `@app.route`·`@bp.get`, `add_url_rule`, `MethodView`, Flask-RESTful·RESTX, Django `urlpatterns`·`include`·함수/클래스 뷰·DRF 라우터·`@action`. 진입점에 `framework` 와 `guards`(인증 단서: 데코레이터, `*Mixin`, `permission_classes`, `before_request`). 새 Python 위험 지점: `render_template_string`(SSTI), Django `redirect`·`HttpResponseRedirect`, `mark_safe`, `RawSQL`, `FileResponse` | ✅ 완료 (v0.12.0) |
| 2-11 | Django `ROOT_URLCONF`: settings 가 가리키는 urls 모듈(설정 파일이 여럿이면 모두)에서 시작해서, 어디에도 연결되지 않은 urls 파일은 뺀다. 없으면 다른 곳에서 include 하지 않은 urls 모듈을 모두 시작점으로 | ✅ 완료 (v0.13.0) |
| 2-12 | 환경 변수로 정한 `ROOT_URLCONF`: `os.environ.get`·`os.getenv` 기본값, django-environ `env(…, default=)`, python-decouple `config(…, default=)`, `os.environ["X"]` 는 `.env*` 파일에서, 그리고 모듈 변수, `a if DEBUG else b`, `+`, f-string | ✅ 완료 (v0.14.0) |
| 2-13 | 환경 변수 값을 docker-compose(`environment:` 목록·맵, `${X:-기본값}`, `env_file:` 로 가리킨 파일)와 Dockerfile `ENV` 에서도 찾는다 | ✅ 완료 (v0.15.0) |
| 2-14 | 환경 변수 값을 Kubernetes 매니페스트(컨테이너 `env`, 한 줄 형식 포함, `configMapKeyRef`·`secretKeyRef` 로 가리킨 ConfigMap `data`·Secret `stringData`, `envFrom` 키)와 Kustomize `configMapGenerator`(`literals`, `envs` 파일)에서도 찾는다. 찾는 깊이 6 | ✅ 완료 (v0.16.0) |
| 2-1 | `JavaGraphSupport`: 규칙이 붙지 않은 Java 부품을 공개 API 로. 다른 제품(안드로이드용 cotcha-pinch)이 파싱·호출 연결을 복사하지 않고 쓴다. be·mcp 결과는 그대로 | ✅ 완료 (v0.3.0) |
| 2-15 | 기본 엔진에서 안드로이드 공격면 탐지(새 `android` 패키지), `AndroidManifest.xml` 이 있을 때만: `ServerSocket.accept()` 수락 루프를 소켓 진입점으로, 매니페스트 `exported` 컴포넌트·intent-filter·딥링크를 진입점으로, 그리고 모바일 위험 지점 — `usesCleartextTraffic`·TLS 없는 `ServerSocket`(CLEARTEXT), 토큰/PIN 검사 없는 소켓(WebSocket) 핸드셰이크(MISSING_AUTH), `WebView.addJavascriptInterface`(WEBVIEW_JS), `SharedPreferences` 토큰 저장(TOKEN_STORAGE). RFC6455 WebSocket 핸드셰이크용 SHA-1 은 더 이상 WEAK_CRYPTO 오탐이 아니다 | ✅ 완료 (v0.17.0) |
| 2-16 | 기본 엔진에서 Node.js 백엔드 분석(새 `node` 패키지, JavaScript·TypeScript): Express, Koa, Fastify, Hono, Restify, NestJS, Next.js 진입점과 마운트 접두사·`guards`, `require`·`import`·클래스·NestJS 주입을 따라가는 호출, 새 분류 NOSQL_INJECTION·PROTOTYPE_POLLUTION 을 포함한 Node 위험 지점 규칙 | ✅ 완료 (v0.18.0) |
| 2-17 | 기본 엔진에서 Go 백엔드 분석(새 `golang` 패키지): net/http, gin, echo, chi, gorilla/mux, fiber 진입점과 그룹·마운트 경로, 함수로 넘긴 라우터, `guards`. Go 에 적힌 타입과 인터페이스로 호출 잇기. 템플릿 주입·`InsecureSkipVerify` 를 포함한 Go 위험 지점 규칙 | ✅ 완료 (v0.19.0) |
| 2-18 | 기본 엔진에서 PHP 백엔드 분석(새 `php` 패키지): Laravel, Symfony, Slim, CodeIgniter 4, 순수 스크립트 진입점과 접두사·`guards`. PHP 에 적힌 타입·인터페이스·Laravel 파사드로 호출 잇기. XXE·`unserialize`·파일 포함을 포함한 PHP 위험 지점 규칙 | ✅ 완료 (v0.20.0) |
| 2-19 | 기본 엔진에서 Ruby 백엔드 분석(새 `ruby` 패키지): Rails 라우트 DSL·Sinatra 진입점과 `before_action`·`authenticate`·`before` guards, `X.new`·인스턴스 변수·클래스 메서드로 호출 잇기, `Kernel#open`·`Marshal.load`·`ERB.new`·`html_safe` 를 포함한 Ruby 위험 지점 규칙 | ✅ 완료 (v0.21.0) |
| 2-20 | 기본 엔진에서 C# 백엔드 분석(새 `csharp` 패키지): ASP.NET Core 컨트롤러(특성·기본 규칙 라우팅)와 Minimal API, `[Authorize]`·`RequireAuthorization` guards. C# 에 적힌 타입·인터페이스로 호출 잇기. 기본으로 안전한 API(`FromSqlInterpolated`, `LocalRedirect`, 리졸버 없는 `XmlDocument`)는 뺀 C# 위험 지점 규칙 | ✅ 완료 (v0.22.0) |
| 2-21 | 실제 앱(OWASP Juice Shop)으로 본 Node.js 보강: 처리기를 만드는 함수(`login()`, `asyncHandler(x())`), 경로·경로 배열을 준 `app.use` 미들웨어를 guards 로, 기반 미들웨어는 guards 에서 뺌, NoSQL `update`·`count`·`distinct`. Juice Shop 진입점 17 → 99 | ✅ 완료 (v0.23.0) |
| 2-22 | Juice Shop 실행이 짚은 Node.js 라우트: finale-rest 가 만드는 REST 라우트(모델 반복문의 템플릿 경로를 펴고, 같은 경로의 guards 라우트를 합침), 파일을 내주거나 응답하는 경로 지정 `app.use` 를 `ANY` 끝점으로(`/ftp`, `/encryptionkeys`, `/support/logs`, `/metrics`), 미들웨어 등록 순서. Juice Shop 진입점 99 → 175 | ✅ 완료 (v0.24.0) |
| 2-23 | 마운트를 거친 Node.js 미들웨어 순서: `app.use('/x', router)` 보다 뒤에 등록한 바깥 `app.use` 는 그 라우터 라우트의 guards 가 아니다(Juice Shop 맨 끝 오류 처리기·SPA 대체 처리기가 더는 guards 로 나오지 않음) | ✅ 완료 (v0.25.0) |

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
├── rules/                    SinkRule, SinkRules (Java), PythonSinkRules, NodeSinkRules, GoSinkRules, PhpSinkRules, RubySinkRules, CsSinkRules
├── java/                     JavaLanguage, JavaProject, TypeResolver, EntryPointDetector, CodeGraphBuilder
├── python/                   PythonLanguage (tree-sitter), PythonModule (import·정의 색인), PythonGraphBuilder (FastAPI, 호출, 위험 지점)
├── csharp/                   CsLanguage (그래프), CsProject (클래스·멤버·타입), CsRoutes (컨트롤러, Minimal API)
├── ruby/                     RubyLanguage (그래프), RubyProject (클래스·인스턴스 변수·타입), RubyRoutes (Rails 라우트 DSL, Sinatra)
├── php/                      PhpLanguage (그래프), PhpProject (namespace·클래스·타입), PhpRoutes (Laravel, Symfony, Slim, CodeIgniter, 순수 스크립트)
├── golang/                   GoLanguage (그래프), GoProject (패키지·타입·타입 추론), GoRoutes (net/http, gin, echo, chi, gorilla, fiber)
├── node/                     NodeLanguage (판별, 그래프), NodeProject (모듈·import·값), NodeRoutes (Express/Koa/Fastify/NestJS/Next.js)
└── frontend/                 FrontendGraph, FrontendScanner (JS/TS/Vue/HTML)
src/test/                     단위 테스트와 예제 앱 (vulnerable-app, idor-app, owasp-app, fastapi-app)
docs/design.md                설계 문서
```

## 라이선스

Apache-2.0. ARTEX 는 아이디어만 참고했고 코드나 프롬프트는 복사하지 않았다.
