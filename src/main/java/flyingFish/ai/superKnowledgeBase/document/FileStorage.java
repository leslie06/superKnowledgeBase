package flyingFish.ai.superKnowledgeBase.document;

import flyingFish.ai.superKnowledgeBase.common.SkbProperties;
import flyingFish.ai.superKnowledgeBase.ingestion.DocType;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 原始文件存储：{storageDir}/{kbId}/{sha256}.{ext}。按内容哈希命名，天然去重，也避免文件名中的特殊字符问题。
 */
@Component
public class FileStorage {

    public record StoredFile(String relativePath, String sha256, long size) {
    }

    private final Path root;

    public FileStorage(SkbProperties properties) throws IOException {
        this.root = Path.of(properties.storageDir()).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("tmp"));
    }

    public StoredFile store(long kbId, MultipartFile file) throws IOException {
        Path temp = Files.createTempFile(root.resolve("tmp"), "upload-", ".part");
        try {
            MessageDigest digest = sha256();
            try (InputStream in = file.getInputStream();
                 OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), digest)) {
                in.transferTo(out);
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            String relative = kbId + "/" + sha + "." + DocType.extension(file.getOriginalFilename());
            Path target = resolve(relative);
            Files.createDirectories(target.getParent());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredFile(relative, sha, Files.size(target));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public Path resolve(String relativePath) {
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("非法文件路径：" + relativePath);
        }
        return path;
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deleteKnowledgeBase(long kbId) {
        try {
            FileSystemUtils.deleteRecursively(root.resolve(String.valueOf(kbId)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
