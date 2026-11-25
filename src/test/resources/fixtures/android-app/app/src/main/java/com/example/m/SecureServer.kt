package com.example.m

import android.util.Base64
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/** 핸드셰이크 전에 토큰을 검사하는 소켓 서버 (인증 있음 → MISSING_AUTH 아님). 평문은 그대로 위험. */
class SecureServer(private val authToken: String) {

    private var serverSocket: ServerSocket? = null

    fun start(port: Int) {
        val ss = ServerSocket(port)
        serverSocket = ss
        acceptLoop(ss)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            handle(ss.accept())
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val token = readToken(input)
        if (token != authToken) {
            socket.close()
            return
        }
        upgrade(socket, "key")
    }

    private fun readToken(input: InputStream): String = "t"

    private fun upgrade(socket: Socket, key: String) {
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            Base64.NO_WRAP
        )
        socket.getOutputStream().write(
            ("HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
        )
    }
}
