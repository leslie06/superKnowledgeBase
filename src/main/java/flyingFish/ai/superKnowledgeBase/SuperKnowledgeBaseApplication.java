package flyingFish.ai.superKnowledgeBase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SuperKnowledgeBaseApplication {

	public static void main(String[] args) {
		SpringApplication.run(SuperKnowledgeBaseApplication.class, args);
	}

}
