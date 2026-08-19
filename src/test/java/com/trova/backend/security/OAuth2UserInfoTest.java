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
