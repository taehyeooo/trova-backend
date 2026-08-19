# 소셜 로그인 (Google/Kakao) 설계

날짜: 2026-08-19
상태: 승인됨 (브레인스토밍 완료)

## 배경

파이프라인 검증(1단계)이 끝나 Spring Boot API 개발(2단계)에 착수 가능한
상태다. 백엔드 프로젝트는 아직 초기 세팅조차 안 되어 있고
(`src/Main.java`만 존재), 로그인 방식도 미결이었다 (TODO.md "2단계 착수
전 확인" 항목). 이 스펙은 그 미결 사항을 해결하고, Spring Boot 프로젝트
초기 세팅 + 로그인/인증 기능을 다룬다.

프론트엔드는 별도 레포(`trova-frontend`)의 Next.js(React 19, App
Router) 웹 앱이며, 개발 중 기본 포트는 3000, 백엔드는 8080을 가정한다.

## 범위

이 스펙은 로그인/인증까지만 다룬다. `SavedPlace`, `ProcessingJob`
엔티티와 `/api/shares` 등 나머지 엔드포인트는 별도 스펙으로 분리한다.

## 결정 사항

브레인스토밍 과정에서 확정한 것:

- **OAuth 흐름 주도권: 백엔드** — Spring Security OAuth2 Client가 리다이렉트/콜백/토큰 교환을 전부 처리. 프론트는 `/oauth2/authorization/{provider}`로 이동하는 링크만 두면 됨.
- **로그인 상태 유지: 세션 쿠키** — Spring Security 기본 방식. JWT의 자체 재발급/저장 로직 구현 부담을 피함.
- **로컬 개발 DB: Supabase 개발용 프로젝트를 그대로 사용** — 로컬/배포 환경 차이 없음.
- **User에 저장할 프로필: 닉네임 + 프로필 이미지** — provider/providerUserId 외에 추가.

## 아키텍처

### 프로젝트 초기 세팅

- Gradle(Kotlin DSL 또는 Groovy — 기존 관례 없으므로 Groovy 채택, IntelliJ 기본값과 호환성 좋음), Spring Boot 3.x, Java 21
- 의존성: `spring-boot-starter-web`, `spring-boot-starter-security`,
  `spring-boot-starter-oauth2-client`, `spring-boot-starter-data-jpa`,
  `postgresql` 드라이버
- 패키지 구조 (CLAUDE.md 규칙): `controller` / `service` / `entity` /
  `repository` — 이번 스펙에서는 `config` 패키지도 추가 (SecurityConfig 등
  스프링 설정 클래스용)
- 기존 `src/Main.java`(IntelliJ 템플릿 예제)는 삭제하고
  `src/main/java/...` Gradle 표준 레이아웃으로 전환

### 인증 흐름

1. 프론트: `<a href="{BACKEND_URL}/oauth2/authorization/google">` (카카오도 동일 패턴)
2. 백엔드 → provider 리다이렉트 → 사용자 동의 → provider가
   `/login/oauth2/code/{provider}`로 콜백
3. `CustomOAuth2UserService.loadUser()`가 provider별 attribute를 파싱해
   `User`를 upsert (provider + providerUserId로 조회 → 없으면 생성, 있으면
   닉네임/프로필이미지 갱신)
4. 로그인 성공 핸들러가 세션 쿠키를 발급하고 프론트 URL로 리다이렉트
5. 프론트: 페이지 로드 시 `GET /api/auth/me` 호출 (`credentials: 'include'`)로 로그인 여부 확인 — 401이면 비로그인
6. 로그아웃: `POST /api/auth/logout` → 세션 무효화, 쿠키 만료

### Kakao 커스텀 provider 설정

Kakao는 Spring Security의 기본 지원 provider 목록(Google 등)에 없으므로
`application.yml`에 provider 상세(authorization-uri, token-uri,
user-info-uri, user-name-attribute: `id`)를 직접 명시해야 한다.

### 컴포넌트

- `SecurityConfig` (`config` 패키지) — `oauth2Login()` 설정, CORS
  (`allowedOrigins=FRONTEND_URL`, `allowCredentials=true`), 세션 기반
  인증, `/api/auth/**` 외 엔드포인트는 이후 스펙에서 인증 요구 여부 결정
- `CustomOAuth2UserService` (`service` 패키지) — provider별 속성 파싱
  (Google: `sub/email/name/picture`, Kakao:
  `id/kakao_account.profile.nickname/profile_image_url`) + User upsert.
  가장 중요한 테스트 대상.
- `OAuth2LoginSuccessHandler` — 로그인 성공 후 프론트 URL로 리다이렉트
- `OAuth2LoginFailureHandler` — 로그인 실패(사용자 거부 등) 시 프론트
  에러 페이지로 리다이렉트
- `AuthController` (`controller` 패키지) — `GET /api/auth/me`,
  `POST /api/auth/logout`만 담당. 실제 로그인 리다이렉트/콜백은 Spring
  Security가 처리하므로 컨트롤러에 로그인 로직 없음.
- `User` 엔티티/`UserRepository`

### User 엔티티

```
User
- id (PK)
- provider (VARCHAR, 예: "google" | "kakao")
- providerUserId (VARCHAR)
- nickname (VARCHAR, nullable)
- profileImageUrl (VARCHAR, nullable)
- createdAt (TIMESTAMP)

UNIQUE(provider, providerUserId)
```

### 환경변수 / 설정

`pipeline-test/.env` 패턴처럼 시크릿은 커밋되는 `application.yml`에
직접 쓰지 않고 환경변수로 분리:

- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`
- `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`
- `FRONTEND_URL` (리다이렉트 대상, 로컬: `http://localhost:3000`)
- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
  (Spring Boot가 자동 바인딩하는 표준 이름 — `application.yml`에는 아예 안 적음)

`.gitignore`에 로컬 시크릿 파일(`.env`, `application-local.yml` 등) 등록.

### 사용자가 직접 해야 하는 일 (Claude가 대신 할 수 없음)

1. **Google Cloud Console**: OAuth 동의 화면 설정 + OAuth 클라이언트 ID
   생성. Redirect URI: `http://localhost:8080/login/oauth2/code/google`
2. **Kakao Developers**: 앱 생성 + 카카오 로그인 활성화. Redirect URI:
   `http://localhost:8080/login/oauth2/code/kakao`. 동의 항목에서
   닉네임/프로필이미지는 필수, 이메일은 선택으로 둘 것(카카오 이메일
   동의는 비즈 앱 심사가 필요해 개인 개발 단계에서 막힐 수 있음)
3. 위에서 발급받은 client-id/secret 4개 값을 로컬 환경변수 또는
   `.env`로 Claude에게 전달 (또는 직접 `application-local.yml`에 입력)

## 에러 처리

- OAuth 실패(사용자 거부, provider 오류) → `OAuth2LoginFailureHandler`가
  `FRONTEND_URL/login?error=oauth_failed`로 리다이렉트
- Kakao 이메일 미동의 → User 스키마에 애초에 email 필드가 없으므로
  (provider+providerUserId만 식별자로 씀) 이메일 동의 여부는 로그인에
  영향을 주지 않음
- `/api/auth/me` 미인증 접근 → 401 (인증된 사용자 정보 없음을 프론트가
  구분할 수 있어야 하므로 예외 처리 필요)

## 테스트 전략

- 단위: `CustomOAuth2UserService`의 provider별 attribute 파싱 로직 —
  Google/Kakao 각각의 mock attribute map으로 User 필드 매핑 검증
- 통합: `@WithMockUser` 또는 Spring Security Test로 `/api/auth/me`의
  인증/미인증 분기(401 vs 200) 검증
- 수동: 실제 브라우저로 Google/Kakao 로그인 전체 왕복 확인 (OAuth
  왕복 자체는 자동화 대상에서 제외)

## 커밋 전 확인

CLAUDE.md 규칙대로 `./gradlew build` 실행.
