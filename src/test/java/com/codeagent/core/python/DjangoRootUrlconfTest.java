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

    @Test
    void docker_compose_의_environment_목록과_기본값에서_찾는다() throws IOException {
        site();
        write("site/config/settings.py", "import os\nROOT_URLCONF = os.environ[\"DJANGO_URLCONF\"]\n");
        write("docker-compose.yml", """
                services:
                  web:
                    image: shop
                    environment:
                      - DEBUG=0
                      - DJANGO_URLCONF=${DJANGO_URLCONF:-config.urls}
                """);

        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
    }

    @Test
    void docker_compose_의_env_file_에서_찾는다() throws IOException {
        site();
        write("site/config/settings.py", "import os\nROOT_URLCONF = os.getenv(\"URLCONF\")\n");
        write("deploy/compose.prod.yaml", """
                services:
                  web:
                    env_file:
                      - ../deploy/prod.env   # 운영 값
                      - path: ../missing.env
                    environment:
                      LOG_LEVEL: info
                """);
        write("deploy/prod.env", "URLCONF=config.urls\n");

        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
    }

    @Test
    void docker_compose_의_environment_맵에서_찾는다() throws IOException {
        site();
        write("site/config/settings.py", "import os\nROOT_URLCONF = os.getenv(\"URLCONF\")\n");
        write("compose.yml", """
                services:
                  api:
                    environment:
                      "URLCONF": "config.urls"
                """);

        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
    }

    @Test
    void Dockerfile_의_ENV_에서_찾는다() throws IOException {
        site();
        write("site/config/settings.py", "import os\nROOT_URLCONF = os.environ[\"DJANGO_URLCONF\"]\n");
        write("site/Dockerfile", """
                FROM python:3.12-slim
                ENV PYTHONUNBUFFERED=1 DJANGO_URLCONF=config.urls
                CMD ["gunicorn", "config.wsgi"]
                """);

        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
    }

    @Test
    void 쿠버네티스_Deployment_의_env_에서_찾는다() throws IOException {
        site();
        write("site/config/settings.py", "import os\nROOT_URLCONF = os.environ[\"DJANGO_URLCONF\"]\n");
        write("deploy/k8s/base/apps/web/deployment.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                spec:
                  template:
                    spec:
                      containers:
                        - name: web
                          image: shop:1.0
                          env:
                            - name: DEBUG
                              value: "legacy.urls"
                            - name: DJANGO_URLCONF
                              value: "config.urls"
                """);

        // 컨테이너 항목 아래의 다른 변수 값(legacy.urls)을 DJANGO_URLCONF 값으로 잘못 읽지 않는다
        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
    }

    @Test
    void configMapKeyRef_가_가리키는_ConfigMap_값을_찾고_Secret_data_는_읽지_않는다() throws IOException {
        site();
        write("site/config/settings.py", "import os\nROOT_URLCONF = os.environ[\"DJANGO_URLCONF\"]\n");
        write("k8s/web.yml", """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: web-config
                data:
                  urlconf: config.urls
                ---
                apiVersion: v1
                kind: Secret
                metadata:
                  name: web-secret
                data:
                  urlconf: bGVnYWN5LnVybHM=
                ---
                apiVersion: apps/v1
                kind: Deployment
                spec:
                  template:
                    spec:
                      containers:
                        - name: web
                          env:
                            - name: DJANGO_URLCONF
                              valueFrom:
                                configMapKeyRef:
                                  name: web-config
                                  key: urlconf
                """);

        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
    }

    @Test
    void 한_줄로_쓴_env_에서_찾는다() throws IOException {
        site();
        write("site/config/settings.py", "import os\nROOT_URLCONF = os.environ[\"DJANGO_URLCONF\"]\n");
        write("deploy/pod.yaml", """
                apiVersion: v1
                kind: Pod
                spec:
                  containers:
                    - name: web
                      env:
                        - {name: DJANGO_URLCONF, value: config.urls}
                """);

        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
    }

    @Test
    void Kustomize_literals_에서_찾는다() throws IOException {
        site();
        write("site/config/settings.py", "import os\nROOT_URLCONF = os.environ[\"KUSTOMIZE_URLCONF\"]\n");
        write("deploy/overlays/prod/kustomization.yaml", """
                configMapGenerator:
                  - name: web-config
                    literals:
                      - KUSTOMIZE_URLCONF=config.urls
                """);

        assertThat(entries()).containsExactly("ANY /blog/<int:pk> -> site.blog.views.post");
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
