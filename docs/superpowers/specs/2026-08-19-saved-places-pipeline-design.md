# SavedPlace/ProcessingJob + 공유 파이프라인 설계

날짜: 2026-08-19
상태: 승인됨 (브레인스토밍 완료)

## 배경

파이프라인 검증(1단계)과 소셜 로그인(2단계 일부)이 끝났다. 이 스펙은
CLAUDE.md 2단계의 핵심 — `POST /api/shares`로 URL을 받아 검증된
파이프라인(`pipeline-test/`)으로 장소를 추출하고 `SavedPlace`로 저장하는
흐름 — 을 다룬다.

실제 배치 테스트 결과(`pipeline-test/work/batch_results_final.json`,
25개 유튜브 쇼츠)를 확인한 결과, CLAUDE.md의 원래 엔티티 가정과 다른
사실이 드러났다:

- URL 하나에서 장소가 **여러 개** 나온다 (평균 3.1개, 0개인 영상 없음).
  CLAUDE.md의 `ProcessingJob(savedPlaceId)`는 1:1 가정이라 맞지 않음.
- 처리 시간이 URL당 **~30초** — `@Async` 비동기 처리가 필수임이 실측으로도 확인됨.
- 파이프라인 출력(`{name, region, category, confidence}`)에
  **위도/경도가 없다** — 카카오 지오코딩 단계가 어디에도 구현되어 있지 않음.
- 파이프라인은 순수 stdlib Python + yt-dlp/ffmpeg 서브프로세스라
  별도 pip 의존성이 없음.

## 결정 사항

브레인스토밍에서 확정한 것:

- **파이프라인 통합 방식: Java에서 Python 스크립트를 서브프로세스로 호출.**
  이미 25/25 성공률로 검증된 코드를 그대로 쓴다. 재구현 리스크를 지지
  않음. 배포 컨테이너에는 어차피 yt-dlp/ffmpeg가 필요했으므로(TODO.md
  3단계) python3 추가는 비용이 작다.
- **ProcessingJob 1 : SavedPlace N.** `POST /api/shares` 호출마다
  `ProcessingJob` 1건 생성(URL 단위 작업 추적). 파이프라인이 완료되면
  추출된 장소 수만큼 `SavedPlace`를 생성하고 각각 그 `ProcessingJob`을
  가리키는 FK를 가짐. "처리 상태"는 `ProcessingJob`에만 있고
  `SavedPlace`에는 상태 필드가 없음(완료된 것만 존재하므로).
- **카카오 지오코딩 포함.** 이름+지역으로 카카오 로컬 API 키워드 검색 →
  위도/경도. 위도/경도 없이는 지도에 못 찍으므로 핵심 가치 미완성이라고
  판단.
- **SavedPlace/ProcessingJob을 User와 연결(FK).** 로그인이 이미 구현되어
  있고 `/api/**`가 이미 인증을 요구하므로 자연스러운 확장.

## 범위

이 스펙은 `POST /api/shares`, `GET /api/places`, `GET /api/places/{id}`,
`DELETE /api/places/{id}`, `GET /api/places/pending` 5개 엔드포인트와
그 뒤의 파이프라인 실행/지오코딩까지를 다룬다. 프론트엔드 연동(실제
fetch로 mock 교체)은 별도 스펙.

## 아키텍처

### 엔티티

```
ProcessingJob
- id (PK)
- user (FK → User, nullable 아님)
- sourceUrl (VARCHAR)
- sourcePlatform (VARCHAR: "INSTAGRAM" | "YOUTUBE")
- status (VARCHAR: "PENDING" | "PROCESSING" | "DONE" | "FAILED")
- errorMessage (VARCHAR, nullable)
- retryCount (INT, default 0)
- createdAt, updatedAt (TIMESTAMP)

SavedPlace
- id (PK)
- processingJob (FK → ProcessingJob, nullable 아님)
- user (FK → User, nullable 아님 — ProcessingJob에서 파생 가능하지만
  조회 편의를 위해 비정규화해서 직접 들고 있음)
- placeName (VARCHAR)
- region (VARCHAR, nullable)
- category (VARCHAR — 파이프라인 값 그대로: restaurant|cafe|attraction|lodging|shopping|other)
- latitude, longitude (DOUBLE, 둘 다 nullable — 지오코딩 실패 시)
- sourceUrl, sourcePlatform (VARCHAR — ProcessingJob에서 복사, 조회 편의)
- createdAt (TIMESTAMP)
```

`sourcePlatform` 판별: URL에 `youtube.com`/`youtu.be` 포함 여부로
(프론트 mock의 `createShare`가 이미 쓰는 판별 로직과 동일 기준).

### 처리 흐름

1. `POST /api/shares {url}` — 인증된 사용자만. `ProcessingJob(status=PENDING)`
   저장, 202 Accepted + `{jobId}` 응답. 별도 `@Async` 메서드로 아래를
   백그라운드 실행(호출자는 기다리지 않음).
2. 백그라운드(`PlaceExtractionService` 또는 유사):
   a. `ProcessingJob.status = PROCESSING`으로 갱신
   b. `PipelineRunner`(신규)가
      `ProcessBuilder("python3", pipelineScriptPath, url, workDir)` 실행.
      `workDir`는 `work/job-{job.id}` — job id 기반이라 과거에 겪었던
      "작업 폴더 충돌" 버그가 구조적으로 재발 불가.
      `GEMINI_API_KEY`는 Java 쪽 설정에서 읽어 서브프로세스 환경변수로
      명시적으로 주입(`pipeline-test/.env` 관례에 의존하지 않음).
      stdout을 JSON 배열로 파싱: `[{name, region, category, confidence}, ...]`.
      비정상 종료(exit code != 0) 또는 파싱 실패 시 예외.
   c. 장소가 0개면: `ProcessingJob.status = DONE`(빈 결과도 정상 완료 —
      "장소 없음"과 "실패"는 다름). 하나 이상이면 각 항목마다:
      - `KakaoGeocodingService`(신규)로 `name + region` 키워드 검색 →
        첫 결과의 위도/경도. 결과 없으면 null.
      - `SavedPlace` 저장(위 필드 매핑)
      완료되면 `ProcessingJob.status = DONE`.
   d. 서브프로세스 실행 중 예외 발생 시:
      `ProcessingJob.status = FAILED`, `errorMessage`에 원인 요약 저장.
      이미 만들어진 `SavedPlace`가 있다면 그대로 둠(부분 성공 허용 —
      끝까지 실패해도 이미 뽑힌 장소는 버리지 않음).
3. `GET /api/places` — 로그인한 사용자의 `SavedPlace` 전체(최신순).
4. `GET /api/places/pending` — 로그인한 사용자의 `ProcessingJob` 중
   `PENDING`/`PROCESSING` (아직 장소가 안 나왔으므로 `SavedPlace`가 아닌
   `ProcessingJob` 기준으로 응답 — 프론트 처리 대기열 화면이 기대하는
   "이 URL 처리 중"이라는 개념과 정확히 일치).
5. `GET /api/places/{id}` — 본인 소유 `SavedPlace` 단건, 아니면 404.
6. `DELETE /api/places/{id}` — 본인 소유만 삭제 가능, 아니면 404.
   연결된 `ProcessingJob`은 그대로 둠(작업 이력).

### 동시성

`@Async` 기본 실행자(`SimpleAsyncTaskExecutor`)는 스레드를 무제한
생성하므로, 명시적으로 bounded thread pool(`ThreadPoolTaskExecutor`,
core/max 소수)을 빈으로 등록해서 씀 — 동시에 여러 사용자가 공유해도
서브프로세스가 무한정 늘어나지 않게.

### 외부 연동

- **Gemini API**: 이미 파이프라인이 씀. 새 설정 없음(`GEMINI_API_KEY`
  환경변수, 무료 티어).
- **카카오 로컬 API**: 로그인에 이미 쓰는 **같은 카카오 REST API 키**로
  인증 가능(카카오 로컬 API는 카카오 로그인과 별개 기능이지만 동일
  앱 키 체계 사용) — 새 시크릿 발급 불필요. 다만 Kakao Developers에서
  "카카오맵" 서비스 활용 동의가 REST API 키와 별개로 필요할 수 있음 —
  구현 착수 시 콘솔에서 먼저 확인. 무료 쿼터(일 10만/월 300만) 안에서
  429 등 오류 시 재시도/백오프 반영(CLAUDE.md 비용 원칙).
- **yt-dlp/ffmpeg/python3**: 서브프로세스 실행 환경에 있어야 함 — 로컬
  개발 환경엔 이미 있음(pipeline-test 검증에 씀). 배포 시 Dockerfile에
  반영 필요(TODO.md 3단계, 이번 스펙 범위 밖).

## 에러 처리

- 서브프로세스 자체 실행 실패(yt-dlp 403/429 등은 이미 `download.py`가
  자체 재시도) → 그래도 최종 실패하면 `ProcessingJob.FAILED` +
  `errorMessage`에 stderr 요약
- Gemini/카카오 API 오류 → 이미 파이프라인/재시도 로직이 처리. Java
  쪽에서 추가로 감쌀 필요 없음(2xx 아닌 서브프로세스 종료 코드만 처리)
- 지오코딩 결과 없음 → 에러 아님, `latitude`/`longitude`만 null로 저장
- 다른 사용자의 `SavedPlace`에 접근 시도 → 404(403 아님 — 존재 여부
  노출 안 함)

## 테스트 전략

- `PipelineRunner`: 서브프로세스 실행 자체는 통합 성격이라 단위
  테스트에서 실제 실행하지 않음. JSON stdout 파싱 로직만 순수 함수로
  분리해서 단위 테스트(고정된 샘플 stdout 문자열로 파싱 검증)
- `KakaoGeocodingService`: HTTP 클라이언트를 인터페이스로 분리해서
  Mockito로 응답 시나리오(정상/결과없음/오류) 단위 테스트
- `SavedPlace`/`ProcessingJob` 리포지토리: `@DataJpaTest` (H2)
- 엔드포인트: `@SpringBootTest` + `oauth2Login()`으로 인증 시뮬레이션,
  타인 소유 리소스 접근 시 404 확인 포함
- 실제 파이프라인 서브프로세스 실행(yt-dlp/ffmpeg/Gemini/카카오 전부
  실제로 호출)은 자동화 테스트 범위 밖 — 수동 검증 가이드로 별도 문서화

## 커밋 전 확인

CLAUDE.md 규칙대로 `./gradlew build` 실행.
