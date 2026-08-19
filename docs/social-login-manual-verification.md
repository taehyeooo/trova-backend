# 소셜 로그인 수동 검증 가이드

자동화 테스트로 커버되지 않는 실제 OAuth 왕복은 아래 순서로 브라우저에서
직접 확인한다. (스펙의 테스트 전략에서 OAuth 왕복 자체는 수동 검증
대상으로 명시함)

## 사전 준비

1. Google Cloud Console에서 OAuth 클라이언트 생성, Redirect URI:
   `http://localhost:8080/login/oauth2/code/google`
2. Kakao Developers에서 앱 생성 + 카카오 로그인 활성화, Redirect URI:
   `http://localhost:8080/login/oauth2/code/kakao`
3. 아래 환경변수를 설정하고 백엔드 실행:

   ```bash
   export GOOGLE_CLIENT_ID=...
   export GOOGLE_CLIENT_SECRET=...
   export KAKAO_CLIENT_ID=...
   export KAKAO_CLIENT_SECRET=...
   export FRONTEND_URL=http://localhost:3000
   export SPRING_DATASOURCE_URL=jdbc:postgresql://<supabase-host>:5432/postgres
   export SPRING_DATASOURCE_USERNAME=...
   export SPRING_DATASOURCE_PASSWORD=...
   ./gradlew bootRun
   ```

## 검증 체크리스트

- [ ] 브라우저에서 `http://localhost:8080/oauth2/authorization/google` 접속 → 구글 동의 화면 → 로그인 후 `FRONTEND_URL`(`http://localhost:3000`)로 리다이렉트되는지 확인
- [ ] devtools > Application > Cookies에서 세션 쿠키(`JSESSIONID`)가 발급됐는지 확인
- [ ] 같은 브라우저 세션에서 `http://localhost:8080/api/auth/me` 접속 → 200 + 로그인한 사용자의 nickname/profileImageUrl JSON 확인
- [ ] Supabase 대시보드에서 `users` 테이블에 실제로 행이 생성됐는지 확인
- [ ] `http://localhost:8080/oauth2/authorization/kakao`로 위 과정을 동일하게 반복
- [ ] 시크릿 브라우저 창(비로그인 상태)에서 `http://localhost:8080/api/auth/me` 접속 → 401 확인
- [ ] 로그인 상태에서 `curl -X POST -b <저장된 쿠키> http://localhost:8080/api/auth/logout` 호출 후 `/api/auth/me`가 다시 401을 반환하는지 확인
- [ ] 구글 로그인 화면에서 취소(거부)를 눌렀을 때 `FRONTEND_URL/login?error=oauth_failed`로 리다이렉트되는지 확인
