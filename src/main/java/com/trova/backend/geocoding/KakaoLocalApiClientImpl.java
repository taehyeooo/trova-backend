package com.trova.backend.geocoding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoLocalApiClientImpl implements KakaoLocalApiClient {

    private final RestClient restClient;

    public KakaoLocalApiClientImpl(@Value("${app.kakao.rest-api-key}") String restApiKey) {
        this.restClient = RestClient.builder()
                .baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK " + restApiKey)
                .build();
    }

    @Override
    public KakaoKeywordSearchResponse searchKeyword(String query) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/keyword.json")
                        .queryParam("query", query)
                        .build())
                .retrieve()
                .body(KakaoKeywordSearchResponse.class);
    }
}
