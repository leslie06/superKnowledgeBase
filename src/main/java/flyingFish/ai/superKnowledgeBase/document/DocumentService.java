package flyingFish.ai.superKnowledgeBase.document;

import flyingFish.ai.superKnowledgeBase.common.NotFoundException;
import flyingFish.ai.superKnowledgeBase.ingestion.ChunkRepository;
import flyingFish.ai.superKnowledgeBase.ingestion.ChunkRepository.ChunkView;
import flyingFish.ai.superKnowledgeBase.ingestion.DocType;
import flyingFish.ai.superKnowledgeBase.ingestion.IngestionService;
import flyingFish.ai.superKnowledgeBase.ingestion.OcrPageRepository;
import flyingFish.ai.superKnowledgeBase.kb.KnowledgeBaseService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentService {

    /** ACCEPTED 新文档；UPDATED 同一来源（路径或文件名相同）内容有变化，替换了旧版本；DUPLICATE 内容相同的文件已存在 */
    public enum UploadStatus {ACCEPTED, UPDATED, DUPLICATE, REJECTED}

    public record UploadResult(String filename, UploadStatus status, Long documentId, String message) {
    }

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final FileStorage fileStorage;
    private final IngestionService ingestionService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final OcrPageRepository ocrPages;

    public DocumentService(DocumentRepository documents, ChunkRepository chunks, FileStorage fileStorage,
                           IngestionService ingestionService, KnowledgeBaseService knowledgeBaseService,
                           OcrPageRepository ocrPages) {
        this.documents = documents;
        this.chunks = chunks;
        this.fileStorage = fileStorage;
        this.ingestionService = ingestionService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.ocrPages = ocrPages;
    }

    public List<Document> list(long kbId) {
        knowledgeBaseService.get(kbId);
        return documents.findByKb(kbId);
    }

    public Document get(long id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("文档不存在：" + id));
    }

    /**
     * @param paths 与 files 一一对应的相对路径（文件夹导入时由前端或脚本提供），可以为 null
     */
    public List<UploadResult> upload(long kbId, List<MultipartFile> files, List<String> paths) {
        knowledgeBaseService.get(kbId);
        if (paths != null && paths.size() != files.size()) {
            throw new IllegalArgumentException("paths 的数量（" + paths.size() + "）与文件数量（" + files.size() + "）不一致");
        }
        List<UploadResult> results = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            results.add(uploadOne(kbId, files.get(i), paths == null ? null : sourcePath(paths.get(i))));
        }
        return results;
    }

    /**
     * 规范化文件夹导入时的相对路径：统一用 / 分隔、去掉开头的 /；只有文件名（不在文件夹里）或试图跳出所选文件夹时返回 null。
     */
    static String sourcePath(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String path = StringUtils.cleanPath(raw.strip());
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path.contains("/") && !path.startsWith("../") ? path : null;
    }

    private UploadResult uploadOne(long kbId, MultipartFile file, String sourcePath) {
        String filename = StringUtils.getFilename(StringUtils.cleanPath(String.valueOf(file.getOriginalFilename())));
        try {
            DocType.fromFilename(filename);
            if (file.isEmpty()) {
                throw new IllegalArgumentException("文件为空");
            }
        } catch (IllegalArgumentException e) {
            return new UploadResult(filename, UploadStatus.REJECTED, null, e.getMessage());
        }
        try {
            FileStorage.StoredFile stored = fileStorage.store(kbId, file);
            var existing = documents.findBySha256(kbId, stored.sha256());
            if (existing.isPresent()) {
                return duplicate(filename, existing.get());
            }
            var previous = documents.findBySource(kbId, sourcePath, filename);
            if (previous.isPresent()) {
                return replace(previous.get(), filename, stored);
            }
            long id = documents.insert(kbId, filename, sourcePath, stored.relativePath(), stored.sha256(),
                    mediaType(filename).toString(), stored.size());
            ingestionService.submit(id);
            return new UploadResult(filename, UploadStatus.ACCEPTED, id, "已加入处理队列");
        } catch (DuplicateKeyException e) {
            return new UploadResult(filename, UploadStatus.DUPLICATE, null, "内容相同的文件已存在");
        } catch (IOException e) {
            return new UploadResult(filename, UploadStatus.REJECTED, null, "保存文件失败：" + e.getMessage());
        }
    }

    /**
     * 同一来源的新版本：在原文档上换文件并重新处理，文档 id 不变。重新处理是原子替换（删旧片段、写新片段在同一事务），
     * 新版本处理完成前旧内容仍可检索，不会出现"检索不到"的空档；旧版本的文件和识别缓存随即删除。
     */
    private UploadResult replace(Document previous, String filename, FileStorage.StoredFile stored) {
        if (ingestionService.isProcessing(previous.id())) {
            // 新文件已写入存储，内容在库里没有其他文档引用（上面按 SHA-256 查过），直接删掉
            fileStorage.delete(stored.relativePath());
            return new UploadResult(filename, UploadStatus.REJECTED, previous.id(), "旧版本正在处理中，请处理完成后再上传");
        }
        documents.replaceFile(previous.id(), filename, stored.relativePath(), stored.sha256(),
                mediaType(filename).toString(), stored.size());
        if (!previous.filePath().equals(stored.relativePath())) {
            fileStorage.delete(previous.filePath());
        }
        ocrPages.deleteOrphans();
        ingestionService.submit(previous.id());
        return new UploadResult(filename, UploadStatus.UPDATED, previous.id(), "内容有变化，已替换旧版本并重新处理");
    }

    private static UploadResult duplicate(String filename, Document existing) {
        return new UploadResult(filename, UploadStatus.DUPLICATE, existing.id(),
                "内容相同的文件已存在：" + existing.filename());
    }

    public void reindex(long id) {
        get(id);
        if (ingestionService.isProcessing(id)) {
            throw new IllegalStateException("文档正在处理中，请稍后再试");
        }
        documents.updateStatus(id, DocumentStatus.PENDING, null);
        ingestionService.submit(id);
    }

    public void delete(long id) {
        Document doc = get(id);
        documents.delete(id);
        fileStorage.delete(doc.filePath());
        ocrPages.deleteOrphans();
    }

    public Path file(Document doc) {
        return fileStorage.resolve(doc.filePath());
    }

    public List<ChunkView> chunks(long id) {
        get(id);
        return chunks.findByDocument(id);
    }

    public static MediaType mediaType(String filename) {
        return MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);
    }
}
