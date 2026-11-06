-- 回答反馈与评测题集。评测题集是手工出的，已经接近满分，真实使用中哪些问题答不好系统并不知道：
-- 用户对回答点"有用 / 没用"并选原因，问答分析页汇总差评、拒答、核对有问题、精排未生效、慢的回答，
-- 确认是检索问题的加入评测题集（eval_case），导出成 scripts/eval_retrieval.py 的 CSV 格式，以后每次改动都回归这些题。

ALTER TABLE message ADD COLUMN feedback VARCHAR(10);
ALTER TABLE message ADD COLUMN feedback_reason VARCHAR(30);
ALTER TABLE message ADD COLUMN feedback_note TEXT;
ALTER TABLE message ADD COLUMN feedback_at TIMESTAMPTZ;

COMMENT ON COLUMN message.feedback IS '用户对回答的评价：UP 有用 / DOWN 没用；为空表示没有评价';
COMMENT ON COLUMN message.feedback_reason IS '评价为没用时的原因：NOT_FOUND 没找到 / WRONG 答错了 / INCOMPLETE 不完整 / CITATION 引用不对 / OTHER 其他';
COMMENT ON COLUMN message.feedback_note IS '评价时的补充说明，如正确答案在哪个文件';
COMMENT ON COLUMN message.feedback_at IS '评价时间';

CREATE TABLE eval_case (
    id               BIGSERIAL PRIMARY KEY,
    kb_id            BIGINT      NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    question         TEXT        NOT NULL,
    expected_file    TEXT,
    expected_section TEXT,
    expected_text    TEXT,
    scope            TEXT,
    note             TEXT,
    message_id       BIGINT      REFERENCES message (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_eval_case_kb ON eval_case (kb_id);

COMMENT ON TABLE eval_case IS '从真实问答中整理出的检索评测题，导出格式与 scripts/eval_retrieval.py 的题集 CSV 相同';
COMMENT ON COLUMN eval_case.id IS '主键';
COMMENT ON COLUMN eval_case.kb_id IS '所属知识库';
COMMENT ON COLUMN eval_case.question IS '用户问题';
COMMENT ON COLUMN eval_case.expected_file IS '预期命中的文件名（子串匹配，| 分隔多个可接受的值）；三个 expected 都为空表示无答案题';
COMMENT ON COLUMN eval_case.expected_section IS '预期章节路径（子串匹配）';
COMMENT ON COLUMN eval_case.expected_text IS '预期片段中包含的原文（子串匹配，最精确）';
COMMENT ON COLUMN eval_case.scope IS '检索范围：文件夹路径，| 分隔；为空表示整个知识库';
COMMENT ON COLUMN eval_case.note IS '备注';
COMMENT ON COLUMN eval_case.message_id IS '来源回答；回答被删除后为空';
COMMENT ON COLUMN eval_case.created_at IS '创建时间';
