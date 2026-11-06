import type {
  AnswerVerification, ChatMessage, ChunkView, Citation, Conversation, EvalCase, Feedback, FeedbackReason,
  InsightOverview, KbDocument, KnowledgeBase, RetrievedChunk, SearchScope, UploadResult,
} from './types'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: init?.body && !(init.body instanceof FormData) ? { 'Content-Type': 'application/json' } : undefined,
  })
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      detail = body.detail || detail
    } catch {
      // 非 JSON 错误响应
    }
    throw new Error(detail)
  }
  return res.status === 204 || res.status === 202 ? (undefined as T) : res.json()
}

const json = (body: unknown) => JSON.stringify(body)

export const api = {
  listKbs: () => request<KnowledgeBase[]>('/api/knowledge-bases'),
  createKb: (name: string, description: string) =>
    request<KnowledgeBase>('/api/knowledge-bases', { method: 'POST', body: json({ name, description }) }),
  updateKb: (id: number, name: string, description: string) =>
    request<KnowledgeBase>(`/api/knowledge-bases/${id}`, { method: 'PUT', body: json({ name, description }) }),
  deleteKb: (id: number) => request<void>(`/api/knowledge-bases/${id}`, { method: 'DELETE' }),

  listDocuments: (kbId: number) => request<KbDocument[]>(`/api/knowledge-bases/${kbId}/documents`),
  /** paths 与 files 一一对应，是文件夹导入时文件的相对路径 */
  uploadDocuments: (kbId: number, files: File[], paths?: string[]) => {
    const form = new FormData()
    files.forEach((f) => form.append('files', f))
    paths?.forEach((p) => form.append('paths', p))
    return request<UploadResult[]>(`/api/knowledge-bases/${kbId}/documents`, { method: 'POST', body: form })
  },
  deleteDocument: (id: number) => request<void>(`/api/documents/${id}`, { method: 'DELETE' }),
  reindexDocument: (id: number) => request<void>(`/api/documents/${id}/reindex`, { method: 'POST' }),
  listChunks: (id: number) => request<ChunkView[]>(`/api/documents/${id}/chunks`),
  search: (kbId: number, query: string, topK = 10) =>
    request<RetrievedChunk[]>(`/api/knowledge-bases/${kbId}/search`, { method: 'POST', body: json({ query, topK }) }),

  listConversations: (kbId: number) => request<Conversation[]>(`/api/knowledge-bases/${kbId}/conversations`),
  createConversation: (kbId: number, scope?: SearchScope) =>
    request<Conversation>(`/api/knowledge-bases/${kbId}/conversations`, { method: 'POST', body: json({ scope }) }),
  updateConversationScope: (id: number, scope: SearchScope) =>
    request<Conversation>(`/api/conversations/${id}/scope`, { method: 'PUT', body: json(scope) }),
  renameConversation: (id: number, title: string) =>
    request<Conversation>(`/api/conversations/${id}`, { method: 'PUT', body: json({ title }) }),
  deleteConversation: (id: number) => request<void>(`/api/conversations/${id}`, { method: 'DELETE' }),
  listMessages: (id: number) => request<ChatMessage[]>(`/api/conversations/${id}/messages`),
  /** rating 为 null 时取消评价 */
  feedback: (messageId: number, rating: 'UP' | 'DOWN' | null, reason?: FeedbackReason, note?: string) =>
    request<Feedback | undefined>(`/api/messages/${messageId}/feedback`, { method: 'PUT', body: json({ rating, reason, note }) }),

  insights: (kbId: number, days: number) =>
    request<InsightOverview>(`/api/knowledge-bases/${kbId}/insights?days=${days}`),
  listEvalCases: (kbId: number) => request<EvalCase[]>(`/api/knowledge-bases/${kbId}/eval-cases`),
  createEvalCase: (kbId: number, draft: Partial<EvalCase>) =>
    request<EvalCase>(`/api/knowledge-bases/${kbId}/eval-cases`, { method: 'POST', body: json(draft) }),
  deleteEvalCase: (id: number) => request<void>(`/api/eval-cases/${id}`, { method: 'DELETE' }),
}

/** 原文件地址；PDF 带上页码锚点，浏览器内置阅读器会跳到对应页 */
export function fileUrl(documentId: number, filename: string, location?: string | null) {
  const url = `/api/documents/${documentId}/file`
  const page = location?.match(/^第(\d+)(?:-\d+)?页$/)?.[1]
  return filename.toLowerCase().endsWith('.pdf') && page ? `${url}#page=${page}` : url
}

export interface ChatStreamHandlers {
  onCitations: (citations: Citation[]) => void
  onDelta: (text: string) => void
  onDone: (messageId: number, timings?: Record<string, number>, subQueries?: string[], fullTextDocuments?: string[]) => void
  /** 回答核对结果，在 done 之后到达；未开启核对或核对失败时没有 */
  onVerification?: (verification: AnswerVerification) => void
  onError: (message: string) => void
}

/** POST + SSE：EventSource 只支持 GET，所以用 fetch 读取流并手动解析事件 */
export async function streamChat(conversationId: number, question: string, handlers: ChatStreamHandlers,
                                 signal: AbortSignal) {
  const res = await fetch(`/api/conversations/${conversationId}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: json({ question }),
    signal,
  })
  if (!res.ok || !res.body) {
    handlers.onError(`请求失败：${res.status}`)
    return
  }
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += value.replace(/\r\n/g, '\n')
    let boundary: number
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      dispatch(buffer.slice(0, boundary), handlers)
      buffer = buffer.slice(boundary + 2)
    }
  }
}

function dispatch(raw: string, handlers: ChatStreamHandlers) {
  let event = 'message'
  const data: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  if (!data.length) return
  const payload = JSON.parse(data.join('\n'))
  switch (event) {
    case 'citations': handlers.onCitations(payload); break
    case 'delta': handlers.onDelta(payload.text); break
    case 'done': handlers.onDone(payload.messageId, payload.timings, payload.subQueries, payload.fullTextDocuments); break
    case 'verification': handlers.onVerification?.(payload); break
    case 'error': handlers.onError(payload.message); break
  }
}

/** 评测题导出地址，评测脚本可以直接用：--csv 这个地址 */
export function evalCasesCsvUrl(kbId: number) {
  return `/api/knowledge-bases/${kbId}/eval-cases.csv`
}
