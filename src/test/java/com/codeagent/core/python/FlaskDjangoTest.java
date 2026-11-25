package com.codeagent.core.python;

import com.codeagent.core.CodeGraphEngine;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
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

class FlaskDjangoTest {

    @TempDir
    static Path root;

    private static CodeGraphEngine.Result result;

    @BeforeAll
    static void build() throws IOException {
        // ---- Flask ----
        write("flaskapp/__init__.py", "");
        write("flaskapp/app.py", """
                from flask import Flask
                from flask_restful import Api
                from .users import bp as users_bp
                from .admin import admin_bp, AdminView
                from .resources import ItemResource

                app = Flask(__name__)
                app.register_blueprint(users_bp, url_prefix="/api/users")
                app.register_blueprint(admin_bp)
                app.add_url_rule("/admin/panel", view_func=AdminView.as_view("panel"))

                api = Api(app, prefix="/rest")
                api.add_resource(ItemResource, "/items", "/items/<int:item_id>")

                @app.before_request
                def check_api_key():
                    pass

                @app.route("/health")
                def health():
                    return "ok"
                """);
        write("flaskapp/users.py", """
                import subprocess
                from flask import Blueprint, request, render_template_string
                from .auth import login_required

                bp = Blueprint("users", __name__, url_prefix="/ignored")

                @bp.route("/<int:uid>", methods=["GET", "DELETE"])
                @login_required
                def user(uid):
                    return str(uid)

                @bp.post("/run")
                def run():
                    subprocess.run(request.form["cmd"], shell=True)
                    return "done"

                @bp.get("/greet")
                def greet():
                    return render_template_string("<p>" + request.args["name"] + "</p>")
                """);
        write("flaskapp/auth.py", """
                def login_required(f):
                    return f
                """);
        write("flaskapp/admin.py", """
                from flask import Blueprint
                from flask.views import MethodView

                admin_bp = Blueprint("admin", __name__, url_prefix="/admin")

                @admin_bp.route("/stats")
                def stats():
                    return "{}"

                class AdminView(MethodView):
                    decorators = [admin_only]

                    def get(self):
                        return "panel"

                    def post(self):
                        return "saved"
                """);
        write("flaskapp/resources.py", """
                from flask_restful import Resource

                class ItemResource(Resource):
                    def get(self, item_id=None):
                        return {}

                    def put(self, item_id):
                        return {}
                """);

        // ---- Django ----
        write("shop/manage.py", "");
        write("shop/shop/__init__.py", "");
        write("shop/shop/urls.py", """
                from django.urls import path, include
                from rest_framework.routers import DefaultRouter
                from orders.views import OrderViewSet

                router = DefaultRouter()
                router.register(r"orders", OrderViewSet, basename="order")

                urlpatterns = [
                    path("accounts/", include("accounts.urls")),
                    path("api/", include(router.urls)),
                ]
                """);
        write("shop/accounts/__init__.py", "");
        write("shop/accounts/urls.py", """
                from django.urls import path, re_path
                from . import views

                urlpatterns = [
                    path("profile/<int:pk>/", views.profile, name="profile"),
                    path("login/", views.login_view),
                    path("settings/", views.SettingsView.as_view()),
                    re_path(r"^export/(?P<fmt>\\w+)/$", views.ExportView.as_view()),
                ]
                urlpatterns += [path("go/", views.go)]
                """);
        write("shop/accounts/views.py", """
                from django.contrib.auth.decorators import login_required
                from django.views.decorators.http import require_POST, require_http_methods
                from django.contrib.auth.mixins import LoginRequiredMixin
                from django.views import View
                from django.views.generic import UpdateView
                from django.shortcuts import redirect
                from django.db import connection

                @login_required
                @require_http_methods(["GET", "POST"])
                def profile(request, pk):
                    with connection.cursor() as cursor:
                        cursor.execute("SELECT * FROM profile WHERE id = %s" % request.GET["id"])
                    return None

                @require_POST
                def login_view(request):
                    return None

                def go(request):
                    return redirect(request.GET["next"])

                class SettingsView(LoginRequiredMixin, View):
                    def get(self, request):
                        return None

                    def post(self, request):
                        return None

                class ExportView(UpdateView):
                    def get_queryset(self):
                        return None
                """);
        write("shop/orders/__init__.py", "");
        write("shop/orders/views.py", """
                from rest_framework import viewsets
                from rest_framework.decorators import action
                from rest_framework.permissions import IsAuthenticated

                class OrderViewSet(viewsets.ModelViewSet):
                    permission_classes = [IsAuthenticated]

                    def get_queryset(self):
                        return None

                    def destroy(self, request, pk=None):
                        return None

                    @action(detail=True, methods=["post"], url_path="refund")
                    def refund(self, request, pk=None):
                        return None

                    @action(detail=False)
                    def recent(self, request):
                        return None
                """);
        result = CodeGraphEngine.analyze(root);
    }

    @Test
    void Flask_라우트와_블루프린트_경로를_찾는다() {
        assertThat(entries()).contains(
                "GET /health -> flaskapp.app.health",
                "GET /api/users/<int:uid> -> flaskapp.users.user",
                "DELETE /api/users/<int:uid> -> flaskapp.users.user",
                "POST /api/users/run -> flaskapp.users.run",
                "GET /api/users/greet -> flaskapp.users.greet",
                "GET /admin/stats -> flaskapp.admin.stats",
                "GET /admin/panel -> flaskapp.admin.AdminView.get",
                "POST /admin/panel -> flaskapp.admin.AdminView.post",
                "GET /rest/items -> flaskapp.resources.ItemResource.get",
                "PUT /rest/items/<int:item_id> -> flaskapp.resources.ItemResource.put");
        assertThat(entries()).noneMatch(e -> e.contains("/ignored"));
        assertThat(entry("GET /api/users/<int:uid>").props())
                .containsEntry("framework", "flask")
                .containsEntry("guards", List.of("login_required", "before_request:check_api_key"));
        assertThat(entry("GET /admin/panel").props().get("guards").toString()).contains("decorators=[admin_only]");
    }

    @Test
    void Django_urlpatterns_와_DRF_라우터_경로를_찾는다() {
        assertThat(entries()).contains(
                "GET /accounts/profile/<int:pk> -> shop.accounts.views.profile",
                "POST /accounts/profile/<int:pk> -> shop.accounts.views.profile",
                "POST /accounts/login -> shop.accounts.views.login_view",
                "ANY /accounts/go -> shop.accounts.views.go",
                "GET /accounts/settings -> shop.accounts.views.SettingsView.get",
                "POST /accounts/settings -> shop.accounts.views.SettingsView.post",
                "GET /accounts/export/(?P<fmt>\\w+) -> shop.accounts.views.ExportView",
                "POST /accounts/export/(?P<fmt>\\w+) -> shop.accounts.views.ExportView",
                "GET /api/orders -> shop.orders.views.OrderViewSet.list",
                "POST /api/orders -> shop.orders.views.OrderViewSet.create",
                "GET /api/orders/{pk} -> shop.orders.views.OrderViewSet.retrieve",
                "PATCH /api/orders/{pk} -> shop.orders.views.OrderViewSet.partial_update",
                "DELETE /api/orders/{pk} -> shop.orders.views.OrderViewSet.destroy",
                "POST /api/orders/{pk}/refund -> shop.orders.views.OrderViewSet.refund",
                "GET /api/orders/recent -> shop.orders.views.OrderViewSet.recent");
        assertThat(entry("GET /accounts/profile").props()).containsEntry("framework", "django")
                .containsEntry("guards", List.of("login_required", "require_http_methods"));
        assertThat(entry("GET /accounts/settings").props().get("guards")).isEqualTo(List.of("LoginRequiredMixin"));
        assertThat(entry("GET /api/orders/{pk}").props().get("guards")).isEqualTo(List.of("permission_classes=[IsAuthenticated]"));
        // 라이브러리가 처리하는 동작은 프로젝트 훅(get_queryset)으로 잇는다
        assertThat(result.draft().edges()).contains(new EdgeDraft(
                CodeGraphDraft.key(CodeNodeKind.ENTRY_POINT, "GET /api/orders -> shop.orders.views.OrderViewSet.list"),
                CodeGraphDraft.key(CodeNodeKind.METHOD, "shop.orders.views.OrderViewSet.get_queryset"), CodeEdgeKind.ROUTES_TO));
    }

    @Test
    void 진입점에서_Flask_Django_위험_지점까지_잇는다() {
        assertThat(sinksOf("flaskapp.users.run")).contains("COMMAND");
        assertThat(sinksOf("flaskapp.users.greet")).contains("CODE_INJECTION");
        assertThat(sinksOf("shop.accounts.views.profile")).contains("SQL");
        assertThat(sinksOf("shop.accounts.views.go")).contains("OPEN_REDIRECT");
        assertThat(result.draft().edges()).contains(new EdgeDraft(
                CodeGraphDraft.key(CodeNodeKind.ENTRY_POINT, "POST /api/users/run -> flaskapp.users.run"),
                CodeGraphDraft.key(CodeNodeKind.METHOD, "flaskapp.users.run"), CodeEdgeKind.ROUTES_TO));
    }

    private static List<String> entries() {
        return result.draft().nodes().stream().filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT).map(NodeDraft::qualifiedName).toList();
    }

    private static NodeDraft entry(String prefix) {
        return result.draft().nodes().stream()
                .filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT && n.qualifiedName().startsWith(prefix))
                .findFirst().orElseThrow(() -> new AssertionError("진입점 없음: " + prefix + " / " + entries()));
    }

    private static List<Object> sinksOf(String method) {
        return result.draft().nodes().stream()
                .filter(n -> n.kind() == CodeNodeKind.SINK && method.equals(n.props().get("method")))
                .map(n -> n.props().get("category")).toList();
    }

    private static void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
