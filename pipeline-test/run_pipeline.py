#!/usr/bin/env python3
"""URL 하나를 받아 끝까지 돌리는 파이프라인.

어떤 영상이 들어올지 모르므로, 구할 수 있는 신호를 전부 모아 한 번에
Gemini로 보낸다:
  - 자막이 있으면 자막 텍스트 (가장 저렴, 우선)
  - 자막이 없으면 오디오도 함께 (내레이션 대응)
  - 프레임(화면 텍스트/위치 태그)은 자막 유무와 상관없이 항상 포함
    (내레이션이 있어도 화면 캡션에만 장소가 적힌 경우가 많음을 실측 확인함)
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import download
import frames as frames_mod
from extract_places import extract_places

MAX_FRAMES = 8


def extract_audio(video_path: Path, out_path: Path) -> Path:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(video_path),
        "-vn", "-acodec", "libmp3lame", "-ar", "16000", "-ac", "1", "-b:a", "64k",
        str(out_path),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"ffmpeg 오디오 추출 실패: {result.stderr}")
    return out_path


def run(url: str, work_dir: Path) -> list[dict]:
    print(f"[pipeline] 다운로드 중: {url}", file=sys.stderr)
    info = download.download(url, work_dir / "download")
    video_path = info["video_path"]
    caption_path = info["caption_path"]

    transcript = None
    if caption_path:
        transcript = download.vtt_to_text(caption_path)
        print(f"[pipeline] 자막 발견 ({len(transcript)}자) — 오디오는 생략", file=sys.stderr)
    else:
        print("[pipeline] 자막 없음 — 오디오 추출", file=sys.stderr)

    audio_path = None
    if not transcript:
        audio_path = extract_audio(video_path, work_dir / "audio.mp3")

    print(f"[pipeline] 프레임 추출 (최대 {MAX_FRAMES}장)", file=sys.stderr)
    frame_paths = frames_mod.extract_frames(video_path, work_dir / "frames", max_frames=MAX_FRAMES)

    print("[pipeline] Gemini 호출", file=sys.stderr)
    return extract_places(transcript=transcript, audio_path=audio_path, frame_paths=frame_paths)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: run_pipeline.py <url> [work_dir]", file=sys.stderr)
        raise SystemExit(2)
    url = sys.argv[1]
    work_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("work") / "run"
    places = run(url, work_dir)

    import json
    print(json.dumps(places, ensure_ascii=False, indent=2))
