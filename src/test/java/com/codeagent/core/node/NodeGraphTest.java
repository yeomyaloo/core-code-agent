package com.codeagent.core.node;

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

class NodeGraphTest {

    @TempDir
    static Path root;

    private static CodeGraphEngine.Result result;

    @BeforeAll
    static void build() throws IOException {
        write("package.json", """
                { "name": "shop", "dependencies": { "express": "^4.19.0", "mysql2": "^3.9.0", "@nestjs/core": "^10.0.0",
                  "next": "^14.0.0", "koa": "^2.15.0", "@koa/router": "^12.0.0", "fastify": "^4.0.0" } }
                """);
        // ---- Express ----
        write("src/db.js", """
                const mysql = require('mysql2/promise');
                const pool = mysql.createPool({ host: 'db' });
                module.exports = pool;
                """);
        write("src/middleware/auth.js", """
                function requireAuth(req, res, next) { next(); }
                function requireAdmin(req, res, next) { next(); }
                module.exports = { requireAuth, requireAdmin };
                """);
        write("src/app.js", """
                const express = require('express');
                const productRoutes = require('./routes/products');
                const adminRoutes = require('./routes/admin');
                const { requireAuth } = require('./middleware/auth');

                const app = express();
                app.use(express.json());
                app.use('/api/products', productRoutes);
                app.use('/api/admin', requireAuth, adminRoutes);
                app.get('/health', (req, res) => res.json({ ok: true }));

                const helmet = require('helmet');
                const { orderHistory } = require('./routes/history');
                function asyncHandler(fn) { return fn; }
                app.use(helmet.noSniff());
                app.use('/rest/history', requireAuth);
                app.use(['/rest/a', '/rest/b'], requireAuth);
                app.get('/rest/history/:id', asyncHandler(orderHistory()));
                app.get('/rest/open', asyncHandler(orderHistory()));
                module.exports = app;
                """);
        write("src/routes/products.js", """
                const express = require('express');
                const path = require('path');
                const db = require('../db');
                const router = express.Router();

                router.get('/search', async (req, res) => {
                  const [rows] = await db.query(`SELECT * FROM products WHERE name LIKE '%${req.query.q}%'`);
                  res.json(rows);
                });

                router.get('/:id', async (req, res) => {
                  const [rows] = await db.query('SELECT * FROM products WHERE id = ?', [req.params.id]);
                  res.json(rows);
                });

                router.get('/images/:name', (req, res) => {
                  res.sendFile(path.join(__dirname, 'images', req.params.name));
                });

                module.exports = router;
                """);
        write("src/routes/admin.js", """
                const { Router } = require('express');
                const { exec } = require('child_process');
                const { requireAdmin } = require('../middleware/auth');
                const router = Router();

                router.post('/export', requireAdmin, exportData);

                function exportData(req, res) {
                  exec(`tar czf /tmp/out.tgz ${req.query.dir}`, () => res.send('ok'));
                }

                module.exports = router;
                """);
        write("src/routes/history.js", """
                exports.orderHistory = function orderHistory () {
                  return (req, res) => res.sendFile(req.params.id);
                };
                """);
        // finale-rest 가 만드는 라우트, 경로를 준 app.use 끝점, 등록 순서
        write("src/rest.js", """
                const express = require('express');
                const finale = require('finale-rest');
                const serveIndex = require('serve-index');
                const { requireAuth } = require('./middleware/auth');
                const app = express();
                function appendUserId() { return (req, res, next) => { req.body.UserId = 1; next(); }; }
                function servePublicFiles() { return (req, res) => { res.status(404).send('none'); }; }
                function audit(req, res, next) { next(); }

                app.use('/files', serveIndex('public', { icons: true }));
                app.use('/files/:file', servePublicFiles());
                app.use('/rest/basket', requireAuth, appendUserId());
                app.get('/rest/basket/:id', (req, res) => res.json({}));
                app.get('/api/Users', requireAuth);
                finale.initialize({ app, sequelize: {} });
                const models = [{ name: 'User' }, { name: 'Product' }];
                for (const { name } of models) {
                  finale.resource({ model: {}, endpoints: [`/api/${name}s`, `/api/${name}s/:id`] });
                }
                app.get('/late', (req, res) => res.json({}));
                app.use(audit);
                """);
        // ---- NestJS ----
        write("src/users/users.controller.ts", """
                import { Controller, Get, Post, Body, Param, UseGuards } from '@nestjs/common';
                import { UsersService } from './users.service';
                import { AuthGuard } from '../auth.guard';

                @Controller('users')
                export class UsersController {
                  constructor(private readonly users: UsersService) {}

                  @Get(':id')
                  find(@Param('id') id: string) {
                    return this.users.find(id);
                  }

                  @UseGuards(AuthGuard)
                  @Post('eval')
                  run(@Body() body: any) {
                    return eval(body.code);
                  }
                }
                """);
        write("src/users/users.service.ts", """
                import { Injectable } from '@nestjs/common';
                import * as fs from 'fs';

                @Injectable()
                export class UsersService {
                  find(id: string) {
                    return fs.readFileSync('/data/' + id);
                  }
                }
                """);
        write("src/auth.guard.ts", """
                export class AuthGuard {}
                """);
        // ---- Next.js ----
        write("pages/api/fetch.ts", """
                import axios from 'axios';

                export default async function handler(req, res) {
                  const r = await axios.get(req.query.url);
                  res.json(r.data);
                }
                """);
        write("app/api/items/[id]/route.ts", """
                import { redirect } from 'next/navigation';

                export async function GET(request: Request) {
                  return Response.json({});
                }
                """);
        // ---- Koa ----
        write("koa/server.js", """
                const Koa = require('koa');
                const Router = require('@koa/router');
                const _ = require('lodash');

                const app = new Koa();
                const router = new Router({ prefix: '/koa' });
                router.post('/prefs', (ctx) => {
                  ctx.body = _.merge({}, ctx.request.body);
                });
                app.use(router.routes());
                """);
        // ---- Fastify ----
        write("fastify/server.js", """
                const fastify = require('fastify')();
                const crypto = require('crypto');

                fastify.register(async (instance) => {
                  instance.get('/token', async () => crypto.createHash('md5').update('x').digest('hex'));
                }, { prefix: '/f' });
                """);
        result = CodeGraphEngine.analyze(root);
    }

    @Test
    void 설정_없이_Node_백엔드로_판별한다() {
        assertThat(result.languages()).contains("node");
    }

    @Test
    void Express_라우터와_마운트_경로를_찾는다() {
        assertThat(entries()).contains(
                "GET /health -> src/app.js#GET /health",
                "GET /api/products/search -> src/routes/products.js#GET /search",
                "GET /api/products/:id -> src/routes/products.js#GET /:id",
                "GET /api/products/images/:name -> src/routes/products.js#GET /images/:name",
                "POST /api/admin/export -> src/routes/admin.js#exportData");
    }

    @Test
    void 라우트와_마운트의_미들웨어를_인증_단서로_남긴다() {
        assertThat(entry("POST /api/admin/export").props().get("guards")).isEqualTo(List.of("requireAdmin", "requireAuth"));
        assertThat(entry("GET /api/products/search").props().get("guards")).isEqualTo(List.of());
        assertThat(entry("POST /users/eval").props().get("guards")).isEqualTo(List.of("UseGuards(AuthGuard)"));
    }

    @Test
    void 처리기를_만드는_함수와_경로_지정_미들웨어를_읽는다() {
        // asyncHandler(orderHistory()) 의 처리기는 orderHistory, app.use('/rest/history', requireAuth) 는 그 아래에만
        assertThat(entries()).contains("GET /rest/history/:id -> src/routes/history.js#orderHistory");
        assertThat(entry("GET /rest/history/:id").props().get("guards")).isEqualTo(List.of("requireAuth"));
        // helmet 같은 기반 미들웨어는 단서가 아니고, 경로가 다른 라우트에는 걸리지 않는다
        assertThat(entry("GET /rest/open").props().get("guards")).isEqualTo(List.of());
    }

    @Test
    void finale_rest_가_만드는_라우트와_같은_경로의_미들웨어를_읽는다() {
        assertThat(entries()).contains(
                "GET /api/Users -> src/rest.js#<finale resource>",
                "POST /api/Products -> src/rest.js#<finale resource>",
                "DELETE /api/Products/:id -> src/rest.js#<finale resource>",
                "PUT /api/Users/:id -> src/rest.js#<finale resource>");
        // app.get('/api/Users', requireAuth) 는 따로 끝점이 아니라 자동 생성 라우트의 guards
        assertThat(entries().stream().filter(e -> e.startsWith("GET /api/Users ->"))).hasSize(1);
        assertThat((List<Object>) entry("GET /api/Users").props().get("guards")).contains("requireAuth");
        assertThat(entry("PUT /api/Products/:id").props().get("guards")).isEqualTo(List.of());
    }

    @Test
    void 경로를_준_app_use_가_응답하면_끝점으로_본다() {
        assertThat(entries()).contains(
                "ANY /files -> src/rest.js#<static> /files",
                "ANY /files/:file -> src/rest.js#servePublicFiles");
        // next() 로 넘기는 미들웨어는 끝점이 아니라 그 아래 라우트의 guards
        assertThat(entries()).noneMatch(e -> e.startsWith("ANY /rest/basket "));
        assertThat((List<Object>) entry("GET /rest/basket/:id").props().get("guards")).contains("requireAuth", "appendUserId()");
    }

    @Test
    void 라우트보다_뒤에_등록한_미들웨어는_guards_가_아니다() {
        assertThat(entry("GET /late").props().get("guards")).isEqualTo(List.of());
    }

    @Test
    void NestJS_Nextjs_Koa_Fastify_진입점을_찾는다() {
        assertThat(entries()).contains(
                "GET /users/:id -> src/users/users.controller.ts#UsersController.find",
                "POST /users/eval -> src/users/users.controller.ts#UsersController.run",
                "ANY /api/fetch -> pages/api/fetch.ts#handler",
                "GET /api/items/:id -> app/api/items/[id]/route.ts#GET",
                "POST /koa/prefs -> koa/server.js#POST /prefs",
                "GET /f/token -> fastify/server.js#GET /token");
        assertThat(entry("GET /users/:id").props().get("framework")).isEqualTo("nestjs");
    }

    @Test
    void 요청값이_들어가는_위험_호출만_위험_지점으로_본다() {
        assertThat(sinks()).containsExactlyInAnyOrder(
                "src/routes/products.js:7 SQL",
                "src/routes/products.js:17 PATH",
                "src/routes/history.js:2 PATH",
                "src/routes/admin.js:9 COMMAND",
                "src/users/users.controller.ts:17 CODE_INJECTION",
                "src/users/users.service.ts:7 PATH",
                "pages/api/fetch.ts:4 SSRF",
                "koa/server.js:8 PROTOTYPE_POLLUTION",
                "fastify/server.js:5 WEAK_CRYPTO");
    }

    @Test
    void 주입한_서비스_호출을_잇는다() {
        assertThat(result.draft().edges()).contains(new EdgeDraft(
                "METHOD|src/users/users.controller.ts#UsersController.find",
                "METHOD|src/users/users.service.ts#UsersService.find", CodeEdgeKind.CALLS));
    }

    private static List<String> entries() {
        return result.draft().nodes().stream().filter(n -> n.kind() == CodeNodeKind.ENTRY_POINT).map(NodeDraft::qualifiedName).toList();
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

    private static void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
