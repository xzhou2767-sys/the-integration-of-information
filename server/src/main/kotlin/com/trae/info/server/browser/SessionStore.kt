package com.trae.info.server.browser

import java.io.File

/**
 * 浏览器登录会话存储：Playwright storageState（Cookie + localStorage）
 * 按平台持久化到 data/browser-sessions/。
 * 合规约定：仅保存登录后的会话，不接触账号密码；文件权限建议 600。
 */
object SessionStore {

    fun dir(dataDir: File): File = File(dataDir, "browser-sessions").apply { mkdirs() }

    fun statePath(dataDir: File, platformId: String): File =
        File(dir(dataDir), "$platformId.storageState.json")

    fun hasSession(dataDir: File, platformId: String): Boolean =
        statePath(dataDir, platformId).exists()
}
