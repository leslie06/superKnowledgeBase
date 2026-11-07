<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineSquare, Delete, EditPen, MoreFilled, Plus, Promotion, VideoPause } from '@element-plus/icons-vue'
import CitationDrawer from '../components/CitationDrawer.vue'
import ScopeSelector from '../components/ScopeSelector.vue'
import { api, streamChat } from '../api'
import { FEEDBACK_REASONS } from '../format'
import { renderMarkdown } from '../markdown'
import { store } from '../store'
import type { AnswerVerification, ChatMessage, Citation, Conversation, FeedbackReason, SearchScope } from '../types'

const props = defineProps<{ kbId: string; convId?: string }>()
const router = useRouter()

const kbId = computed(() => Number(props.kbId))
const convId = computed(() => (props.convId ? Number(props.convId) : null))
const kb = computed(() => store.kbs.find((k) => k.id === kbId.value))

const conversations = ref<Conversation[]>([])
const messages = ref<ChatMessage[]>([])
const question = ref('')
const streaming = ref(false)
const messagesEl = ref<HTMLElement>()
const activeCitation = ref<Citation | null>(null)
/** 正在填写"没用"原因的回答 */
const feedbackTarget = ref<ChatMessage | null>(null)
const feedbackReason = ref<FeedbackReason>('NOT_FOUND')
const feedbackNote = ref('')
/** 当前会话的检索范围；还没有会话时，新建会话会带上它 */
const scope = ref<SearchScope>({ folders: [], documentIds: [] })
let abortController: AbortController | null = null
/** 发送首条消息时新建会话并跳转路由，这次路由变化不需要重新加载消息 */
let skipLoadFor: number | null = null

const EXAMPLES = ['这些资料主要讲了什么？', '年假怎么计算？', '帮我对比一下各产品的价格和保修期']

async function loadConversations() {
  conversations.value = await api.listConversations(kbId.value)
  syncScope()
}

/** 打开已有会话时显示它的范围；新对话沿用当前选择 */
function syncScope() {
  const conv = conversations.value.find((c) => c.id === convId.value)
  if (conv) scope.value = conv.scope
}

async function changeScope(value: SearchScope) {
  const previous = scope.value
  scope.value = value
  if (convId.value === null) return
  try {
    const conv = await api.updateConversationScope(convId.value, value)
    conversations.value = conversations.value.map((c) => (c.id === conv.id ? conv : c))
  } catch (e) {
    scope.value = previous
    ElMessage.error((e as Error).message)
  }
}

async function loadMessages() {
  if (convId.value === null) {
    messages.value = []
    return
  }
  if (skipLoadFor === convId.value) {
    skipLoadFor = null
    return
  }
  abortController?.abort()
  try {
    messages.value = await api.listMessages(convId.value)
    scrollToBottom(true)
  } catch (e) {
    ElMessage.error((e as Error).message)
    router.replace(`/kb/${kbId.value}/chat`)
  }
}

onMounted(() => {
  loadConversations()
  loadMessages()
})
onBeforeUnmount(() => {
  abortController?.abort()
  window.clearInterval(clock)
})

/** 回答进行中每 0.1 秒刷新一次，用于显示实时耗时；没有进行中的回答时停止 */
const now = ref(Date.now())
let clock: number | undefined
watch(streaming, (value) => {
  window.clearInterval(clock)
  if (value) {
    now.value = Date.now()
    clock = window.setInterval(() => (now.value = Date.now()), 100)
  }
})

function liveSeconds(m: ChatMessage) {
  return m.startedAt ? ((now.value - m.startedAt) / 1000).toFixed(1) : ''
}
watch(convId, () => {
  loadMessages()
  syncScope()
})

function newConversation() {
  abortController?.abort()
  router.push(`/kb/${kbId.value}/chat`)
}

async function send(text = question.value) {
  const q = text.trim()
  if (!q || streaming.value) return
  const startedAt = Date.now()
  question.value = ''
  streaming.value = true

  let id = convId.value
  if (id === null) {
    try {
      id = (await api.createConversation(kbId.value, scope.value)).id
    } catch (e) {
      ElMessage.error((e as Error).message)
      streaming.value = false
      return
    }
    skipLoadFor = id
    messages.value = []
    router.replace(`/kb/${kbId.value}/chat/${id}`)
  }

  messages.value.push({ id: null, role: 'user', content: q, citations: [] })
  messages.value.push({ id: null, role: 'assistant', content: '', citations: [], streaming: true, startedAt })
  // 取响应式代理，后续修改才能触发界面更新
  const answer = messages.value[messages.value.length - 1]
  scrollToBottom(true)

  const controller = new AbortController()
  abortController = controller
  try {
    await streamChat(id, q, {
      onCitations: (c) => { answer.citations = c },
      onDelta: (t) => {
        answer.content += t
        scrollToBottom()
      },
      onDone: (messageId, timings, subQueries, fullTextDocuments, followUps, intent) => {
        answer.id = messageId
        answer.timings = timings
        answer.subQueries = subQueries
        answer.fullTextDocuments = fullTextDocuments
        answer.followUps = followUps
        answer.intent = intent || null
        // 回答已保存，之后的核对在后台进行，不妨碍继续提问
        answer.streaming = false
        answer.verifying = true
        streaming.value = false
        loadConversations()
      },
      onVerification: (v) => {
        answer.verification = v
        scrollToBottom()
      },
      onError: (message) => { answer.error = message },
    }, controller.signal)
  } catch (e) {
    if ((e as Error).name !== 'AbortError') answer.error = `连接中断：${(e as Error).message}`
  } finally {
    answer.streaming = false
    answer.verifying = false
    // 核对期间已经开始了下一次提问时，不要清掉新提问的状态
    if (abortController === controller) {
      streaming.value = false
      abortController = null
    }
    loadConversations()
  }
}

function stop() {
  abortController?.abort()
}

function onKeydown(e: KeyboardEvent) {
  // 中文输入法选词时的回车不发送
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

function scrollToBottom(force = false) {
  nextTick(() => {
    const el = messagesEl.value
    if (!el) return
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120
    if (force || nearBottom) el.scrollTop = el.scrollHeight
  })
}

/** 例如"第 1 轮：缺少西门庆临终的情节 → 查「西门庆 断气身亡」「第七十九回」，补到 5 段" */
function followUpTitle(m: ChatMessage) {
  return ['首轮检索到的资料不够回答，换说法补充检索：', ...m.followUps!.map((f) =>
    `第 ${f.round} 轮：${f.missing ?? ''} → ${[...f.queries.map((q) => `查「${q}」`), ...(f.context ?? []).map((c) => `补看${c}`)]
      .join('、')}，补到 ${f.added} 段`)].join('\n')
}

/** 检索（改写 + 召回与精排）超过这个时长时标出来，通常是精排服务被换出到磁盘 */
const SLOW_RETRIEVAL_MS = 15000

function sec(ms: number | undefined) {
  return ms == null ? '-' : (ms / 1000).toFixed(1)
}

function retrievalMs(m: ChatMessage) {
  const t = m.timings
  return t && (t.rewriteMs != null || t.retrieveMs != null)
    ? (t.rewriteMs ?? 0) + (t.retrieveMs ?? 0) + (t.followUpMs ?? 0) : null
}

/**
 * 例如"检索 6.9 秒（改写 1.4 · 召回与精排 5.5）· 生成 4.1 秒（首字 1.5）· 总计 11.0 秒"。
 * 各段可以直接相加：首字包含在生成里，所以放在括号中；总计 = 检索 + 生成，不用前端实测时间
 * （实测还包含网络和保存消息的时间，会比各段之和多零点几秒，看起来对不上）。
 */
function timingText(m: ChatMessage) {
  const t = m.timings!
  const retrieval = retrievalMs(m)!
  const parts = [`检索 ${sec(retrieval)} 秒（改写 ${sec(t.rewriteMs)} · 召回与精排 ${sec(t.retrieveMs)}`
    + (t.followUpMs != null ? ` · 补充检索 ${sec(t.followUpMs)}` : '') + '）']
  if (t.answerMs != null) {
    parts.push(`生成 ${sec(t.answerMs)} 秒` + (t.firstTokenMs != null ? `（首字 ${sec(t.firstTokenMs)}）` : ''))
  }
  parts.push(`总计 ${sec(retrieval + (t.answerMs ?? 0))} 秒`)
  return parts.join(' · ')
}

/** 再点一次同一个按钮取消评价；"没用"先选原因 */
async function rate(m: ChatMessage, rating: 'UP' | 'DOWN') {
  if (m.id == null) return
  if (rating === 'DOWN' && m.feedback?.rating !== 'DOWN') {
    feedbackTarget.value = m
    feedbackReason.value = 'NOT_FOUND'
    feedbackNote.value = ''
    return
  }
  try {
    m.feedback = (await api.feedback(m.id, m.feedback?.rating === rating ? null : rating)) ?? null
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function submitDown() {
  const m = feedbackTarget.value
  if (!m || m.id == null) return
  try {
    m.feedback = (await api.feedback(m.id, 'DOWN', feedbackReason.value, feedbackNote.value.trim())) ?? null
    feedbackTarget.value = null
    ElMessage.success('已记录，可以在「问答分析」中查看')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function issueCount(v: AnswerVerification) {
  return v.unsupported.length + v.citationErrors.length
}

/** 回答中实际引用到的来源；模型没标注编号时展示全部检索结果 */
function referencedCitations(msg: ChatMessage) {
  const used = new Set(Array.from(msg.content.matchAll(/\[(\d{1,2})\]/g), (m) => Number(m[1])))
  const list = msg.citations.filter((c) => used.has(c.index))
  return list.length ? list : msg.citations
}

function onAnswerClick(e: MouseEvent, msg: ChatMessage) {
  const target = (e.target as HTMLElement).closest<HTMLElement>('.cite')
  if (!target) return
  const citation = msg.citations.find((c) => c.index === Number(target.dataset.cite))
  if (citation) activeCitation.value = citation
}

async function onConversationCommand(command: string, conv: Conversation) {
  if (command === 'rename') {
    const { value } = await ElMessageBox.prompt('新的对话标题', '重命名', {
      inputValue: conv.title, inputValidator: (v) => !!v.trim() || '标题不能为空',
    })
    await api.renameConversation(conv.id, value)
    await loadConversations()
    return
  }
  await ElMessageBox.confirm(`删除对话「${conv.title}」？`, '删除对话', { type: 'warning', confirmButtonText: '删除' })
  await api.deleteConversation(conv.id)
  await loadConversations()
  if (conv.id === convId.value) router.replace(`/kb/${kbId.value}/chat`)
}
</script>

<template>
  <div class="chat-page">
    <aside class="conv-list">
      <el-button class="new-conv" :icon="Plus" @click="newConversation">新对话</el-button>
      <div class="conv-items">
        <div
          v-for="c in conversations"
          :key="c.id"
          class="conv-item"
          :class="{ active: c.id === convId }"
          @click="router.push(`/kb/${kbId}/chat/${c.id}`)"
        >
          <el-icon><ChatLineSquare /></el-icon>
          <span class="conv-title" :title="c.title">{{ c.title }}</span>
          <el-dropdown trigger="click" @command="(cmd: string) => onConversationCommand(cmd, c)" @click.stop>
            <el-icon class="conv-more" @click.stop><MoreFilled /></el-icon>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="rename" :icon="EditPen">重命名</el-dropdown-item>
                <el-dropdown-item command="delete" :icon="Delete">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div v-if="!conversations.length" class="conv-empty">暂无对话</div>
      </div>
    </aside>

    <section class="chat-main">
      <div ref="messagesEl" class="messages">
        <div v-if="!messages.length" class="welcome">
          <h2>向「{{ kb?.name ?? '知识库' }}」提问</h2>
          <p>回答只基于知识库中的文件，每个结论都会标注来源，点击角标可以查看原文片段。</p>
          <div v-if="kb && !kb.documentCount" class="welcome-warn">
            这个知识库还没有文件，请先到<router-link :to="`/kb/${kbId}/docs`">文档管理</router-link>上传资料。
          </div>
          <div class="examples">
            <button v-for="e in EXAMPLES" :key="e" @click="send(e)">{{ e }}</button>
          </div>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div v-if="m.role === 'user'" class="bubble">{{ m.content }}</div>
          <div v-else class="answer">
            <div v-if="m.streaming && !m.content" class="thinking">
              <span class="dot" /><span class="dot" /><span class="dot" />
              {{ m.citations.length ? `已找到 ${m.citations.length} 个相关片段，正在生成回答…` : '正在检索知识库…' }}
              <span v-if="m.startedAt" class="live-elapsed">{{ liveSeconds(m) }} 秒</span>
            </div>
            <div class="markdown" @click="onAnswerClick($event, m)" v-html="renderMarkdown(m.content)" />
            <span v-if="m.streaming && m.content" class="cursor" />
            <div v-if="m.streaming && m.content && m.startedAt" class="streaming-elapsed">已用时 {{ liveSeconds(m) }} 秒</div>
            <el-alert v-if="m.error" :title="m.error" type="error" :closable="false" show-icon />
            <div v-if="!m.streaming && m.citations.length" class="sources">
              <span class="sources-label">参考来源</span>
              <button v-for="c in referencedCitations(m)" :key="c.index" class="source" @click="activeCitation = c">
                <b>{{ c.index }}</b>{{ c.filename }}
                <span v-if="c.headingPath || c.location" class="muted">· {{ c.location || c.headingPath }}</span>
              </button>
            </div>
            <div v-if="m.verifying" class="verify verify-pending">正在核对回答与参考资料…</div>
            <div v-else-if="m.verification && !issueCount(m.verification)" class="verify verify-ok">
              ✓ 已核对：回答内容都能在参考资料中找到依据
            </div>
            <div v-else-if="m.verification" class="verify verify-warn">
              <div class="verify-title">⚠ 核对发现 {{ issueCount(m.verification) }} 处需要确认</div>
              <ul>
                <li v-for="(i, k) in m.verification.unsupported" :key="'u' + k">
                  「{{ i.text }}」<span class="muted">资料中找不到依据：{{ i.reason }}</span>
                </li>
                <li v-for="(i, k) in m.verification.citationErrors" :key="'c' + k">
                  「{{ i.text }}」<span class="muted">引用与内容不符：{{ i.reason }}</span>
                </li>
              </ul>
            </div>
            <div v-if="m.id != null && !m.streaming" class="answer-actions">
              <button :class="{ active: m.feedback?.rating === 'UP' }" @click="rate(m, 'UP')">👍 有用</button>
              <button :class="{ active: m.feedback?.rating === 'DOWN' }" @click="rate(m, 'DOWN')">
                👎 没用<template v-if="m.feedback?.rating === 'DOWN' && m.feedback.reason">：{{ FEEDBACK_REASONS[m.feedback.reason] }}</template>
              </button>
              <span v-if="m.fullTextDocuments?.length" class="answer-plan"
                    :title="`这些文档篇幅不长（或是问题点名的章节），整篇交给模型通读后回答：\n${m.fullTextDocuments.join('\n')}`">
                已读全文 · {{ m.fullTextDocuments.length > 1 ? `${m.fullTextDocuments.length} 个文档` : m.fullTextDocuments[0] }}
              </span>
              <span v-if="m.intent" class="answer-plan" :title="`口语、俗语的问法按本意检索和回答：\n${m.intent}`">
                理解为：{{ m.intent }}
              </span>
              <span v-if="m.followUps?.length" class="answer-plan" :title="followUpTitle(m)">
                补充检索 · {{ m.followUps.length }} 轮
              </span>
              <span v-if="m.subQueries?.length" class="answer-plan"
                    :title="`这个问题需要汇总多处资料，拆成以下子问题分别检索：\n${m.subQueries.join('\n')}`">
                多步检索 · {{ m.subQueries.length }} 个子问题
              </span>
              <span v-if="retrievalMs(m) != null" class="answer-timing" :class="{ slow: retrievalMs(m)! > SLOW_RETRIEVAL_MS }"
                    title="总计 = 检索 + 生成；检索 = 查询改写 + 召回与精排；首字是从开始生成到出现第一个字，包含在生成里">
                {{ timingText(m) }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <div class="composer-bar">
        <ScopeSelector :kb-id="kbId" :model-value="scope" @update:model-value="changeScope" />
      </div>
      <div class="composer">
        <el-input
          v-model="question"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 6 }"
          resize="none"
          placeholder="输入问题，Enter 发送，Shift + Enter 换行"
          @keydown="onKeydown"
        />
        <el-button v-if="streaming" :icon="VideoPause" circle @click="stop" title="停止生成" />
        <el-button v-else type="primary" :icon="Promotion" circle :disabled="!question.trim()" @click="send()" title="发送" />
      </div>
    </section>

    <CitationDrawer :citation="activeCitation" @close="activeCitation = null" />

    <el-dialog :model-value="!!feedbackTarget" title="这个回答哪里不好？" width="440px" @close="feedbackTarget = null">
      <el-radio-group v-model="feedbackReason" class="feedback-reasons">
        <el-radio v-for="(label, key) in FEEDBACK_REASONS" :key="key" :value="key">{{ label }}</el-radio>
      </el-radio-group>
      <el-input v-model="feedbackNote" type="textarea" :rows="3" maxlength="500"
                placeholder="可选：正确答案在哪个文件、哪里说得不对，方便以后排查" />
      <template #footer>
        <el-button @click="feedbackTarget = null">取消</el-button>
        <el-button type="primary" @click="submitDown">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>
