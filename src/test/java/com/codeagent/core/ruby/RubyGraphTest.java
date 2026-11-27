package com.codeagent.core.ruby;

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

class RubyGraphTest {

    @TempDir
    static Path root;

    private static CodeGraphEngine.Result result;

    @BeforeAll
    static void build() throws IOException {
        // ---- Rails ----
        write("config/routes.rb", """
                Rails.application.routes.draw do
                  root 'pages#home'
                  get '/search', to: 'products#search'
                  get 'products/export' => 'products#export'
                  resources :photos, only: [:index, :show, :destroy] do
                    member do
                      post :refund
                    end
                    resources :comments, only: [:create]
                  end
                  resource :profile, only: [:show]
                  namespace :admin do
                    resources :reports, only: [:create]
                  end
                  authenticate :user do
                    get '/billing', to: 'pages#billing'
                  end
                end
                """);
        write("app/controllers/application_controller.rb", """
                class ApplicationController < ActionController::Base
                  before_action :authenticate_user!
                end
                """);
        write("app/controllers/pages_controller.rb", """
                class PagesController < ApplicationController
                  skip_before_action :authenticate_user!, only: [:home]

                  def home
                    render html: params[:q].html_safe
                  end

                  def billing
                    redirect_to params[:next]
                    redirect_to root_path
                  end
                end
                """);
        write("app/controllers/products_controller.rb", """
                class ProductsController < ApplicationController
                  skip_before_action :authenticate_user!

                  def search
                    @products = repo.search(params[:q])
                    render json: @products
                  end

                  def export
                    system("tar czf /tmp/out.tgz #{params[:dir]}")
                    system('uptime')
                  end

                  private

                  def repo
                    @repo ||= ProductRepository.new
                  end
                end
                """);
        write("app/repositories/product_repository.rb", """
                class ProductRepository
                  def search(keyword)
                    Product.where("name LIKE '%#{keyword}%'")
                    Product.where('name = ?', keyword)
                    Product.order(keyword)
                    Product.order(created_at: :desc)
                  end
                end
                """);
        write("app/models/product.rb", """
                class Product < ApplicationRecord
                end
                """);
        write("app/controllers/photos_controller.rb", """
                class PhotosController < ApplicationController
                  before_action :set_photo, only: [:show, :destroy]

                  def index; end

                  def show
                    send_file Rails.root.join('photos', params[:id])
                  end

                  def destroy
                    File.delete("/photos/#{params[:id]}")
                  end

                  def refund
                    Marshal.load(Base64.decode64(params[:state]))
                  end

                  private

                  def set_photo; end
                end
                """);
        write("app/controllers/comments_controller.rb", """
                class CommentsController < ApplicationController
                  def create
                    eval(params[:expr])
                  end
                end
                """);
        write("app/controllers/profiles_controller.rb", """
                class ProfilesController < ApplicationController
                  def show
                    Digest::MD5.hexdigest(current_user.email)
                  end
                end
                """);
        write("app/controllers/admin/reports_controller.rb", """
                module Admin
                  class ReportsController < ApplicationController
                    before_action :require_admin

                    def create
                      out = `pg_dump #{params[:table]}`
                      render plain: out
                    end
                  end
                end
                """);
        // ---- Sinatra ----
        write("sinatra/app.rb", """
                require 'sinatra'
                require 'net/http'

                before '/admin/*' do
                  halt 401 unless session[:admin]
                end

                get '/fetch' do
                  Net::HTTP.get(URI(params[:url]))
                end

                get '/admin/run/:name' do |name|
                  IO.popen(name)
                end

                post '/token' do
                  rand(1000)
                end
                """);
        result = CodeGraphEngine.analyze(root);
    }

    @Test
    void 설정_없이_Ruby_로_판별한다() {
        assertThat(result.languages()).contains("ruby");
        assertThat(result.parseErrors()).isEmpty();
    }

    @Test
    void Rails_라우트_DSL_과_컨트롤러_필터를_읽는다() {
        assertThat(entries()).contains(
                "GET / -> PagesController#home",
                "GET /search -> ProductsController#search",
                "GET /products/export -> ProductsController#export",
                "GET /photos -> PhotosController#index",
                "GET /photos/:id -> PhotosController#show",
                "DELETE /photos/:id -> PhotosController#destroy",
                "POST /photos/:id/refund -> PhotosController#refund",
                "POST /photos/:photo_id/comments -> CommentsController#create",
                "GET /profile -> ProfilesController#show",
                "POST /admin/reports -> Admin::ReportsController#create",
                "GET /billing -> PagesController#billing");
        // skip_before_action 으로 뺀 액션, 인증과 무관한 before_action(set_photo) 은 단서가 아니다
        assertThat(entry("GET /").props().get("guards")).isEqualTo(List.of());
        assertThat(entry("GET /search").props().get("guards")).isEqualTo(List.of());
        assertThat(entry("GET /photos/:id").props().get("guards")).isEqualTo(List.of("authenticate_user!"));
        assertThat(entry("POST /admin/reports").props().get("guards")).isEqualTo(List.of("authenticate_user!", "require_admin"));
        assertThat(entry("GET /billing").props().get("guards")).isEqualTo(List.of("authenticate :user", "authenticate_user!"));
    }

    @Test
    void Sinatra_라우트와_before_필터를_읽는다() {
        assertThat(entries()).contains(
                "GET /fetch -> sinatra/app.rb#GET /fetch",
                "GET /admin/run/:name -> sinatra/app.rb#GET /admin/run/:name",
                "POST /token -> sinatra/app.rb#POST /token");
        assertThat(entry("GET /admin/run/:name").props().get("guards").toString()).contains("before '/admin/*'");
        assertThat(entry("GET /fetch").props().get("guards")).isEqualTo(List.of());
    }

    @Test
    void 상수가_아닌_값이_들어가는_위험_호출만_위험_지점으로_본다() {
        assertThat(sinks()).containsExactlyInAnyOrder(
                "app/controllers/pages_controller.rb:5 XSS",
                "app/controllers/pages_controller.rb:9 OPEN_REDIRECT",
                "app/controllers/products_controller.rb:10 COMMAND",
                "app/repositories/product_repository.rb:3 SQL",
                "app/repositories/product_repository.rb:5 SQL",
                "app/controllers/photos_controller.rb:7 PATH",
                "app/controllers/photos_controller.rb:11 PATH",
                "app/controllers/photos_controller.rb:15 DESERIALIZATION",
                "app/controllers/comments_controller.rb:3 CODE_INJECTION",
                "app/controllers/profiles_controller.rb:3 WEAK_CRYPTO",
                "app/controllers/admin/reports_controller.rb:6 COMMAND",
                "sinatra/app.rb:9 SSRF",
                "sinatra/app.rb:13 COMMAND",
                "sinatra/app.rb:17 WEAK_RANDOM");
    }

    @Test
    void 메모이즈한_인스턴스_변수의_타입으로_호출을_잇는다() {
        assertThat(result.draft().edges()).contains(new EdgeDraft(
                "METHOD|ProductsController#search", "METHOD|ProductRepository#search", CodeEdgeKind.CALLS));
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
