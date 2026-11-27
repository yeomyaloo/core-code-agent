package com.codeagent.core.php;

import com.codeagent.core.CodeGraphEngine;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft.EdgeDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PhpGraphTest {

    @TempDir
    static Path root;

    private static CodeGraphEngine.Result result;

    @BeforeAll
    static void build() throws IOException {
        // ---- Laravel ----
        write("routes/web.php", """
                <?php
                use App\\Http\\Controllers\\ProductController;
                use App\\Http\\Controllers\\AdminController;
                use Illuminate\\Support\\Facades\\Route;

                Route::get('/', function () { return view('home'); });
                Route::get('/products/search', [ProductController::class, 'search']);
                Route::prefix('admin')->middleware(['auth', 'can:admin'])->group(function () {
                    Route::post('/export', [AdminController::class, 'export']);
                    Route::get('/go', function () {
                        return redirect(request('next'));
                    });
                });
                Route::resource('photos', PhotoController::class);
                Route::get('/legacy/{id}', 'ProductController@show')->middleware('auth');
                """);
        write("routes/api.php", """
                <?php
                use App\\Http\\Controllers\\ProductController;
                use Illuminate\\Support\\Facades\\Route;

                Route::middleware('auth:sanctum')->get('/me', [ProductController::class, 'me']);
                """);
        write("app/Http/Controllers/ProductController.php", """
                <?php
                namespace App\\Http\\Controllers;

                use App\\Repositories\\ProductRepository;
                use Illuminate\\Http\\Request;

                class ProductController extends Controller
                {
                    public function __construct(private ProductRepository $products) {}

                    public function search(Request $request)
                    {
                        return $this->products->search($request->input('q'));
                    }

                    public function show($id)
                    {
                        return file_get_contents('/data/' . $id);
                    }

                    public function me(Request $request)
                    {
                        Http::timeout(5)->get($request->input('u'));
                        return md5('x');
                    }
                }
                """);
        write("app/Http/Controllers/PhotoController.php", """
                <?php
                namespace App\\Http\\Controllers;

                class PhotoController extends Controller
                {
                    public function __construct()
                    {
                        $this->middleware('auth');
                    }

                    public function index() { return []; }

                    public function destroy($photo) { return unlink('/photos/' . $photo); }
                }
                """);
        write("app/Http/Controllers/AdminController.php", """
                <?php
                namespace App\\Http\\Controllers;

                use Illuminate\\Http\\Request;

                class AdminController extends Controller
                {
                    public function export(Request $request)
                    {
                        $dir = $request->input('dir');
                        exec("tar czf /tmp/out.tgz /exports/$dir");
                        exec('uptime');
                    }
                }
                """);
        write("app/Repositories/ProductRepository.php", """
                <?php
                namespace App\\Repositories;

                use Illuminate\\Support\\Facades\\DB;

                class ProductRepository
                {
                    public function search(string $q)
                    {
                        $rows = DB::select("SELECT * FROM products WHERE name LIKE '%$q%'");
                        DB::select('SELECT * FROM products WHERE id = ?', [$q]);
                        return $rows;
                    }
                }
                """);
        // ---- Symfony ----
        write("src/Controller/ReportController.php", """
                <?php
                namespace App\\Controller;

                use Symfony\\Component\\HttpFoundation\\Request;
                use Symfony\\Component\\HttpFoundation\\Response;
                use Symfony\\Component\\Routing\\Attribute\\Route;
                use Symfony\\Component\\Security\\Http\\Attribute\\IsGranted;

                #[Route('/reports')]
                class ReportController
                {
                    #[Route('/{name}', methods: ['GET'])]
                    #[IsGranted('ROLE_USER')]
                    public function show(Request $request, string $name): Response
                    {
                        return new Response($request->query->get('title'));
                    }

                    /**
                     * @Route("/legacy", methods={"POST"})
                     */
                    public function legacy(Request $request): Response
                    {
                        $data = unserialize($request->getContent());
                        return new Response('ok');
                    }
                }
                """);
        // ---- Slim ----
        write("slim/routes.php", """
                <?php
                use Slim\\App;
                use Slim\\Routing\\RouteCollectorProxy;

                return function (App $app) {
                    $app->get('/ping', function ($request, $response) { return $response; });
                    $app->group('/api', function (RouteCollectorProxy $group) {
                        $group->get('/fetch', function ($request, $response) {
                            $url = $request->getQueryParams()['url'];
                            $ch = curl_init($url);
                            return $response;
                        });
                    })->add(new AuthMiddleware());
                };
                """);
        // ---- CodeIgniter 4 ----
        write("app/Config/Routes.php", """
                <?php
                $routes->get('/news/(:segment)', 'News::view/$1');
                $routes->group('admin', ['filter' => 'auth'], static function ($routes) {
                    $routes->get('users', 'Admin::users');
                });
                """);
        write("app/Controllers/News.php", """
                <?php
                namespace App\\Controllers;

                class News extends BaseController
                {
                    public function view($slug)
                    {
                        echo $this->request->getGet('q');
                    }
                }
                """);
        write("app/Controllers/Admin.php", """
                <?php
                namespace App\\Controllers;

                class Admin extends BaseController
                {
                    public function users()
                    {
                        return rand(1, 10);
                    }
                }
                """);
        // ---- 순수 PHP ----
        write("public/search.php", """
                <?php
                require_once __DIR__ . '/../lib/auth_check.php';
                $q = $_GET['q'];
                $conn = mysqli_connect('db', 'u', 'p', 'shop');
                $result = mysqli_query($conn, "SELECT * FROM items WHERE name = '" . $q . "'");
                echo "<h1>" . $q . "</h1>";
                echo htmlspecialchars('static');
                include $_GET['page'] . '.php';
                $out = `ls $q`;
                header('Location: ' . $_GET['next']);
                header('Content-Type: text/html');
                """);
        write("lib/auth_check.php", """
                <?php
                session_start();
                if (empty($_SESSION['user'])) { header('Location: /login.php'); exit; }
                """);
        result = CodeGraphEngine.analyze(root);
    }

    @Test
    void 설정_없이_PHP_로_판별한다() {
        assertThat(result.languages()).contains("php");
        assertThat(result.parseErrors()).isEmpty();
    }

    @Test
    void Laravel_라우트_그룹_리소스_미들웨어를_찾는다() {
        assertThat(entries()).contains(
                "GET / -> routes/web.php#GET /",
                "GET /products/search -> App\\Http\\Controllers\\ProductController#search",
                "POST /admin/export -> App\\Http\\Controllers\\AdminController#export",
                "GET /admin/go -> routes/web.php#GET /admin/go",
                "GET /photos -> App\\Http\\Controllers\\PhotoController#index",
                "DELETE /photos/{photo} -> App\\Http\\Controllers\\PhotoController#destroy",
                "GET /legacy/{id} -> App\\Http\\Controllers\\ProductController#show",
                "GET /api/me -> App\\Http\\Controllers\\ProductController#me");
        assertThat(entry("POST /admin/export").props().get("guards")).isEqualTo(List.of("auth", "can:admin"));
        assertThat(entry("GET /photos").props().get("guards")).isEqualTo(List.of("auth"));
        assertThat(entry("GET /api/me").props().get("guards")).isEqualTo(List.of("auth:sanctum"));
        assertThat(entry("GET /products/search").props().get("guards")).isEqualTo(List.of());
    }

    @Test
    void Symfony_Slim_CodeIgniter_순수_PHP_진입점을_찾는다() {
        assertThat(entries()).contains(
                "GET /reports/{name} -> App\\Controller\\ReportController#show",
                "POST /reports/legacy -> App\\Controller\\ReportController#legacy",
                "GET /ping -> slim/routes.php#GET /ping",
                "GET /api/fetch -> slim/routes.php#GET /api/fetch",
                "GET /news/(:segment) -> App\\Controllers\\News#view",
                "GET /admin/users -> App\\Controllers\\Admin#users",
                "ANY /search.php -> public/search.php#<main>");
        assertThat(entry("GET /reports/{name}").props().get("guards")).isEqualTo(List.of("IsGranted('ROLE_USER')"));
        assertThat(entry("GET /api/fetch").props().get("guards")).isEqualTo(List.of("AuthMiddleware"));
        assertThat(entry("GET /admin/users").props().get("guards")).isEqualTo(List.of("filter:auth"));
        assertThat(entry("ANY /search.php").props().get("guards")).isEqualTo(List.of("require_once __DIR__ . '/../lib/auth_check.php'"));
        // 요청 값을 읽지 않는 파일은 진입점이 아니다
        assertThat(entries()).noneMatch(e -> e.contains("auth_check.php"));
    }

    @Test
    void 상수가_아닌_값이_들어가는_위험_호출만_위험_지점으로_본다() {
        assertThat(sinks()).containsExactlyInAnyOrder(
                "app/Repositories/ProductRepository.php:10 SQL",
                "app/Http/Controllers/ProductController.php:18 PATH",
                "app/Http/Controllers/ProductController.php:23 SSRF",
                "app/Http/Controllers/ProductController.php:24 WEAK_CRYPTO",
                "app/Http/Controllers/PhotoController.php:13 PATH",
                "app/Http/Controllers/AdminController.php:11 COMMAND",
                "routes/web.php:11 OPEN_REDIRECT",
                "src/Controller/ReportController.php:16 XSS",
                "src/Controller/ReportController.php:24 DESERIALIZATION",
                "slim/routes.php:10 SSRF",
                "app/Controllers/News.php:8 XSS",
                "app/Controllers/Admin.php:8 WEAK_RANDOM",
                "public/search.php:5 SQL",
                "public/search.php:6 XSS",
                "public/search.php:8 CODE_INJECTION",
                "public/search.php:9 COMMAND",
                "public/search.php:10 OPEN_REDIRECT");
    }

    @Test
    void 생성자_승격_프로퍼티의_타입으로_호출을_잇는다() {
        assertThat(result.draft().edges()).contains(new EdgeDraft(
                "METHOD|App\\Http\\Controllers\\ProductController#search", "METHOD|App\\Repositories\\ProductRepository#search",
                CodeEdgeKind.CALLS));
    }

    private static NodeDraft entry(String prefix) {
        return result.draft().nodes().stream()
                .filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT && n.qualifiedName().startsWith(prefix + " -> "))
                .findFirst().orElseThrow();
    }

    private static List<String> sinks() {
        return result.draft().nodes().stream().filter(n -> n.kind() == CodeNodeKind.SINK)
                .map(n -> n.filePath() + ":" + n.startLine() + " " + n.props().get("category")).toList();
    }

    private static List<String> entries() {
        return result.draft().nodes().stream().filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT).map(NodeDraft::qualifiedName).toList();
    }

    private static void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
