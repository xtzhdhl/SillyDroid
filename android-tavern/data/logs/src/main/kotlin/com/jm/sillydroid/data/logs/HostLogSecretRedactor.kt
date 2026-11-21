package com.jm.sillydroid.data.logs

import java.util.Locale

/**
 * 导出 / 上传日志前的密钥打码。
 *
 * 只在“写 zip 的那一刻”生效：手机上的原始日志保持原样，你（和排查问题的人）在 App 里看到的
 * 内容不变，只有**分享出去的那份**被洗过。这样既不影响自查，又避免把 key 发给别人。
 *
 * 局限要清楚：这是正则启发式，会漏（自定义格式的密钥）、也会误伤（日志里恰好有 token=xxx
 * 这类非密钥字段）。它的目标是“降低误发风险”，不是“保证零泄露”。
 */
internal object HostLogSecretRedactor {
    const val placeholder = "[已隐藏]"

    // 只处理文本类日志；截图等二进制附件原样保留（图片里的 key 这里洗不掉）。
    private val redactableExtensions = setOf(
        "log", "txt", "json", "jsonl", "md", "yaml", "yml",
        "ini", "conf", "cfg", "sh", "js", "ts", "csv"
    )

    private val skKeyPattern = Regex("""\bsk-[A-Za-z0-9_\-]{10,}""")
    private val anthropicKeyPattern = Regex("""\bsk-ant-[A-Za-z0-9_\-]{10,}""")
    private val googleKeyPattern = Regex("""\bAIza[0-9A-Za-z_\-]{30,}""")
    private val githubTokenPattern = Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""")
    private val bearerPattern = Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-]{12,}""")
    private val authorizationPattern = Regex("""(?i)\bauthorization\s*[:=]\s*[^\s,;]{8,}""")
    private val assignmentPattern = Regex(
        """(?i)\b(api[_-]?key|apikey|api[_-]?token|access[_-]?token|auth[_-]?token|secret|password|passwd|pwd|token)(\s*[:=]\s*)(["']?)([^\s"'`,;}\]]{6,})"""
    )
    private val queryPattern = Regex("""([?&](?:key|apikey|api_key|token|access_token|auth)=)([^&\s#]{4,})""")

    fun isRedactableFileName(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        return extension.isNotEmpty() && redactableExtensions.contains(extension.lowercase(Locale.US))
    }

    fun redact(text: String): String {
        if (text.isEmpty()) {
            return text
        }

        var result = text
        // 先处理特征最明确的密钥格式，避免被后面的通用规则截断成半截。
        result = anthropicKeyPattern.replace(result) { match -> "sk-ant-" + placeholder }
        result = skKeyPattern.replace(result) { match -> "sk-" + placeholder }
        result = googleKeyPattern.replace(result) { match -> "AIza" + placeholder }
        result = githubTokenPattern.replace(result) { match -> placeholder }
        result = bearerPattern.replace(result) { match -> "Bearer " + placeholder }
        result = authorizationPattern.replace(result) { match -> "Authorization: " + placeholder }
        result = assignmentPattern.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + placeholder
        }
        result = queryPattern.replace(result) { match ->
            match.groupValues[1] + placeholder
        }
        return result
    }
}
