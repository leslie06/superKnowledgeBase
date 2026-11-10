-- 引用本机原文件：用 scripts/import_dir.py --link 导入时不复制文件，只记录原文件的绝对路径，处理时直接读原文件。
-- 资料库里的电子书复制一份要多占 1.7GB；代价是原文件移动后，重新处理和查看原文件要先改回路径（文档管理「修改文件夹位置」或重新运行导入脚本），
-- 检索和问答不受影响（片段和向量都在库里）。网页上传拿不到原文件的路径，仍然复制。

ALTER TABLE document ADD COLUMN local_path TEXT;
ALTER TABLE document ALTER COLUMN file_path DROP NOT NULL;

COMMENT ON COLUMN document.local_path IS '引用本机原文件时原文件的绝对路径（scripts/import_dir.py --link 导入），这时 file_path 为空；为空表示文件已复制到 skb.storage-dir';
COMMENT ON COLUMN document.file_path IS '复制保存的原文件路径，相对于 skb.storage-dir，格式为 {kbId}/{sha256}.{扩展名}；引用本机原文件（local_path）时为空';
