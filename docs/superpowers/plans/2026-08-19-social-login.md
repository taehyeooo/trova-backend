# 소셜 로그인 (Google/Kakao) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring Boot 프로젝트를 초기 세팅하고, Google/Kakao 소셜 로그인(세션 쿠키 기반, 백엔드 주도 OAuth2)을 구현한다.

**Architecture:** Spring Security OAuth2 Client가 OAuth 리다이렉트/콜백/토큰 교환을 전담한다. `CustomOAuth2UserService`가 로그인 시점에 provider 응답을 파싱해 `User`를 upsert하고, 세션 쿠키로 로그인 상태를 유지한다. 프론트(Next.js, 별도 레포)는 `/oauth2/authorization/{provider}` 링크와 `GET /api/auth/me` 폴링만으로 연동한다.

**Tech Stack:** Spring Boot 3.x (Gradle, Groovy DSL), Java 21, Spring Security + OAuth2 Client, Spring Data JPA, PostgreSQL(Supabase), H2(테스트 전용)

**Spec:** `docs/superpowers/specs/2026-08-19-social-login-design.md`

## Global Constraints

- 패키지 구조: `controller`/`service`/`entity`/`repository` 기본 구조 + 이번 기능 전용 `config`/`security` 패키지 추가 (CLAUDE.md 코드 스타일)
- 커밋 메시지는 `타입: 내용` 형식만 사용. AI 서명/트레일러("Generated with Claude Code", "Co-Authored-By: Claude", 🤖 등) 절대 추가 금지 (CLAUDE.md Git 컨벤션) — 아래 모든 커밋 단계에 적용됨
- 커밋 전 `./gradlew build` 실행 (CLAUDE.md)
- 유료 API/신규 외부 서비스 도입 금지 — 이 기능은 Google/Kakao OAuth(무료), 기존 Supabase(무료 티어) 범위 내에서만 진행 (CLAUDE.md 비용 원칙)
- 시크릿(client-id/secret, DB 접속정보)은 커밋되는 파일에 직접 쓰지 않고 환경변수로 주입 (스펙)
- 프론트엔드(Next.js) 코드는 이 레포에 포함하지 않음 — 이 플랜은 백엔드 전용이며, 로그인 버튼 등 프론트 연동은 별도 레포 작업 (CLAUDE.md)

---

### Task 1: Spring Boot 프로젝트 초기 세팅

**Files:**
- Create: `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`
- Create: `src/main/java/com/trova/backend/TrovaBackendApplication.java` (Initializr 생성)
- Delete: `src/Main.java` (IntelliJ 템플릿 placeholder)
- Delete: `src/main/resources/application.properties` (이후 Task에서 `application.yml`로 대체)
- Modify: `.gitignore`

**Interfaces:**
- Produces: Gradle 표준 레이아웃(`src/main/java`, `src/main/resources`, `src/test/java`, `src/test/resources`), `./gradlew build` 커맨드, base package `com.trova.backend`

- [ ] **Step 1: Spring Initializr로 프로젝트 생성**

```bash
curl -s https://start.spring.io/starter.zip \
  -d type=gradle-project \
  -d language=java \
  -d javaVersion=21 \
  -d groupId=com.trova \
  -d artifactId=trova-backend \
  -d name=trova-backend \
  -d packageName=com.trova.backend \
  -d dependencies=web,security,oauth2-client,data-jpa,postgresql \
  -o /tmp/trova-backend-init.zip
```

- [ ] **Step 2: 압축 해제 후 필요한 파일만 레포로 복사**

```bash
rm -rf /tmp/trova-backend-init-extracted
mkdir -p /tmp/trova-backend-init-extracted
unzip -q /tmp/trova-backend-init.zip -d /tmp/trova-backend-init-extracted

rm -f src/Main.java
cp /tmp/trova-backend-init-extracted/build.gradle .
cp /tmp/trova-backend-init-extracted/settings.gradle .
cp /tmp/trova-backend-init-extracted/gradlew .
cp /tmp/trova-backend-init-extracted/gradlew.bat .
cp -r /tmp/trova-backend-init-extracted/gradle .
cp -r /tmp/trova-backend-init-extracted/src .
chmod +x gradlew
rm -f src/main/resources/application.properties
```

- [ ] **Step 3: `build.gradle`의 `dependencies` 블록에 테스트 전용 의존성 2줄 추가**

```groovy
testImplementation 'org.springframework.security:spring-security-test'
testRuntimeOnly 'com.h2database:h2'
```

(기존 `testImplementation 'org.springframework.boot:spring-boot-starter-test'` 등 Initializr가 생성한 줄들은 그대로 둔다.)

- [ ] **Step 4: `.gitignore`에 Gradle 빌드 산출물 무시 규칙 추가**

`.gitignore` 끝에 추가:

```
### Gradle ###
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 5: 빌드 검증**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` (Initializr가 생성한 기본 컨텍스트 로딩 테스트 통과)

- [ ] **Step 6: 커밋**

```bash
git add -A src build.gradle settings.gradle gradlew gradlew.bat gradle .gitignore
git commit -m "feat: Spring Boot 프로젝트 초기 세팅"
```

---

### Task 2: User 엔티티 및 리포지토리

**Files:**
- Create: `src/main/java/com/trova/backend/entity/User.java`
- Create: `src/main/java/com/trova/backend/repository/UserRepository.java`
- Test: `src/test/java/com/trova/backend/repository/UserRepositoryTest.java`

**Interfaces:**
- Produces: `User(String provider, String providerUserId, String nickname, String profileImageUrl)` 생성자, `User#getId()/getProvider()/getProviderUserId()/getNickname()/getProfileImageUrl()/getCreatedAt()`, `User#updateProfile(String nickname, String profileImageUrl)`, `UserRepository#findByProviderAndProviderUserId(String, String) -> Optional<User>`

- [ ] **Step 1: 실패하는 리포지토리 테스트 작성**

```java
package com.trova.backend.repository;

import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void provider와_providerUserId로_사용자를_조회한다() {
        User user = new User("google", "1234567890", "테스트유저", "https://example.com/photo.jpg");
        userRepository.save(user);

        Optional<User> found = userRepository.findByProviderAndProviderUserId("google", "1234567890");

        assertThat(found).isPresent();
        assertThat(found.get().getNickname()).isEqualTo("테스트유저");
    }

    @Test
    void 존재하지_않는_사용자는_빈_Optional을_반환한다() {
        Optional<User> found = userRepository.findByProviderAndProviderUserId("google", "nope");

        assertThat(found).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.repository.UserRepositoryTest"`
Expected: FAIL (컴파일 에러 — `User`, `UserRepository` 클래스 없음)

- [ ] **Step 3: `User` 엔티티 작성**

```java
package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_user_id"}))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    private String nickname;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected User() {
    }

    public User(String provider, String providerUserId, String nickname, String profileImageUrl) {
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void updateProfile(String nickname, String profileImageUrl) {
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
    }
}
```

(테이블명은 `user`가 아닌 `users`로 지정 — PostgreSQL 예약어 충돌 방지)

- [ ] **Step 4: `UserRepository` 작성**

```java
package com.trova.backend.repository;

import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByProviderAndProviderUserId(String provider, String providerUserId);
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.repository.UserRepositoryTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/entity/User.java \
        src/main/java/com/trova/backend/repository/UserRepository.java \
        src/test/java/com/trova/backend/repository/UserRepositoryTest.java
git commit -m "feat: User 엔티티 및 리포지토리 추가"
```

---

### Task 3: OAuth2UserInfo (provider별 속성 파싱)

**Files:**
- Create: `src/main/java/com/trova/backend/security/OAuth2UserInfo.java`
- Test: `src/test/java/com/trova/backend/security/OAuth2UserInfoTest.java`

**Interfaces:**
- Consumes: 없음 (순수 값 객체, Spring 컨텍스트 불필요)
- Produces: `record OAuth2UserInfo(String provider, String providerUserId, String nickname, String profileImageUrl)`, 정적 팩토리 `OAuth2UserInfo.of(String registrationId, Map<String, Object> attributes) -> OAuth2UserInfo`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.trova.backend.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OAuth2UserInfoTest {

    @Test
    void google_속성에서_사용자_정보를_추출한다() {
        Map<String, Object> attributes = Map.of(
                "sub", "1234567890",
                "email", "test@example.com",
                "name", "테스트유저",
                "picture", "https://example.com/photo.jpg"
        );

        OAuth2UserInfo info = OAuth2UserInfo.of("google", attributes);

        assertThat(info.provider()).isEqualTo("google");
        assertThat(info.providerUserId()).isEqualTo("1234567890");
        assertThat(info.nickname()).isEqualTo("테스트유저");
        assertThat(info.profileImageUrl()).isEqualTo("https://example.com/photo.jpg");
    }

    @Test
    void kakao_속성에서_사용자_정보를_추출한다() {
        Map<String, Object> attributes = Map.of(
                "id", 123456789L,
                "kakao_account", Map.of(
                        "profile", Map.of(
                                "nickname", "테스트유저",
                                "profile_image_url", "https://example.com/photo.jpg"
                        )
                )
        );

        OAuth2UserInfo info = OAuth2UserInfo.of("kakao", attributes);

        assertThat(info.provider()).isEqualTo("kakao");
        assertThat(info.providerUserId()).isEqualTo("123456789");
        assertThat(info.nickname()).isEqualTo("테스트유저");
        assertThat(info.profileImageUrl()).isEqualTo("https://example.com/photo.jpg");
    }

    @Test
    void 지원하지_않는_provider면_예외를_던진다() {
        assertThrows(IllegalArgumentException.class, () -> OAuth2UserInfo.of("naver", Map.of()));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.security.OAuth2UserInfoTest"`
Expected: FAIL (컴파일 에러 — `OAuth2UserInfo` 클래스 없음)

- [ ] **Step 3: `OAuth2UserInfo` 구현**

```java
package com.trova.backend.security;

import java.util.Map;

public record OAuth2UserInfo(
        String provider,
        String providerUserId,
        String nickname,
        String profileImageUrl
) {

    public static OAuth2UserInfo of(String registrationId, Map<String, Object> attributes) {
        return switch (registrationId) {
            case "google" -> ofGoogle(attributes);
            case "kakao" -> ofKakao(attributes);
            default -> throw new IllegalArgumentException("지원하지 않는 로그인 provider: " + registrationId);
        };
    }

    private static OAuth2UserInfo ofGoogle(Map<String, Object> attributes) {
        return new OAuth2UserInfo(
                "google",
                String.valueOf(attributes.get("sub")),
                (String) attributes.get("name"),
                (String) attributes.get("picture")
        );
    }

    @SuppressWarnings("unchecked")
    private static OAuth2UserInfo ofKakao(Map<String, Object> attributes) {
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");
        return new OAuth2UserInfo(
                "kakao",
                String.valueOf(attributes.get("id")),
                (String) profile.get("nickname"),
                (String) profile.get("profile_image_url")
        );
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.security.OAuth2UserInfoTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/security/OAuth2UserInfo.java \
        src/test/java/com/trova/backend/security/OAuth2UserInfoTest.java
git commit -m "feat: provider별 사용자 정보 파싱(OAuth2UserInfo) 추가"
```

---

### Task 4: UserUpsertService

**Files:**
- Create: `src/main/java/com/trova/backend/service/UserUpsertService.java`
- Test: `src/test/java/com/trova/backend/service/UserUpsertServiceTest.java`

**Interfaces:**
- Consumes: `OAuth2UserInfo`(Task 3), `UserRepository#findByProviderAndProviderUserId`/`#save`(Task 2)
- Produces: `UserUpsertService#upsert(OAuth2UserInfo info) -> User`

- [ ] **Step 1: 실패하는 테스트 작성 (Mockito로 UserRepository 목킹)**

```java
package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.OAuth2UserInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserUpsertServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserUpsertService userUpsertService;

    @Test
    void 신규_사용자면_새로_저장한다() {
        OAuth2UserInfo info = new OAuth2UserInfo("google", "1234567890", "테스트유저", "https://example.com/photo.jpg");
        when(userRepository.findByProviderAndProviderUserId("google", "1234567890"))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User result = userUpsertService.upsert(info);

        assertThat(result.getProvider()).isEqualTo("google");
        assertThat(result.getNickname()).isEqualTo("테스트유저");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void 기존_사용자면_프로필만_갱신한다() {
        User existing = new User("google", "1234567890", "옛날닉네임", "https://example.com/old.jpg");
        OAuth2UserInfo info = new OAuth2UserInfo("google", "1234567890", "새닉네임", "https://example.com/new.jpg");
        when(userRepository.findByProviderAndProviderUserId("google", "1234567890"))
                .thenReturn(Optional.of(existing));

        User result = userUpsertService.upsert(info);

        assertThat(result.getNickname()).isEqualTo("새닉네임");
        assertThat(result.getProfileImageUrl()).isEqualTo("https://example.com/new.jpg");
        verify(userRepository, never()).save(any(User.class));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.service.UserUpsertServiceTest"`
Expected: FAIL (컴파일 에러 — `UserUpsertService` 클래스 없음)

- [ ] **Step 3: `UserUpsertService` 구현**

```java
package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.OAuth2UserInfo;
import org.springframework.stereotype.Service;

@Service
public class UserUpsertService {

    private final UserRepository userRepository;

    public UserUpsertService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User upsert(OAuth2UserInfo info) {
        return userRepository.findByProviderAndProviderUserId(info.provider(), info.providerUserId())
                .map(existing -> {
                    existing.updateProfile(info.nickname(), info.profileImageUrl());
                    return existing;
                })
                .orElseGet(() -> userRepository.save(
                        new User(info.provider(), info.providerUserId(), info.nickname(), info.profileImageUrl())
                ));
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.service.UserUpsertServiceTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/service/UserUpsertService.java \
        src/test/java/com/trova/backend/service/UserUpsertServiceTest.java
git commit -m "feat: UserUpsertService 추가"
```

---

### Task 5: CustomOAuth2UserService

**Files:**
- Create: `src/main/java/com/trova/backend/security/CustomOAuth2UserService.java`

**Interfaces:**
- Consumes: `OAuth2UserInfo.of(...)`(Task 3), `UserUpsertService#upsert(...)`(Task 4)
- Produces: Spring Security `OAuth2UserService<OAuth2UserRequest, OAuth2User>` 빈 — Task 6의 `SecurityConfig`가 `userInfoEndpoint().userService(...)`로 등록

**참고:** 이 클래스는 `DefaultOAuth2UserService#loadUser()`가 provider의 실제 userinfo 엔드포인트로 네트워크 호출을 하는 것을 감싸는 얇은 래퍼라 격리된 단위 테스트를 작성하지 않는다 (스펙의 테스트 전략에서 OAuth 왕복 자체는 수동 검증 대상으로 명시함). 핵심 파싱/upsert 로직은 이미 Task 3/4에서 단위 테스트로 검증됐다.

- [ ] **Step 1: `CustomOAuth2UserService` 구현**

```java
package com.trova.backend.security;

import com.trova.backend.entity.User;
import com.trova.backend.service.UserUpsertService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserUpsertService userUpsertService;

    public CustomOAuth2UserService(UserUpsertService userUpsertService) {
        this.userUpsertService = userUpsertService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        OAuth2UserInfo userInfo = OAuth2UserInfo.of(registrationId, oAuth2User.getAttributes());
        User user = userUpsertService.upsert(userInfo);

        String userNameAttributeName = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                oAuth2User.getAttributes(),
                userNameAttributeName
        );
    }
}
```

- [ ] **Step 2: 빌드 확인 (기존 테스트 회귀 없는지)**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/trova/backend/security/CustomOAuth2UserService.java
git commit -m "feat: CustomOAuth2UserService 추가"
```

---

### Task 6: OAuth2 클라이언트 설정 + SecurityConfig + 로그인 성공/실패 핸들러

**Files:**
- Create: `src/main/resources/application.yml`
- Create: `src/test/resources/application.yml`
- Create: `src/main/java/com/trova/backend/config/SecurityConfig.java`
- Create: `src/main/java/com/trova/backend/security/OAuth2LoginSuccessHandler.java`
- Create: `src/main/java/com/trova/backend/security/OAuth2LoginFailureHandler.java`

**Interfaces:**
- Consumes: `CustomOAuth2UserService`(Task 5)
- Produces: `app.frontend-url` 프로퍼티, `/oauth2/authorization/{google|kakao}` 및 `/login/oauth2/code/{provider}` 표준 엔드포인트(Spring Security가 자동 등록), `POST /api/auth/logout`(SecurityConfig의 logout DSL로 등록 — 별도 컨트롤러 메서드 없음), `/api/**`에 대해 미인증 시 401 반환하는 인증 진입점

- [ ] **Step 1: `src/main/resources/application.yml` 작성 (시크릿은 환경변수 참조만, 값은 넣지 않음)**

```yaml
server:
  port: 8080

spring:
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: email, profile
          kakao:
            client-id: ${KAKAO_CLIENT_ID}
            client-secret: ${KAKAO_CLIENT_SECRET}
            client-authentication-method: client_secret_post
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            client-name: Kakao
            scope: profile_nickname, profile_image
        provider:
          kakao:
            authorization-uri: https://kauth.kakao.com/oauth/authorize
            token-uri: https://kauth.kakao.com/oauth/token
            user-info-uri: https://kapi.kakao.com/v2/user/me
            user-name-attribute: id

app:
  frontend-url: ${FRONTEND_URL:http://localhost:3000}
```

(`spring.datasource.*`는 의도적으로 비워둔다 — `SPRING_DATASOURCE_URL`/`SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD` 환경변수로 Spring Boot가 자동 바인딩한다.)

- [ ] **Step 2: `src/test/resources/application.yml` 작성 (테스트는 항상 격리된 H2 사용, 로컬 환경변수에 의존하지 않음)**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: test-client-id
            client-secret: test-client-secret
          kakao:
            client-id: test-client-id
            client-secret: test-client-secret
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
        provider:
          kakao:
            authorization-uri: https://kauth.kakao.com/oauth/authorize
            token-uri: https://kauth.kakao.com/oauth/token
            user-info-uri: https://kapi.kakao.com/v2/user/me
            user-name-attribute: id

app:
  frontend-url: http://localhost:3000
```

- [ ] **Step 3: `OAuth2LoginSuccessHandler` 작성**

```java
package com.trova.backend.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException, ServletException {
        response.sendRedirect(frontendUrl);
    }
}
```

- [ ] **Step 4: `OAuth2LoginFailureHandler` 작성**

```java
package com.trova.backend.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        response.sendRedirect(frontendUrl + "/login?error=oauth_failed");
    }
}
```

- [ ] **Step 5: `SecurityConfig` 작성**

```java
package com.trova.backend.config;

import com.trova.backend.security.CustomOAuth2UserService;
import com.trova.backend.security.OAuth2LoginFailureHandler;
import com.trova.backend.security.OAuth2LoginSuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                           OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
                           OAuth2LoginFailureHandler oAuth2LoginFailureHandler) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.oAuth2LoginFailureHandler = oAuth2LoginFailureHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 세션 쿠키 기반 API + 별도 origin 프론트 조합이라 CSRF는 우선 비활성화.
                // 상태 변경 엔드포인트(POST /api/shares 등)를 붙이는 다음 스펙에서 재검토 필요.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler(oAuth2LoginFailureHandler)
                )
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.OK.value()))
                )
                .exceptionHandling(exception -> exception
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/**")
                        )
                );

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(frontendUrl));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        configuration.setAllowCredentials(true);
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
```

- [ ] **Step 6: 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/application.yml src/test/resources/application.yml \
        src/main/java/com/trova/backend/config/SecurityConfig.java \
        src/main/java/com/trova/backend/security/OAuth2LoginSuccessHandler.java \
        src/main/java/com/trova/backend/security/OAuth2LoginFailureHandler.java
git commit -m "feat: OAuth2 로그인 설정 및 SecurityConfig 추가"
```

---

### Task 7: AuthController (`/api/auth/me`)

**Files:**
- Create: `src/main/java/com/trova/backend/controller/AuthController.java`
- Test: `src/test/java/com/trova/backend/controller/AuthControllerTest.java`

**Interfaces:**
- Consumes: `UserRepository#findByProviderAndProviderUserId`(Task 2), `OAuth2UserInfo.of(...)`(Task 3), Task 6의 `SecurityConfig`(인증 요구/401 처리)
- Produces: `GET /api/auth/me` → 200 + `{id, nickname, profileImageUrl}` (인증 시) / 401 (미인증 시, SecurityConfig가 처리하므로 컨트롤러 진입 전에 차단됨)

**참고:** 로그아웃(`POST /api/auth/logout`)은 Task 6의 `SecurityConfig`가 `logout()` DSL로 이미 등록했으므로 이 컨트롤러에는 로그아웃 메서드가 없다.

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    @Test
    void 인증되지_않은_요청은_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 인증된_사용자는_자신의_정보를_받는다() throws Exception {
        userRepository.save(new User("google", "1234567890", "테스트유저", "https://example.com/photo.jpg"));

        mockMvc.perform(get("/api/auth/me").with(oauth2Login()
                        .clientRegistration(googleRegistration())
                        .attributes(attrs -> {
                            attrs.put("sub", "1234567890");
                            attrs.put("name", "테스트유저");
                            attrs.put("picture", "https://example.com/photo.jpg");
                        })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("테스트유저"));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.AuthControllerTest"`
Expected: FAIL (컴파일 에러 — `AuthController` 클래스 없음, 또는 `/api/auth/me`가 아직 없어 404)

- [ ] **Step 3: `AuthController` 구현**

```java
package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.OAuth2UserInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final UserRepository userRepository;

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<MeResponse> me(OAuth2AuthenticationToken authentication) {
        OAuth2UserInfo info = OAuth2UserInfo.of(
                authentication.getAuthorizedClientRegistrationId(),
                authentication.getPrincipal().getAttributes()
        );

        User user = userRepository.findByProviderAndProviderUserId(info.provider(), info.providerUserId())
                .orElseThrow(() -> new IllegalStateException("인증된 사용자를 찾을 수 없습니다: " + info));

        return ResponseEntity.ok(new MeResponse(user.getId(), user.getNickname(), user.getProfileImageUrl()));
    }

    public record MeResponse(Long id, String nickname, String profileImageUrl) {
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.AuthControllerTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: 전체 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/AuthController.java \
        src/test/java/com/trova/backend/controller/AuthControllerTest.java
git commit -m "feat: AuthController(/api/auth/me) 추가"
```

---

### Task 8: 수동 E2E 검증 가이드 작성 + 진행 문서 갱신

**Files:**
- Create: `docs/social-login-manual-verification.md`
- Modify: `TODO.md`, `PROGRESS.md` (레포에 커밋되지 않는 내부 문서 — `.gitignore`에 등록되어 있음, 로컬 갱신만)

**Interfaces:**
- 없음 (문서 전용 태스크)

- [ ] **Step 1: 수동 검증 가이드 작성**

```markdown
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
```

- [ ] **Step 2: `TODO.md`의 "2단계 착수 전 확인"과 "2단계: Spring Boot API" 항목 갱신**

- "로그인 방식 결정" 항목을 `[x]`로 체크하고 결정 내용(세션 쿠키, 백엔드 주도 OAuth2, Google+Kakao) 한 줄 추가
- "프로젝트 초기 세팅 (Gradle, 기본 구조)" 항목을 `[x]`로 체크
- 새 항목 추가: `[ ] Google/Kakao 소셜 로그인 구현 (Spring Security OAuth2 Client, 세션 쿠키) — docs/superpowers/plans/2026-08-19-social-login.md 참고`, 이번 태스크들이 끝나면 `[x]`로 체크

- [ ] **Step 3: `PROGRESS.md`에 작업 로그 추가**

PROGRESS.md 상단 로그 형식(한 것 / 왜 이렇게 했는지 / 막힌 것)에 맞춰 이번 소셜 로그인 구현 세션 내용을 한 항목으로 추가한다.

- [ ] **Step 4: 커밋 (문서 중 `.gitignore`에 없는 파일만 대상)**

```bash
git add docs/social-login-manual-verification.md
git commit -m "docs: 소셜 로그인 수동 검증 가이드 추가"
```

(`TODO.md`, `PROGRESS.md`는 `.gitignore`에 등록되어 있어 커밋되지 않는다 — 로컬 파일만 갱신하면 된다.)

---

## 실행 후 남는 일 (이 플랜 범위 밖)

- 실제 Google/Kakao 클라이언트 ID/Secret 발급 및 환경변수 설정은 사용자가 직접 해야 함 (아래 "당장 사용자가 해야 할 일" 참고)
- 프론트(Next.js)에서 로그인 버튼을 `/oauth2/authorization/{provider}` 링크로 연결하는 작업은 별도 레포(`trova-frontend`) 작업
- `SavedPlace`/`ProcessingJob` 엔티티, `/api/shares` 등 나머지 엔드포인트는 별도 스펙/플랜
