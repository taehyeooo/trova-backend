package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.OAuth2UserInfo;
import org.springframework.stereotype.Service;

@Service
public class UserUpsertService {

    private final UserRepository userRepository;

    public UserUpsertService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User upsert(OAuth2UserInfo info) {
        return userRepository.findByProviderAndProviderUserId(info.provider(), info.providerUserId())
                .map(existing -> {
                    existing.updateProfile(info.nickname(), info.profileImageUrl());
                    return existing;
                })
                .orElseGet(() -> userRepository.save(
                        new User(info.provider(), info.providerUserId(), info.nickname(), info.profileImageUrl())
                ));
    }
}
