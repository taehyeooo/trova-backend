#!/usr/bin/env python3
"""samples.txt의 링크를 순서대로 하나씩 돌려서 결과를 JSON으로 모은다.

한 영상이 실패해도(비공개/삭제/다운로드 오류 등) 전체가 멈추지 않고
다음 영상으로 넘어간다 — 25개 중 일부 실패는 실제 서비스에서도 항상
생기는 일이라 배치 자체가 그걸 견뎌야 함.
"""
from __future__ import annotations

import json
import re
import sys
import time
import traceback
from pathlib import Path

import run_pipeline


def video_id(url: str) -> str:
    """URL에서 폴더명으로 쓸 영상 ID를 뽑는다. 실패하면 URL 전체를 안전한 문자로 치환."""
    match = re.search(r"(?:shorts/|watch\?v=|youtu\.be/)([\w-]{6,})", url)
    slug = match.group(1) if match else url
    return re.sub(r"[^\w-]", "_", slug)


def load_urls(path: Path) -> list[str]:
    urls = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        urls.append(line)
    return urls


PACE_SECONDS = 8.0  # 영상 사이 대기 — 유튜브 요청 속도 제한(429) 예방


def main() -> None:
    samples_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("samples.txt")
    out_path = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("work/batch_results.json")
    work_root = Path("work/batch")

    urls = load_urls(samples_path)
    results = []

    for i, url in enumerate(urls, start=1):
        if i > 1:
            time.sleep(PACE_SECONDS)
        print(f"\n[batch] ({i}/{len(urls)}) {url}", file=sys.stderr)
        start = time.time()
        entry = {"index": i, "url": url}
        try:
            places = run_pipeline.run(url, work_root / video_id(url))
            entry["places"] = places
            entry["place_count"] = len(places)
            entry["error"] = None
        except SystemExit as exc:
            entry["places"] = None
            entry["place_count"] = 0
            entry["error"] = str(exc)
            print(f"[batch] 실패: {exc}", file=sys.stderr)
        except Exception:
            entry["places"] = None
            entry["place_count"] = 0
            entry["error"] = traceback.format_exc(limit=3)
            print(f"[batch] 예외 발생:\n{entry['error']}", file=sys.stderr)
        entry["elapsed_sec"] = round(time.time() - start, 1)
        results.append(entry)

        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    ok = sum(1 for r in results if r["error"] is None)
    total_places = sum(r["place_count"] for r in results)
    print(
        f"\n[batch] 완료: {ok}/{len(results)}건 성공, 총 {total_places}개 장소 추출, "
        f"결과 저장: {out_path}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
