package com.codeagent.core.golang;

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

class GoGraphTest {

    @TempDir
    static Path root;

    private static CodeGraphEngine.Result result;

    @BeforeAll
    static void build() throws IOException {
        write("go.mod", """
                module example.com/shop

                go 1.22
                """);
        // ---- gin: 그룹, 메서드로 넘긴 라우터, 인터페이스 호출 ----
        write("main.go", """
                package main

                import (
                	"database/sql"
                	"os"

                	"example.com/shop/internal/handlers"
                	"example.com/shop/internal/middleware"
                	"example.com/shop/internal/store"
                	"github.com/gin-gonic/gin"
                )

                func main() {
                	db, _ := sql.Open("postgres", os.Getenv("DSN"))
                	st := store.New(db)
                	h := handlers.New(st)
                	r := gin.Default()
                	r.GET("/health", func(c *gin.Context) { c.String(200, "ok") })
                	api := r.Group("/api")
                	h.Register(api)
                	admin := api.Group("/admin", middleware.RequireAuth(), middleware.RequireAdmin())
                	admin.POST("/export", h.Export)
                	r.Run()
                }
                """);
        write("internal/middleware/auth.go", """
                package middleware

                import "github.com/gin-gonic/gin"

                func RequireAuth() gin.HandlerFunc { return func(c *gin.Context) { c.Next() } }

                func RequireAdmin() gin.HandlerFunc { return func(c *gin.Context) { c.Next() } }
                """);
        write("internal/handlers/handlers.go", """
                package handlers

                import (
                	"os/exec"
                	"path/filepath"

                	"example.com/shop/internal/store"
                	"github.com/gin-gonic/gin"
                )

                type Handler struct {
                	store store.Store
                }

                func New(s store.Store) *Handler { return &Handler{store: s} }

                func (h *Handler) Register(g *gin.RouterGroup) {
                	g.GET("/products/search", h.Search)
                	g.GET("/files/:name", func(c *gin.Context) {
                		c.File(filepath.Join("/data", c.Param("name")))
                	})
                }

                func (h *Handler) Search(c *gin.Context) {
                	q := c.Query("q")
                	rows, _ := h.store.Search(q)
                	c.JSON(200, rows)
                }

                func (h *Handler) Export(c *gin.Context) {
                	exec.Command("sh", "-c", "tar czf out.tgz "+c.Query("dir")).Run()
                	exec.Command("uptime").Run()
                	c.Data(200, "application/json", []byte(c.Query("dir")))
                }
                """);
        write("internal/store/store.go", """
                package store

                import "database/sql"

                type Store interface {
                	Search(q string) ([]string, error)
                }

                type SQLStore struct {
                	db *sql.DB
                }

                func New(db *sql.DB) *SQLStore { return &SQLStore{db: db} }

                func (s *SQLStore) Search(q string) ([]string, error) {
                	rows, err := s.db.Query("SELECT name FROM products WHERE name LIKE '%" + q + "%'")
                	if err != nil {
                		return nil, err
                	}
                	defer rows.Close()
                	s.db.Query("SELECT name FROM products WHERE id = $1", q)
                	return nil, nil
                }
                """);
        // ---- chi ----
        write("cmd/chiapp/main.go", """
                package main

                import (
                	"net/http"

                	"github.com/go-chi/chi/v5"
                )

                func main() {
                	r := chi.NewRouter()
                	r.Route("/v1", func(r chi.Router) {
                		r.Use(auth)
                		r.Get("/redirect", func(w http.ResponseWriter, req *http.Request) {
                			http.Redirect(w, req, req.URL.Query().Get("next"), http.StatusFound)
                		})
                	})
                	r.Mount("/admin", adminRouter())
                	http.ListenAndServe(":8080", r)
                }

                func auth(next http.Handler) http.Handler { return next }

                func adminRouter() chi.Router {
                	r := chi.NewRouter()
                	r.Get("/fetch", fetch)
                	return r
                }

                func fetch(w http.ResponseWriter, r *http.Request) {
                	resp, _ := http.Get(r.URL.Query().Get("url"))
                	defer resp.Body.Close()
                }
                """);
        // ---- net/http (Go 1.22 패턴) ----
        write("cmd/std/main.go", """
                package main

                import (
                	"crypto/md5"
                	"fmt"
                	"math/rand"
                	"net/http"
                	"os"
                )

                func main() {
                	mux := http.NewServeMux()
                	mux.HandleFunc("GET /items/{id}", getItem)
                	mux.Handle("/api/", http.StripPrefix("/api", requireKey(api())))
                	http.ListenAndServe(":8080", mux)
                }

                func requireKey(next http.Handler) http.Handler { return next }

                func api() *http.ServeMux {
                	m := http.NewServeMux()
                	m.HandleFunc("POST /echo", echoHandler)
                	return m
                }

                func echoHandler(w http.ResponseWriter, r *http.Request) {
                	fmt.Fprintf(w, "<p>%s</p>", r.FormValue("msg"))
                	fmt.Fprintf(os.Stdout, "%s", r.FormValue("msg"))
                }

                func getItem(w http.ResponseWriter, r *http.Request) {
                	id := r.PathValue("id")
                	f, _ := os.Open("/items/" + id)
                	defer f.Close()
                	os.ReadFile("/etc/app.conf")
                	md5.Sum([]byte(id))
                	_ = rand.Intn(10)
                }
                """);
        // ---- echo ----
        write("cmd/echoapp/main.go", """
                package main

                import (
                	"crypto/tls"
                	"html/template"
                	"net/http"

                	"github.com/labstack/echo/v4"
                )

                func main() {
                	e := echo.New()
                	g := e.Group("/e", jwtMiddleware)
                	g.GET("/tpl", renderTpl, audit)
                	e.Start(":8080")
                }

                func jwtMiddleware(next echo.HandlerFunc) echo.HandlerFunc { return next }

                func audit(next echo.HandlerFunc) echo.HandlerFunc { return next }

                func renderTpl(c echo.Context) error {
                	t, _ := template.New("x").Parse(c.QueryParam("t"))
                	_ = t
                	tr := &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}
                	_ = tr
                	return nil
                }
                """);
        // ---- gorilla/mux ----
        write("cmd/gorilla/main.go", """
                package main

                import (
                	"net/http"

                	"github.com/gorilla/mux"
                )

                func main() {
                	r := mux.NewRouter()
                	s := r.PathPrefix("/g").Subrouter()
                	s.Use(authMW)
                	s.HandleFunc("/u/{id}", getU).Methods("GET", "DELETE")
                	http.ListenAndServe(":8080", r)
                }

                func authMW(next http.Handler) http.Handler { return next }

                func getU(w http.ResponseWriter, r *http.Request) {
                	w.Write([]byte(mux.Vars(r)["id"]))
                }
                """);
        // ---- fiber ----
        write("cmd/fiberapp/main.go", """
                package main

                import "github.com/gofiber/fiber/v2"

                func main() {
                	app := fiber.New()
                	api := app.Group("/f", guard)
                	api.Get("/go", func(c *fiber.Ctx) error {
                		return c.Redirect(c.Query("to"))
                	})
                	app.Listen(":3000")
                }

                func guard(c *fiber.Ctx) error { return c.Next() }
                """);
        result = CodeGraphEngine.analyze(root);
    }

    @Test
    void 설정_없이_Go_로_판별한다() {
        assertThat(result.languages()).contains("go");
        assertThat(result.parseErrors()).isEmpty();
    }

    @Test
    void gin_그룹과_넘겨받은_라우터의_경로를_잇는다() {
        assertThat(entries()).contains(
                "GET /health -> .#GET /health",
                "GET /api/products/search -> internal/handlers#Handler.Search",
                "GET /api/files/:name -> internal/handlers#GET /files/:name",
                "POST /api/admin/export -> internal/handlers#Handler.Export");
        assertThat(entry("POST /api/admin/export").props().get("guards"))
                .isEqualTo(List.of("middleware.RequireAuth()", "middleware.RequireAdmin()"));
        assertThat(entry("GET /api/products/search").props().get("guards")).isEqualTo(List.of());
    }

    @Test
    void chi_net_http_echo_gorilla_fiber_진입점을_찾는다() {
        assertThat(entries()).contains(
                "GET /v1/redirect -> cmd/chiapp#GET /redirect",
                "GET /admin/fetch -> cmd/chiapp#fetch",
                "GET /items/{id} -> cmd/std#getItem",
                "POST /api/echo -> cmd/std#echoHandler",
                "GET /e/tpl -> cmd/echoapp#renderTpl",
                "GET /g/u/{id} -> cmd/gorilla#getU",
                "DELETE /g/u/{id} -> cmd/gorilla#getU",
                "GET /f/go -> cmd/fiberapp#GET /go");
        assertThat(entry("GET /v1/redirect").props().get("guards")).isEqualTo(List.of("auth"));
        assertThat(entry("POST /api/echo").props().get("guards")).isEqualTo(List.of("requireKey"));
        assertThat(entry("GET /e/tpl").props().get("guards")).isEqualTo(List.of("audit", "jwtMiddleware"));
        assertThat(entry("GET /g/u/{id}").props().get("guards")).isEqualTo(List.of("authMW"));
        assertThat(entry("GET /f/go").props().get("guards")).isEqualTo(List.of("guard"));
    }

    @Test
    void 상수가_아닌_값이_들어가는_위험_호출만_위험_지점으로_본다() {
        assertThat(sinks()).containsExactlyInAnyOrder(
                "internal/store/store.go:16 SQL",
                "internal/handlers/handlers.go:20 PATH",
                "internal/handlers/handlers.go:31 COMMAND",
                "cmd/chiapp/main.go:14 OPEN_REDIRECT",
                "cmd/chiapp/main.go:30 SSRF",
                "cmd/std/main.go:27 XSS",
                "cmd/std/main.go:33 PATH",
                "cmd/std/main.go:36 WEAK_CRYPTO",
                "cmd/std/main.go:37 WEAK_RANDOM",
                "cmd/echoapp/main.go:23 CODE_INJECTION",
                "cmd/echoapp/main.go:25 WEAK_CRYPTO",
                "cmd/gorilla/main.go:20 XSS",
                "cmd/fiberapp/main.go:9 OPEN_REDIRECT");
    }

    @Test
    void 인터페이스_호출은_시그니처가_맞는_구현으로만_잇는다() {
        assertThat(result.draft().edges()).contains(new EdgeDraft(
                "METHOD|internal/handlers#Handler.Search", "METHOD|internal/store#SQLStore.Search", CodeEdgeKind.CALLS));
        assertThat(result.draft().edges()).doesNotContain(new EdgeDraft(
                "METHOD|internal/handlers#Handler.Search", "METHOD|internal/handlers#Handler.Search", CodeEdgeKind.CALLS));
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
