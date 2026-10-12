package flyingFish.ai.superKnowledgeBase.kb;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    public record KnowledgeBaseRequest(String name, String description) {
    }

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @GetMapping
    public List<KnowledgeBase> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public KnowledgeBase get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBase create(@RequestBody KnowledgeBaseRequest request) {
        return service.create(request.name(), request.description());
    }

    @PutMapping("/{id}")
    public KnowledgeBase update(@PathVariable long id, @RequestBody KnowledgeBaseRequest request) {
        return service.update(id, request.name(), request.description());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
