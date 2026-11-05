-- 扫描版 PDF 文字识别（OCR）。识别一本几百页的书要几分钟到几十分钟，结果按文件内容缓存：
-- 处理中断后从缓存继续，以后修改切块规则重新处理时也不必重新识别；同一文件出现在多个知识库时共用。

CREATE TABLE ocr_page (
    sha256     CHAR(64)    NOT NULL,
    page       INT         NOT NULL,
    engine     VARCHAR(50) NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sha256, page)
);

COMMENT ON TABLE ocr_page IS 'PDF 页面的文字识别结果缓存，按文件内容（SHA-256）和页码保存';
COMMENT ON COLUMN ocr_page.sha256 IS '文件内容的 SHA-256，与 document.sha256 对应；没有文档引用时缓存随之删除';
COMMENT ON COLUMN ocr_page.page IS '页码，从 1 开始';
COMMENT ON COLUMN ocr_page.engine IS '识别引擎，如 apple-vision';
COMMENT ON COLUMN ocr_page.content IS '识别出的文字，每行一个换行';
COMMENT ON COLUMN ocr_page.created_at IS '识别时间';

ALTER TABLE document ADD COLUMN ocr_pages INT NOT NULL DEFAULT 0;
ALTER TABLE document ADD COLUMN ocr_done INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN document.ocr_pages IS '最近一次处理时需要文字识别的页数（没有文字层的页），0 表示不需要';
COMMENT ON COLUMN document.ocr_done IS '已完成文字识别的页数（含缓存命中），用于显示进度';
COMMENT ON COLUMN document.status IS '处理状态：PENDING 排队中 / PARSING 解析中 / OCR 文字识别中 / EMBEDDING 向量化中 / READY 已就绪 / FAILED 失败';
