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
