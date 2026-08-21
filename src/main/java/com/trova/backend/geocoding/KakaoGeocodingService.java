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
        boolean hasRegion = region != null && !region.isBlank();
        GeocodingResult result = search(hasRegion ? region + " " + name : name);
        if (result.latitude() != null || !hasRegion) {
            return result;
        }
        // STT 오탈자 등으로 name이 정확히 매칭 안 될 수 있음 — region만으로 재검색해서
        // 최소한 지역 중심 좌표라도 남긴다 (완전 실패보다 나은 근사치).
        log.info("정확 매칭 실패, region만으로 재검색합니다(name={}, region={})", name, region);
        return search(region);
    }

    private GeocodingResult search(String query) {
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
