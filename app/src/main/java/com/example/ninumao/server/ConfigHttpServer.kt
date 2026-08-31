package com.example.ninumao.server

import android.content.Context
import com.example.ninumao.data.config.ConfigRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.IOException

// ConfigHttpServer 在局域网提供配置读写 Web 页面。
class ConfigHttpServer(
    port: Int,
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val onConfigUpdated: () -> Unit,
) : NanoHTTPD(port) {

    // startServer 启动 HTTP 服务。
    fun startServer() {
        try {
            if (!isAlive) {
                start(SOCKET_READ_TIMEOUT, false)
            }
        } catch (e: IOException) {
            throw IllegalStateException("配置服务启动失败", e)
        }
    }

    // stopServer 停止 HTTP 服务。
    fun stopServer() {
        stop()
    }

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/" -> serveIndexHtml()
            session.method == Method.GET && session.uri == "/api/config" -> serveGetConfig(session)
            session.method == Method.POST && session.uri == "/api/config" -> servePostConfig(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    // serveIndexHtml 返回 assets 中的配置页。
    private fun serveIndexHtml(): Response {
        return try {
            val html = context.assets.open("config/index.html").bufferedReader().use { it.readText() }
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
    }

    // serveGetConfig 返回当前配置 JSON。
    private fun serveGetConfig(session: IHTTPSession): Response {
        val pin = session.parameters["pin"]?.firstOrNull().orEmpty()
        val config = runBlocking { configRepository.getConfig() }
        if (pin != config.pin) {
            return jsonResponse(Response.Status.UNAUTHORIZED, false, "PIN 不正确")
        }
        val body = JSONObject()
            .put("uid", config.uid)
            .toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json", body)
    }

    // servePostConfig 保存配置并通知 App 刷新。
    private fun servePostConfig(session: IHTTPSession): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"].orEmpty()
            val json = JSONObject(body)
            val pin = json.optString("pin")
            val uid = json.optString("uid")

            val config = runBlocking { configRepository.getConfig() }
            if (pin != config.pin) {
                return jsonResponse(Response.Status.UNAUTHORIZED, false, "PIN 不正确")
            }
            if (uid.isBlank()) {
                return jsonResponse(Response.Status.BAD_REQUEST, false, "UID 不能为空")
            }

            runBlocking {
                configRepository.saveConfig(config.copy(uid = uid))
            }
            onConfigUpdated()
            jsonResponse(Response.Status.OK, true, "保存成功")
        } catch (e: Exception) {
            jsonResponse(Response.Status.BAD_REQUEST, false, e.message ?: "请求无效")
        }
    }

    // jsonResponse 构造统一 JSON 响应。
    private fun jsonResponse(status: Response.Status, ok: Boolean, message: String): Response {
        val body = JSONObject().put("ok", ok).put("message", message).toString()
        return newFixedLengthResponse(status, "application/json", body)
    }
}
