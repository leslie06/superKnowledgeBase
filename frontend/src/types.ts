export interface KnowledgeBase {
  id: number
  name: string
  description: string | null
  embeddingModel: string
  createdAt: string
  documentCount: number
}

export type DocumentStatus = 'PENDING' | 'PARSING' | 'OCR' | 'EMBEDDING' | 'READY' | 'FAILED'

export interface KbDocument {
  id: number
  kbId: number
  filename: string
  /** 文件夹导入时的相对路径（含文件夹名和文件名），单独上传的文件为 null */
  sourcePath: string | null
  mimeType: string
  sizeBytes: number
  status: DocumentStatus
  errorMsg: string | null
  chunkCount: number
  /** 最近一次处理时需要文字识别的页数（扫描页），0 表示不需要 */
  ocrPages: number
  /** 已完成文字识别的页数 */
  ocrDone: number
  /** LLM 生成的文档摘要，作为特殊片段参与检索；还没有生成时为 null */
  summary: string | null
  createdAt: string
  updatedAt: string
}

export interface UploadResult {
  filename: string
  /** UPDATED：路径（单独上传时为文件名）相同但内容有变化，替换了旧版本 */
  status: 'ACCEPTED' | 'UPDATED' | 'DUPLICATE' | 'REJECTED'
  documentId: number | null
  message: string
}

export interface ChunkView {
  id: number
  chunkIndex: number
  content: string
  headingPath: string | null
  location: string | null
}

export interface RetrievedChunk {
  chunkId: number
  documentId: number
  filename: string
  headingPath: string | null
  location: string | null
  content: string
  /** 与问题的余弦相似度 */
  score: number
  /** BM25 得分，未被关键词召回时为 null */
  keywordScore: number | null
  vectorRank: number | null
  keywordRank: number | null
  /** 交叉编码器精排分数（0~1），未精排时为 null */
  rerankScore: number | null
  /** 是否精确命中问题中的编号（条款号、型号等） */
  exactMatch: boolean
  /** 是否通过相关性过滤，问答时只使用通过的片段 */
  relevant: boolean
  /** 片段类型：TEXT 正文 / SUMMARY 文档摘要 */
  kind: 'TEXT' | 'SUMMARY'
}

export interface Citation {
  index: number
  chunkId: number
  documentId: number
  filename: string
  headingPath: string | null
  location: string | null
  content: string
  score: number
}

/** 检索范围：所选文件夹下的文档与单独选中的文档取并集，两者都为空表示整个知识库 */
export interface SearchScope {
  /** 文件夹路径，对应文档 sourcePath 的前缀，包含子文件夹 */
  folders: string[]
  documentIds: number[]
}

export interface Conversation {
  id: number
  kbId: number
  title: string
  scope: SearchScope
  createdAt: string
  updatedAt: string
}

export interface VerificationIssue {
  /** 回答中的原句 */
  text: string
  reason: string
}

/** 回答核对结果：两个列表都为空表示回答内容都能在参考资料中找到依据 */
export interface AnswerVerification {
  /** 参考资料无法支持的内容（编造、常识补充、资料之外的推断） */
  unsupported: VerificationIssue[]
  /** 标注了 [n] 但第 n 条资料并不支持的句子 */
  citationErrors: VerificationIssue[]
}

export type FeedbackReason = 'NOT_FOUND' | 'WRONG' | 'INCOMPLETE' | 'CITATION' | 'OTHER'

/** 用户对回答的评价 */
export interface Feedback {
  rating: 'UP' | 'DOWN'
  /** 没用的原因，有用时为 null */
  reason: FeedbackReason | null
  note: string | null
}

/** 一轮补充检索（迭代检索）：资料不够回答时，缺什么、换了哪些说法去查、补进几个新片段 */
export interface FollowUp {
  round: number
  missing: string | null
  queries: string[]
  added: number
}

export interface ChatMessage {
  id: number | null
  role: 'user' | 'assistant'
  content: string
  citations: Citation[]
  /** 没有核对时为 null（未开启、无命中直接拒答、停止生成或核对失败） */
  verification?: AnswerVerification | null
  feedback?: Feedback | null
  /** 各环节耗时（毫秒）：rewriteMs 查询改写、retrieveMs 召回与精排、firstTokenMs 首字、answerMs 生成完成 */
  timings?: Record<string, number>
  /** 汇总类问题拆出的子问题（多步检索），普通问题为空 */
  subQueries?: string[]
  /** 整篇交给模型的小文档的文件名 */
  fullTextDocuments?: string[]
  followUps?: FollowUp[]
  /** 前端记录：按下发送的时间（毫秒时间戳），用于显示实时耗时 */
  startedAt?: number
  streaming?: boolean
  /** 回答已生成，正在核对 */
  verifying?: boolean
  error?: string
}

/** 坏案例类型：差评、有用、拒答、核对发现问题、精排未生效、慢（改写 + 检索 + 首字超过 15 秒） */
export type InsightFlag = 'DOWN' | 'UP' | 'REFUSED' | 'VERIFY_ISSUE' | 'UNRERANKED' | 'SLOW'

/** 毫秒；没有数据时为 null */
export interface Timing {
  p50: number | null
  p90: number | null
}

export interface InsightStats {
  answers: number
  up: number
  down: number
  refused: number
  verifyIssues: number
  unreranked: number
  slow: number
  rewrite: Timing
  retrieve: Timing
  firstToken: Timing
}

export interface InsightDaily {
  date: string
  answers: number
  down: number
  refused: number
  verifyIssues: number
}

export interface InsightHit {
  index: number
  chunkId: number
  documentId: number
  filename: string
  headingPath: string | null
  location: string | null
  snippet: string
  score: number | null
  rerankScore: number | null
}

export interface InsightCase {
  messageId: number
  conversationId: number
  createdAt: string
  question: string | null
  answer: string
  searchQuery: string | null
  /** 汇总类问题拆出的子问题，普通问题为空 */
  subQueries: string[]
  followUps: FollowUp[]
  scope: SearchScope
  hits: InsightHit[]
  timings: Record<string, number>
  verification: AnswerVerification | null
  feedback: Feedback | null
  flags: InsightFlag[]
}

export interface InsightOverview {
  days: number
  stats: InsightStats
  daily: InsightDaily[]
  cases: InsightCase[]
}

/** 从真实问答中整理出的检索评测题，字段与 scripts/eval_retrieval.py 的题集 CSV 对应 */
export interface EvalCase {
  id: number
  kbId: number
  question: string
  expectedFile: string | null
  expectedSection: string | null
  expectedText: string | null
  /** 文件夹路径，| 分隔；为空表示整个知识库 */
  scope: string | null
  note: string | null
  messageId: number | null
  createdAt: string
}
