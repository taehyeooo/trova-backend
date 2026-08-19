package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.OAuth2UserInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserUpsertService {

    private final UserRepository userRepository;

    public UserUpsertService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 조회 -> 변경 -> 커밋을 하나의 트랜잭션으로 묶어야 기존 사용자 프로필 갱신이 변경 감지로 반영된다.
    // (open-in-view=false라 트랜잭션이 없으면 조회된 엔티티가 준영속 상태가 되어 갱신이 유실된다)
    @Transactional
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
