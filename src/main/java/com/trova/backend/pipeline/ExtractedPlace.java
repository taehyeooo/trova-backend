package com.trova.backend.pipeline;

public record ExtractedPlace(
        String name, String region, String category, Double confidence,
        Integer dayNumber, Integer orderInDay
) {
}
