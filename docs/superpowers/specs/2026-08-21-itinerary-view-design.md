# 일정형 영상 자동 일정 뷰 설계

날짜: 2026-08-21
상태: 승인됨 (브레인스토밍 완료)

## 배경

지금까지는 영상 하나에서 장소를 추출해서 `SavedPlace` 목록으로 평면
나열만 했다. "부산 여행 일정" 같은 영상은 실제로는 일자별(1일차/2일차...)
구조를 갖고 있는 경우가 많고, 사용자가 이걸 네이버 지도의 "코스" 기능처럼
일자별 탭 + 지도 위 순서가 있는 핀/경로로 보고 싶어함. TODO.md 백로그에
있던 "일정 자동 생성"을 실제로 설계한다.

## 결정 사항

브레인스토밍에서 확정한 것:

- **일정 판단은 Gemini가 영상 내용에서 추론.** 제목 키워드 매칭이 아니라,
  기존 2단계 필터링 프롬프트(`FILTER_PROMPT`)를 확장해서 "이 영상이
  일자별 구조인가"까지 같은 호출에서 같이 판단. **새 Gemini 호출을
  추가하지 않음** — 무료 티어 RPM 한도를 안 늘리기 위해서.
- **그룹화는 새 엔티티 없이 `SavedPlace`에 필드 2개만 추가.**
  `dayNumber`(1일차, 2일차...), `orderInDay`(그 날 안에서의 순서).
  일정형이 아니면 둘 다 `null` — 기존 데이터/로직에 영향 없음.
  판단은 영상(=`ProcessingJob`) 단위: 한 영상에서 나온 장소는 전부
  일정형이거나 전부 아니거나 둘 중 하나.
- **지도의 "경로"는 실제 길찾기가 아니라 핀을 순서대로 잇는 직선
  (폴리라인).** 카카오의 대중교통/도보/자전거 경로 API는 이번에
  신설된 유료 상품(무료 쿼터 일 1,000건, 초과 시 건당 10원)이라
  "비용 0원" 원칙에 안 맞음. 카카오맵 API 공지
  (https://devtalk.kakao.com/t/api-notice-on-new-kakao-map-api-features-and-free-quota-policy/150222,
  2026-07-21) 확인 결과, **지도 API(JS SDK, 마커/폴리라인)와 로컬 API
  (지오코딩)는 둘 다 "기존 제공량 유지"로 무료** — Trova의 카카오 앱이
  이미 계정 기준 첫 번째(유일한) 활성화 앱이라 이 무료 쿼터가 그대로
  적용됨. 경로 API만 안 쓰면 완전히 0원으로 갈 수 있음.
- **프론트에서 카카오맵 JS SDK를 이번에 처음 연동한다.** 지금까지는
  로컬 API(REST, 서버 전용)만 썼고 화면에 실제 지도를 띄운 적이 없음.
  배포 시 Kakao Developers 콘솔에 실제 배포 도메인을 Web 플랫폼으로
  등록해야 지도가 뜸(로컬 개발 도메인은 이미 등록돼 있을 것으로 가정,
  구현 착수 시 확인).

## 범위

파이프라인(`extract_places.py`)의 일정 판단/필드 추가, 백엔드
(`ExtractedPlace`/`SavedPlace`/`PlacesController`)의 필드 전달, 프론트
(`/places`의 일자별 탭 UI + 카카오맵 SDK 첫 연동)까지 다룬다.
카카오 길찾기(실제 경로) 연동은 범위 밖 — 필요해지면 별도 스펙.

## 아키텍처

### 처리 흐름

```
URL 제출 → 기존과 동일하게 수집(COLLECT_PROMPT) → 필터링(FILTER_PROMPT, 확장)
         → FILTER_PROMPT가 이제 판단하는 것:
             1. 이 영상이 일자별 일정 구조인가? (예/아니오)
             2. 예라면 각 장소에 dayNumber(1부터)/orderInDay(그 날 안에서 1부터) 부여
         → 아니라면 두 필드 다 null (기존과 동일하게 평면 리스트)
         → 카카오 지오코딩 (기존과 동일, 변경 없음)
         → SavedPlace 저장 시 dayNumber/orderInDay도 같이 저장
```

### 파이프라인 (`pipeline-test/extract_places.py`)

- `FILTER_PROMPT`에 일정 판단 지시 추가, 출력 JSON 스키마에
  `dayNumber`(int|null), `orderInDay`(int|null) 필드 추가
- 한 영상 안에서 일부 장소만 일정형이고 나머지는 아닌 경우는 없다고
  가정(Gemini가 영상 전체를 보고 한 번에 판단하므로) — 만약 Gemini가
  일관성 없이 섞어서 반환하면(일부만 dayNumber 있음), Java 쪽에서
  "하나라도 null이 아니면 그 영상은 일정형"으로 보되 null인 항목은
  그냥 dayNumber 없이 저장(불완전해도 저장은 막지 않음)

### 백엔드

```
ExtractedPlace(name, region, category, confidence, dayNumber, orderInDay)
  — dayNumber/orderInDay는 Integer, nullable

SavedPlace에 컬럼 추가:
  day_number (INT, nullable)
  order_in_day (INT, nullable)
```

- `PlaceExtractionService`가 파이프라인 JSON의 두 필드를 그대로
  `SavedPlace` 생성자/세터에 전달
- `PlacesController.PlaceResponse`에 `dayNumber`/`orderInDay` 추가,
  `PlaceResponse.from()`에서 매핑
- `ddl-auto: update`라 별도 마이그레이션 스크립트 불필요(Supabase에
  실제 접속해서 스키마 확인하는 기존 관례 유지 — 구현 후 재확인)

### 프론트엔드

- 카카오맵 JS SDK 최초 연동: `<script>` 로드(도메인 등록된 JS 키 사용),
  지도 컴포넌트 신규 작성
- `/places`: 같은 `sourceUrl`(또는 job)에서 나온 장소들의 `dayNumber`가
  하나라도 non-null이면 해당 그룹을 일정형으로 판단해서 "1일차/2일차"
  탭 UI로 렌더링. 나머지(일정형 아닌 장소)는 기존 플랫 리스트 그대로.
- 탭 안에서: 카카오맵에 `orderInDay` 순서로 마커 표시, 마커 사이를
  `Polyline`(직선)으로 연결. 실제 길찾기 아님을 UI 문구로 명시할지는
  구현 시 결정.

## 에러 처리

- Gemini가 일정 여부 판단을 못 하거나 애매하게 반환 → 전부 null로
  간주(기존 평면 리스트로 폴백) — 일정 판단 실패가 전체 추출을
  실패시키지 않음
- 지오코딩 실패(좌표 null)인 장소가 일정에 섞여 있으면 → 그 장소는
  지도에 핀을 못 찍지만(좌표 없음), 탭의 리스트에는 그대로 표시
  (기존 좌표 null 처리 방식과 동일)
- 카카오맵 SDK 로드 실패(도메인 미등록, 네트워크 오류 등) → 지도
  영역만 에러 상태로 표시, 리스트(장소명/순서)는 지도 없이도 보임

## 테스트 전략

- `extract_places.py`: 일정형 샘플 자막/프레임으로 실제 Gemini 호출
  재현 테스트(기존 수동 검증 스크립트 확장), dayNumber/orderInDay가
  기대한 순서로 나오는지 확인
- 백엔드: `PlaceExtractionService`/`PlacesController` 단위·통합
  테스트에 dayNumber/orderInDay 케이스 추가(일정형/비일정형 둘 다)
- 프론트: 탭 전환 로직(그룹핑 함수) 단위 테스트, 카카오맵 렌더링은
  브라우저로 수동 확인(자동화 테스트 범위 밖 — 기존 지도 없는
  화면들과 동일한 관례)

## 커밋 전 확인

CLAUDE.md 규칙대로 `./gradlew build`(백엔드), `npm run lint && npm run
build`(프론트) 실행.
