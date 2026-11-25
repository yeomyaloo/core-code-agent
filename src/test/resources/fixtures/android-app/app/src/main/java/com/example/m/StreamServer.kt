package com.example.m

import android.util.Base64
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/** 토큰·PIN 검사 없이 소켓을 올리는 평문 스트림 서버 (인증 부재 + 평문). */
class StreamServer {

    private var serverSocket: ServerSocket? = null

    fun start(port: Int) {
        val ss = ServerSocket()
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        Thread { acceptLoop(ss) }.start()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = ss.accept()
            Thread { handle(socket) }.start()
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val key = readKey(input) ?: return
        upgrade(socket, input, key)
    }

    private fun readKey(input: InputStream): String? = "dummy"

    private fun upgrade(socket: Socket, input: InputStream, key: String) {
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()),
            Base64.NO_WRAP
        )
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
        )
        out.flush()
    }

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
