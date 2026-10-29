package flyingFish.ai.superKnowledgeBase.retrieval;

import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * 调用本地 Rerank 服务（scripts/rerank_server.py），接口与 HuggingFace TEI 的 /rerank 相同。
 */
@Component
public class RerankClient {

    record RerankRequest(String query, List<String> texts) {
    }

    record RerankResult(int index, double score) {
    }

    private final RestClient restClient;

    public RerankClient(SkbProperties properties) {
        SkbProperties.Rerank rerank = properties.rerank();
        Duration timeout = rerank.timeout() == null ? Duration.ofSeconds(10) : rerank.timeout();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(rerank.baseUrl())
                // 缓冲请求体以带上 Content-Length，否则 JDK HttpClient 用分块传输，Python 的 http.server 读不到请求体
                .requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
                .build();
    }

    /** 返回每个片段的相关性分数（0~1），顺序与 texts 一致。服务不可用时抛出异常，由调用方决定如何降级。 */
    public double[] score(String query, List<String> texts) {
        RerankResult[] results = restClient.post()
                .uri("/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RerankRequest(query, texts))
                .retrieve()
                .body(RerankResult[].class);
        if (results == null || results.length != texts.size()) {
            throw new IllegalStateException("Rerank 服务返回的结果数量与片段数不一致");
        }
        double[] scores = new double[texts.size()];
        for (RerankResult r : results) {
            scores[r.index()] = r.score();
        }
        return scores;
    }
}
