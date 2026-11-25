package com.codeagent.core.python;

import com.codeagent.core.CodeGraphEngine;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DjangoRootUrlconfTest {

    @TempDir
    Path root;

    @Test
    void ROOT_URLCONF_에서_이어지는_경로만_진입점으로_만든다() throws IOException {
        write("site/config/settings/base.py", """
                ROOT_URLCONF = "config.urls"
                INSTALLED_APPS = ["blog"]
                """);
        write("site/config/urls.py", """
                from django.urls import path, include

                urlpatterns = [
                    path("blog/", include("blog.urls")),
                ]
                """);
        write("site/blog/urls.py", """
                from django.urls import path
                from . import views

                urlpatterns = [path("<int:pk>/", views.post)]
                """);
        write("site/blog/views.py", """
                def post(request, pk):
                    return None
                """);
        // 어디에도 연결되지 않은 예전 urls (실제로는 열리지 않음)
        write("site/legacy/urls.py", """
                from django.urls import path
                from . import views

                urlpatterns = [path("old/", views.old)]
                """);
        write("site/legacy/views.py", """
                def old(request):
                    return None
                """);

        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
    }

    @Test
    void ROOT_URLCONF_가_가리키는_모듈이_없으면_연결되지_않은_urls_도_본다() throws IOException {
        write("app/settings.py", "ROOT_URLCONF = \"missing.urls\"\n");
        write("app/legacy/urls.py", """
                from django.urls import path
                from . import views

                urlpatterns = [path("old/", views.old)]
                """);
        write("app/legacy/views.py", """
                def old(request):
                    return None
                """);

        assertThat(entries()).containsExactly("ANY /old -> app.legacy.views.old");
    }

    @Test
    void 환경_변수의_기본값으로_ROOT_URLCONF_를_푼다() throws IOException {
        site();
        write("site/config/settings.py", """
                import os
                ROOT_URLCONF = os.environ.get("DJANGO_URLCONF", "config.urls")
                """);

        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
    }

    @Test
    void 기본값이_없으면_env_파일에서_찾는다() throws IOException {
        site();
        write("site/config/settings.py", """
                from os import environ
                ROOT_URLCONF = environ["URLCONF"]
                """);
        write(".env.example", """
                # 예시 설정
                export URLCONF="config.urls"
                DEBUG=1
                """);

        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
    }

    @Test
    void django_environ_과_조건식도_푼다() throws IOException {
        site();
        write("site/config/prod_urls.py", """
                from django.urls import path, include

                urlpatterns = [path("p/", include("blog.urls"))]
                """);
        write("site/config/settings.py", """
                import environ
                env = environ.Env()
                DEBUG = env.bool("DEBUG", default=False)
                PROJECT = "config"
                DEV_URLCONF = env("DEV_URLCONF", default=f"{PROJECT}.urls")
                ROOT_URLCONF = DEV_URLCONF if DEBUG else PROJECT + ".prod_urls"
                """);

        assertThat(entries()).containsExactlyInAnyOrder(
                "ANY /blog/<int:pk> -> site.blog.views.post",
                "ANY /p/<int:pk> -> site.blog.views.post");
    }

    /** config/urls.py → blog, 그리고 어디에도 연결되지 않은 legacy/urls.py */
    private void site() throws IOException {
        write("site/config/urls.py", """
                from django.urls import path, include

                urlpatterns = [path("blog/", include("blog.urls"))]
                """);
        write("site/blog/urls.py", """
                from django.urls import path
                from . import views

                urlpatterns = [path("<int:pk>/", views.post)]
                """);
        write("site/blog/views.py", "def post(request, pk):\n    return None\n");
        write("site/legacy/urls.py", """
                from django.urls import path
                from . import views

                urlpatterns = [path("old/", views.old)]
                """);
        write("site/legacy/views.py", "def old(request):\n    return None\n");
    }

    private List<String> entries() {
        return CodeGraphEngine.analyze(root).draft().nodes().stream()
                .filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT).map(NodeDraft::qualifiedName).toList();
    }

    private void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
