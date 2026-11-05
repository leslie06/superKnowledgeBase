package flyingFish.ai.superKnowledgeBase.retrieval;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Objects;

/**
 * 检索范围：所选文件夹下的文档与单独选中的文档的并集，两者都为空表示整个知识库。
 * 知识库里资料很杂时（技术书、小说、个人证件放在一起），限定范围可以避免别的书里措辞相近的片段挤掉真正的答案。
 *
 * @param folders     文件夹路径，对应 document.source_path 的前缀（如"资料/计算机"），包含所有子文件夹；
 *                    按文件夹保存而不是展开成文档 ID，之后导入到该文件夹的文档也在范围内
 * @param documentIds 单独选中的文档，用于没有文件夹的单独上传的文件，或只想查某几本书
 */
public record SearchScope(List<String> folders, List<Long> documentIds) {

    public static final SearchScope ALL = new SearchScope(List.of(), List.of());

    public SearchScope {
        folders = folders == null ? List.of() : folders.stream()
                .filter(Objects::nonNull)
                .map(f -> f.strip().replaceAll("^/+|/+$", ""))
                .filter(f -> !f.isEmpty())
                .distinct()
                .toList();
        documentIds = documentIds == null ? List.of() : documentIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    public static SearchScope of(List<String> folders, List<Long> documentIds) {
        return new SearchScope(folders, documentIds);
    }

    /** 是否不限范围（整个知识库） */
    @JsonIgnore
    public boolean isAll() {
        return folders.isEmpty() && documentIds.isEmpty();
    }
}
