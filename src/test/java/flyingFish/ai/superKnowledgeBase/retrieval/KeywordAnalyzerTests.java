package flyingFish.ai.superKnowledgeBase.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordAnalyzerTests {

    @Test
    void splitsChineseIntoBigrams() {
        assertThat(KeywordAnalyzer.analyze("年假天数")).containsExactly("年假", "假天", "天数");
        assertThat(KeywordAnalyzer.analyze("车")).containsExactly("车");
    }

    @Test
    void keepsLowercaseWordsAndUnderscoreParts() {
        assertThat(KeywordAnalyzer.analyze("Q5 系统")).contains("q5", "系统");
        assertThat(KeywordAnalyzer.analyze("如RW_202607050001"))
                .contains("rw_202607050001", "rw", "202607050001");
        // 单个字母、数字区分度太低，不作为词项
        assertThat(KeywordAnalyzer.analyze("a、b、1")).isEmpty();
    }

    @Test
    void normalizesArticleNumbers() {
        assertThat(KeywordAnalyzer.analyze("第一千零七十九条 夫妻一方要求离婚的")).contains("第1079条");
        assertThat(KeywordAnalyzer.analyze("民法典第1079条")).contains("第1079条");
        assertThat(KeywordAnalyzer.analyze("民法典1254条")).contains("第1254条");
        assertThat(KeywordAnalyzer.analyze("第十七条")).contains("第17条");
        assertThat(KeywordAnalyzer.analyze("第七百零五条")).contains("第705条");
        assertThat(KeywordAnalyzer.analyze("第一百一十条")).contains("第110条");
        assertThat(KeywordAnalyzer.analyze("第二章 劳动合同的订立")).contains("第2章");
        // 全角数字先转半角
        assertThat(KeywordAnalyzer.analyze("第８２条")).contains("第82条");
        // 编号本身不再切二字组
        assertThat(KeywordAnalyzer.analyze("劳动合同法第三十九条")).containsExactly("第39条", "劳动", "动合", "合同", "同法");
    }

    @Test
    void queryTermsDropQuestionWords() {
        assertThat(KeywordAnalyzer.queryTerms("Q5 系统是用来做什么的？")).containsExactly("系统", "统是", "是用", "用来", "来做", "的", "q5");
        assertThat(KeywordAnalyzer.queryTerms("年假有几天？年假")).containsExactly("年假", "假有", "有几", "几天");
    }

    @Test
    void parsesChineseNumbers() {
        assertThat(KeywordAnalyzer.parseChinese("十")).isEqualTo(10);
        assertThat(KeywordAnalyzer.parseChinese("三十九")).isEqualTo(39);
        assertThat(KeywordAnalyzer.parseChinese("一千二百五十四")).isEqualTo(1254);
        assertThat(KeywordAnalyzer.parseChinese("两万零一")).isEqualTo(20001);
    }

    @Test
    void recognizesExactTerms() {
        assertThat(KeywordAnalyzer.exactTerms(List.of("第39条", "q5", "2025999500042779", "2018", "系统", "oa")))
                .containsExactly("第39条", "q5", "2025999500042779");
    }

    @Test
    void buildsTsvectorWithPositions() {
        assertThat(KeywordAnalyzer.toTsvector(List.of("年假", "q5", "年假"))).isEqualTo("'年假':1,3 'q5':2");
        assertThat(KeywordAnalyzer.toTsquery(List.of("年假", "q5"))).isEqualTo("'年假' | 'q5'");
    }
}
