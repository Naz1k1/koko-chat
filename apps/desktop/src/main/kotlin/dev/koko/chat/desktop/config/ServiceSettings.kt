package dev.koko.chat.desktop.config

import java.net.URI

/** 非敏感服务地址；默认明文地址仅用于本机开发，远程部署使用 HTTPS/WSS。 */
data class ServiceSettings(
    val apiBaseUrl: String = "http://127.0.0.1:8080",
    val imUrl: String = "ws://127.0.0.1:8081/im",
) {
    /** 规范化地址并拒绝内嵌凭证、查询参数及不支持的协议，返回可安全保存的副本。 */
    fun validated(): ServiceSettings {
        validateUrl(apiBaseUrl, setOf("http", "https"), "HTTP 服务地址")
        validateUrl(imUrl, setOf("ws", "wss"), "IM 服务地址")
        val api = URI(apiBaseUrl.trim())
        require(api.rawPath.isNullOrEmpty() || api.rawPath == "/") { "HTTP 地址只填写协议、主机和端口" }
        return copy(apiBaseUrl = apiBaseUrl.trim().trimEnd('/'), imUrl = imUrl.trim())
    }

    private fun validateUrl(value: String, schemes: Set<String>, label: String) {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        require(uri != null && uri.scheme in schemes && !uri.host.isNullOrBlank()) { "$label 格式不正确" }
        require(uri.userInfo == null && uri.fragment == null && uri.query == null) { "$label 不能包含凭证、查询参数或片段" }
        require(uri.port == -1 || uri.port in 1..65535) { "$label 端口不正确" }
    }
}
