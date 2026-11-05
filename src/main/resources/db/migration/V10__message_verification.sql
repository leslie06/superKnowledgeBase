-- 回答核对。生成回答后再请 LLM 对照参考资料检查一遍：资料中找不到依据的内容（编造、常识补充、推断）
-- 和 [n] 引用与句子对不上的地方，结果显示在回答下方，翻看历史对话时也能看到。

ALTER TABLE message ADD COLUMN verification JSONB;

COMMENT ON COLUMN message.verification IS '回答核对结果：{"unsupported": [{"text", "reason"}], "citationErrors": [{"text", "reason"}]}，两个列表都为空表示通过；为空表示没有核对（未开启、无命中直接拒答、停止生成或核对失败）';
