<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineSquare, Delete, EditPen, MoreFilled, Plus, Promotion, VideoPause } from '@element-plus/icons-vue'
import CitationDrawer from '../components/CitationDrawer.vue'
import { api, streamChat } from '../api'
import { renderMarkdown } from '../markdown'
import { store } from '../store'
import type { ChatMessage, Citation, Conversation } from '../types'

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
let abortController: AbortController | null = null
/** 发送首条消息时新建会话并跳转路由，这次路由变化不需要重新加载消息 */
let skipLoadFor: number | null = null

const EXAMPLES = ['这些资料主要讲了什么？', '年假怎么计算？', '帮我对比一下各产品的价格和保修期']

async function loadConversations() {
  conversations.value = await api.listConversations(kbId.value)
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
onBeforeUnmount(() => abortController?.abort())
watch(convId, loadMessages)

function newConversation() {
  abortController?.abort()
  router.push(`/kb/${kbId.value}/chat`)
}

async function send(text = question.value) {
  const q = text.trim()
  if (!q || streaming.value) return
  question.value = ''
  streaming.value = true

  let id = convId.value
  if (id === null) {
    try {
      id = (await api.createConversation(kbId.value)).id
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
  messages.value.push({ id: null, role: 'assistant', content: '', citations: [], streaming: true })
  // 取响应式代理，后续修改才能触发界面更新
  const answer = messages.value[messages.value.length - 1]
  scrollToBottom(true)

  abortController = new AbortController()
  try {
    await streamChat(id, q, {
      onCitations: (c) => { answer.citations = c },
      onDelta: (t) => {
        answer.content += t
        scrollToBottom()
      },
      onDone: (messageId) => { answer.id = messageId },
      onError: (message) => { answer.error = message },
    }, abortController.signal)
  } catch (e) {
    if ((e as Error).name !== 'AbortError') answer.error = `连接中断：${(e as Error).message}`
  } finally {
    answer.streaming = false
    streaming.value = false
    abortController = null
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
            </div>
            <div class="markdown" @click="onAnswerClick($event, m)" v-html="renderMarkdown(m.content)" />
            <span v-if="m.streaming && m.content" class="cursor" />
            <el-alert v-if="m.error" :title="m.error" type="error" :closable="false" show-icon />
            <div v-if="!m.streaming && m.citations.length" class="sources">
              <span class="sources-label">参考来源</span>
              <button v-for="c in referencedCitations(m)" :key="c.index" class="source" @click="activeCitation = c">
                <b>{{ c.index }}</b>{{ c.filename }}
                <span v-if="c.headingPath || c.location" class="muted">· {{ c.location || c.headingPath }}</span>
              </button>
            </div>
          </div>
        </div>
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
  </div>
</template>
