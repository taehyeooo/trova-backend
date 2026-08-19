package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.OAuth2UserInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 영속성 컨텍스트를 사용하는 통합 테스트.
 * <p>
 * Mockito 기반 {@link UserUpsertServiceTest}는 UserRepository를 통째로 목으로 대체하기 때문에
 * "기존 사용자 프로필 갱신이 실제로 DB에 반영되는가"를 검증할 수 없다.
 * 이 테스트는 일부러 클래스 레벨 {@code @Transactional}을 붙이지 않아 운영과 동일한 조건
 * (open-in-view=false, 주변 트랜잭션 없음)을 재현한다. upsert()에 @Transactional이 없으면
 * 조회로 얻은 엔티티가 준영속 상태라 변경 감지가 동작하지 않아 이 테스트는 실패한다.
 */
@SpringBootTest
class UserUpsertServiceIntegrationTest {

    private static final String PROVIDER = "google";
    private static final String PROVIDER_USER_ID = "upsert-integration-9876543210";

    @Autowired
    private UserUpsertService userUpsertService;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        userRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID)
                .ifPresent(userRepository::delete);
    }

    @Test
    void 기존_사용자의_프로필_갱신이_DB에_실제로_반영된다() {
        User saved = userRepository.save(
                new User(PROVIDER, PROVIDER_USER_ID, "옛날닉네임", "https://example.com/old.jpg"));

        userUpsertService.upsert(new OAuth2UserInfo(
                PROVIDER, PROVIDER_USER_ID, "새닉네임", "https://example.com/new.jpg"));

        // upsert()가 끝나 트랜잭션이 커밋된 뒤, 새 영속성 컨텍스트로 다시 읽어 실제 반영 여부를 확인한다.
        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getNickname()).isEqualTo("새닉네임");
        assertThat(reloaded.getProfileImageUrl()).isEqualTo("https://example.com/new.jpg");
    }
}
