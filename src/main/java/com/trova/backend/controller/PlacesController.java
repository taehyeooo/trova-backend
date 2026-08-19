package com.trova.backend.controller;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/places")
public class PlacesController {

    private final CurrentUserService currentUserService;
    private final SavedPlaceRepository savedPlaceRepository;
    private final ProcessingJobRepository processingJobRepository;

    public PlacesController(
            CurrentUserService currentUserService,
            SavedPlaceRepository savedPlaceRepository,
            ProcessingJobRepository processingJobRepository
    ) {
        this.currentUserService = currentUserService;
        this.savedPlaceRepository = savedPlaceRepository;
        this.processingJobRepository = processingJobRepository;
    }

    public record PlaceResponse(
            Long id, String placeName, String region, String category,
            Double latitude, Double longitude, String sourceUrl,
            String sourcePlatform, String createdAt
    ) {
        static PlaceResponse from(SavedPlace place) {
            return new PlaceResponse(
                    place.getId(), place.getPlaceName(), place.getRegion(), place.getCategory(),
                    place.getLatitude(), place.getLongitude(), place.getSourceUrl(),
                    place.getSourcePlatform().name(), place.getCreatedAt().toString()
            );
        }
    }

    public record PendingJobResponse(Long jobId, String sourceUrl, String sourcePlatform, String status, String createdAt) {
        static PendingJobResponse from(ProcessingJob job) {
            return new PendingJobResponse(
                    job.getId(), job.getSourceUrl(), job.getSourcePlatform().name(),
                    job.getStatus().name(), job.getCreatedAt().toString()
            );
        }
    }

    @GetMapping
    public List<PlaceResponse> list(OAuth2AuthenticationToken authentication) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user).stream()
                .map(PlaceResponse::from)
                .toList();
    }

    @GetMapping("/pending")
    public List<PendingJobResponse> pending(OAuth2AuthenticationToken authentication) {
        User user = currentUserService.resolve(authentication);
        return processingJobRepository.findByUserAndStatusIn(user, List.of(JobStatus.PENDING, JobStatus.PROCESSING))
                .stream()
                .map(PendingJobResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaceResponse> get(OAuth2AuthenticationToken authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByIdAndUser(id, user)
                .map(place -> ResponseEntity.ok(PlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(OAuth2AuthenticationToken authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByIdAndUser(id, user)
                .map(place -> {
                    savedPlaceRepository.delete(place);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
