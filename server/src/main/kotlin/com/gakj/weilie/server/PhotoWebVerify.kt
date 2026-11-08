package com.gakj.weilie.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.accept
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.core.readAvailable
import io.ktor.utils.io.core.use
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 本文件共享的日志器（异常只记服务端日志，不外泄堆栈） */
private val log = LoggerFactory.getLogger("weilie.photo.web")

/**
 * 第三方网页验真：
 * - GET  /v/{code}                 自包含单页 HTML 验真页（任何人扫码即可打开）
 * - POST /api/photo/verify/file    multipart 上传照片原件，服务端计算 SHA-256 与登记记录比对
 *
 * 设计约束（赛场环境）：
 * - 页面完全自包含：内联 CSS、无外链 CDN/字体/JS 库，HTTP 明文下也可用；
 * - 移动端优先，暗色玻璃风（#0B1020 底 / #111834 卡片 / #22D3EE 主色）；
 * - 所有来自 URL / 登记库的文本一律 HTML 转义，杜绝 XSS；
 * - 上传大小限 15MB，按字节流式计算哈希，不整包载入内存；
 * - 任何异常都渲染错误页，绝不把 500 堆栈抛给评委。
 */

/** 上传大小上限：15MB */
private const val MAX_PHOTO_UPLOAD_BYTES: Long = 15L * 1024 * 1024

/** /v/{code} 路径里的验证码格式：4-16 位大写字母或数字 */
private val VERIFY_CODE_PATH = Regex("^[A-Z0-9]{4,16}$")

/** 服务器时区的时间格式（登记时间/拍摄时间展示用） */
private val TS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** POST /api/photo/verify/file 的 JSON 响应（Accept: application/json 时返回） */
@Serializable
data class PhotoFileVerifyResponse(
    val ok: Boolean = true,
    val found: Boolean,
    val hashMatch: Boolean? = null,
    val code: String,
    val registeredSha256: String = "",
    val uploadedSha256: String = "",
)

/** HTML 转义：覆盖 & < > " ' 五个危险字符，URL/登记库文本入库前一律过这里 */
internal fun escapeHtml(raw: String): String = buildString(raw.length + 16) {
    for (ch in raw) when (ch) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(ch)
    }
}

/** 毫秒时间戳 -> 服务器时区文本；0 视为未提供 */
private fun formatTs(ms: Long): String =
    if (ms <= 0L) "未提供" else TS_FORMAT.format(Instant.ofEpochMilli(ms))

/** 空文本统一展示为占位符 */
private fun dashIfBlank(raw: String): String = raw.ifBlank { "—" }

/** 上传文件字节数组的十六进制文本（小写） */
private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it) }

/**
 * 页面外壳：所有验真相关 HTML 共用的 <head> 与暗色玻璃风样式。
 * 手机评委优先：单列布局、大号验证码、可点按按钮。
 */
private fun htmlShell(title: String, body: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${escapeHtml(title)}</title>
<style>
:root { color-scheme: dark; }
* { box-sizing: border-box; margin: 0; padding: 0; }
body { background: #0B1020; color: #E5E7EB; min-height: 100vh; padding: 22px 14px 44px;
  font-family: -apple-system, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
  -webkit-text-size-adjust: 100%; }
.wrap { max-width: 640px; margin: 0 auto; }
h1 { color: #22D3EE; font-size: 20px; font-weight: 700; letter-spacing: 1px; }
.sub { color: #64748B; font-size: 12px; margin: 4px 0 16px; }
.card { background: rgba(17,24,52,.88); border: 1px solid rgba(148,163,184,.14);
  border-radius: 16px; padding: 18px 16px; margin-bottom: 14px;
  box-shadow: 0 8px 30px rgba(0,0,0,.35); }
.badge { display: inline-block; padding: 6px 14px; border-radius: 999px;
  font-size: 14px; font-weight: 600; line-height: 1.4; }
.badge.ok { color: #4ADE80; background: rgba(34,197,94,.12); border: 1px solid rgba(74,222,128,.45); }
.badge.bad { color: #F87171; background: rgba(239,68,68,.12); border: 1px solid rgba(248,113,113,.45); }
.desc { color: #CBD5E1; font-size: 13px; margin-top: 10px; line-height: 1.7; }
.code-box { margin: 14px 0 4px; background: #0A0F22; border: 1px dashed rgba(34,211,238,.35);
  border-radius: 12px; padding: 14px 8px; text-align: center; }
.code { font-family: ui-monospace, SFMono-Regular, Consolas, "Courier New", monospace;
  font-size: 26px; letter-spacing: 3px; color: #22D3EE; user-select: all; word-break: break-all; }
.hint { color: #64748B; font-size: 11px; text-align: center; margin-top: 6px; }
.kv { display: grid; grid-template-columns: 92px minmax(0, 1fr); gap: 9px 10px; font-size: 13px; }
.kv .k { color: #94A3B8; min-width: 0; }
.kv .v { color: #E5E7EB; word-break: break-all; overflow-wrap: anywhere; min-width: 0; line-height: 1.6; }
.mono { font-family: ui-monospace, SFMono-Regular, Consolas, "Courier New", monospace; }
a { color: #22D3EE; word-break: break-all; }
form { margin-top: 4px; }
input[type=file] { width: 100%; font-size: 12px; color: #94A3B8; margin: 12px 0; }
button { width: 100%; background: #22D3EE; color: #06202A; border: 0; border-radius: 10px;
  padding: 12px; font-size: 15px; font-weight: 700; }
.foot { color: #475569; font-size: 11px; text-align: center; margin-top: 18px; line-height: 1.8; }
</style>
</head>
<body><div class="wrap">$body</div></body>
</html>
"""

/** 验真页页头（标题 + 徽章） */
private fun pageHead(badgeText: String, badgeClass: String, desc: String?): String = buildString {
    append("<h1>围猎 · 取证照片验真</h1>")
    append("<div class=\"sub\">第三方独立核验 · 数据来自服务端登记库</div>")
    append("<div class=\"card\"><span class=\"badge $badgeClass\">${escapeHtml(badgeText)}</span>")
    if (desc != null) append("<div class=\"desc\">${escapeHtml(desc)}</div>")
    append("</div>")
}

/** 上传表单卡片：POST /api/photo/verify/file（multipart） */
private fun uploadFormCard(code: String): String {
    val safeCode = escapeHtml(code)
    return """
<div class="card">
  <form method="post" action="/api/photo/verify/file" enctype="multipart/form-data">
    <input type="hidden" name="code" value="$safeCode">
    <label for="photo" style="font-size:13px;color:#94A3B8;">上传这张照片原件，核对是否被修改：</label>
    <input type="file" id="photo" name="photo" required>
    <button type="submit">上传并核验</button>
  </form>
</div>
"""
}

/** 页脚说明 */
private fun pageFoot(): String =
    "<div class=\"foot\">本页面由围猎取证服务直接生成 · 签名密钥仅存于服务端<br>" +
        "照片 SHA-256 与拍摄元数据在拍照时登记，任何篡改都会导致哈希不一致</div>"

/** 已登记记录的字段表卡片 */
private fun entryCard(entry: PhotoEntry, sigValid: Boolean): String {
    val coord = if (entry.lat == 0.0 && entry.lon == 0.0) {
        "未提供"
    } else {
        val pos = "${entry.lon},${entry.lat}"
        "<a href=\"https://uri.amap.com/marker?position=$pos\">${entry.lat}, ${entry.lon}（高德地图）</a>"
    }
    return """
<div class="card">
  <div class="kv">
    <div class="k">登记时间</div><div class="v">${formatTs(entry.serverTs)}（服务器时间）<br>${formatTs(entry.ts)}（拍摄时间）</div>
    <div class="k">设备</div><div class="v">${escapeHtml(dashIfBlank(entry.device))}</div>
    <div class="k">类型</div><div class="v">${escapeHtml(dashIfBlank(entry.kind))}</div>
    <div class="k">备注</div><div class="v">${escapeHtml(dashIfBlank(entry.note))}</div>
    <div class="k">坐标</div><div class="v">$coord</div>
    <div class="k">SHA-256</div><div class="v mono">${escapeHtml(entry.sha256)}</div>
    <div class="k">签名校验</div><div class="v"><span class="badge ${if (sigValid) "ok" else "bad"}">${if (sigValid) "签名有效" else "签名无效"}</span></div>
  </div>
</div>
"""
}

/** GET /v/{code} 的验真页 */
private fun verifyPage(code: String, entry: PhotoEntry?, registry: PhotoRegistry): String {
    if (entry == null) {
        val body = buildString {
            append(pageHead("未登记（可能是伪造的验证码）", "bad", "登记库中没有该验证码对应的照片记录，请核对照片上的验证码后重试。"))
            append("<div class=\"card\"><div class=\"code-box\"><div class=\"code\">${escapeHtml(code)}</div>" +
                "<div class=\"hint\">点按验证码可全选复制</div></div></div>")
            append(pageFoot())
        }
        return htmlShell("围猎 · 取证照片验真", body)
    }
    val sigValid = registry.sigValid(entry)
    val body = buildString {
        append(pageHead("已登记", "ok", "该验证码对应一张已登记的取证照片，登记信息如下。"))
        append("<div class=\"card\"><div class=\"code-box\"><div class=\"code\">${escapeHtml(code)}</div>" +
            "<div class=\"hint\">点按验证码可全选复制</div></div></div>")
        append(entryCard(entry, sigValid))
        append(uploadFormCard(code))
        append(pageFoot())
    }
    return htmlShell("围猎 · 取证照片验真", body)
}

/** POST /api/photo/verify/file 的比对结果页 */
private fun fileResultPage(
    code: String,
    found: Boolean,
    match: Boolean?,
    registeredSha: String,
    uploadedSha: String,
): String {
    if (!found) {
        val body = buildString {
            append(pageHead("未登记", "bad", "验证码未命中任何登记记录，无法核验。请确认二维码/验证码来自照片原件。"))
            append("<div class=\"card\"><div class=\"code-box\"><div class=\"code\">${escapeHtml(code)}</div></div></div>")
            append(pageFoot())
        }
        return htmlShell("围猎 · 验真结果", body)
    }
    if (match == true) {
        val body = buildString {
            append(pageHead("一致", "ok", "该照片与登记时完全一致，未被修改。"))
            append("<div class=\"card\"><div class=\"kv\">" +
                "<div class=\"k\">验证码</div><div class=\"v mono\">${escapeHtml(code)}</div>" +
                "<div class=\"k\">照片哈希</div><div class=\"v mono\">${escapeHtml(uploadedSha)}</div>" +
                "</div></div>")
            append("<div class=\"card\"><a href=\"/v/${escapeHtml(code)}\">返回验真页</a></div>")
            append(pageFoot())
        }
        return htmlShell("围猎 · 验真结果", body)
    }
    // 不一致：并列展示登记哈希与上传哈希的前 16 位
    val body = buildString {
        append(pageHead("不一致", "bad", "该照片与登记记录不匹配（可能被修改或用别处的照片冒充）。"))
        append("<div class=\"card\"><div class=\"kv\">" +
            "<div class=\"k\">验证码</div><div class=\"v mono\">${escapeHtml(code)}</div>" +
            "<div class=\"k\">登记哈希</div><div class=\"v mono\">${escapeHtml(registeredSha.take(16))}…</div>" +
            "<div class=\"k\">上传哈希</div><div class=\"v mono\">${escapeHtml(uploadedSha.take(16))}…</div>" +
            "</div></div>")
        append("<div class=\"card\"><a href=\"/v/${escapeHtml(code)}\">返回验真页</a></div>")
        append(pageFoot())
    }
    return htmlShell("围猎 · 验真结果", body)
}

/** 通用错误页（任何异常都不抛 500 堆栈） */
private fun errorPage(title: String, detail: String): String {
    val body = buildString {
        append(pageHead(title, "bad", detail))
        append("<div class=\"card\"><a href=\"/\">返回服务首页</a></div>")
        append(pageFoot())
    }
    return htmlShell("围猎 · 验真结果", body)
}

/**
 * 网页验真路由：
 * - GET  /v/{code}               验真页（未登记也是 200 的红色徽章页；验证码格式非法返回 400）
 * - POST /api/photo/verify/file  multipart 上传核验（Accept: application/json 时返回 JSON）
 */
fun Route.photoWebVerifyRoutes(registry: PhotoRegistry) {
    get("/v/{code}") {
        val code = call.parameters["code"].orEmpty().trim()
        if (!VERIFY_CODE_PATH.matches(code)) {
            call.respondText(
                errorPage("验证码格式不正确", "验证码应为 4-16 位大写字母或数字，请扫描照片上的二维码重新进入。"),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@get
        }
        val entry = registry.find(code)
        call.respondText(verifyPage(code, entry, registry), ContentType.Text.Html, HttpStatusCode.OK)
    }

    post("/api/photo/verify/file") {
        val wantsJson = call.request.accept().orEmpty().contains("application/json")
        try {
            // 先校验 Content-Type：非 multipart 请求直接 400（ktor 收 multipart 时遇缺失 Content-Type 会抛 IllegalStateException）
            if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
                if (wantsJson) {
                    call.respond(HttpStatusCode.BadRequest, PhotoErrorResponse(error = "请求需为 multipart/form-data 表单（含 code 与 photo 字段）"))
                } else {
                    call.respondText(
                        errorPage("请求格式不正确", "上传需为表单提交，请从验真页重新操作。"),
                        ContentType.Text.Html,
                        HttpStatusCode.BadRequest,
                    )
                }
                return@post
            }
            var code: String? = null
            var sawFile = false
            var tooLarge = false
            val digest = MessageDigest.getInstance("SHA-256")

            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> if (part.name == "code") code = part.value.trim()
                    is PartData.FileItem -> {
                        // 只取第一个文件部件；超出 15MB 立即停止读取并标记
                        if (!sawFile && !tooLarge) {
                            sawFile = true
                            var size = 0L
                            part.provider().use { input ->
                                val buf = ByteArray(8192)
                                // 注意：不同 ktor 版本 readAvailable 在 EOF 时返回 -1 或 0，
                                // 因此用 endOfInput + 返回值 <=0 双重判断，避免死循环
                                while (!input.endOfInput) {
                                    val n = input.readAvailable(buf)
                                    if (n <= 0) break
                                    size += n
                                    if (size > MAX_PHOTO_UPLOAD_BYTES) {
                                        tooLarge = true
                                        break
                                    }
                                    digest.update(buf, 0, n)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
                part.dispose()
            }

            val codeVal = code.orEmpty()
            if (codeVal.isBlank() || !VERIFY_CODE_PATH.matches(codeVal)) {
                if (wantsJson) {
                    call.respond(HttpStatusCode.BadRequest, PhotoErrorResponse(error = "缺少合法的 code 字段（4-16 位大写字母或数字）"))
                } else {
                    call.respondText(errorPage("缺少验证码", "请从验真页发起上传，确保表单携带 code 字段。"), ContentType.Text.Html, HttpStatusCode.BadRequest)
                }
                return@post
            }
            if (!sawFile) {
                if (wantsJson) {
                    call.respond(HttpStatusCode.BadRequest, PhotoErrorResponse(error = "未收到照片文件字段 photo"))
                } else {
                    call.respondText(errorPage("未收到照片", "请选择照片原件后再提交。"), ContentType.Text.Html, HttpStatusCode.BadRequest)
                }
                return@post
            }
            if (tooLarge) {
                if (wantsJson) {
                    call.respond(HttpStatusCode.BadRequest, PhotoErrorResponse(error = "照片超过 15MB 上限"))
                } else {
                    call.respondText(errorPage("文件过大", "照片超过 15MB 上限，请压缩后重试。"), ContentType.Text.Html, HttpStatusCode.BadRequest)
                }
                return@post
            }

            val uploadedSha = digest.digest().toLowerHex()
            val entry = registry.find(codeVal)
            if (entry == null) {
                if (wantsJson) {
                    call.respond(PhotoFileVerifyResponse(found = false, code = codeVal, uploadedSha256 = uploadedSha))
                } else {
                    call.respondText(fileResultPage(codeVal, found = false, match = null, registeredSha = "", uploadedSha = uploadedSha), ContentType.Text.Html)
                }
                return@post
            }
            val match = registry.hashMatches(entry, uploadedSha)
            if (wantsJson) {
                call.respond(
                    PhotoFileVerifyResponse(
                        found = true,
                        hashMatch = match,
                        code = codeVal,
                        registeredSha256 = entry.sha256,
                        uploadedSha256 = uploadedSha,
                    ),
                )
            } else {
                call.respondText(
                    fileResultPage(codeVal, found = true, match = match, registeredSha = entry.sha256, uploadedSha = uploadedSha),
                    ContentType.Text.Html,
                )
            }
        } catch (e: Exception) {
            // 任何异常都渲染错误页，绝不把堆栈抛给访客；请求本身的问题记日志并回 400，其余回 500
            log.warn("文件验真处理异常: {}", e.message ?: e.javaClass.simpleName)
            if (wantsJson) {
                val status = if (e is BadRequestException) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError
                call.respond(status, PhotoErrorResponse(error = "请求处理失败：${e.message ?: e.javaClass.simpleName}"))
            } else if (e is BadRequestException) {
                call.respondText(
                    errorPage("请求不完整", "上传请求缺少 multipart 表单体，请从验真页重新提交。"),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
            } else {
                call.respondText(
                    errorPage("处理失败", "请求处理出现问题，请重试或联系现场工作人员。"),
                    ContentType.Text.Html,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
    }
}
