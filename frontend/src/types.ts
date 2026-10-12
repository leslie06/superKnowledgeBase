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
  score: number
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
