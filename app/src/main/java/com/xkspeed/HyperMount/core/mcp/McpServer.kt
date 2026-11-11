package com.xkspeed.HyperMount.core.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 极简 HTTP 服务 + MCP（JSON-RPC 2.0）
 *
 * 只用 JDK 的 ServerSocket 手写，不引第三方框架：
 * 这个 App 的依赖已经够重，MCP 这一层只需要「收 JSON、回 JSON」。
 *
 * 路由：
 *   POST /mcp           JSON-RPC（initialize / tools/list / tools/call / ping）
 *   POST /upload        raw body 存成 apk，返回私有路径（大文件走这里）
 *   GET  /log/latest    当前任务日志文件
 *   GET  /health        存活探针
 *
 * 认证：Authorization: Bearer <token>，或 ?token=（方便浏览器 / curl 直接取日志）。
 */
class McpServer(private val context: Context) {

    private var socket: ServerSocket? = null
    private var accept: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile private var stopped = false

    /** null = 起来了；否则是给界面看的原因 */
    fun start(): String? {
        if (socket != null) return null
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            // 默认只听本机；开了「局域网访问」才对外
            val host = if (McpConfig.lan) "0.0.0.0" else "127.0.0.1"
            s.bind(InetSocketAddress(host, McpConfig.port))
            socket = s
            stopped = false
            accept = Thread({ loop(s) }, "mcp-accept").apply {
                isDaemon = true
                start()
            }
            McpServerState.markRunning(true, McpConfig.port, null)
            Log.i(TAG, "MCP 服务已启动 :${McpConfig.port}")
            null
        } catch (e: Exception) {
            val msg = e.message ?: "端口被占用"
            McpServerState.markRunning(false, McpConfig.port, msg)
            Log.w(TAG, "MCP 启动失败", e)
            msg
        }
    }

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
        socket = null
        accept = null
        McpServerState.markRunning(false, McpConfig.port, null)
        Log.i(TAG, "MCP 服务已停止")
    }

    private fun loop(s: ServerSocket) {
        while (!stopped) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                break
            }
            pool.execute {
                runCatching { handle(client) }
                    .onFailure { Log.w(TAG, "请求处理失败", it) }
                runCatching { client.close() }
            }
        }
    }

    // ---------------- HTTP ----------------

    private fun handle(client: Socket) {
        client.soTimeout = 20_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val path = rawTarget.substringBefore('?')
        val query = parseQuery(rawTarget.substringAfter('?', ""))

        var contentLength = 0L
        var authorization: String? = null
        var host = "127.0.0.1:${McpConfig.port}"
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i <= 0) continue
            val key = line.substring(0, i).trim().lowercase()
            val value = line.substring(i + 1).trim()
            when (key) {
                "content-length" -> contentLength = value.toLongOrNull() ?: 0L
                "authorization" -> authorization = value
                "host" -> host = value
            }
        }

        if (method == "OPTIONS") {
            send(out, 204, "text/plain", ByteArray(0))
            return
        }
        if (path == "/health") {
            val body = """{"ok":true,"running":${McpServerState.running}}"""
            send(out, 200, "application/json", body.toByteArray())
            return
        }
        if (!authorized(authorization, query)) {
            send(out, 401, "application/json", """{"error":"unauthorized"}""".toByteArray())
            return
        }

        when {
            method == "POST" && path == "/mcp" -> {
                if (contentLength > MAX_JSON) {
                    send(out, 413, "application/json", """{"error":"body too large"}""".toByteArray())
                    return
                }
                val body = String(readExactly(input, contentLength), Charsets.UTF_8)
                val baseUrl = "http://$host"
                val (code, text) = runCatching { handleRpc(body, baseUrl) }
                    .getOrElse { 200 to rpcError(null, -32603, it.message ?: "internal error") }
                if (code == 202) {
                    send(out, 202, "text/plain", ByteArray(0))
                } else {
                    send(out, code, "application/json", text.toByteArray())
                }
            }

            method == "POST" && path == "/upload" -> {
                if (contentLength <= 0L) {
                    send(out, 400, "application/json", """{"error":"empty body"}""".toByteArray())
                    return
                }
                if (contentLength > MAX_UPLOAD) {
                    send(out, 413, "application/json", """{"error":"apk 太大"}""".toByteArray())
                    return
                }
                val dir = McpTools.uploadDir(context)
                val file = File(dir, "upload-${UUID.randomUUID().toString().substring(0, 8)}.apk")
                file.outputStream().use { sink -> copyExactly(input, sink, contentLength) }
                val body = """{"ok":true,"path":"${file.absolutePath}","size":${file.length()}}"""
                send(out, 200, "application/json", body.toByteArray())
            }

            method == "GET" && path == "/log/latest" -> {
                val file = File(McpTask.logFile)
                if (McpTask.logFile.isBlank() || !file.isFile) {
                    send(out, 404, "application/json", """{"error":"还没有日志"}""".toByteArray())
                    return
                }
                send(out, 200, "text/plain; charset=utf-8", file.readBytes())
            }

            else -> send(out, 404, "application/json", """{"error":"not found"}""".toByteArray())
        }
    }

    private fun authorized(authHeader: String?, query: Map<String, String>): Boolean {
        // 密码开关关掉时不做校验（默认只听本机，别人够不着）
        if (!McpConfig.requireToken) return true
        val expected = McpConfig.token
        if (expected.isBlank()) return false
        val given = authHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: query["token"]
        return given == expected
    }

    // ---------------- MCP ----------------

    private fun handleRpc(body: String, baseUrl: String): Pair<Int, String> {
        val req = runCatching { parseJson(body).jsonObject }.getOrNull()
            ?: return 200 to rpcError(null, -32700, "Parse error")

        val id = req["id"]?.takeIf { it !is JsonNull }
        val method = req["method"]?.jsonPrimitive?.contentOrNull
            ?: return 200 to rpcError(id, -32600, "Invalid Request")

        // 通知（没有 id）：按 MCP 规范回 202，不带 body
        if (id == null) return 202 to ""

        return when (method) {
            "initialize" -> {
                val clientVer = req["params"]?.jsonObject
                    ?.get("protocolVersion")?.jsonPrimitive?.contentOrNull
                val ver = clientVer?.takeIf { it.isNotBlank() } ?: DEFAULT_PROTOCOL
                val result = buildJsonObject {
                    put("protocolVersion", ver)
                    put(
                        "capabilities",
                        buildJsonObject {
                            put("tools", buildJsonObject { put("listChanged", false) })
                        },
                    )
                    put(
                        "serverInfo",
                        buildJsonObject {
                            put("name", "HyperMount")
                            put("version", "1.0.0")
                        },
                    )
                }
                200 to rpcResult(id, result)
            }

            "ping" -> 200 to rpcResult(id, buildJsonObject { })

            "tools/list" -> 200 to rpcResult(id, buildJsonObject { put("tools", toolDefs()) })

            "tools/call" -> {
                val params = req["params"]?.jsonObject
                val name = params?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                val args = params?.get("arguments")?.jsonObject ?: buildJsonObject { }
                val text = runBlocking { McpTools.call(context, name, args, baseUrl) }
                val isError = text.contains("\"ok\":false")
                val result = buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                },
                            )
                        },
                    )
                    put("isError", isError)
                }
                200 to rpcResult(id, result)
            }

            else -> 200 to rpcError(id, -32601, "Method not found: $method")
        }
    }

    private fun toolDefs(): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("name", "update_systemui_and_run_systemui_test")
                put(
                    "description",
                    "上传 SystemUI 包并立刻开跑「界面页」那条测试链路：" +
                        "把包覆盖成模块挂载点的 MiuiSystemUI.apk、重启 SystemUI、由模块守护接管。" +
                        "只接受 com.android.systemui；新包有问题会黑屏，只能按音量键救砖拉回，" +
                        "所以必须传 confirm=true。" +
                        "小文件直接传 apk_base64；大文件先 POST /upload 拿 path，再传 apk_path。" +
                        "启动后立刻返回 task_id，用 get_result 查结果、get_log 取日志。",
                )
                put(
                    "inputSchema",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "apk_base64",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "apk 文件的 Base64")
                                    },
                                )
                                put(
                                    "apk_path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "POST /upload 上传后返回的应用内路径")
                                    },
                                )
                                put(
                                    "confirm",
                                    buildJsonObject {
                                        put("type", "boolean")
                                        put("description", "必须为 true，表示知道会替换 SystemUI 且可能黑屏")
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add(JsonPrimitive("confirm")) })
                    },
                )
            },
        )
        add(
            buildJsonObject {
                put("name", "update_apk_run_apk_test")
                put(
                    "description",
                    "上传普通 apk 并立刻开跑「主页」那条测试链路：" +
                        "pm install 后抓 seconds 秒 logcat，崩了按 auto_replace 决定要不要装回救砖包。" +
                        "监视进程默认是该包自己；miui.systemui.plugin 例外，监视宿主 com.android.systemui。" +
                        "com.android.systemui 本体请改用 update_systemui_and_run_systemui_test。" +
                        "启动后立刻返回 task_id，用 get_result 查结果、get_log 取日志。",
                )
                put(
                    "inputSchema",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "apk_base64",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "apk 文件的 Base64")
                                    },
                                )
                                put(
                                    "apk_path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "POST /upload 上传后返回的应用内路径")
                                    },
                                )
                                put(
                                    "seconds",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("minimum", 5)
                                        put("maximum", 60)
                                        put("description", "抓取时长，默认跟 App 里设置的一致（30 秒）")
                                    },
                                )
                                put(
                                    "auto_replace",
                                    buildJsonObject {
                                        put("type", "boolean")
                                        put("description", "崩了是否自动装救砖 apk 并重启 SystemUI")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        add(
            buildJsonObject {
                put("name", "get_result")
                put(
                    "description",
                    "查上一次测试的结果，两种模式共用这一个入口。" +
                        "mode=apk：state 为 running/done/failed，result 取 " +
                        "normal / rescue_triggered / crash / rescue_failed / error，另外给 systemui_alive。" +
                        "mode=systemui：state 为 running/done，result 取 " +
                        "normal（界面正常）/ rescue_triggered（崩了并已换回救砖界面）/ crash；" +
                        "另外给 protect_count 与 protect_delta（本轮救砖次数）。",
                )
                put("inputSchema", buildJsonObject { put("type", "object") })
            },
        )
        add(
            buildJsonObject {
                put("name", "get_log")
                put(
                    "description",
                    "取这一轮测试的 logcat 尾部若干行，用来定位崩溃原因（Java 异常栈、ANR 等）。" +
                        "apk 模式读 App 抓的 logcat 文件；systemui 模式读模块在崩溃时抓的 crash buffer。" +
                        "systemui 模式若返回空，说明这一轮没崩、模块没抓日志。",
                )
                put(
                    "inputSchema",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "lines",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("minimum", 1)
                                        put("maximum", 2000)
                                        put("description", "返回多少行，默认 100")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    private fun rpcResult(id: JsonElement?, result: JsonElement): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }.toString()

    private fun rpcError(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }.toString()

    private fun parseJson(text: String): JsonElement =
        kotlinx.serialization.json.Json.parseToJsonElement(text)

    // ---------------- IO ----------------

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "OK"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: authorization, content-type, mcp-session-id\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    /** 逐字节读一行：BufferedReader 会预读 body，二进制上传就不能用了 */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
            if (buf.size() > 8 * 1024) return buf.toString("UTF-8")
        }
    }

    private fun readExactly(input: InputStream, length: Long): ByteArray {
        val out = ByteArrayOutputStream(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        copyExactly(input, out, length)
        return out.toByteArray()
    }

    private fun copyExactly(input: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        out.flush()
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null
            else URLDecoder.decode(part.substring(0, i), "UTF-8") to
                URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }.toMap()
    }

    companion object {
        private const val TAG = "McpServer"
        private const val DEFAULT_PROTOCOL = "2025-06-18"
        private const val MAX_JSON = 64L * 1024 * 1024
        private const val MAX_UPLOAD = 300L * 1024 * 1024

        @Volatile private var instance: McpServer? = null

        fun current(): McpServer? = instance

        fun startServer(context: Context): String? {
            instance?.stop()
            val server = McpServer(context.applicationContext)
            val error = server.start()
            instance = if (error == null) server else null
            return error
        }

        fun stopServer() {
            instance?.stop()
            instance = null
        }
    }
}
