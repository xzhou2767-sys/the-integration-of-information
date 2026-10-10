package com.trae.info.server.service

import com.trae.info.core.api.KeyFrame
import com.trae.info.core.api.TranscriptSegment
import com.trae.info.core.api.VideoAnalyzeRequest
import com.trae.info.core.api.VideoAnalyzeResponse
import com.trae.info.server.ai.AiProviders
import com.trae.info.server.config.ConfigStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job

/**
 * 视频内容分析服务（推荐组合：字幕优先 + ASR 兜底 + 多视觉模型降级链 + OCR 增强）：
 * 1. 下载视频
 * 2. 【方案B】优先提取内嵌字幕；有字幕则跳过 ASR，直接用字幕文本（速度快 5-10 倍）
 * 3. 无字幕时：FFmpeg 提取音频 → faster-whisper 语音转文字
 * 4. AI 分析文本推测关键帧时间点
 * 5. FFmpeg 提取关键帧
 * 6.【方案D】对关键帧做 OCR 提取画面文字（PPT/带字幕视频增强）
 * 7.【多视觉模型降级链】视觉模型描述关键帧（seed-2-0-mini → seed-2-0-lite → seed-2-1-turbo → seed-2-1-pro → LAS → seed-evolving）
 * 8. AI 生成总结（融合文本 + 视觉描述 + OCR 文字）
 */
class VideoAnalysisService(
    private val store: ConfigStore,
    private val ai: AiProviders,
    private val http: HttpClient
) {
    private val log = LoggerFactory.getLogger(VideoAnalysisService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val scriptsDir = File("scripts").absoluteFile

    /** 并发闸门：whisper/ffmpeg/OCR 内存开销大，3.7G VPS 上只允许 1 个分析任务。 */
    private val analyzeGate = Semaphore(1)

    init {
        // 启动时清扫上次异常退出残留的临时目录
        runCatching {
            File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.isDirectory && f.name.startsWith("video-") }
                ?.forEach { runCatching { it.deleteRecursively() } }
        }
    }

    /** 视频分析主入口。 */
    suspend fun analyze(req: VideoAnalyzeRequest): VideoAnalyzeResponse {
        if (!analyzeGate.tryAcquire()) {
            throw IllegalStateException("服务器正忙：已有视频分析任务在运行（内存限制并发为 1），请稍后再试")
        }
        try {
            return withContext(Dispatchers.IO) {
                try {
                    // P0 修复（方案 §9 资源边界）：默认 30 分钟任务时长上限，
                    // 超限终止并返回受限原因（此前各阶段只有子进程超时，无总期限约束）
                    withTimeout(TOTAL_TASK_TIMEOUT_MS) { analyzeInternal(req) }
                } catch (e: TimeoutCancellationException) {
                    if (!currentCoroutineContext().isActive) throw e // 外部取消向上传递
                    throw IllegalStateException(
                        "视频分析超过总时长上限（${TOTAL_TASK_TIMEOUT_MS / 60000} 分钟），已终止；" +
                            "可降低 summaryLevel 档位（模糊档纯转录零抽帧）或更换较短视频"
                    )
                }
            }
        } finally {
            analyzeGate.release()
        }
    }

    private suspend fun analyzeInternal(req: VideoAnalyzeRequest): VideoAnalyzeResponse {
        val workDir = File(System.getProperty("java.io.tmpdir"), "video-${System.nanoTime()}").apply { mkdirs() }
        return try {
            // 1. 下载视频
            val videoFile = File(workDir, "input.mp4")
            log.info("下载视频: {}", req.url)
            downloadVideo(req.url, videoFile)
            require(videoFile.exists() && videoFile.length() > 0) { "视频下载失败" }

            // 2. 【方案B】优先提取内嵌字幕
            val transcript: TranscriptResult = extractTranscript(videoFile)

            // 3. 成本档位分级（借鉴 claude-video 帧预算三档）：
            //    模糊=0帧纯转录总结（零视觉/OCR成本）；默认=3帧视觉无OCR不回图；详细=尊重keyFrameCount+OCR+回图
            val level = normalizeLevel(req.summaryLevel)
            val (frameCount, includeOcr, includeImage) = when (level) {
                "模糊" -> Triple(0, false, false)
                "详细" -> Triple(if (req.keyFrameCount > 0) req.keyFrameCount else 5, true, true)
                else -> Triple(3, false, false)
            }
            log.info("视频分析档位={} 帧数={} OCR={} 回图={}", level, frameCount, includeOcr, includeImage)

            val keyFrames: List<KeyFrame> = if (frameCount == 0) {
                emptyList() // 模糊档：跳过帧提取与视觉/OCR，纯转录→总结（成本最低、速度最快）
            } else {
                // 4. AI 分析文本，推测关键帧时间点
                val keyTimestamps = inferKeyFrames(transcript, frameCount)
                log.info("关键帧时间点: {}", keyTimestamps)

                // 5. 提取关键帧
                val framesDir = File(workDir, "frames")
                val framesJson = runPython("frames", 2L * 60 * 1000, videoFile.absolutePath,
                    keyTimestamps.joinToString(","), framesDir.absolutePath)
                val framesResult = json.decodeFromString<FramesResult>(framesJson)

                // 6+7. 编码帧 + OCR（仅详细档）+ 视觉描述（多模型降级）
                framesResult.frames.mapIndexed { _, f ->
                    val imgBytes = File(f.path).readBytes()
                    val b64 = Base64.getEncoder().encodeToString(imgBytes)
                    // 方案D：OCR 提取画面文字（详细档成本才可接受，默认档跳过省一次子进程+视觉调用）
                    val ocrText = if (includeOcr && req.describeFrames) runOcr(f.path) else null
                    // 视觉模型描述（多模型降级链）
                    val desc = if (req.describeFrames) describeFrame(b64) else null
                    KeyFrame(
                        timestamp = f.timestamp,
                        // 默认档不回传帧图：响应体积降 ~90%，客户端凭描述理解画面
                        imageBase64 = if (includeImage) b64 else null,
                        description = desc,
                        ocrText = ocrText
                    )
                }
            }

            // 8. AI 生成总结
            val summary = generateSummary(transcript, keyFrames)

            VideoAnalyzeResponse(
                title = inferTitle(transcript),
                summary = summary,
                transcript = transcript.fullText,
                language = transcript.language,
                durationSec = transcript.duration,
                segments = transcript.segments.map { TranscriptSegment(it.start, it.end, it.text) },
                keyFrames = keyFrames,
                usedSubtitles = transcript.usedSubtitles
            )
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    /** 档位归一化：非法输入回退「默认」（与信息流摘要三档惯例一致）。 */
    private fun normalizeLevel(level: String): String =
        if (level in setOf("模糊", "默认", "详细")) level else "默认"

    /**
     * 获取文本转写结果：
     * - 优先用内嵌字幕（方案B），速度极快；
     * - 无字幕时回退到 ASR（faster-whisper）。
     */
    private suspend fun extractTranscript(videoFile: File): TranscriptResult {
        // 方案B：尝试提取字幕
        val subJson = runPython("subtitles", 2L * 60 * 1000, videoFile.absolutePath)
        val sub = runCatching { json.decodeFromString<SubtitlesResult>(subJson) }.getOrNull()
        if (sub != null && sub.found && sub.text.isNotBlank()) {
            log.info("命中内嵌字幕（方案B），跳过 ASR，字幕长度: {}", sub.text.length)
            return TranscriptResult(
                language = "subtitle",
                duration = 0.0, // 字幕无时长信息，后续用视频时长修正
                segments = sub.segments.map { Seg(it.start, it.end, it.text) },
                fullText = sub.text,
                usedSubtitles = true
            )
        }

        // 回退：ASR
        log.info("无内嵌字幕，使用 faster-whisper 转写（ASR）")
        val transcriptJson = runPython("transcribe", 15L * 60 * 1000, videoFile.absolutePath)
        val transcript = json.decodeFromString<TranscriptResult>(transcriptJson)
        log.info("转写完成: lang={} duration={}s segments={}",
            transcript.language, transcript.duration, transcript.segments.size)
        return transcript.copy(usedSubtitles = false)
    }

    private suspend fun downloadVideo(url: String, dest: File) {
        val resp = http.get(url)
        // Content-Length 预检：超过 300MB 拒绝
        val declared = resp.headers["Content-Length"]?.toLongOrNull()
        if (declared != null && declared > MAX_VIDEO_BYTES) {
            throw IllegalStateException("视频文件过大（${declared / 1024 / 1024}MB），超过 300MB 上限")
        }
        val channel = resp.bodyAsChannel()
        // 拷贝计数兜底，防止无 Content-Length 的超大文件写满磁盘
        val counting = object : OutputStream() {
            var total = 0L
            override fun write(b: Int) { total++; if (total > MAX_VIDEO_BYTES) throw IllegalStateException("视频超过 300MB 上限") }
            override fun write(b: ByteArray, off: Int, len: Int) {
                total += len
                if (total > MAX_VIDEO_BYTES) throw IllegalStateException("视频超过 300MB 上限")
            }
        }
        dest.outputStream().use { out ->
            val tee = object : OutputStream() {
                override fun write(b: Int) { out.write(b); counting.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); counting.write(b, off, len) }
                override fun flush() { out.flush() }
            }
            channel.copyTo(tee)
        }
    }

    /**
     * 调用 Python 脚本（带超时与取消销毁）。
     * @param timeoutMs 子进程最长运行时间；超时强杀。
     */
    private suspend fun runPython(action: String, timeoutMs: Long, vararg args: String): String =
        withContext(Dispatchers.IO) {
            val cmd = listOf("python3", File(scriptsDir, "video_pipeline.py").absolutePath, action) + args
            log.debug("exec: {}", cmd.joinToString(" "))
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            // 协程取消（HTTP 断开）时立即杀掉子进程，避免孤儿 python/ffmpeg
            currentCoroutineContext()[Job]?.invokeOnCompletion {
                if (proc.isAlive) runCatching { proc.destroyForcibly() }
            }
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                proc.waitFor(5, TimeUnit.SECONDS)
                throw RuntimeException("video_pipeline.py $action 超时（${timeoutMs / 1000}s），已终止")
            }
            val output = proc.inputStream.bufferedReader().readText()
            val code = proc.exitValue()
            if (code != 0) {
                throw RuntimeException("video_pipeline.py $action 失败(code=$code): ${output.take(500)}")
            }
            output.trim()
        }

    /** 对单帧做 OCR（方案D） */
    private suspend fun runOcr(imagePath: String): String? {
        return runCatching {
            val out = runPython("ocr", 2L * 60 * 1000, imagePath)
            val r = json.decodeFromString<OcrResult>(out)
            r.text.ifBlank { null }
        }.getOrElse {
            log.warn("OCR 失败: {}", it.message)
            null
        }
    }

    /** AI 推测关键帧时间点 */
    private suspend fun inferKeyFrames(transcript: TranscriptResult, count: Int): List<Double> {
        val durations = transcript.segments.joinToString("\n") {
            "[${it.start}-${it.end}s] ${it.text.take(80)}"
        }
        val prompt = """
            以下是一段视频的文本内容（含时间戳，单位秒）。
            请选出 $count 个最能代表视频核心内容的关键时间点（用于提取关键帧）。
            要求：
            1. 覆盖视频的开头、核心内容、结尾；
            2. 选择内容转折或重点强调的时刻；
            3. 仅输出时间戳（秒，可带小数），用逗号分隔，不要任何解释。
            示例：12.5,45.0,78.3,120.0,165.5

            文本内容：
            $durations
        """.trimIndent()

        val resp = ai.chat("你是视频内容分析专家。", prompt).content
        return resp.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            .filter { it >= 0.0 }
            .distinct()
            .sorted()
            .take(count)
            .ifEmpty {
                // 降级：均匀取样（假设 10 分钟视频）
                val dur = if (transcript.duration > 0) transcript.duration else 600.0
                (0 until count).map { (dur * it / count).coerceAtMost(dur - 1) }
            }
    }

    /** 视觉模型描述单帧（多模型降级链，在 AiProviders 中实现） */
    private suspend fun describeFrame(imageBase64: String): String? {
        val visionModel = store.get().doubaoVisionModel
        if (visionModel.isBlank()) return null
        return runCatching {
            ai.describeImage(visionModel, imageBase64)
        }.getOrElse {
            log.warn("视觉描述失败（已尝试降级链）: {}", it.message)
            null
        }
    }

    /** AI 生成视频总结（融合文本 + 视觉描述 + OCR） */
    private suspend fun generateSummary(transcript: TranscriptResult, frames: List<KeyFrame>): String {
        val frameDesc = frames.mapIndexed { i, f ->
            val parts = mutableListOf<String>()
            f.description?.let { parts.add("视觉描述：$it") }
            f.ocrText?.let { parts.add("画面文字：$it") }
            "帧${i + 1}(${f.timestamp}s)：${parts.joinToString(" | ").ifEmpty { "（未启用视觉描述）" }}"
        }.joinToString("\n")

        val prompt = """
            请基于以下视频信息生成一份内容总结：

            【视频语言】${transcript.language}
            【文本来源】${if (transcript.usedSubtitles) "内嵌字幕" else "语音转写(ASR)"}
            【关键帧信息】
            $frameDesc

            【文本全文】
            ${transcript.fullText.take(4000)}

            请输出：
            1. 视频核心主题（1-2句）
            2. 内容要点（3-5条结构化要点）
            3. 关键结论或收获（2-3条）
            用中文输出，简洁清晰。
        """.trimIndent()

        return ai.chat("你是专业的视频内容总结助手。", prompt).content
    }

    private fun inferTitle(transcript: TranscriptResult): String {
        val first = transcript.segments.firstOrNull()?.text?.take(30) ?: "视频"
        return "视频分析：$first"
    }

    // ---- Python 脚本返回结构 ----
    @Serializable
    private data class TranscriptResult(
        val language: String = "unknown",
        val duration: Double = 0.0,
        val segments: List<Seg> = emptyList(),
        @SerialName("full_text") val fullText: String = "",
        val usedSubtitles: Boolean = false
    )

    @Serializable
    private data class Seg(val start: Double, val end: Double, val text: String)

    @Serializable
    private data class FramesResult(val frames: List<FrameInfo> = emptyList())

    @Serializable
    private data class FrameInfo(val timestamp: Double, val path: String)

    @Serializable
    private data class SubtitlesResult(
        val found: Boolean = false,
        val text: String = "",
        val segments: List<Seg> = emptyList()
    )

    @Serializable
    private data class OcrResult(val text: String = "")

    companion object {
        private const val MAX_VIDEO_BYTES = 300L * 1024 * 1024 // 300MB
        /** 视频分析任务总时长上限（方案 §9：默认 30 分钟，超限返回受限原因）。 */
        private const val TOTAL_TASK_TIMEOUT_MS = 30L * 60 * 1000
    }
}
