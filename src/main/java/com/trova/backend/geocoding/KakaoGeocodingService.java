package com.trova.backend.geocoding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KakaoGeocodingService {

    private static final Logger log = LoggerFactory.getLogger(KakaoGeocodingService.class);

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
            log.warn("카카오 지오코딩 실패(query={}) — 좌표 없이 저장합니다", query, e);
            return GeocodingResult.empty();
        }
    }
}
