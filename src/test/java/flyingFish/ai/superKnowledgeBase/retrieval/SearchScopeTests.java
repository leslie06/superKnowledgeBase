package flyingFish.ai.superKnowledgeBase.retrieval;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchScopeTests {

    @Test
    void normalizesFolderPaths() {
        SearchScope scope = SearchScope.of(Arrays.asList("/资料/计算机/", " 资料/计算机", "", null, "资料/中医"),
                Arrays.asList(3L, null, 3L));

        assertThat(scope.folders()).containsExactly("资料/计算机", "资料/中医");
        assertThat(scope.documentIds()).containsExactly(3L);
        assertThat(scope.isAll()).isFalse();
    }

    @Test
    void emptyScopeMeansWholeKnowledgeBase() {
        assertThat(SearchScope.of(null, null).isAll()).isTrue();
        assertThat(SearchScope.of(List.of(" / "), List.of()).isAll()).isTrue();
    }
}
