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
    void 검색_결과가_있으면_첫_번째_좌표와_확인된_이름을_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("부산 해운대")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document("해운대해수욕장", "129.160384", "35.158698")
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("해운대"), "부산");

        assertThat(result.latitude()).isEqualTo(35.158698);
        assertThat(result.longitude()).isEqualTo(129.160384);
        assertThat(result.matchedName()).isEqualTo("해운대해수욕장");
    }

    @Test
    void 정확_매칭과_region_폴백_둘_다_없으면_빈_결과를_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("어딘가 없는곳")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("어딘가")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("없는곳"), "어딘가");

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
        assertThat(result.matchedName()).isNull();
    }

    @Test
    void 정확_매칭에_실패하면_region만으로_재검색해서_좌표를_반환하되_matchedName은_채우지_않는다() {
        when(kakaoLocalApiClient.searchKeyword("부산 광알리")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("부산")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document("부산광역시", "129.075642", "35.179554")
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("광알리"), "부산");

        assertThat(result.latitude()).isEqualTo(35.179554);
        assertThat(result.longitude()).isEqualTo(129.075642);
        assertThat(result.matchedName()).isNull();
    }

    @Test
    void region이_없으면_폴백_없이_한_번만_검색한다() {
        when(kakaoLocalApiClient.searchKeyword("없는곳")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("없는곳"), null);

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

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("지역"), "장애");

        assertThat(result.latitude()).isEqualTo(37.0);
        assertThat(result.longitude()).isEqualTo(127.0);
    }

    @Test
    void 첫_번째_후보가_실패해도_두_번째_후보로_매칭되면_확인된_이름을_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("인천 하늘기")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("인천 하늘길")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document("하늘길", "126.7", "37.4")
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("하늘기", "하늘길"), "인천");

        assertThat(result.latitude()).isEqualTo(37.4);
        assertThat(result.longitude()).isEqualTo(126.7);
        assertThat(result.matchedName()).isEqualTo("하늘길");
    }

    @Test
    void 모든_후보가_실패하면_region만으로_재검색한다() {
        when(kakaoLocalApiClient.searchKeyword("인천 하늘기")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("인천 하늘길")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("인천")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document("인천광역시", "126.7", "37.45")
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("하늘기", "하늘길"), "인천");

        assertThat(result.latitude()).isEqualTo(37.45);
        assertThat(result.longitude()).isEqualTo(126.7);
        assertThat(result.matchedName()).isNull();
    }
}
