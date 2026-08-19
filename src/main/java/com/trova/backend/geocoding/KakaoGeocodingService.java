package com.trova.backend.geocoding;

import org.springframework.stereotype.Service;

@Service
public class KakaoGeocodingService {

    private final KakaoLocalApiClient kakaoLocalApiClient;

    public KakaoGeocodingService(KakaoLocalApiClient kakaoLocalApiClient) {
        this.kakaoLocalApiClient = kakaoLocalApiClient;
    }

    public GeocodingResult geocode(String name, String region) {
        String query = (region != null && !region.isBlank()) ? region + " " + name : name;
        try {
            KakaoKeywordSearchResponse response = kakaoLocalApiClient.searchKeyword(query);
            if (response == null || response.documents() == null || response.documents().isEmpty()) {
                return GeocodingResult.empty();
            }
            KakaoKeywordSearchResponse.Document first = response.documents().get(0);
            return new GeocodingResult(Double.parseDouble(first.y()), Double.parseDouble(first.x()));
        } catch (Exception e) {
            return GeocodingResult.empty();
        }
    }
}
