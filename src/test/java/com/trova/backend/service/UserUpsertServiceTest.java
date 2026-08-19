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
