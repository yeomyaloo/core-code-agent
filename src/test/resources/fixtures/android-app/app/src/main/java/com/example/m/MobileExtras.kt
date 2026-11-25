package com.example.m

import android.content.Context
import android.webkit.WebView
import java.security.MessageDigest

/** WebView 에 자바스크립트 인터페이스 노출 (WEBVIEW_JS). */
class WebScreen(private val context: Context) {

    fun show(url: String) {
        val webView = WebView(context)
        webView.addJavascriptInterface(Bridge(), "android")
        webView.loadUrl(url)
    }

    class Bridge
}

/** SharedPreferences 에 토큰을 평문 저장 (TOKEN_STORAGE). */
class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun save(value: String) {
        prefs.edit().putString("accessToken", value).apply()
    }
}

/** 핸드셰이크와 무관한 MD5 — 여전히 약한 암호화로 잡혀야 한다 (과도한 억제 방지 확인용). */
object Hasher {
    fun fingerprint(data: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(data)
}
