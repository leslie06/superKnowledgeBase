package flyingFish.ai.superKnowledgeBase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 连接的是开发用的真实数据库：关掉启动时的摘要补生成，否则测试会调用 DeepSeek 并写入摘要
@SpringBootTest(properties = "skb.summary.enabled=false")
class SuperKnowledgeBaseApplicationTests {

	@Test
	void contextLoads() {
	}

}
