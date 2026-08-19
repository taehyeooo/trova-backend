package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional // 공유 H2(jdbc:h2:mem:testdb)에 테스트 데이터가 남지 않도록 각 테스트 후 롤백
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    @Test
    void 인증되지_않은_요청은_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 인증된_사용자는_자신의_정보를_받는다() throws Exception {
        userRepository.save(new User("google", "1234567890", "테스트유저", "https://example.com/photo.jpg"));

        mockMvc.perform(get("/api/auth/me").with(oauth2Login()
                        .clientRegistration(googleRegistration())
                        .attributes(attrs -> {
                            attrs.put("sub", "1234567890");
                            attrs.put("name", "테스트유저");
                            attrs.put("picture", "https://example.com/photo.jpg");
                        })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("테스트유저"));
    }
}
