#!/usr/bin/env python3
"""URL에서 영상 + (있으면) 자막을 함께 받는다.

YouTube가 2025년 하반기부터 PO Token 검증을 강화하면서 쿠키 없는 요청은
종종(확률적으로) 403을 반환한다 — 같은 영상도 순수 재시도만으로 성공하는 경우를
실측으로 확인했다. 그래서 403이 뜨면 먼저 순수 재시도를 몇 번 하고, 그래도 안 되면
브라우저(Chrome) 로그인 쿠키로 재시도한다. 쿠키 재시도는 로컬(Chrome 설치된 환경)
에서만 의미가 있고, 헤드리스 배포 환경에서는 실패하고 넘어간다 — 그래서 배포
환경에서는 사실상 순수 재시도가 유일한 방어선이다.
"""
from __future__ import annotations

import re
import subprocess
import sys
import time
from pathlib import Path

SUB_LANGS = "ko,en"
MAX_PLAIN_RETRIES = 3
PLAIN_RETRY_BACKOFF = 3.0


def _run_yt_dlp(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(["yt-dlp", *args], capture_output=True, text=True)


def _download_with_retry(video_args: list[str]) -> subprocess.CompletedProcess:
    result = _run_yt_dlp(video_args)
    attempt = 0
    while result.returncode != 0 and "403" in result.stderr and attempt < MAX_PLAIN_RETRIES:
        attempt += 1
        delay = PLAIN_RETRY_BACKOFF * attempt
        print(
            f"[download] 403 감지 — 순수 재시도 {attempt}/{MAX_PLAIN_RETRIES} ({delay:.0f}초 대기)",
            file=sys.stderr,
        )
        time.sleep(delay)
        result = _run_yt_dlp(video_args)

    if result.returncode != 0 and "403" in result.stderr:
        print("[download] 순수 재시도 소진 — Chrome 쿠키로 재시도(로컬에 Chrome 있을 때만 동작)", file=sys.stderr)
        result = _run_yt_dlp(["--cookies-from-browser", "chrome", *video_args])

    return result


def download(url: str, out_dir: Path) -> dict:
    """영상을 out_dir/video.* 로, 자막이 있으면 out_dir/video.*.vtt 로 받는다.

    영상 다운로드는 필수, 자막은 best-effort로 별도 호출한다. 두 요청을 한 번에
    묶으면 yt-dlp가 요청한 자막 언어 중 하나만 실패(예: rate limit)해도 영상
    다운로드까지 통째로 중단시키는 것을 실측으로 확인했기 때문이다.

    반환: {"video_path": Path, "caption_path": Path | None}
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    out_tmpl = str(out_dir / "video.%(ext)s")
    video_args = ["-f", "bv*+ba/b", "-o", out_tmpl, "--", url]

    result = _download_with_retry(video_args)

    if result.returncode != 0:
        raise SystemExit(f"yt-dlp 다운로드 실패:\n{result.stderr[-2000:]}")

    video_candidates = sorted(
        (p for p in out_dir.glob("video.*") if p.suffix not in (".vtt", ".json", ".part")),
        key=lambda p: p.stat().st_size,
        reverse=True,
    )
    if not video_candidates:
        raise SystemExit(f"yt-dlp가 영상 파일을 만들지 못했습니다 (out_dir={out_dir})")

    sub_args = [
        "--write-auto-sub", "--write-sub", "--sub-lang", SUB_LANGS,
        "--sub-format", "vtt", "--skip-download",
        "-o", out_tmpl,
        "--",
        url,
    ]
    sub_result = _run_yt_dlp(sub_args)
    if sub_result.returncode != 0 and "403" in sub_result.stderr:
        sub_result = _run_yt_dlp(["--cookies-from-browser", "chrome", *sub_args])
    if sub_result.returncode != 0:
        print(f"[download] 자막 다운로드 실패(무시하고 진행): {sub_result.stderr[-500:]}", file=sys.stderr)

    # 언어 우선순위(한국어 우선)를 명시 — 알파벳순 정렬이면 "en"이 "ko"보다 먼저 와서
    # 둘 다 받아졌을 때 영어 자막이 잘못 선택될 수 있다.
    caption_path = None
    for lang in SUB_LANGS.split(","):
        candidates = sorted(out_dir.glob(f"video.{lang}*.vtt"))
        if candidates:
            caption_path = candidates[0]
            break

    return {"video_path": video_candidates[0], "caption_path": caption_path}


def get_title(url: str) -> str | None:
    """영상 제목을 가져온다. 실패해도 파이프라인 전체를 막지 않는다(best-effort) —
    다운로드/자막과 마찬가지로 별도 호출로 분리해서, 제목 조회 실패가 나머지 흐름을
    막지 않게 한다.
    """
    result = _run_yt_dlp(["--get-title", "--", url])
    if result.returncode != 0 and "403" in result.stderr:
        result = _run_yt_dlp(["--cookies-from-browser", "chrome", "--get-title", "--", url])
    if result.returncode != 0:
        print(f"[download] 제목 가져오기 실패(무시하고 진행): {result.stderr[-500:]}", file=sys.stderr)
        return None
    title = result.stdout.strip()
    return title or None


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
