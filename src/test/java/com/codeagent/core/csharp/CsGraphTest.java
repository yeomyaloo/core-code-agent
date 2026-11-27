package com.codeagent.core.csharp;

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

class CsGraphTest {

    @TempDir
    static Path root;

    private static CodeGraphEngine.Result result;

    @BeforeAll
    static void build() throws IOException {
        write("Shop.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"></Project>\n");
        write("Program.cs", """
                var builder = WebApplication.CreateBuilder(args);
                var app = builder.Build();

                app.MapControllers();
                app.MapGet("/health", () => "ok");

                var api = app.MapGroup("/api/v2").RequireAuthorization();
                api.MapGet("/files/{name}", (string name) => Results.File(Path.Combine("/data", name)));
                api.MapPost("/fetch", async (string url, HttpClient http) => await http.GetStringAsync(url)).AllowAnonymous();
                app.MapGet("/go", (string next) => Results.Redirect(next));

                app.Run();
                """);
        write("Controllers/ProductsController.cs", """
                using Microsoft.AspNetCore.Mvc;
                using Shop.Data;

                namespace Shop.Controllers;

                [ApiController]
                [Route("api/[controller]")]
                public class ProductsController(IProductRepository products) : ControllerBase
                {
                    [HttpGet("search")]
                    public IActionResult Search([FromQuery] string q)
                    {
                        return Ok(products.Search(q));
                    }

                    [HttpGet("{id}")]
                    [Authorize(Roles = "Admin")]
                    public IActionResult Delete(int id)
                    {
                        var md5 = MD5.Create();
                        return Content("<p>" + id + "</p>", "text/html");
                    }

                    [HttpPost("/legacy/run")]
                    public IActionResult Run(string cmd)
                    {
                        Process.Start("/bin/sh", "-c " + cmd);
                        Process.Start("uptime");
                        return Ok();
                    }
                }
                """);
        write("Data/ProductRepository.cs", """
                using Microsoft.EntityFrameworkCore;

                namespace Shop.Data;

                public interface IProductRepository
                {
                    List<Product> Search(string q);
                }

                public class ProductRepository : IProductRepository
                {
                    private readonly AppDbContext _db;

                    public ProductRepository(AppDbContext db) { _db = db; }

                    public List<Product> Search(string q)
                    {
                        var unsafeRows = _db.Products.FromSqlRaw($"SELECT * FROM Products WHERE Name LIKE '%{q}%'").ToList();
                        var safeRows = _db.Products.FromSqlInterpolated($"SELECT * FROM Products WHERE Name LIKE {q}").ToList();
                        using var cmd = new SqlCommand("SELECT * FROM Products WHERE Id = @id");
                        cmd.CommandText = "SELECT * FROM Products WHERE Name = '" + q + "'";
                        return unsafeRows;
                    }
                }
                """);
        write("Controllers/HomeController.cs", """
                namespace Shop.Controllers;

                [Authorize]
                public class HomeController : Controller
                {
                    [AllowAnonymous]
                    public IActionResult Index(string returnUrl)
                    {
                        return Redirect(returnUrl);
                    }

                    public IActionResult Import(string xml)
                    {
                        var doc = new XmlDocument { XmlResolver = new XmlUrlResolver() };
                        doc.LoadXml(xml);
                        var settings = new JsonSerializerSettings { TypeNameHandling = TypeNameHandling.All };
                        var obj = JsonConvert.DeserializeObject(xml, settings);
                        var safe = JsonConvert.DeserializeObject<Product>(xml);
                        return LocalRedirect(returnUrl());
                    }

                    private string returnUrl() => "/";
                }
                """);
        write("Controllers/ExtraController.cs", """
                namespace Shop.Controllers;

                public class ExtraController(IHttpClientFactory factory) : ControllerBase
                {
                    [HttpGet("/extra/fetch")]
                    public async Task<IActionResult> Fetch(string url)
                    {
                        var http = factory.CreateClient();
                        await http.GetStringAsync(url);
                        using var reader = new StreamReader(Request.Body);
                        var info = new ProcessStartInfo("git", url);
                        Process.Start(info);
                        return Ok();
                    }
                }
                """);
        result = CodeGraphEngine.analyze(root);
    }

    @Test
    void 설정_없이_CSharp_으로_판별한다() {
        assertThat(result.languages()).contains("csharp");
        assertThat(result.parseErrors()).isEmpty();
    }

    @Test
    void 컨트롤러_특성_라우팅과_기본_규칙_라우팅을_찾는다() {
        assertThat(entries()).contains(
                "GET /api/Products/search -> Shop.Controllers.ProductsController#Search",
                "GET /api/Products/{id} -> Shop.Controllers.ProductsController#Delete",
                "POST /legacy/run -> Shop.Controllers.ProductsController#Run",
                "ANY /Home/Index -> Shop.Controllers.HomeController#Index",
                "ANY /Home/Import -> Shop.Controllers.HomeController#Import");
        assertThat(entry("GET /api/Products/{id}").props().get("guards")).isEqualTo(List.of("Authorize(Roles = \"Admin\")"));
        assertThat(entry("GET /api/Products/search").props().get("guards")).isEqualTo(List.of());
        // 클래스의 [Authorize] 는 [AllowAnonymous] 액션에서 빠진다
        assertThat(entry("ANY /Home/Import").props().get("guards")).isEqualTo(List.of("Authorize"));
        assertThat(entry("ANY /Home/Index").props().get("guards")).isEqualTo(List.of());
    }

    @Test
    void Minimal_API_와_그룹_경로_인증을_찾는다() {
        assertThat(entries()).contains(
                "GET /health -> Program.cs#GET /health",
                "GET /api/v2/files/{name} -> Program.cs#GET /api/v2/files/{name}",
                "POST /api/v2/fetch -> Program.cs#POST /api/v2/fetch",
                "GET /go -> Program.cs#GET /go");
        assertThat(entry("GET /api/v2/files/{name}").props().get("guards")).isEqualTo(List.of("RequireAuthorization()"));
        assertThat(entry("POST /api/v2/fetch").props().get("guards")).isEqualTo(List.of());
    }

    @Test
    void 안전한_API_는_빼고_위험_지점을_찾는다() {
        // FromSqlInterpolated, 상수 SqlCommand, LocalRedirect, TypeNameHandling 없는 DeserializeObject, Process.Start("uptime") 은 아니다
        assertThat(sinks()).containsExactlyInAnyOrder(
                "Data/ProductRepository.cs:18 SQL",
                "Data/ProductRepository.cs:21 SQL",
                "Controllers/ProductsController.cs:20 WEAK_CRYPTO",
                "Controllers/ProductsController.cs:21 XSS",
                "Controllers/ProductsController.cs:27 COMMAND",
                "Controllers/HomeController.cs:9 OPEN_REDIRECT",
                "Controllers/HomeController.cs:15 XXE",
                "Controllers/HomeController.cs:17 DESERIALIZATION",
                "Program.cs:8 PATH",
                "Program.cs:9 SSRF",
                "Program.cs:10 OPEN_REDIRECT",
                // CreateClient() 로 만든 HttpClient, ProcessStartInfo 는 한 번만 (Process.Start(info) 와 new StreamReader(스트림) 은 아니다)
                "Controllers/ExtraController.cs:9 SSRF",
                "Controllers/ExtraController.cs:11 COMMAND");
    }

    @Test
    void 기본_생성자로_받은_인터페이스_호출을_구현으로_잇는다() {
        assertThat(result.draft().edges()).contains(new EdgeDraft(
                "METHOD|Shop.Controllers.ProductsController#Search", "METHOD|Shop.Data.ProductRepository#Search", CodeEdgeKind.CALLS));
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
