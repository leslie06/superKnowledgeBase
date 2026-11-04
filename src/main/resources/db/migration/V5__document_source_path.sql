-- 文件夹导入时记录文件的相对路径。资料通常按文件夹分类（部门、产品、年份），
-- 路径会拼进向量文本，"XX 部门的 YY 规定"这类问题能靠目录名命中，以后也可以按目录限定检索范围。

ALTER TABLE document ADD COLUMN source_path TEXT;

COMMENT ON COLUMN document.source_path IS '文件夹导入时文件的相对路径，含所选文件夹名和文件名（如"资料/人事制度/考勤管理办法.docx"）；单独上传的文件为空';
