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
