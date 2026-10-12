package flyingFish.ai.superKnowledgeBase.document;

import flyingFish.ai.superKnowledgeBase.common.NotFoundException;
import flyingFish.ai.superKnowledgeBase.ingestion.ChunkRepository;
import flyingFish.ai.superKnowledgeBase.ingestion.ChunkRepository.ChunkView;
import flyingFish.ai.superKnowledgeBase.ingestion.DocType;
import flyingFish.ai.superKnowledgeBase.ingestion.IngestionService;
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

    public enum UploadStatus {ACCEPTED, DUPLICATE, REJECTED}

    public record UploadResult(String filename, UploadStatus status, Long documentId, String message) {
    }

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final FileStorage fileStorage;
    private final IngestionService ingestionService;
    private final KnowledgeBaseService knowledgeBaseService;

    public DocumentService(DocumentRepository documents, ChunkRepository chunks, FileStorage fileStorage,
                           IngestionService ingestionService, KnowledgeBaseService knowledgeBaseService) {
        this.documents = documents;
        this.chunks = chunks;
        this.fileStorage = fileStorage;
        this.ingestionService = ingestionService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    public List<Document> list(long kbId) {
        knowledgeBaseService.get(kbId);
        return documents.findByKb(kbId);
    }

    public Document get(long id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("文档不存在：" + id));
    }

    public List<UploadResult> upload(long kbId, List<MultipartFile> files) {
        knowledgeBaseService.get(kbId);
        List<UploadResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(uploadOne(kbId, file));
        }
        return results;
    }

    private UploadResult uploadOne(long kbId, MultipartFile file) {
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
            long id = documents.insert(kbId, filename, stored.relativePath(), stored.sha256(),
                    mediaType(filename).toString(), stored.size());
            ingestionService.submit(id);
            return new UploadResult(filename, UploadStatus.ACCEPTED, id, "已加入处理队列");
        } catch (DuplicateKeyException e) {
            return new UploadResult(filename, UploadStatus.DUPLICATE, null, "内容相同的文件已存在");
        } catch (IOException e) {
            return new UploadResult(filename, UploadStatus.REJECTED, null, "保存文件失败：" + e.getMessage());
        }
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
