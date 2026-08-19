package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.geocoding.KakaoGeocodingService;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.PipelineRunner;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PlaceExtractionService {

    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final PipelineRunner pipelineRunner;
    private final KakaoGeocodingService kakaoGeocodingService;

    public PlaceExtractionService(
            ProcessingJobRepository processingJobRepository,
            SavedPlaceRepository savedPlaceRepository,
            PipelineRunner pipelineRunner,
            KakaoGeocodingService kakaoGeocodingService
    ) {
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.pipelineRunner = pipelineRunner;
        this.kakaoGeocodingService = kakaoGeocodingService;
    }

    @Async("pipelineTaskExecutor")
    @Transactional
    public void process(Long jobId) {
        ProcessingJob job = processingJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("ProcessingJob을 찾을 수 없습니다: " + jobId));
        job.markProcessing();

        try {
            List<ExtractedPlace> extractedPlaces = pipelineRunner.run(job.getSourceUrl(), job.getId());

            for (ExtractedPlace extracted : extractedPlaces) {
                GeocodingResult geocoded = kakaoGeocodingService.geocode(extracted.name(), extracted.region());
                savedPlaceRepository.save(new SavedPlace(
                        job,
                        job.getUser(),
                        extracted.name(),
                        extracted.region(),
                        extracted.category(),
                        geocoded.latitude(),
                        geocoded.longitude()
                ));
            }

            job.markDone();
        } catch (Exception e) {
            job.markFailed(e.getMessage());
        }
    }
}
