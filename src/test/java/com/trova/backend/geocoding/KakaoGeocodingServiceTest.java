package com.trova.backend.geocoding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KakaoGeocodingServiceTest {

    @Mock
    private KakaoLocalApiClient kakaoLocalApiClient;

    @InjectMocks
    private KakaoGeocodingService kakaoGeocodingService;

    @Test
    void 검색_결과가_있으면_첫_번째_좌표를_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("부산 해운대")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document("해운대해수욕장", "129.160384", "35.158698")
                )));

        GeocodingResult result = kakaoGeocodingService.geocode("해운대", "부산");

        assertThat(result.latitude()).isEqualTo(35.158698);
        assertThat(result.longitude()).isEqualTo(129.160384);
    }

    @Test
    void 정확_매칭과_region_폴백_둘_다_없으면_빈_결과를_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("어딘가 없는곳")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("어딘가")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));

        GeocodingResult result = kakaoGeocodingService.geocode("없는곳", "어딘가");

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
    }

    @Test
    void 정확_매칭에_실패하면_region만으로_재검색해서_좌표를_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("부산 광알리")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("부산")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document("부산광역시", "129.075642", "35.179554")
                )));

        GeocodingResult result = kakaoGeocodingService.geocode("광알리", "부산");

        assertThat(result.latitude()).isEqualTo(35.179554);
        assertThat(result.longitude()).isEqualTo(129.075642);
    }

    @Test
    void region이_없으면_폴백_없이_한_번만_검색한다() {
        when(kakaoLocalApiClient.searchKeyword("없는곳")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));

        GeocodingResult result = kakaoGeocodingService.geocode("없는곳", null);

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
    }

    @Test
    void 클라이언트가_예외를_던지면_region_폴백을_시도한다() {
        when(kakaoLocalApiClient.searchKeyword("장애 지역"))
                .thenThrow(new RuntimeException("카카오 API 오류"));
        when(kakaoLocalApiClient.searchKeyword("장애")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document("장애", "127.0", "37.0")
                )));

        GeocodingResult result = kakaoGeocodingService.geocode("지역", "장애");

        assertThat(result.latitude()).isEqualTo(37.0);
        assertThat(result.longitude()).isEqualTo(127.0);
    }
}
