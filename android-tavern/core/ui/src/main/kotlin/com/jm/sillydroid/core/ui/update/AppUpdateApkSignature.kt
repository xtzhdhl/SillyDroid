package com.jm.sillydroid.core.ui.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 更新包签名校验结果。
 *
 * [MATCH] 同一个签名，可以直接覆盖安装；
 * [MISMATCH] 不是同一个签名（或不是同一个应用），系统安装器一定会失败，应提前拦下并说明原因；
 * [UNREADABLE] 读不出来（包损坏 / 系统限制），交给系统安装器自行判断，不硬拦。
 */
enum class AppUpdateApkSignatureVerdict {
    MATCH,
    MISMATCH,
    UNREADABLE
}

/**
 * 覆盖安装前比对更新包与当前应用的签名。
 *
 * Android 本身就会拒绝签名不一致的覆盖安装（INSTALL_FAILED_UPDATE_INCOMPATIBLE），
 * 但系统只弹一句含糊的“应用未安装”。这里提前比对一次，把“为什么装不上、装上了会变成另一个应用”
 * 讲清楚；同时能拦住 debug 包 / release 包混装（两者 applicationId 不同，是独立应用）。
 */
object AppUpdateApkSignature {
    fun verify(context: Context, apkFile: File): AppUpdateApkSignatureVerdict {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            return AppUpdateApkSignatureVerdict.UNREADABLE
        }

        val packageManager = context.packageManager
        return try {
            val installedInfo = packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val archiveInfo = packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: return AppUpdateApkSignatureVerdict.UNREADABLE

            if (archiveInfo.packageName != context.packageName) {
                // 包名不同：即使签名一致也是另一个应用，装上去等于装了个新 App，用户会以为数据丢了。
                return AppUpdateApkSignatureVerdict.MISMATCH
            }

            val installedDigests = signingDigests(installedInfo.signingInfo)
            val archiveDigests = signingDigests(archiveInfo.signingInfo)
            if (installedDigests.isEmpty() || archiveDigests.isEmpty()) {
                return AppUpdateApkSignatureVerdict.UNREADABLE
            }

            if (archiveDigests.any { digest -> installedDigests.contains(digest) }) {
                AppUpdateApkSignatureVerdict.MATCH
            } else {
                AppUpdateApkSignatureVerdict.MISMATCH
            }
        } catch (error: Exception) {
            // 解析 330MB 包的签名偶发失败时不要把用户卡死，交给系统安装器兜底。
            AppUpdateApkSignatureVerdict.UNREADABLE
        }
    }

    private fun signingDigests(signingInfo: SigningInfo?): Set<String> {
        if (signingInfo == null) {
            return emptySet()
        }

        val digests = mutableSetOf<String>()
        // 多签名包（含签名轮换）要同时看当前签名和历史签名，任一交集即视为同一来源。
        signingInfo.apkContentsSigners?.forEach { signature ->
            digests.add(sha256(signature.toByteArray()))
        }
        signingInfo.signingCertificateHistory?.forEach { signature ->
            digests.add(sha256(signature.toByteArray()))
        }
        return digests
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }
    }
}
