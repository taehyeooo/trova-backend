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
