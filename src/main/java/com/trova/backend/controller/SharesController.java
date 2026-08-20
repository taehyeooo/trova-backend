package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.PlaceExtractionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

@RestController
public class SharesController {

    private static final Set<String> YOUTUBE_HOSTS =
            Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
                    "youtube-nocookie.com", "www.youtube-nocookie.com");
    private static final Set<String> INSTAGRAM_HOSTS =
            Set.of("instagram.com", "www.instagram.com", "m.instagram.com");

    private final CurrentUserService currentUserService;
    private final ProcessingJobRepository processingJobRepository;
    private final PlaceExtractionService placeExtractionService;

    public SharesController(
            CurrentUserService currentUserService,
            ProcessingJobRepository processingJobRepository,
            PlaceExtractionService placeExtractionService
    ) {
        this.currentUserService = currentUserService;
        this.processingJobRepository = processingJobRepository;
        this.placeExtractionService = placeExtractionService;
    }

    public record CreateShareRequest(String url) {
    }

    public record ShareResponse(Long jobId, String status) {
    }

    public record ErrorResponse(String message) {
    }

    @PostMapping("/api/shares")
    public ResponseEntity<?> create(
            OAuth2AuthenticationToken authentication,
            @RequestBody CreateShareRequest request
    ) {
        String url = request == null ? null : request.url();
        SourcePlatform platform = resolvePlatform(url);
        if (platform == null) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("지원하지 않는 URL입니다. 유튜브 또는 인스타그램 링크만 등록할 수 있습니다."));
        }

        User user = currentUserService.resolve(authentication);
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, platform));
        placeExtractionService.process(job.getId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ShareResponse(job.getId(), job.getStatus().name()));
    }

    /**
     * 허용된 호스트의 http(s) URL이면 해당 플랫폼을, 아니면 null을 반환한다.
     * (yt-dlp argv 주입 및 내부망 SSRF 방지를 위한 화이트리스트 검증)
     */
    private SourcePlatform resolvePlatform(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
            return null;
        }

        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.endsWith(".")) {
            normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
        }

        if (YOUTUBE_HOSTS.contains(normalizedHost)) {
            return SourcePlatform.YOUTUBE;
        }
        if (INSTAGRAM_HOSTS.contains(normalizedHost)) {
            return SourcePlatform.INSTAGRAM;
        }
        return null;
    }
}
