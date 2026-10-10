package com.trae.info.server.util

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 原子文件写入：先写同目录临时文件，再 ATOMIC_MOVE 替换目标。
 * 防止写一半崩溃导致 JSON 截断、密钥/配置丢失。
 * 加载失败时把坏文件备份为 .corrupt-<时间戳> 以便排查，而不是静默覆盖。
 */
object AtomicFiles {
    private val log = LoggerFactory.getLogger(AtomicFiles::class.java)

    fun writeTextAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        try {
            Files.move(tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // 某些文件系统不支持 ATOMIC_MOVE，退回普通替换
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        runCatching { target.setReadable(false, false); target.setReadable(true, true) }
    }

    /** 读取文本；文件不存在返回 null；读取/解析失败时备份坏文件并返回 null。 */
    fun readTextOrBackup(target: File): String? {
        if (!target.exists()) return null
        val text = target.readText()
        if (text.isBlank()) {
            backupCorrupt(target)
            return null
        }
        return text
    }

    fun backupCorrupt(target: File) {
        runCatching {
            val bak = File(target.parentFile, target.name + ".corrupt-" + System.currentTimeMillis())
            Files.move(target.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
            log.warn("损坏文件已备份为 {}，将以默认值启动", bak.name)
        }
    }
}
