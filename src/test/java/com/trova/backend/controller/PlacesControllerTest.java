package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlacesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

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

    private org.springframework.test.web.servlet.request.RequestPostProcessor loginAs(String sub, String name) {
        return oauth2Login()
                .clientRegistration(googleRegistration())
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    @Test
    void 본인_장소_목록만_조회된다() throws Exception {
        User me = userRepository.save(new User("google", "aaa", "나", null));
        User other = userRepository.save(new User("google", "bbb", "남", null));
        ProcessingJob myJob = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/x", SourcePlatform.YOUTUBE));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/y", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(myJob, me, "내 장소", "서울", "cafe", 37.5, 127.0));
        savedPlaceRepository.save(new SavedPlace(otherJob, other, "남의 장소", "부산", "cafe", 35.1, 129.0));

        mockMvc.perform(get("/api/places").with(loginAs("aaa", "나")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].placeName").value("내 장소"));
    }

    @Test
    void 타인_소유_장소_단건_조회는_404() throws Exception {
        User me = userRepository.save(new User("google", "ccc", "나2", null));
        User other = userRepository.save(new User("google", "ddd", "남2", null));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/z", SourcePlatform.YOUTUBE));
        SavedPlace otherPlace = savedPlaceRepository.save(new SavedPlace(otherJob, other, "남의 장소", null, "cafe", null, null));

        mockMvc.perform(get("/api/places/" + otherPlace.getId()).with(loginAs("ccc", "나2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 본인_장소_삭제_성공() throws Exception {
        User me = userRepository.save(new User("google", "eee", "나3", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/w", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, me, "삭제될 장소", null, "cafe", null, null));

        mockMvc.perform(delete("/api/places/" + place.getId()).with(loginAs("eee", "나3")))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(savedPlaceRepository.findById(place.getId())).isEmpty();
    }

    @Test
    void pending_작업만_조회된다() throws Exception {
        User me = userRepository.save(new User("google", "fff", "나4", null));
        ProcessingJob pending = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/pending", SourcePlatform.YOUTUBE));
        ProcessingJob done = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/done", SourcePlatform.YOUTUBE));
        done.markProcessing();
        done.markDone();
        processingJobRepository.save(done);

        mockMvc.perform(get("/api/places/pending").with(loginAs("fff", "나4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(pending.getId()));
    }

    @Test
    void 실패한_작업도_pending_목록에_포함된다() throws Exception {
        User me = userRepository.save(new User("google", "ggg", "나5", null));
        ProcessingJob failed = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/failed", SourcePlatform.YOUTUBE));
        failed.markFailed("yt-dlp 403");
        processingJobRepository.save(failed);

        mockMvc.perform(get("/api/places/pending").with(loginAs("ggg", "나5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(failed.getId()))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    @Test
    void 일정형_장소는_dayNumber와_orderInDay를_반환한다() throws Exception {
        User me = userRepository.save(new User("google", "hhh", "일정유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/itinerary2", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(
                new SavedPlace(job, me, "해운대", "부산", "attraction", 35.16, 129.16, 1, 1));

        mockMvc.perform(get("/api/places").with(loginAs("hhh", "일정유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayNumber").value(1))
                .andExpect(jsonPath("$[0].orderInDay").value(1));
    }

    @Test
    void 일정형이_아닌_장소는_dayNumber가_null이다() throws Exception {
        User me = userRepository.save(new User("google", "iii", "일반유저2", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/normal2", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(
                new SavedPlace(job, me, "장소", null, "cafe", null, null));

        mockMvc.perform(get("/api/places").with(loginAs("iii", "일반유저2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayNumber").value(org.hamcrest.Matchers.nullValue()));
    }
}
