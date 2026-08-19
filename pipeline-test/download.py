#!/usr/bin/env python3
"""URL에서 영상 + (있으면) 자막을 함께 받는다.

YouTube가 2025년 하반기부터 PO Token 검증을 강화하면서 쿠키 없는 요청은
종종 403을 반환한다. 403이 뜨면 브라우저(Chrome) 로그인 쿠키로 자동 재시도한다.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

SUB_LANGS = "ko,en"


def _run_yt_dlp(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(["yt-dlp", *args], capture_output=True, text=True)


def download(url: str, out_dir: Path) -> dict:
    """영상을 out_dir/video.* 로, 자막이 있으면 out_dir/video.*.vtt 로 받는다.

    반환: {"video_path": Path, "caption_path": Path | None}
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    out_tmpl = str(out_dir / "video.%(ext)s")
    base_args = [
        "-f", "bv*+ba/b",
        "--write-auto-sub", "--write-sub", "--sub-lang", SUB_LANGS,
        "--sub-format", "vtt",
        "-o", out_tmpl,
        "--",
        url,
    ]

    result = _run_yt_dlp(base_args)
    if result.returncode != 0 and "403" in result.stderr:
        print("[download] 403 감지 (PO Token 요구) — Chrome 쿠키로 재시도", file=sys.stderr)
        result = _run_yt_dlp(["--cookies-from-browser", "chrome", *base_args])

    if result.returncode != 0:
        raise SystemExit(f"yt-dlp 다운로드 실패:\n{result.stderr[-2000:]}")

    video_candidates = sorted(
        (p for p in out_dir.glob("video.*") if p.suffix not in (".vtt", ".json", ".part")),
        key=lambda p: p.stat().st_size,
        reverse=True,
    )
    if not video_candidates:
        raise SystemExit(f"yt-dlp가 영상 파일을 만들지 못했습니다 (out_dir={out_dir})")

    caption_candidates = sorted(out_dir.glob("video.*.vtt"))
    caption_path = caption_candidates[0] if caption_candidates else None

    return {"video_path": video_candidates[0], "caption_path": caption_path}


def vtt_to_text(vtt_path: Path) -> str:
    """WebVTT 자막에서 타임스탬프/태그를 제거하고 중복 없는 본문 텍스트만 뽑는다."""
    raw = vtt_path.read_text(encoding="utf-8", errors="replace")
    lines_out: list[str] = []
    last_line = None
    for line in raw.splitlines():
        line = line.strip()
        if not line or line == "WEBVTT":
            continue
        if "-->" in line:
            continue
        if re.match(r"^\d+$", line):
            continue
        # 태그(<c>, <00:00:01.000> 등) 제거
        clean = re.sub(r"<[^>]+>", "", line).strip()
        if not clean or clean == last_line:
            continue
        lines_out.append(clean)
        last_line = clean
    return "\n".join(lines_out)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: download.py <url> [out_dir]", file=sys.stderr)
        raise SystemExit(2)
    url = sys.argv[1]
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("work/download")
    info = download(url, out_dir)
    print(f"video: {info['video_path']}")
    print(f"caption: {info['caption_path']}")
