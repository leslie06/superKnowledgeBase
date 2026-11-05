package flyingFish.ai.superKnowledgeBase.chat;

import flyingFish.ai.superKnowledgeBase.retrieval.SearchScope;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ChatController {

    /** @param scope 新建会话时的检索范围，不传表示整个知识库 */
    public record ConversationRequest(String title, SearchScope scope) {
    }

    public record ChatRequest(String question) {
    }

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @GetMapping("/knowledge-bases/{kbId}/conversations")
    public List<Conversation> list(@PathVariable long kbId) {
        return service.list(kbId);
    }

    @PostMapping("/knowledge-bases/{kbId}/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@PathVariable long kbId, @RequestBody(required = false) ConversationRequest request) {
        return service.create(kbId, request == null ? null : request.title(),
                request == null || request.scope() == null ? SearchScope.ALL : request.scope());
    }

    @PutMapping("/conversations/{id}")
    public Conversation rename(@PathVariable long id, @RequestBody ConversationRequest request) {
        return service.rename(id, request.title());
    }

    @PutMapping("/conversations/{id}/scope")
    public Conversation updateScope(@PathVariable long id, @RequestBody(required = false) SearchScope scope) {
        return service.updateScope(id, scope == null ? SearchScope.ALL : scope);
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessage> messages(@PathVariable long id) {
        return service.messages(id);
    }

    @PostMapping(value = "/conversations/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@PathVariable long id, @RequestBody ChatRequest request) {
        return service.chat(id, request.question());
    }
}
