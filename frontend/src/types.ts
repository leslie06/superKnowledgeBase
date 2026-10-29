export interface KnowledgeBase {
  id: number
  name: string
  description: string | null
  embeddingModel: string
  createdAt: string
  documentCount: number
}

export type DocumentStatus = 'PENDING' | 'PARSING' | 'EMBEDDING' | 'READY' | 'FAILED'

export interface KbDocument {
  id: number
  kbId: number
  filename: string
  mimeType: string
  sizeBytes: number
  status: DocumentStatus
  errorMsg: string | null
  chunkCount: number
  createdAt: string
  updatedAt: string
}

export interface UploadResult {
  filename: string
  status: 'ACCEPTED' | 'DUPLICATE' | 'REJECTED'
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

export interface Conversation {
  id: number
  kbId: number
  title: string
  createdAt: string
  updatedAt: string
}

export interface ChatMessage {
  id: number | null
  role: 'user' | 'assistant'
  content: string
  citations: Citation[]
  streaming?: boolean
  error?: string
}
