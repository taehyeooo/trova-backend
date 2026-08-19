# SavedPlace/ProcessingJob 파이프라인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/shares`로 URL을 받아 검증된 Python 파이프라인을 서브프로세스로 돌리고, 추출된 장소를 카카오 지오코딩까지 거쳐 `SavedPlace`로 저장하는 비동기 파이프라인 + 조회/삭제 API를 구현한다.

**Architecture:** `ProcessingJob`(URL 단위 작업, PENDING→PROCESSING→DONE/FAILED) 1건이 `SavedPlace`(장소 단위 결과) N건을 낳는다. `PipelineRunner`가 `python3 run_pipeline.py`를 서브프로세스로 실행해 JSON을 파싱하고, `KakaoGeocodingService`가 이름+지역을 좌표로 변환한다. 전체 흐름은 `@Async` + bounded thread pool로 백그라운드 실행.

**Tech Stack:** Spring Boot 4.1.0(Java 21), Spring Data JPA, `ProcessBuilder`(서브프로세스), Spring `RestClient`(카카오 API 호출), Jackson(JSON 파싱)

**Spec:** `docs/superpowers/specs/2026-08-19-saved-places-pipeline-design.md`

## Global Constraints

- 패키지 구조: 기존 `controller`/`service`/`entity`/`repository`/`config`/`security` + 이번 스펙 전용 `pipeline`/`geocoding` 패키지 추가, 전부 `com.trova.backend` 아래
- 커밋 메시지는 `타입: 내용` 형식만 사용, AI 서명/트레일러 절대 금지 — 모든 커밋에 적용
- 커밋 전 `./gradlew build` 실행 (전체 빌드, 태스크 텍스트가 부분 테스트만 보여줘도 예외 없이 적용 — 소셜 로그인 플랜에서 이미 확정한 규칙)
- 유료 API 신규 도입 금지 — 이 기능은 Gemini(무료 티어, 이미 사용 중), 카카오 로컬 API(무료 쿼터, 로그인과 같은 키 재사용) 범위 내
- Spring Boot 4.1.0 / Spring Security 7.1.0은 학습 데이터 시점 이후 버전 — 브리핑의 API가 컴파일 안 되면 절대 추측으로 고치지 말고 실제 리졸브된 jar(`./gradlew dependencies`, `javap`, 캐시 jar 압축 해제)로 검증 후 최소한만 수정. 이전 태스크들에서 이 검증을 건너뛰어 리뷰에서 되돌려진 사례가 여러 번 있었음
- DB 쓰기가 있는 비동기/트랜잭션 메서드는 반드시 `@Transactional` 명시 — 소셜 로그인 최종 리뷰에서 이걸 빠뜨려서 프로필 갱신이 조용히 무시되는 Critical 버그가 났던 전례가 있음(`UserUpsertService`)

---

### Task 1: ProcessingJob/SavedPlace 엔티티 + 리포지토리

**Files:**
- Create: `src/main/java/com/trova/backend/entity/JobStatus.java`
- Create: `src/main/java/com/trova/backend/entity/SourcePlatform.java`
- Create: `src/main/java/com/trova/backend/entity/ProcessingJob.java`
- Create: `src/main/java/com/trova/backend/entity/SavedPlace.java`
- Create: `src/main/java/com/trova/backend/repository/ProcessingJobRepository.java`
- Create: `src/main/java/com/trova/backend/repository/SavedPlaceRepository.java`
- Test: `src/test/java/com/trova/backend/repository/ProcessingJobRepositoryTest.java`
- Test: `src/test/java/com/trova/backend/repository/SavedPlaceRepositoryTest.java`

**Interfaces:**
- Produces: `JobStatus{PENDING,PROCESSING,DONE,FAILED}`, `SourcePlatform{INSTAGRAM,YOUTUBE}`, `ProcessingJob(User user, String sourceUrl, SourcePlatform sourcePlatform)` 생성자 + `markProcessing()`/`markDone()`/`markFailed(String)` + getter들, `SavedPlace(ProcessingJob job, User user, String placeName, String region, String category, Double latitude, Double longitude)` 생성자(sourceUrl/sourcePlatform은 job에서 복사) + getter들, `ProcessingJobRepository#findByUserOrderByCreatedAtDesc(User) -> List<ProcessingJob>`, `ProcessingJobRepository#findByUserAndStatusIn(User, List<JobStatus>) -> List<ProcessingJob>`, `SavedPlaceRepository#findByUserOrderByCreatedAtDesc(User) -> List<SavedPlace>`, `SavedPlaceRepository#findByIdAndUser(Long, User) -> Optional<SavedPlace>`

- [ ] **Step 1: 실패하는 리포지토리 테스트 작성**

```java
// src/test/java/com/trova/backend/repository/ProcessingJobRepositoryTest.java
package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProcessingJobRepositoryTest {

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 사용자의_작업을_최신순으로_조회한다() {
        User user = userRepository.save(new User("google", "1", "테스트유저", null));
        ProcessingJob older = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/a", SourcePlatform.YOUTUBE));
        ProcessingJob newer = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/b", SourcePlatform.YOUTUBE));

        List<ProcessingJob> jobs = processingJobRepository.findByUserOrderByCreatedAtDesc(user);

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).getId()).isEqualTo(newer.getId());
        assertThat(jobs.get(1).getId()).isEqualTo(older.getId());
    }

    @Test
    void PENDING과_PROCESSING만_조회한다() {
        User user = userRepository.save(new User("google", "2", "테스트유저2", null));
        ProcessingJob pending = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/c", SourcePlatform.YOUTUBE));
        ProcessingJob done = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/d", SourcePlatform.YOUTUBE));
        done.markProcessing();
        done.markDone();
        processingJobRepository.save(done);

        List<ProcessingJob> active = processingJobRepository.findByUserAndStatusIn(
                user, List.of(JobStatus.PENDING, JobStatus.PROCESSING));

        assertThat(active).extracting(ProcessingJob::getId).containsExactly(pending.getId());
    }
}
```

```java
// src/test/java/com/trova/backend/repository/SavedPlaceRepositoryTest.java
package com.trova.backend.repository;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class SavedPlaceRepositoryTest {

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 본인_소유_장소만_id로_조회된다() {
        User owner = userRepository.save(new User("google", "3", "주인", null));
        User other = userRepository.save(new User("google", "4", "남", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(owner, "https://youtu.be/e", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(
                new SavedPlace(job, owner, "해운대", "부산", "attraction", 35.16, 129.16));

        Optional<SavedPlace> byOwner = savedPlaceRepository.findByIdAndUser(place.getId(), owner);
        Optional<SavedPlace> byOther = savedPlaceRepository.findByIdAndUser(place.getId(), other);

        assertThat(byOwner).isPresent();
        assertThat(byOther).isEmpty();
    }

    @Test
    void 사용자의_장소를_최신순으로_조회한다() {
        User user = userRepository.save(new User("google", "5", "유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/f", SourcePlatform.YOUTUBE));
        SavedPlace first = savedPlaceRepository.save(
                new SavedPlace(job, user, "장소1", null, "cafe", null, null));
        SavedPlace second = savedPlaceRepository.save(
                new SavedPlace(job, user, "장소2", null, "cafe", null, null));

        List<SavedPlace> places = savedPlaceRepository.findByUserOrderByCreatedAtDesc(user);

        assertThat(places).extracting(SavedPlace::getId)
                .containsExactly(second.getId(), first.getId());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.repository.ProcessingJobRepositoryTest" --tests "com.trova.backend.repository.SavedPlaceRepositoryTest"`
Expected: FAIL (컴파일 에러 — 엔티티/리포지토리 없음)

- [ ] **Step 3: enum 작성**

```java
// src/main/java/com/trova/backend/entity/JobStatus.java
package com.trova.backend.entity;

public enum JobStatus {
    PENDING, PROCESSING, DONE, FAILED
}
```

```java
// src/main/java/com/trova/backend/entity/SourcePlatform.java
package com.trova.backend.entity;

public enum SourcePlatform {
    INSTAGRAM, YOUTUBE
}
```

- [ ] **Step 4: `ProcessingJob` 엔티티 작성**

```java
// src/main/java/com/trova/backend/entity/ProcessingJob.java
package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "processing_jobs")
public class ProcessingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_platform", nullable = false)
    private SourcePlatform sourcePlatform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProcessingJob() {
    }

    public ProcessingJob(User user, String sourceUrl, SourcePlatform sourcePlatform) {
        this.user = user;
        this.sourceUrl = sourceUrl;
        this.sourcePlatform = sourcePlatform;
        this.status = JobStatus.PENDING;
        this.retryCount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void markProcessing() {
        this.status = JobStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    public void markDone() {
        this.status = JobStatus.DONE;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.retryCount += 1;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getSourceUrl() { return sourceUrl; }
    public SourcePlatform getSourcePlatform() { return sourcePlatform; }
    public JobStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public int getRetryCount() { return retryCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 5: `SavedPlace` 엔티티 작성**

```java
// src/main/java/com/trova/backend/entity/SavedPlace.java
package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "saved_places")
public class SavedPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "processing_job_id", nullable = false)
    private ProcessingJob processingJob;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "place_name", nullable = false)
    private String placeName;

    private String region;

    private String category;

    private Double latitude;

    private Double longitude;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_platform", nullable = false)
    private SourcePlatform sourcePlatform;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected SavedPlace() {
    }

    public SavedPlace(ProcessingJob processingJob, User user, String placeName, String region,
                       String category, Double latitude, Double longitude) {
        this.processingJob = processingJob;
        this.user = user;
        this.placeName = placeName;
        this.region = region;
        this.category = category;
        this.latitude = latitude;
        this.longitude = longitude;
        this.sourceUrl = processingJob.getSourceUrl();
        this.sourcePlatform = processingJob.getSourcePlatform();
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public ProcessingJob getProcessingJob() { return processingJob; }
    public User getUser() { return user; }
    public String getPlaceName() { return placeName; }
    public String getRegion() { return region; }
    public String getCategory() { return category; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getSourceUrl() { return sourceUrl; }
    public SourcePlatform getSourcePlatform() { return sourcePlatform; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 6: 리포지토리 작성**

```java
// src/main/java/com/trova/backend/repository/ProcessingJobRepository.java
package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {
    List<ProcessingJob> findByUserOrderByCreatedAtDesc(User user);
    List<ProcessingJob> findByUserAndStatusIn(User user, List<JobStatus> statuses);
}
```

```java
// src/main/java/com/trova/backend/repository/SavedPlaceRepository.java
package com.trova.backend.repository;

import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedPlaceRepository extends JpaRepository<SavedPlace, Long> {
    List<SavedPlace> findByUserOrderByCreatedAtDesc(User user);
    Optional<SavedPlace> findByIdAndUser(Long id, User user);
}
```

- [ ] **Step 7: 테스트 실행 → 통과 확인, 전체 빌드**

Run: `./gradlew test --tests "com.trova.backend.repository.ProcessingJobRepositoryTest" --tests "com.trova.backend.repository.SavedPlaceRepositoryTest"`
Expected: PASS (4 tests)

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/trova/backend/entity/JobStatus.java \
        src/main/java/com/trova/backend/entity/SourcePlatform.java \
        src/main/java/com/trova/backend/entity/ProcessingJob.java \
        src/main/java/com/trova/backend/entity/SavedPlace.java \
        src/main/java/com/trova/backend/repository/ProcessingJobRepository.java \
        src/main/java/com/trova/backend/repository/SavedPlaceRepository.java \
        src/test/java/com/trova/backend/repository/ProcessingJobRepositoryTest.java \
        src/test/java/com/trova/backend/repository/SavedPlaceRepositoryTest.java
git commit -m "feat: ProcessingJob/SavedPlace 엔티티 및 리포지토리 추가"
```

---

### Task 2: PipelineRunner (Python 서브프로세스 실행 + JSON 파싱)

**Files:**
- Create: `src/main/java/com/trova/backend/pipeline/ExtractedPlace.java`
- Create: `src/main/java/com/trova/backend/pipeline/PipelineException.java`
- Create: `src/main/java/com/trova/backend/pipeline/PipelineOutputParser.java`
- Create: `src/main/java/com/trova/backend/pipeline/PipelineRunner.java`
- Test: `src/test/java/com/trova/backend/pipeline/PipelineOutputParserTest.java`

**Interfaces:**
- Consumes: 없음 (독립 패키지)
- Produces: `record ExtractedPlace(String name, String region, String category, Double confidence)`, `PipelineOutputParser.parse(String stdout) -> List<ExtractedPlace>`, `PipelineRunner#run(String url, Long jobId) -> List<ExtractedPlace> throws PipelineException`. Task 5(`PlaceExtractionService`)가 `PipelineRunner#run`을 호출한다.

**참고:** `PipelineRunner#run`(실제 서브프로세스 실행)은 통합 성격이라 이 태스크에서 단위 테스트하지 않는다(스펙의 테스트 전략에 명시). `PipelineOutputParser`(순수 JSON 파싱 함수)만 TDD로 검증한다.

- [ ] **Step 1: 실패하는 파서 테스트 작성**

```java
// src/test/java/com/trova/backend/pipeline/PipelineOutputParserTest.java
package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineOutputParserTest {

    @Test
    void 정상_JSON_배열을_파싱한다() {
        String stdout = """
                [
                  {"name": "해운대", "region": "부산", "category": "attraction", "confidence": 0.95},
                  {"name": "송정 씨앗호떡", "region": null, "category": "restaurant", "confidence": 0.8}
                ]
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout);

        assertThat(places).hasSize(2);
        assertThat(places.get(0).name()).isEqualTo("해운대");
        assertThat(places.get(0).region()).isEqualTo("부산");
        assertThat(places.get(0).category()).isEqualTo("attraction");
        assertThat(places.get(0).confidence()).isEqualTo(0.95);
        assertThat(places.get(1).region()).isNull();
    }

    @Test
    void 빈_배열은_빈_리스트를_반환한다() {
        List<ExtractedPlace> places = PipelineOutputParser.parse("[]");
        assertThat(places).isEmpty();
    }

    @Test
    void 잘못된_JSON이면_예외를_던진다() {
        org.junit.jupiter.api.Assertions.assertThrows(
                PipelineException.class,
                () -> PipelineOutputParser.parse("이건 JSON이 아님"));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.pipeline.PipelineOutputParserTest"`
Expected: FAIL (컴파일 에러 — 클래스 없음)

- [ ] **Step 3: `ExtractedPlace`, `PipelineException` 작성**

```java
// src/main/java/com/trova/backend/pipeline/ExtractedPlace.java
package com.trova.backend.pipeline;

public record ExtractedPlace(String name, String region, String category, Double confidence) {
}
```

```java
// src/main/java/com/trova/backend/pipeline/PipelineException.java
package com.trova.backend.pipeline;

public class PipelineException extends RuntimeException {
    public PipelineException(String message) {
        super(message);
    }

    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: `PipelineOutputParser` 구현 (Jackson `ObjectMapper` 사용)**

```java
// src/main/java/com/trova/backend/pipeline/PipelineOutputParser.java
package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class PipelineOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PipelineOutputParser() {
    }

    public static List<ExtractedPlace> parse(String stdout) {
        try {
            return MAPPER.readValue(stdout, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, ExtractedPlace.class));
        } catch (Exception e) {
            throw new PipelineException("파이프라인 출력 파싱 실패: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.pipeline.PipelineOutputParserTest"`
Expected: PASS (3 tests)

- [ ] **Step 6: `PipelineRunner` 구현 (서브프로세스 실행, 테스트 없음 — 통합 성격)**

```java
// src/main/java/com/trova/backend/pipeline/PipelineRunner.java
package com.trova.backend.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class PipelineRunner {

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;

    public PipelineRunner(
            @Value("${app.pipeline.script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey
    ) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
    }

    public List<ExtractedPlace> run(String url, Long jobId) {
        Path workDir = Path.of(workDirBase, "job-" + jobId);
        ProcessBuilder builder = new ProcessBuilder("python3", scriptPath, url, workDir.toString());
        builder.environment().put("GEMINI_API_KEY", geminiApiKey);

        try {
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);

            if (!finished) {
                process.destroyForcibly();
                throw new PipelineException("파이프라인 실행 시간 초과: " + url);
            }
            if (process.exitValue() != 0) {
                throw new PipelineException("파이프라인 실행 실패(exit=" + process.exitValue() + "): " + stderr);
            }
            return PipelineOutputParser.parse(stdout);
        } catch (IOException e) {
            throw new PipelineException("파이프라인 프로세스 시작 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("파이프라인 실행 중 인터럽트: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 7: `application.yml`에 파이프라인 설정 추가**

`src/main/resources/application.yml`의 `app:` 블록에 추가:

```yaml
app:
  frontend-url: ${FRONTEND_URL:http://localhost:3000}
  pipeline:
    script-path: ${PIPELINE_SCRIPT_PATH:pipeline-test/run_pipeline.py}
    work-dir: ${PIPELINE_WORK_DIR:pipeline-test/work}
    gemini-api-key: ${GEMINI_API_KEY:}
```

`src/test/resources/application.yml`의 `app:` 블록에도 동일하게 추가하되 값은 테스트용 더미:

```yaml
app:
  frontend-url: http://localhost:3000
  pipeline:
    script-path: pipeline-test/run_pipeline.py
    work-dir: build/tmp/pipeline-test-work
    gemini-api-key: test-key
```

- [ ] **Step 8: 전체 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/trova/backend/pipeline/ \
        src/test/java/com/trova/backend/pipeline/ \
        src/main/resources/application.yml \
        src/test/resources/application.yml
git commit -m "feat: PipelineRunner 및 파이프라인 출력 파서 추가"
```

---

### Task 3: 카카오 지오코딩

**Files:**
- Create: `src/main/java/com/trova/backend/geocoding/GeocodingResult.java`
- Create: `src/main/java/com/trova/backend/geocoding/KakaoKeywordSearchResponse.java`
- Create: `src/main/java/com/trova/backend/geocoding/KakaoLocalApiClient.java`
- Create: `src/main/java/com/trova/backend/geocoding/KakaoLocalApiClientImpl.java`
- Create: `src/main/java/com/trova/backend/geocoding/KakaoGeocodingService.java`
- Test: `src/test/java/com/trova/backend/geocoding/KakaoGeocodingServiceTest.java`

**Interfaces:**
- Consumes: 없음 (독립 패키지)
- Produces: `record GeocodingResult(Double latitude, Double longitude)` + 정적 `GeocodingResult.empty()`, `interface KakaoLocalApiClient { KakaoKeywordSearchResponse searchKeyword(String query); }`, `KakaoGeocodingService#geocode(String name, String region) -> GeocodingResult`. Task 5(`PlaceExtractionService`)가 `KakaoGeocodingService#geocode`를 호출한다.

**참고:** `KakaoLocalApiClientImpl`(실제 HTTP 호출)은 이 태스크에서 단위 테스트하지 않는다 — `KakaoGeocodingService`가 `KakaoLocalApiClient` 인터페이스에 의존하도록 분리해서, 실제 네트워크 호출 없이 Mockito로 정상/결과없음/오류 3가지 시나리오를 검증한다(스펙의 테스트 전략).

- [ ] **Step 1: 실패하는 서비스 테스트 작성 (Mockito로 `KakaoLocalApiClient` 목킹)**

```java
// src/test/java/com/trova/backend/geocoding/KakaoGeocodingServiceTest.java
package com.trova.backend.geocoding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KakaoGeocodingServiceTest {

    @Mock
    private KakaoLocalApiClient kakaoLocalApiClient;

    @InjectMocks
    private KakaoGeocodingService kakaoGeocodingService;

    @Test
    void 검색_결과가_있으면_첫_번째_좌표를_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("부산 해운대")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document("해운대해수욕장", "129.160384", "35.158698")
                )));

        GeocodingResult result = kakaoGeocodingService.geocode("해운대", "부산");

        assertThat(result.latitude()).isEqualTo(35.158698);
        assertThat(result.longitude()).isEqualTo(129.160384);
    }

    @Test
    void 검색_결과가_없으면_빈_결과를_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("어딘가 없는곳")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));

        GeocodingResult result = kakaoGeocodingService.geocode("없는곳", "어딘가");

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
    }

    @Test
    void 클라이언트가_예외를_던지면_빈_결과를_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("장애 지역"))
                .thenThrow(new RuntimeException("카카오 API 오류"));

        GeocodingResult result = kakaoGeocodingService.geocode("지역", "장애");

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.geocoding.KakaoGeocodingServiceTest"`
Expected: FAIL (컴파일 에러 — 클래스 없음)

- [ ] **Step 3: `GeocodingResult`, `KakaoKeywordSearchResponse` 작성**

```java
// src/main/java/com/trova/backend/geocoding/GeocodingResult.java
package com.trova.backend.geocoding;

public record GeocodingResult(Double latitude, Double longitude) {
    public static GeocodingResult empty() {
        return new GeocodingResult(null, null);
    }
}
```

```java
// src/main/java/com/trova/backend/geocoding/KakaoKeywordSearchResponse.java
package com.trova.backend.geocoding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KakaoKeywordSearchResponse(List<Document> documents) {
    public record Document(
            @JsonProperty("place_name") String placeName,
            String x,
            String y
    ) {
    }
}
```

- [ ] **Step 4: `KakaoLocalApiClient` 인터페이스 작성**

```java
// src/main/java/com/trova/backend/geocoding/KakaoLocalApiClient.java
package com.trova.backend.geocoding;

public interface KakaoLocalApiClient {
    KakaoKeywordSearchResponse searchKeyword(String query);
}
```

- [ ] **Step 5: `KakaoGeocodingService` 구현**

```java
// src/main/java/com/trova/backend/geocoding/KakaoGeocodingService.java
package com.trova.backend.geocoding;

import org.springframework.stereotype.Service;

@Service
public class KakaoGeocodingService {

    private final KakaoLocalApiClient kakaoLocalApiClient;

    public KakaoGeocodingService(KakaoLocalApiClient kakaoLocalApiClient) {
        this.kakaoLocalApiClient = kakaoLocalApiClient;
    }

    public GeocodingResult geocode(String name, String region) {
        String query = (region != null && !region.isBlank()) ? region + " " + name : name;
        try {
            KakaoKeywordSearchResponse response = kakaoLocalApiClient.searchKeyword(query);
            if (response == null || response.documents() == null || response.documents().isEmpty()) {
                return GeocodingResult.empty();
            }
            KakaoKeywordSearchResponse.Document first = response.documents().get(0);
            return new GeocodingResult(Double.parseDouble(first.y()), Double.parseDouble(first.x()));
        } catch (Exception e) {
            return GeocodingResult.empty();
        }
    }
}
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.geocoding.KakaoGeocodingServiceTest"`
Expected: PASS (3 tests)

- [ ] **Step 7: `KakaoLocalApiClientImpl` 구현 (실제 HTTP 호출, 테스트 없음)**

Spring의 `RestClient`가 이 프로젝트의 Spring Boot 4.1.0에서 브리핑과 다른 패키지/API로 존재할 수 있음 — 컴파일 안 되면 반드시 실제 jar로 검증 후 최소 수정.

```java
// src/main/java/com/trova/backend/geocoding/KakaoLocalApiClientImpl.java
package com.trova.backend.geocoding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoLocalApiClientImpl implements KakaoLocalApiClient {

    private final RestClient restClient;

    public KakaoLocalApiClientImpl(@Value("${app.kakao.rest-api-key}") String restApiKey) {
        this.restClient = RestClient.builder()
                .baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK " + restApiKey)
                .build();
    }

    @Override
    public KakaoKeywordSearchResponse searchKeyword(String query) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/keyword.json")
                        .queryParam("query", query)
                        .build())
                .retrieve()
                .body(KakaoKeywordSearchResponse.class);
    }
}
```

- [ ] **Step 8: `application.yml`에 카카오 REST API 키 설정 추가**

`src/main/resources/application.yml`의 `app:` 블록 아래에 추가 (로그인에 이미 쓰는 `KAKAO_CLIENT_ID`와는 다른 이름의 환경변수로 명시 — 카카오 로그인 client-id와 카카오 로컬 API의 REST API 키는 같은 값이지만 용도가 다르므로 별도 프로퍼티로 분리):

```yaml
app:
  kakao:
    rest-api-key: ${KAKAO_REST_API_KEY:}
```

`src/test/resources/application.yml`에도:

```yaml
app:
  kakao:
    rest-api-key: test-kakao-rest-api-key
```

- [ ] **Step 9: 전체 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/trova/backend/geocoding/ \
        src/test/java/com/trova/backend/geocoding/ \
        src/main/resources/application.yml \
        src/test/resources/application.yml
git commit -m "feat: 카카오 지오코딩 서비스 추가"
```

---

### Task 4: CurrentUserService + AsyncConfig

**Files:**
- Create: `src/main/java/com/trova/backend/service/CurrentUserService.java`
- Create: `src/main/java/com/trova/backend/config/AsyncConfig.java`
- Modify: `src/main/java/com/trova/backend/controller/AuthController.java` (기존 인라인 사용자 조회 로직을 `CurrentUserService`로 교체)
- Modify: `src/main/java/com/trova/backend/TrovaBackendApplication.java` (`@EnableAsync` 추가)
- Test: `src/test/java/com/trova/backend/service/CurrentUserServiceTest.java`

**Interfaces:**
- Consumes: `com.trova.backend.repository.UserRepository`(Task 2 of social-login plan), `com.trova.backend.security.OAuth2UserInfo`(Task 3 of social-login plan)
- Produces: `CurrentUserService#resolve(OAuth2AuthenticationToken) -> User`(예외 시 `IllegalStateException`), `@Bean("pipelineTaskExecutor")` bounded thread pool. Task 6/7(컨트롤러들)이 `CurrentUserService#resolve`를 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// src/test/java/com/trova/backend/service/CurrentUserServiceTest.java
package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CurrentUserService currentUserService;

    private OAuth2AuthenticationToken tokenFor(String sub) {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", sub, "name", "테스트", "picture", "https://example.com/p.jpg"),
                "sub"
        );
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    @Test
    void 등록된_사용자를_찾아서_반환한다() {
        User user = new User("google", "42", "테스트", null);
        when(userRepository.findByProviderAndProviderUserId("google", "42"))
                .thenReturn(Optional.of(user));

        User resolved = currentUserService.resolve(tokenFor("42"));

        assertThat(resolved).isEqualTo(user);
    }

    @Test
    void 등록되지_않은_사용자면_예외를_던진다() {
        when(userRepository.findByProviderAndProviderUserId("google", "99"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> currentUserService.resolve(tokenFor("99")))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.service.CurrentUserServiceTest"`
Expected: FAIL (컴파일 에러 — 클래스 없음)

- [ ] **Step 3: `CurrentUserService` 구현**

```java
// src/main/java/com/trova/backend/service/CurrentUserService.java
package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.OAuth2UserInfo;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolve(OAuth2AuthenticationToken authentication) {
        OAuth2UserInfo info = OAuth2UserInfo.of(
                authentication.getAuthorizedClientRegistrationId(),
                authentication.getPrincipal().getAttributes()
        );
        return userRepository.findByProviderAndProviderUserId(info.provider(), info.providerUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "인증된 사용자를 찾을 수 없습니다: " + info.provider() + " " + info.providerUserId()));
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.service.CurrentUserServiceTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: `AuthController`가 `CurrentUserService`를 쓰도록 리팩터링**

`src/main/java/com/trova/backend/controller/AuthController.java`를 열어서, 생성자에 `CurrentUserService currentUserService`를 추가로 주입받고, `me(...)` 메서드 안의 `OAuth2UserInfo.of(...)` + `userRepository.findByProviderAndProviderUserId(...).orElseThrow(...)` 두 줄을 `User user = currentUserService.resolve(authentication);` 한 줄로 교체한다. `UserRepository`/`OAuth2UserInfo` import가 더 이상 필요 없으면 제거. 기존 `AuthControllerTest`는 수정 없이 그대로 통과해야 한다(동작 변경 없음, 내부 구현만 정리).

- [ ] **Step 6: `AsyncConfig` 작성 (bounded thread pool)**

```java
// src/main/java/com/trova/backend/config/AsyncConfig.java
package com.trova.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean("pipelineTaskExecutor")
    public Executor pipelineTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("pipeline-");
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 7: `TrovaBackendApplication`에 `@EnableAsync` 추가**

`src/main/java/com/trova/backend/TrovaBackendApplication.java`를 열어서 `@SpringBootApplication` 옆에 `@EnableAsync`(`org.springframework.scheduling.annotation.EnableAsync`)를 추가한다.

- [ ] **Step 8: 전체 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` (기존 `AuthControllerTest` 포함 전체 통과)

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/trova/backend/service/CurrentUserService.java \
        src/main/java/com/trova/backend/config/AsyncConfig.java \
        src/main/java/com/trova/backend/controller/AuthController.java \
        src/main/java/com/trova/backend/TrovaBackendApplication.java \
        src/test/java/com/trova/backend/service/CurrentUserServiceTest.java
git commit -m "feat: CurrentUserService 및 비동기 실행자 설정 추가"
```

---

### Task 5: PlaceExtractionService (오케스트레이터)

**Files:**
- Create: `src/main/java/com/trova/backend/service/PlaceExtractionService.java`

**Interfaces:**
- Consumes: `ProcessingJobRepository`/`SavedPlaceRepository`(Task 1), `PipelineRunner#run`(Task 2), `KakaoGeocodingService#geocode`(Task 3), `pipelineTaskExecutor` 빈(Task 4)
- Produces: `PlaceExtractionService#process(Long jobId)` — `@Async("pipelineTaskExecutor")` + `@Transactional`. Task 6(`SharesController`)이 `ProcessingJob` 저장 직후 이 메서드를 호출한다.

**참고:** 서브프로세스 실행 + 외부 API 호출을 오케스트레이션하는 통합 성격의 메서드라 이 태스크에서 단위 테스트를 작성하지 않는다(스펙의 테스트 전략 — `PipelineRunner`/`KakaoGeocodingService` 각각의 핵심 로직은 이미 Task 2/3에서 단위 테스트됨). 전체 빌드로만 검증한다. **`@Transactional`을 반드시 붙일 것** — 빠뜨리면 소셜 로그인 때 났던 것과 같은 종류의 "조용히 저장 안 되는" 버그가 남.

- [ ] **Step 1: `PlaceExtractionService` 구현**

```java
// src/main/java/com/trova/backend/service/PlaceExtractionService.java
package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.geocoding.KakaoGeocodingService;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.PipelineRunner;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PlaceExtractionService {

    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final PipelineRunner pipelineRunner;
    private final KakaoGeocodingService kakaoGeocodingService;

    public PlaceExtractionService(
            ProcessingJobRepository processingJobRepository,
            SavedPlaceRepository savedPlaceRepository,
            PipelineRunner pipelineRunner,
            KakaoGeocodingService kakaoGeocodingService
    ) {
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.pipelineRunner = pipelineRunner;
        this.kakaoGeocodingService = kakaoGeocodingService;
    }

    @Async("pipelineTaskExecutor")
    @Transactional
    public void process(Long jobId) {
        ProcessingJob job = processingJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("ProcessingJob을 찾을 수 없습니다: " + jobId));
        job.markProcessing();

        try {
            List<ExtractedPlace> extractedPlaces = pipelineRunner.run(job.getSourceUrl(), job.getId());

            for (ExtractedPlace extracted : extractedPlaces) {
                GeocodingResult geocoded = kakaoGeocodingService.geocode(extracted.name(), extracted.region());
                savedPlaceRepository.save(new SavedPlace(
                        job,
                        job.getUser(),
                        extracted.name(),
                        extracted.region(),
                        extracted.category(),
                        geocoded.latitude(),
                        geocoded.longitude()
                ));
            }

            job.markDone();
        } catch (Exception e) {
            job.markFailed(e.getMessage());
        }
    }
}
```

- [ ] **Step 2: 전체 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/trova/backend/service/PlaceExtractionService.java
git commit -m "feat: PlaceExtractionService 추가"
```

---

### Task 6: SharesController (`POST /api/shares`)

**Files:**
- Create: `src/main/java/com/trova/backend/controller/SharesController.java`
- Test: `src/test/java/com/trova/backend/controller/SharesControllerTest.java`

**Interfaces:**
- Consumes: `CurrentUserService#resolve`(Task 4), `ProcessingJobRepository`(Task 1), `PlaceExtractionService#process`(Task 5)
- Produces: `POST /api/shares` — 요청 `{url}`, 응답 202 + `{jobId, status}`

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
// src/test/java/com/trova/backend/controller/SharesControllerTest.java
package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.PlaceExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SharesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private PlaceExtractionService placeExtractionService;

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
    void URL을_받으면_작업을_생성하고_202를_반환한다() throws Exception {
        userRepository.save(new User("google", "1234567890", "테스트유저", null));
        doNothing().when(placeExtractionService).process(anyLong());

        mockMvc.perform(post("/api/shares")
                        .with(oauth2Login()
                                .clientRegistration(googleRegistration())
                                .attributes(attrs -> {
                                    attrs.put("sub", "1234567890");
                                    attrs.put("name", "테스트유저");
                                    attrs.put("picture", "https://example.com/p.jpg");
                                }))
                        .contentType("application/json")
                        .content("{\"url\":\"https://www.youtube.com/shorts/abc\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        org.assertj.core.api.Assertions.assertThat(processingJobRepository.findAll()).hasSize(1);
    }

    @Test
    void 인증되지_않은_요청은_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/shares")
                        .contentType("application/json")
                        .content("{\"url\":\"https://www.youtube.com/shorts/abc\"}"))
                .andExpect(status().isUnauthorized());
    }
}
```

**주의:** `@org.springframework.boot.test.mock.mockito.MockBean`은 Spring Boot 3.4+에서 `@org.springframework.test.context.bean.override.mockito.MockitoBean`으로 대체된 이력이 있음 — 이 프로젝트의 Spring Boot 4.1.0에서 브리핑대로 컴파일 안 되면, 실제 어떤 애너테이션이 맞는지 `./gradlew dependencies`/jar 확인 후 최소 수정할 것(이 플랜의 Global Constraints 참고).

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.SharesControllerTest"`
Expected: FAIL (컴파일 에러 — `SharesController` 없음, 또는 `/api/shares`가 없어 404)

- [ ] **Step 3: `SharesController` 구현**

```java
// src/main/java/com/trova/backend/controller/SharesController.java
package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.PlaceExtractionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SharesController {

    private final CurrentUserService currentUserService;
    private final ProcessingJobRepository processingJobRepository;
    private final PlaceExtractionService placeExtractionService;

    public SharesController(
            CurrentUserService currentUserService,
            ProcessingJobRepository processingJobRepository,
            PlaceExtractionService placeExtractionService
    ) {
        this.currentUserService = currentUserService;
        this.processingJobRepository = processingJobRepository;
        this.placeExtractionService = placeExtractionService;
    }

    public record CreateShareRequest(String url) {
    }

    public record ShareResponse(Long jobId, String status) {
    }

    @PostMapping("/api/shares")
    public ResponseEntity<ShareResponse> create(
            OAuth2AuthenticationToken authentication,
            @RequestBody CreateShareRequest request
    ) {
        User user = currentUserService.resolve(authentication);
        SourcePlatform platform = (request.url().contains("youtube.com") || request.url().contains("youtu.be"))
                ? SourcePlatform.YOUTUBE
                : SourcePlatform.INSTAGRAM;

        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, request.url(), platform));
        placeExtractionService.process(job.getId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ShareResponse(job.getId(), job.getStatus().name()));
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인, 전체 빌드**

Run: `./gradlew test --tests "com.trova.backend.controller.SharesControllerTest"`
Expected: PASS (2 tests)

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/SharesController.java \
        src/test/java/com/trova/backend/controller/SharesControllerTest.java
git commit -m "feat: SharesController(POST /api/shares) 추가"
```

---

### Task 7: PlacesController (`GET/DELETE /api/places*`)

**Files:**
- Create: `src/main/java/com/trova/backend/controller/PlacesController.java`
- Test: `src/test/java/com/trova/backend/controller/PlacesControllerTest.java`

**Interfaces:**
- Consumes: `CurrentUserService#resolve`(Task 4), `SavedPlaceRepository`/`ProcessingJobRepository`(Task 1)
- Produces: `GET /api/places`, `GET /api/places/{id}`, `DELETE /api/places/{id}`, `GET /api/places/pending`

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
// src/test/java/com/trova/backend/controller/PlacesControllerTest.java
package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlacesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

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

    private org.springframework.test.web.servlet.request.RequestPostProcessor loginAs(String sub, String name) {
        return oauth2Login()
                .clientRegistration(googleRegistration())
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    @Test
    void 본인_장소_목록만_조회된다() throws Exception {
        User me = userRepository.save(new User("google", "aaa", "나", null));
        User other = userRepository.save(new User("google", "bbb", "남", null));
        ProcessingJob myJob = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/x", SourcePlatform.YOUTUBE));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/y", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(myJob, me, "내 장소", "서울", "cafe", 37.5, 127.0));
        savedPlaceRepository.save(new SavedPlace(otherJob, other, "남의 장소", "부산", "cafe", 35.1, 129.0));

        mockMvc.perform(get("/api/places").with(loginAs("aaa", "나")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].placeName").value("내 장소"));
    }

    @Test
    void 타인_소유_장소_단건_조회는_404() throws Exception {
        User me = userRepository.save(new User("google", "ccc", "나2", null));
        User other = userRepository.save(new User("google", "ddd", "남2", null));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/z", SourcePlatform.YOUTUBE));
        SavedPlace otherPlace = savedPlaceRepository.save(new SavedPlace(otherJob, other, "남의 장소", null, "cafe", null, null));

        mockMvc.perform(get("/api/places/" + otherPlace.getId()).with(loginAs("ccc", "나2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 본인_장소_삭제_성공() throws Exception {
        User me = userRepository.save(new User("google", "eee", "나3", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/w", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, me, "삭제될 장소", null, "cafe", null, null));

        mockMvc.perform(delete("/api/places/" + place.getId()).with(loginAs("eee", "나3")))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(savedPlaceRepository.findById(place.getId())).isEmpty();
    }

    @Test
    void pending_작업만_조회된다() throws Exception {
        User me = userRepository.save(new User("google", "fff", "나4", null));
        ProcessingJob pending = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/pending", SourcePlatform.YOUTUBE));
        ProcessingJob done = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/done", SourcePlatform.YOUTUBE));
        done.markProcessing();
        done.markDone();
        processingJobRepository.save(done);

        mockMvc.perform(get("/api/places/pending").with(loginAs("fff", "나4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(pending.getId()));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.PlacesControllerTest"`
Expected: FAIL (컴파일 에러 — `PlacesController` 없음)

- [ ] **Step 3: `PlacesController` 구현**

```java
// src/main/java/com/trova/backend/controller/PlacesController.java
package com.trova.backend.controller;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/places")
public class PlacesController {

    private final CurrentUserService currentUserService;
    private final SavedPlaceRepository savedPlaceRepository;
    private final ProcessingJobRepository processingJobRepository;

    public PlacesController(
            CurrentUserService currentUserService,
            SavedPlaceRepository savedPlaceRepository,
            ProcessingJobRepository processingJobRepository
    ) {
        this.currentUserService = currentUserService;
        this.savedPlaceRepository = savedPlaceRepository;
        this.processingJobRepository = processingJobRepository;
    }

    public record PlaceResponse(
            Long id, String placeName, String region, String category,
            Double latitude, Double longitude, String sourceUrl,
            String sourcePlatform, String createdAt
    ) {
        static PlaceResponse from(SavedPlace place) {
            return new PlaceResponse(
                    place.getId(), place.getPlaceName(), place.getRegion(), place.getCategory(),
                    place.getLatitude(), place.getLongitude(), place.getSourceUrl(),
                    place.getSourcePlatform().name(), place.getCreatedAt().toString()
            );
        }
    }

    public record PendingJobResponse(Long jobId, String sourceUrl, String sourcePlatform, String status, String createdAt) {
        static PendingJobResponse from(ProcessingJob job) {
            return new PendingJobResponse(
                    job.getId(), job.getSourceUrl(), job.getSourcePlatform().name(),
                    job.getStatus().name(), job.getCreatedAt().toString()
            );
        }
    }

    @GetMapping
    public List<PlaceResponse> list(OAuth2AuthenticationToken authentication) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(PlaceResponse::from)
                .toList();
    }

    @GetMapping("/pending")
    public List<PendingJobResponse> pending(OAuth2AuthenticationToken authentication) {
        User user = currentUserService.resolve(authentication);
        return processingJobRepository.findByUserAndStatusIn(user, List.of(JobStatus.PENDING, JobStatus.PROCESSING))
                .stream()
                .map(PendingJobResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaceResponse> get(OAuth2AuthenticationToken authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByIdAndUser(id, user)
                .map(place -> ResponseEntity.ok(PlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(OAuth2AuthenticationToken authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByIdAndUser(id, user)
                .map(place -> {
                    savedPlaceRepository.delete(place);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

**참고:** Spring MVC는 리터럴 경로(`/pending`)를 경로 변수(`/{id}`)보다 더 구체적인 것으로 우선 매칭하므로 선언 순서와 무관하게 정상 동작한다. 다만 가독성을 위해 위 코드 순서(구체적인 것 먼저)를 유지할 것.

- [ ] **Step 4: 테스트 실행 → 통과 확인, 전체 빌드**

Run: `./gradlew test --tests "com.trova.backend.controller.PlacesControllerTest"`
Expected: PASS (4 tests)

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/PlacesController.java \
        src/test/java/com/trova/backend/controller/PlacesControllerTest.java
git commit -m "feat: PlacesController(GET/DELETE /api/places) 추가"
```

---

### Task 8: 수동 검증 가이드 + 문서 갱신

**Files:**
- Create: `docs/saved-places-pipeline-manual-verification.md`
- Modify: `TODO.md`, `PROGRESS.md` (gitignore 대상, 로컬 갱신만 — 커밋 안 함)

- [ ] **Step 1: 수동 검증 가이드 작성**

```markdown
# SavedPlace 파이프라인 수동 검증 가이드

자동화 테스트는 서브프로세스 실행/외부 API 호출을 다루지 않으므로,
아래를 브라우저 + curl로 직접 확인한다.

## 사전 준비

1. `GEMINI_API_KEY`, `KAKAO_REST_API_KEY`(카카오 로그인과 같은 키),
   Google/Kakao OAuth 값, Supabase 접속 정보를 환경변수로 설정
2. `python3`, `yt-dlp`, `ffmpeg`가 PATH에 있는지 확인
3. `./gradlew bootRun`

## 검증 체크리스트

- [ ] 브라우저로 로그인(세션 쿠키 확보) 후, 같은 세션으로
      `curl -b <쿠키> -X POST http://localhost:8080/api/shares -H "Content-Type: application/json" -d '{"url":"<실제 유튜브 쇼츠 URL>"}'`
      → 202 + `{jobId, status: "PENDING"}` 확인
- [ ] `curl -b <쿠키> http://localhost:8080/api/places/pending` → 방금 만든 job이 PENDING/PROCESSING으로 보이는지 확인
- [ ] ~30초 대기 후 다시 `/api/places/pending` 호출 → 목록에서 사라졌는지 확인(완료됨)
- [ ] `curl -b <쿠키> http://localhost:8080/api/places` → 추출된 장소들이 보이는지, `latitude`/`longitude`가 채워졌는지 확인
- [ ] 존재하지 않을 법한 장소명으로 테스트해서 지오코딩 결과 없음(좌표 null)이어도 장소 자체는 저장되는지 확인
- [ ] `curl -b <쿠키> http://localhost:8080/api/places/{id}` 단건 조회 확인
- [ ] `curl -b <쿠키> -X DELETE http://localhost:8080/api/places/{id}` → 204, 이후 목록에서 사라짐 확인
- [ ] 다른 계정으로 로그인해서 위에서 만든 place id로 GET/DELETE 시도 → 404 확인(소유권 검증)
```

- [ ] **Step 2: `TODO.md` 갱신**

`## 2단계: Spring Boot API`에서 `Entity 작성 (SavedPlace, ProcessingJob)`, `POST /api/shares`, `GET /api/places, /api/places/{id}`, `DELETE /api/places/{id}`, `GET /api/places/pending`, `Supabase(Postgres) 연결` 항목을 모두 `[x]`로 체크하고, 새 항목 추가:
`[ ] 카카오맵 서비스 활용 동의 필요 여부 확인 및 배포 컨테이너에 python3 추가 — docs/superpowers/plans/2026-08-19-saved-places-pipeline.md 참고`

- [ ] **Step 3: `PROGRESS.md`에 작업 로그 추가**

기존 형식(한 것/왜 이렇게 했는지/막힌 것)에 맞춰 이번 세션 내용 추가.

- [ ] **Step 4: 커밋 (문서 중 gitignore 안 된 파일만)**

```bash
git add docs/saved-places-pipeline-manual-verification.md
git commit -m "docs: SavedPlace 파이프라인 수동 검증 가이드 추가"
```

---

## 실행 후 남는 일 (이 플랜 범위 밖)

- 실제 URL로 전체 파이프라인 수동 검증 (Task 8 가이드)
- Kakao Developers에서 "카카오맵" 서비스 활용 동의가 REST API 키와 별개로 필요한지 확인
- 배포 Dockerfile에 python3 추가 (yt-dlp/ffmpeg는 이미 TODO에 있음)
- 프론트엔드 mock을 실제 API로 교체 (`// TODO: connect real API` 부분, `category` enum 값이 영어(restaurant 등)로 오는 것과 프론트 mock의 한글 카테고리 값 차이 조정 필요)
