package com.trova.backend.repository;

import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

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
