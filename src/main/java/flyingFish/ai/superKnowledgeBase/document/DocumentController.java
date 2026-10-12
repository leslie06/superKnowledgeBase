package flyingFish.ai.superKnowledgeBase.document;

import flyingFish.ai.superKnowledgeBase.document.DocumentService.UploadResult;
import flyingFish.ai.superKnowledgeBase.ingestion.ChunkRepository.ChunkView;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public List<Document> list(@PathVariable long kbId) {
        return service.list(kbId);
    }

    @PostMapping("/knowledge-bases/{kbId}/documents")
    public List<UploadResult> upload(@PathVariable long kbId, @RequestParam("files") List<MultipartFile> files) {
        return service.upload(kbId, files);
    }

    @GetMapping("/documents/{id}")
    public Document get(@PathVariable long id) {
        return service.get(id);
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PostMapping("/documents/{id}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void reindex(@PathVariable long id) {
        service.reindex(id);
    }

    @GetMapping("/documents/{id}/chunks")
    public List<ChunkView> chunks(@PathVariable long id) {
        return service.chunks(id);
    }

    /** 浏览器内联打开原文件（PDF 可配合 #page=N 定位到引用页）。 */
    @GetMapping("/documents/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable long id) {
        Document doc = service.get(id);
        return ResponseEntity.ok()
                .contentType(DocumentService.mediaType(doc.filename()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(doc.filename(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(service.file(doc)));
    }
}
