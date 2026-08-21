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
    void 예상하지_못한_필드가_있어도_무시하고_파싱한다() {
        String stdout = """
                [
                  {"name": "장소", "region": null, "category": "cafe", "confidence": 0.9, "evidence": "화면 자막"}
                ]
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout);

        assertThat(places).hasSize(1);
        assertThat(places.get(0).name()).isEqualTo("장소");
        assertThat(places.get(0).category()).isEqualTo("cafe");
        assertThat(places.get(0).confidence()).isEqualTo(0.9);
    }

    @Test
    void 잘못된_JSON이면_예외를_던진다() {
        org.junit.jupiter.api.Assertions.assertThrows(
                PipelineException.class,
                () -> PipelineOutputParser.parse("이건 JSON이 아님"));
    }

    @Test
    void dayNumber와_orderInDay가_있으면_함께_파싱된다() {
        String stdout = """
                [
                  {"name": "해운대", "region": "부산", "category": "attraction", "confidence": 0.95, "dayNumber": 1, "orderInDay": 1},
                  {"name": "서면", "region": "부산", "category": "shopping", "confidence": 0.9, "dayNumber": 2, "orderInDay": 1}
                ]
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout);

        assertThat(places.get(0).dayNumber()).isEqualTo(1);
        assertThat(places.get(0).orderInDay()).isEqualTo(1);
        assertThat(places.get(1).dayNumber()).isEqualTo(2);
    }

    @Test
    void dayNumber와_orderInDay가_없으면_null로_파싱된다() {
        String stdout = """
                [
                  {"name": "해운대", "region": "부산", "category": "attraction", "confidence": 0.95}
                ]
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout);

        assertThat(places.get(0).dayNumber()).isNull();
        assertThat(places.get(0).orderInDay()).isNull();
    }
}
