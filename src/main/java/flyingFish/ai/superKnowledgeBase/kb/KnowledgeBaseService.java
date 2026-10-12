package flyingFish.ai.superKnowledgeBase.kb;

import flyingFish.ai.superKnowledgeBase.common.NotFoundException;
import flyingFish.ai.superKnowledgeBase.document.FileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final FileStorage fileStorage;
    private final String embeddingModel;

    public KnowledgeBaseService(KnowledgeBaseRepository repository, FileStorage fileStorage,
                                @Value("${spring.ai.ollama.embedding.options.model}") String embeddingModel) {
        this.repository = repository;
        this.fileStorage = fileStorage;
        this.embeddingModel = embeddingModel;
    }

    public List<KnowledgeBase> list() {
        return repository.findAll();
    }

    public KnowledgeBase get(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("知识库不存在：" + id));
    }

    public KnowledgeBase create(String name, String description) {
        return get(repository.insert(requireName(name), description, embeddingModel));
    }

    public KnowledgeBase update(long id, String name, String description) {
        get(id);
        repository.update(id, requireName(name), description);
        return get(id);
    }

    public void delete(long id) {
        get(id);
        repository.delete(id);
        fileStorage.deleteKnowledgeBase(id);
    }

    private static String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        return name.trim();
    }
}
