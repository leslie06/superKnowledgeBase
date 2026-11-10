package flyingFish.ai.superKnowledgeBase.document;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import flyingFish.ai.superKnowledgeBase.document.DocumentService.UploadResult;
import flyingFish.ai.superKnowledgeBase.ingestion.ChunkRepository.ChunkView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DocumentController {

    /** 文档列表的一项：文档本身，加上引用的本机原文件是否已经找不到 */
    public record DocumentItem(@JsonUnwrapped Document document, boolean fileMissing) {
    }

    public record LinkRequest(List<DocumentService.LocalFile> files) {
    }

    public record RelinkRequest(String from, String to) {
    }

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public List<DocumentItem> list(@PathVariable long kbId) {
        return service.list(kbId).stream().map(d -> new DocumentItem(d, service.fileMissing(d))).toList();
    }

    /**
     * 引用本机原文件导入（scripts/import_dir.py --link），不复制文件。后端会按给出的路径读本机文件，所以只接受本机发来的请求，
     * 否则局域网里的人可以把这台电脑上的任意文件导入知识库再读出来。
     */
    @PostMapping("/knowledge-bases/{kbId}/documents/local")
    public List<UploadResult> linkLocal(@PathVariable long kbId, @RequestBody LinkRequest request,
                                        HttpServletRequest http) {
        requireLocal(http);
        return service.linkLocal(kbId, request.files() == null ? List.of() : request.files());
    }

    /** 原文件所在的文件夹整体移动后，修改文档记录的原文件位置；同样只接受本机发来的请求 */
    @PostMapping("/knowledge-bases/{kbId}/documents/relink")
    public DocumentService.RelinkResult relink(@PathVariable long kbId, @RequestBody RelinkRequest request,
                                               HttpServletRequest http) {
        requireLocal(http);
        return service.relink(kbId, request.from(), request.to());
    }

    private static void requireLocal(HttpServletRequest http) {
        try {
            if (!InetAddress.getByName(http.getRemoteAddr()).isLoopbackAddress()) {
                throw new IllegalStateException("只能在运行后端的这台电脑上操作本机文件");
            }
        } catch (UnknownHostException e) {
            throw new IllegalStateException("只能在运行后端的这台电脑上操作本机文件");
        }
    }

    /**
     * 上传文件；文件夹导入时，表单里为每个文件带一个 paths 字段（顺序与 files 一致），记录文件的相对路径。
     * paths 从原始参数中读取：用 {@code @RequestParam List<String>} 接收时，只有一个值会被按逗号拆开，含逗号的路径会出错。
     */
    @PostMapping("/knowledge-bases/{kbId}/documents")
    public List<UploadResult> upload(@PathVariable long kbId, @RequestParam("files") List<MultipartFile> files,
                                     @RequestParam MultiValueMap<String, String> form) {
        return service.upload(kbId, files, form.get("paths"));
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
