package com.laishengkai.digitalperson.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryTextSimilarityTest {

    @Test
    void normalizesSpacingWidthCaseAndPunctuation() {
        assertThat(MemoryTextNormalizer.normalize(" 王 者 荣 耀！ "))
                .isEqualTo("王者荣耀");
        assertThat(MemoryTextNormalizer.normalize("ＡＢＣ-test"))
                .isEqualTo("abctest");
    }

    @Test
    void toleratesOneWrongChineseCharacterInAName() {
        assertThat(MemoryTextSimilarity.similarity("林小雨", "林晓雨"))
                .isGreaterThanOrEqualTo(0.60);
    }

    @Test
    void recognizesAliasesEmbeddedInLongerDescriptions() {
        assertThat(MemoryTextSimilarity.similarity(
                "王者",
                "用户最近一直在玩王者荣耀"
        )).isGreaterThan(0.70);
    }

    @Test
    void keepsUnrelatedNamesBelowTheDefaultThreshold() {
        assertThat(MemoryTextSimilarity.similarity("小林", "张伟"))
                .isLessThan(0.60);
    }
}
