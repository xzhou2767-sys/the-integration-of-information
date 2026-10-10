#!/usr/bin/env python3
"""
视频内容分析管线：
1. extract_subtitles - FFmpeg 提取内嵌字幕（方案B加速分支）
2. extract_audio     - FFmpeg 提取音频
3. transcribe        - faster-whisper 语音转文字（带时间戳）
4. extract_frames    - FFmpeg 在指定时间点提取关键帧
5. ocr_frame         - 对单帧做 OCR 提取画面文字（方案D增强）

由 Kotlin VideoAnalysisService 调用，输入输出均为 JSON。
"""
import json
import sys
import os
import subprocess
import tempfile
import shutil


def run(cmd):
    """运行命令，返回 (returncode, stdout, stderr)"""
    p = subprocess.run(cmd, capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def extract_audio(video_path, audio_path):
    """提取音轨为 16kHz 单声道 WAV"""
    rc, out, err = run([
        "ffmpeg", "-y", "-i", video_path,
        "-vn", "-acodec", "pcm_s16le",
        "-ar", "16000", "-ac", "1",
        audio_path
    ])
    if rc != 0:
        raise RuntimeError(f"ffmpeg extract audio failed: {err[:300]}")
    return audio_path


def transcribe(audio_path):
    """faster-whisper 转写，返回带时间戳的片段列表"""
    from faster_whisper import WhisperModel
    model = WhisperModel("base", device="cpu", compute_type="int8")
    segments, info = model.transcribe(audio_path, beam_size=1, vad_filter=True)
    result = []
    full_text = []
    for seg in segments:
        result.append({
            "start": round(seg.start, 2),
            "end": round(seg.end, 2),
            "text": seg.text.strip()
        })
        full_text.append(seg.text.strip())
    return {
        "language": info.language,
        "duration": round(info.duration, 2),
        "segments": result,
        "full_text": " ".join(full_text)
    }


def extract_frames(video_path, timestamps, output_dir):
    """在指定时间点（秒）提取帧为 JPEG"""
    os.makedirs(output_dir, exist_ok=True)
    frames = []
    for i, ts in enumerate(timestamps):
        out_path = os.path.join(output_dir, f"frame_{i:03d}_{int(ts)}.jpg")
        rc, out, err = run([
            "ffmpeg", "-y",
            "-ss", str(ts),
            "-i", video_path,
            "-frames:v", "1",
            "-q:v", "2",
            out_path
        ])
        if rc == 0 and os.path.exists(out_path):
            frames.append({"timestamp": ts, "path": out_path})
    return frames


def extract_subtitles(video_path):
    """提取内嵌字幕（方案B）。优先尝试 srt/ass/vtt 等字幕流。
    返回 {"found": bool, "text": str, "segments": [...]}。
    无字幕流时返回 found=False，调用方应回退到 ASR。"""
    # 1. 探测字幕流
    rc, out, err = run([
        "ffprobe", "-v", "error",
        "-select_streams", "s",
        "-show_entries", "stream=index:stream_tags=language",
        "-of", "json",
        video_path
    ])
    streams = []
    if rc == 0 and out.strip():
        try:
            info = json.loads(out)
            streams = info.get("streams", [])
        except Exception:
            pass

    if not streams:
        return {"found": False, "text": "", "segments": []}

    # 2. 提取第一条字幕流为 srt
    with tempfile.TemporaryDirectory() as td:
        srt_path = os.path.join(td, "sub.srt")
        rc, out, err = run([
            "ffmpeg", "-y", "-i", video_path,
            "-map", "0:s:0",
            srt_path
        ])
        if rc != 0 or not os.path.exists(srt_path) or os.path.getsize(srt_path) == 0:
            return {"found": False, "text": "", "segments": []}

        # 3. 解析 srt 为带时间戳的片段
        text = open(srt_path, encoding="utf-8", errors="ignore").read()
        segments = parse_srt(text)
        full_text = " ".join(s["text"] for s in segments)
        return {"found": True, "text": full_text, "segments": segments}


def parse_srt(srt_text):
    """简单 SRT 解析，返回 [{start, end, text}]，时间单位秒。"""
    import re
    segments = []
    blocks = re.split(r"\n\n+", srt_text.strip())
    time_re = re.compile(r"(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})")
    for block in blocks:
        lines = block.strip().splitlines()
        if len(lines) < 2:
            continue
        m = time_re.search(lines[1]) if len(lines) > 1 else None
        if not m:
            continue
        sh, sm, ss, sms, eh, em, es, ems = m.groups()
        start = int(sh) * 3600 + int(sm) * 60 + int(ss) + int(sms) / 1000
        end = int(eh) * 3600 + int(em) * 60 + int(es) + int(ems) / 1000
        text = " ".join(lines[2:]).strip()
        if text:
            segments.append({"start": round(start, 2), "end": round(end, 2), "text": text})
    return segments


def ocr_frame(image_path):
    """对单帧图片做 OCR（方案D）。优先用 paddleocr，其次 tesseract。
    返回识别到的文字字符串，失败返回空串。"""
    # 尝试 PaddleOCR（中文识别效果好）
    try:
        from paddleocr import PaddleOCR
        ocr = PaddleOCR(use_angle_cls=True, lang="ch", show_log=False)
        result = ocr.ocr(image_path, cls=True)
        texts = []
        for line in result:
            if not line:
                continue
            for word in line:
                if word and len(word) >= 2:
                    texts.append(word[1][0])
        return " ".join(texts)
    except Exception:
        pass

    # 回退到 tesseract
    try:
        rc, out, err = run(["tesseract", image_path, "stdout", "-l", "chi_sim+eng"])
        if rc == 0:
            return out.strip()
    except Exception:
        pass

    return ""


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "usage: video_pipeline.py <action> [args...]"}))
        sys.exit(1)

    action = sys.argv[1]
    try:
        if action == "transcribe":
            video_path = sys.argv[2]
            with tempfile.TemporaryDirectory() as td:
                audio_path = os.path.join(td, "audio.wav")
                extract_audio(video_path, audio_path)
                result = transcribe(audio_path)
                print(json.dumps(result, ensure_ascii=False))
        elif action == "frames":
            video_path = sys.argv[2]
            timestamps = [float(t) for t in sys.argv[3].split(",") if t]
            output_dir = sys.argv[4] if len(sys.argv) > 4 else tempfile.mkdtemp(prefix="frames_")
            frames = extract_frames(video_path, timestamps, output_dir)
            print(json.dumps({"frames": frames, "dir": output_dir}, ensure_ascii=False))
        elif action == "extract_audio":
            video_path = sys.argv[2]
            audio_path = sys.argv[3]
            extract_audio(video_path, audio_path)
            print(json.dumps({"audio_path": audio_path}))
        elif action == "subtitles":
            video_path = sys.argv[2]
            result = extract_subtitles(video_path)
            print(json.dumps(result, ensure_ascii=False))
        elif action == "ocr":
            image_path = sys.argv[2]
            text = ocr_frame(image_path)
            print(json.dumps({"text": text}, ensure_ascii=False))
        else:
            print(json.dumps({"error": f"unknown action: {action}"}))
            sys.exit(1)
    except Exception as e:
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == "__main__":
    main()
