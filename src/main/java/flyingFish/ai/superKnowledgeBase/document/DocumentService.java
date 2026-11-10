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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentService {

    /**
     * ACCEPTED 新文档；UPDATED 同一来源（路径或文件名相同）内容有变化，替换了旧版本；DUPLICATE 内容相同的文件已存在；
     * LINKED 已有文档由复制保存改为引用本机原文件（删除了副本）；MOVED 引用的原文件换了位置，已更新路径
     */
    public enum UploadStatus {ACCEPTED, UPDATED, DUPLICATE, LINKED, MOVED, REJECTED}

    public record UploadResult(String filename, UploadStatus status, Long documentId, String message) {
    }

    /**
     * 引用本机原文件导入的一个文件
     *
     * @param path       原文件的绝对路径
     * @param sourcePath 相对路径（以所选文件夹名开头），与上传时的 paths 相同
     */
    public record LocalFile(String path, String sourcePath) {
    }

    /**
     * 修改文件夹位置的结果
     *
     * @param missing 新位置找不到的文件（旧路径）
     * @param changed     新位置有文件但内容不同的（旧路径），没有修改，重新运行导入脚本可按新版本更新
     * @param reprocessed 之前因为找不到原文件处理失败、改完位置后重新处理的文档数
     */
    public record RelinkResult(int updated, List<String> missing, List<String> changed, int reprocessed) {
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
                if (existing.get().localPath() != null) {
                    // 已有文档引用的是本机原文件，刚复制的这份没人用
                    fileStorage.delete(stored.relativePath());
                }
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
        // 旧版本引用的本机原文件不能删
        if (previous.filePath() != null && !previous.filePath().equals(stored.relativePath())) {
            fileStorage.delete(previous.filePath());
        }
        ocrPages.deleteOrphans();
        ingestionService.submit(previous.id());
        return new UploadResult(filename, UploadStatus.UPDATED, previous.id(), "内容有变化，已替换旧版本并重新处理");
    }

    /**
     * 引用本机原文件导入（scripts/import_dir.py --link）：不复制文件，只记录原文件的绝对路径。后端和文件在同一台电脑上才能用。
     * 按 SHA-256 判断：库里已有内容相同的文档时，复制保存的改为引用原文件并删掉副本（LINKED），引用的原文件换了位置时更新路径
     * （MOVED），都不用重新处理；同一来源内容变了按新版本替换；否则新增。
     */
    public List<UploadResult> linkLocal(long kbId, List<LocalFile> files) {
        knowledgeBaseService.get(kbId);
        return files.stream().map(f -> linkOne(kbId, f)).toList();
    }

    private UploadResult linkOne(long kbId, LocalFile file) {
        Path path = Path.of(String.valueOf(file.path())).normalize();
        String filename = String.valueOf(path.getFileName());
        String sourcePath = sourcePath(file.sourcePath());
        try {
            DocType.fromFilename(filename);
            if (!path.isAbsolute() || !Files.isRegularFile(path)) {
                throw new IllegalArgumentException("文件不存在或不是绝对路径：" + path);
            }
            if (Files.size(path) == 0) {
                throw new IllegalArgumentException("文件为空");
            }
        } catch (IllegalArgumentException | IOException e) {
            return new UploadResult(filename, UploadStatus.REJECTED, null, e.getMessage());
        }
        try {
            String sha = FileStorage.sha256(path);
            long size = Files.size(path);
            String localPath = path.toString();
            var existing = documents.findBySha256(kbId, sha);
            if (existing.isPresent()) {
                Document doc = existing.get();
                // 同一本书在目录里放了几份时，引用文档原来路径对应的那一份，不跟着扫描顺序来回换
                Path target = preferredCopy(path, sourcePath, doc);
                String targetPath = target.toString();
                if (targetPath.equals(doc.localPath())
                        || doc.localPath() != null && Files.isRegularFile(Path.of(doc.localPath()))) {
                    // 引用的原文件还在，这一份只是内容相同的另一个副本
                    return duplicate(filename, doc);
                }
                if (ingestionService.isProcessing(doc.id())) {
                    return new UploadResult(filename, UploadStatus.REJECTED, doc.id(), "文档正在处理中，请处理完成后再运行");
                }
                documents.updateLocalPath(doc.id(), targetPath, target.equals(path) ? sourcePath : null);
                if (doc.filePath() != null) {
                    fileStorage.delete(doc.filePath());
                    return new UploadResult(filename, UploadStatus.LINKED, doc.id(),
                            "改为引用原文件 " + targetPath + "，删除副本 " + size / 1024 / 1024 + " MB");
                }
                return new UploadResult(filename, UploadStatus.MOVED, doc.id(), "原文件位置已更新：" + doc.localPath() + " → " + targetPath);
            }
            var previous = documents.findBySource(kbId, sourcePath, filename);
            if (previous.isPresent()) {
                Document doc = previous.get();
                if (ingestionService.isProcessing(doc.id())) {
                    return new UploadResult(filename, UploadStatus.REJECTED, doc.id(), "旧版本正在处理中，请处理完成后再运行");
                }
                documents.replaceWithLocal(doc.id(), filename, localPath, sha, mediaType(filename).toString(), size);
                if (doc.filePath() != null) {
                    fileStorage.delete(doc.filePath());
                }
                ocrPages.deleteOrphans();
                ingestionService.submit(doc.id());
                return new UploadResult(filename, UploadStatus.UPDATED, doc.id(), "内容有变化，已替换旧版本并重新处理");
            }
            long id = documents.insert(kbId, filename, sourcePath, null, localPath, sha, mediaType(filename).toString(), size);
            ingestionService.submit(id);
            return new UploadResult(filename, UploadStatus.ACCEPTED, id, "已加入处理队列（引用原文件，不复制）");
        } catch (DuplicateKeyException e) {
            return new UploadResult(filename, UploadStatus.DUPLICATE, null, "内容相同的文件已存在");
        } catch (IOException e) {
            return new UploadResult(filename, UploadStatus.REJECTED, null, "读取文件失败：" + e.getMessage());
        }
    }

    /**
     * 内容相同的文件在目录里有多份时（"计算机/java/X.pdf"和"计算机/并发/X.pdf"），优先用文档原来的相对路径对应的那一份：
     * 由这一份的绝对路径和相对路径推出所选目录的位置，再拼上文档原来的相对路径，存在且内容相同就用它，否则用这一份。
     */
    private static Path preferredCopy(Path path, String sourcePath, Document doc) {
        if (sourcePath == null || doc.sourcePath() == null || sourcePath.equals(doc.sourcePath())) {
            return path;
        }
        String absolute = path.toString();
        if (!absolute.endsWith("/" + sourcePath)) {
            return path;
        }
        Path original = Path.of(absolute.substring(0, absolute.length() - sourcePath.length()) + doc.sourcePath());
        try {
            return Files.isRegularFile(original) && FileStorage.sha256(original).equals(doc.sha256()) ? original : path;
        } catch (IOException e) {
            return path;
        }
    }

    /**
     * 修改文件夹位置：引用的原文件在 from 文件夹（含子文件夹）里的文档，路径改到 to 下的对应位置。
     * 新位置的文件必须存在且内容（SHA-256）不变才修改；内容变了的不改，列出来，重新运行导入脚本会按新版本更新。
     */
    public RelinkResult relink(long kbId, String from, String to) {
        knowledgeBaseService.get(kbId);
        String source = folder(from);
        String target = folder(to);
        if (source.equals(target)) {
            throw new IllegalArgumentException("新位置与旧位置相同");
        }
        List<Document> docs = documents.findLocalUnder(kbId, source);
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("没有引用「" + source + "」里原文件的文档");
        }
        int updated = 0;
        int reprocessed = 0;
        List<String> missing = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Document doc : docs) {
            Path moved = Path.of(target + doc.localPath().substring(source.length()));
            try {
                if (!Files.isRegularFile(moved)) {
                    missing.add(doc.localPath());
                } else if (!FileStorage.sha256(moved).equals(doc.sha256())) {
                    changed.add(doc.localPath());
                } else {
                    documents.updateLocalPath(doc.id(), moved.toString(), null);
                    updated++;
                    // 找不到原文件时处理失败的，现在能读到了
                    if (DocumentStatus.FAILED.name().equals(doc.status()) && !ingestionService.isProcessing(doc.id())) {
                        documents.updateStatus(doc.id(), DocumentStatus.PENDING, null);
                        ingestionService.submit(doc.id());
                        reprocessed++;
                    }
                }
            } catch (IOException e) {
                missing.add(doc.localPath());
            }
        }
        return new RelinkResult(updated, missing, changed, reprocessed);
    }

    /** 文件夹的绝对路径，去掉末尾的 / */
    private static String folder(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("请填写文件夹路径");
        }
        Path path = Path.of(raw.strip()).normalize();
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("请填写完整路径（以 / 开头）：" + raw);
        }
        return path.toString();
    }

    /** 引用本机原文件、但原文件已经找不到的文档 */
    public boolean fileMissing(Document doc) {
        return doc.localPath() != null && !Files.isRegularFile(Path.of(doc.localPath()));
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
        // 引用的本机原文件是用户的文件，不删
        if (doc.filePath() != null) {
            fileStorage.delete(doc.filePath());
        }
        ocrPages.deleteOrphans();
    }

    public Path file(Document doc) {
        Path path = fileStorage.locate(doc);
        if (!Files.isRegularFile(path)) {
            throw new NotFoundException("原文件不存在：" + path + "（移动过的话，可在文档管理「修改文件夹位置」或重新运行导入脚本）");
        }
        return path;
    }

    public List<ChunkView> chunks(long id) {
        get(id);
        return chunks.findByDocument(id);
    }

    public static MediaType mediaType(String filename) {
        return MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);
    }
}
