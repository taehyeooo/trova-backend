#!/usr/bin/env python3
"""영상에서 균등 간격으로 프레임(JPEG)을 추출한다 (ffmpeg 필요)."""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


def probe_duration(video_path: Path) -> float:
    result = subprocess.run(
        ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", str(video_path)],
        capture_output=True,
        text=True,
    )
    fmt = json.loads(result.stdout or "{}").get("format", {})
    return float(fmt.get("duration") or 0.0)


def extract_frames(video_path: Path, out_dir: Path, max_frames: int = 8, width: int = 512) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    duration = probe_duration(video_path) or float(max_frames)
    interval = max(duration / max_frames, 0.5)

    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(video_path),
        "-vf", f"fps=1/{interval:.3f},scale={width}:-1",
        "-vframes", str(max_frames),
        str(out_dir / "frame_%03d.jpg"),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"ffmpeg 프레임 추출 실패: {result.stderr}")
    return sorted(out_dir.glob("frame_*.jpg"))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: frames.py <video_path> [out_dir] [max_frames]", file=sys.stderr)
        raise SystemExit(2)
    video_path = Path(sys.argv[1])
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("work/frames")
    max_frames = int(sys.argv[3]) if len(sys.argv) > 3 else 8
    for frame in extract_frames(video_path, out_dir, max_frames=max_frames):
        print(frame)
