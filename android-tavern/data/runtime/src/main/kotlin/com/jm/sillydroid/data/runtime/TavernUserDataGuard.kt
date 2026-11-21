package com.jm.sillydroid.data.runtime

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APK 升级后，宿主会重新解包 Tavern server 资产。角色卡、聊天记录、世界书等用户数据必须始终落在
 * [HostPaths.serverDataDir]（持久目录）下，和 [HostPaths.serverDir]（每次升级都要覆盖解包的目标目录）
 * 完全分离——这是数据不丢的第一道防线。
 *
 * 这里补第二道防线：在真正覆盖 server 资产之前，把 data / extensions / plugins 做一次快照。
 * 快照优先用硬链接（同分区零拷贝，几个 G 的目录也是瞬时完成），硬链接不可用时才退回复制，
 * 既不会因为备份本身把手机存储写满，也能在上游解包逻辑出现意外时留一个可回滚点。
 */
internal object TavernUserDataGuard {
    private const val backupRootName = "backups"
    private const val retainedSnapshotCount = 2
    private val protectedDirectoryNames = listOf("data", "extensions", "plugins")

    /**
     * 覆盖 server 资产前调用一次。返回创建出来的快照目录；没有可保护的数据时返回 null。
     * 任何异常都只记录日志，不阻断启动——保护数据的前提是应用能正常起来。
     */
    fun snapshotBeforeAssetRefresh(
        paths: HostPaths,
        log: (String) -> Unit = {}
    ): File? {
        return try {
            createSnapshot(paths, log)
        } catch (error: Exception) {
            log(
                "user_data_snapshot event=failed reason=${error.javaClass.simpleName} " +
                    "message=${error.message.orEmpty()}"
            )
            null
        }
    }

    private fun createSnapshot(paths: HostPaths, log: (String) -> Unit): File? {
        val dataRoot = paths.serverDataDir
        if (!dataRoot.isDirectory) {
            log("user_data_snapshot event=skipped reason=data_root_missing path=${dataRoot.absolutePath}")
            return null
        }

        val sources = protectedDirectoryNames
            .map { name -> File(dataRoot, name) }
            .filter { directory -> directory.exists() }
        if (sources.isEmpty()) {
            log("user_data_snapshot event=skipped reason=nothing_to_protect path=${dataRoot.absolutePath}")
            return null
        }

        val backupsRoot = File(dataRoot, backupRootName)
        if (!backupsRoot.exists() && !backupsRoot.mkdirs()) {
            log("user_data_snapshot event=skipped reason=backup_root_unwritable path=${backupsRoot.absolutePath}")
            return null
        }

        val snapshotDirectory = File(backupsRoot, timestampName())
        if (snapshotDirectory.exists()) {
            // 同一秒内重复触发（例如重试启动）不重复备份，直接复用已有快照。
            log("user_data_snapshot event=reused path=${snapshotDirectory.absolutePath}")
            return snapshotDirectory
        }
        if (!snapshotDirectory.mkdirs()) {
            log("user_data_snapshot event=skipped reason=snapshot_dir_unwritable path=${snapshotDirectory.absolutePath}")
            return null
        }

        var fileCount = 0
        var byteCount = 0L
        sources.forEach { source ->
            val target = File(snapshotDirectory, source.name)
            val linked = linkOrCopyTree(source, target)
            fileCount += linked.first
            byteCount += linked.second
            log(
                "user_data_snapshot event=copied source=${source.absolutePath} target=${target.absolutePath} " +
                    "files=${linked.first} bytes=${linked.second}"
            )
        }

        writeSnapshotManifest(snapshotDirectory, fileCount, byteCount)
        pruneOldSnapshots(backupsRoot, keep = retainedSnapshotCount)
        log("user_data_snapshot event=ready path=${snapshotDirectory.absolutePath} files=$fileCount bytes=$byteCount")
        return snapshotDirectory
    }

    private fun timestampName(): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }

    /** 返回 (文件数, 字节数)。硬链接与复制都不会跟随符号链接，避免把外部目录卷进快照。 */
    private fun linkOrCopyTree(source: File, target: File): Pair<Int, Long> {
        val counters = intArrayOf(0)
        val byteTotal = longArrayOf(0L)
        val sourceRoot = source.toPath()
        val targetRoot = target.toPath()

        Files.walkFileTree(
            sourceRoot,
            emptySet<FileVisitOption>(),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(targetRoot.resolve(sourceRoot.relativize(directory)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val destination = targetRoot.resolve(sourceRoot.relativize(file))
                    try {
                        // 硬链接：不占额外空间，删掉快照也只是删链接，原数据仍然完好。
                        Files.createLink(destination, file)
                    } catch (error: Exception) {
                        // 跨分区、文件系统不支持硬链接或属性不兼容时退回复制。
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                    counters[0] += 1
                    if (!attrs.isSymbolicLink) {
                        byteTotal[0] += attrs.size()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }
            }
        )

        return counters[0] to byteTotal[0]
    }

    private fun writeSnapshotManifest(snapshotDirectory: File, fileCount: Int, byteCount: Long) {
        try {
            File(snapshotDirectory, "snapshot.txt").writeText(
                buildString {
                    appendLine("created_at=${Date()}")
                    appendLine("directories=${protectedDirectoryNames.joinToString(",")}")
                    appendLine("files=$fileCount")
                    appendLine("bytes=$byteCount")
                    appendLine("note=硬链接快照，删除本目录不会影响原始数据")
                }
            )
        } catch (error: IOException) {
            // 清单只是辅助信息，写不进去不影响快照本身。
        }
    }

    private fun pruneOldSnapshots(backupsRoot: File, keep: Int) {
        val snapshots = backupsRoot.listFiles()
            ?.filter { file -> file.isDirectory }
            ?.sortedByDescending { file -> file.name }
            .orEmpty()

        snapshots.drop(keep).forEach { obsolete ->
            try {
                deleteWithoutFollowingSymlinks(obsolete)
            } catch (error: IOException) {
                // 清理失败下次再清，不影响本次备份。
            }
        }
    }

    private fun deleteWithoutFollowingSymlinks(target: File) {
        val targetPath = target.toPath()
        if (!Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            return
        }

        Files.walkFileTree(
            targetPath,
            emptySet<FileVisitOption>(),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            }
        )
    }
}
