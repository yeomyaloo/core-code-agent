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
