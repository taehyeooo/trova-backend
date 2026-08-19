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
                .orElseThrow(() -> new IllegalStateException(
                        "인증된 사용자를 찾을 수 없습니다: provider=" + info.provider()
                                + ", providerUserId=" + info.providerUserId()));

        return ResponseEntity.ok(new MeResponse(user.getId(), user.getNickname(), user.getProfileImageUrl()));
    }

    public record MeResponse(Long id, String nickname, String profileImageUrl) {
    }
}
