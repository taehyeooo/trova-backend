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
    void 검색_결과가_없으면_빈_결과를_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("어딘가 없는곳")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));

        GeocodingResult result = kakaoGeocodingService.geocode("없는곳", "어딘가");

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
    }

    @Test
    void 클라이언트가_예외를_던지면_빈_결과를_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("장애 지역"))
                .thenThrow(new RuntimeException("카카오 API 오류"));

        GeocodingResult result = kakaoGeocodingService.geocode("지역", "장애");

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
    }
}
