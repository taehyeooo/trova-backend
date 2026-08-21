package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessingJobLifecycleService {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;

    public ProcessingJobLifecycleService(
            ProcessingJobRepository processingJobRepository,
            SavedPlaceRepository savedPlaceRepository
    ) {
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
    }

    @Transactional
    public String markProcessing(Long jobId) {
        ProcessingJob job = getJob(jobId);
        job.markProcessing();
        return job.getSourceUrl();
    }

    @Transactional
    public void setTitle(Long jobId, String title) {
        getJob(jobId).setTitle(title);
    }

    @Transactional
    public void savePlace(Long jobId, ExtractedPlace extracted, GeocodingResult geocoded) {
        ProcessingJob job = getJob(jobId);
        savedPlaceRepository.save(new SavedPlace(
                job,
                job.getUser(),
                extracted.name(),
                extracted.region(),
                extracted.category(),
                geocoded.latitude(),
                geocoded.longitude(),
                extracted.dayNumber(),
                extracted.orderInDay()
        ));
    }

    @Transactional
    public void markDone(Long jobId) {
        getJob(jobId).markDone();
    }

    @Transactional
    public void markFailed(Long jobId, String rawMessage) {
        getJob(jobId).markFailed(truncate(rawMessage));
    }

    private ProcessingJob getJob(Long jobId) {
        return processingJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("ProcessingJob을 찾을 수 없습니다: " + jobId));
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > ERROR_MESSAGE_MAX_LENGTH
                ? message.substring(message.length() - ERROR_MESSAGE_MAX_LENGTH)
                : message;
    }
}
