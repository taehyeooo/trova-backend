package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineOutputParserTest {

    @Test
    void 정상_JSON_배열을_파싱한다() {
        String stdout = """
                [
                  {"name": "해운대", "region": "부산", "category": "attraction", "confidence": 0.95},
                  {"name": "송정 씨앗호떡", "region": null, "category": "restaurant", "confidence": 0.8}
                ]
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout);

        assertThat(places).hasSize(2);
        assertThat(places.get(0).name()).isEqualTo("해운대");
        assertThat(places.get(0).region()).isEqualTo("부산");
        assertThat(places.get(0).category()).isEqualTo("attraction");
        assertThat(places.get(0).confidence()).isEqualTo(0.95);
        assertThat(places.get(1).region()).isNull();
    }

    @Test
    void 빈_배열은_빈_리스트를_반환한다() {
        List<ExtractedPlace> places = PipelineOutputParser.parse("[]");
        assertThat(places).isEmpty();
    }

    @Test
    void 잘못된_JSON이면_예외를_던진다() {
        org.junit.jupiter.api.Assertions.assertThrows(
                PipelineException.class,
                () -> PipelineOutputParser.parse("이건 JSON이 아님"));
    }
}
