package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final CurrentUserService currentUserService;

    public AuthController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<MeResponse> me(OAuth2AuthenticationToken authentication) {
        User user = currentUserService.resolve(authentication);

        return ResponseEntity.ok(new MeResponse(user.getId(), user.getNickname(), user.getProfileImageUrl()));
    }

    public record MeResponse(Long id, String nickname, String profileImageUrl) {
    }
}
