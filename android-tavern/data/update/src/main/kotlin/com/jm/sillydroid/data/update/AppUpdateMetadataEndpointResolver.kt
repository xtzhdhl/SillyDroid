package com.jm.sillydroid.data.update

/**
 * App 内更新只认一份 latest.json 契约，真实世界里这份文件的可用性却取决于 CDN：
 *
 * - jsDelivr 默认会给 `gh/<owner>/<repo>@<ref>` 缓 12 小时，刚发版后的一段时间里仍可能读到旧内容；
 * - 国内网络下 jsDelivr 的可用性并不稳定，单点失败会让“检查更新”整个哑掉。
 *
 * 这里把同一份文件在多个镜像上的地址算出来：仓储层按顺序兜底重试，主地址命中就直接用，
 * 主地址说“已是最新”时再用不走 CDN 的 raw.githubusercontent.com 复核一次，
 * 避免 CDN 缓存陈旧导致漏掉真实更新。
 */
internal object AppUpdateMetadataEndpointResolver {
    // 10 分钟内共用同一个 cache-buster，既能绕过 CDN 陈旧缓存，又不至于每次启动都强制回源。
    private const val cacheBucketMillis = 10 * 60 * 1_000L

    private val jsdelivrPattern = Regex(
        """^https?://(?:cdn|fastly|gcore)\.jsdelivr\.net/gh/([^/]+)/([^/@]+)@([^/]+)/(.+)"""
    )

    fun resolve(rawUrl: String, nowMillis: Long = System.currentTimeMillis()): List<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }

        val matched = jsdelivrPattern.find(trimmed) ?: return listOf(trimmed)
        val owner = matched.groupValues[1]
        val repo = matched.groupValues[2]
        val ref = matched.groupValues[3]
        val path = matched.groupValues[4]
        val bucket = nowMillis / cacheBucketMillis

        return listOf(
            "https://cdn.jsdelivr.net/gh/$owner/$repo@$ref/$path?ts=$bucket",
            "https://fastly.jsdelivr.net/gh/$owner/$repo@$ref/$path?ts=$bucket",
            "https://gcore.jsdelivr.net/gh/$owner/$repo@$ref/$path?ts=$bucket",
            "https://raw.githubusercontent.com/$owner/$repo/$ref/$path?ts=$bucket"
        ).distinct()
    }
}
