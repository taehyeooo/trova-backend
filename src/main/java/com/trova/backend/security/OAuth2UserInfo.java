package com.trova.backend.security;

import java.util.Map;
import java.util.Objects;

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
        Object sub = Objects.requireNonNull(attributes.get("sub"), "google 'sub' attribute missing");
        return new OAuth2UserInfo(
                "google",
                String.valueOf(sub),
                (String) attributes.get("name"),
                (String) attributes.get("picture")
        );
    }

    @SuppressWarnings("unchecked")
    private static OAuth2UserInfo ofKakao(Map<String, Object> attributes) {
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");
        Object id = Objects.requireNonNull(attributes.get("id"), "kakao 'id' attribute missing");
        return new OAuth2UserInfo(
                "kakao",
                String.valueOf(id),
                (String) profile.get("nickname"),
                (String) profile.get("profile_image_url")
        );
    }
}
