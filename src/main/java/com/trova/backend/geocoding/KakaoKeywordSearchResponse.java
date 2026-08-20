package com.trova.backend.geocoding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KakaoKeywordSearchResponse(List<Document> documents) {
    public record Document(
            @JsonProperty("place_name") String placeName,
            String x,
            String y
    ) {
    }
}
