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

@RestController
public class SharesController {

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

    @PostMapping("/api/shares")
    public ResponseEntity<ShareResponse> create(
            OAuth2AuthenticationToken authentication,
            @RequestBody CreateShareRequest request
    ) {
        User user = currentUserService.resolve(authentication);
        SourcePlatform platform = (request.url().contains("youtube.com") || request.url().contains("youtu.be"))
                ? SourcePlatform.YOUTUBE
                : SourcePlatform.INSTAGRAM;

        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, request.url(), platform));
        placeExtractionService.process(job.getId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ShareResponse(job.getId(), job.getStatus().name()));
    }
}
