<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Document, Refresh, Search, View } from '@element-plus/icons-vue'
import { api, fileUrl } from '../api'
import DocumentUploader from '../components/DocumentUploader.vue'
import { formatSize } from '../format'
import { loadKbs } from '../store'
import type { ChunkView, DocumentStatus, KbDocument, RetrievedChunk } from '../types'

const props = defineProps<{ kbId: string }>()
const kbId = computed(() => Number(props.kbId))

const POLL_INTERVAL = 2000

const STATUS: Record<DocumentStatus, { label: string; type: 'info' | 'warning' | 'primary' | 'success' | 'danger' }> = {
  PENDING: { label: '排队中', type: 'info' },
  PARSING: { label: '解析中', type: 'warning' },
  OCR: { label: '文字识别中', type: 'warning' },
  EMBEDDING: { label: '向量化中', type: 'primary' },
  READY: { label: '已就绪', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
}

const documents = ref<KbDocument[]>([])
const loading = ref(true)
const keyword = ref('')
const statusFilter = ref<DocumentStatus | ''>('')
let pollTimer: number | undefined

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return documents.value.filter((d) => (d.sourcePath ?? d.filename).toLowerCase().includes(kw)
    && (!statusFilter.value || d.status === statusFilter.value))
})
const stats = computed(() => ({
  total: documents.value.length,
  ready: documents.value.filter((d) => d.status === 'READY').length,
  processing: documents.value.filter((d) => !['READY', 'FAILED'].includes(d.status)).length,
  failed: documents.value.filter((d) => d.status === 'FAILED').length,
  chunks: documents.value.reduce((sum, d) => sum + d.chunkCount, 0),
}))

async function refresh() {
  try {
    documents.value = await api.listDocuments(kbId.value)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
  schedulePoll()
}

/** 有文档在处理时轮询刷新状态 */
function schedulePoll() {
  window.clearTimeout(pollTimer)
  if (stats.value.processing > 0) pollTimer = window.setTimeout(refresh, POLL_INTERVAL)
}

onMounted(refresh)
onBeforeUnmount(() => window.clearTimeout(pollTimer))

/** 每上传完一批就刷新列表，新文档处于处理中，会触发轮询 */
async function onUploaded() {
  await refresh()
  loadKbs()
}

async function remove(doc: KbDocument) {
  await ElMessageBox.confirm(`确定删除「${doc.filename}」？对应的向量数据也会一并删除。`, '删除文件',
    { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' })
  await api.deleteDocument(doc.id)
  ElMessage.success('已删除')
  await refresh()
  loadKbs()
}

async function reindex(doc: KbDocument) {
  try {
    await api.reindexDocument(doc.id)
    ElMessage.success('已重新加入处理队列')
    await refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---------- 片段预览 ----------
const chunkDoc = ref<KbDocument | null>(null)
const chunks = ref<ChunkView[]>([])
const chunksLoading = ref(false)

async function showChunks(doc: KbDocument) {
  chunkDoc.value = doc
  chunks.value = []
  chunksLoading.value = true
  try {
    chunks.value = await api.listChunks(doc.id)
  } finally {
    chunksLoading.value = false
  }
}

// ---------- 检索测试 ----------
const searchQuery = ref('')
const searchResults = ref<RetrievedChunk[] | null>(null)
const searching = ref(false)

async function runSearch() {
  if (!searchQuery.value.trim()) return
  searching.value = true
  try {
    searchResults.value = await api.search(kbId.value, searchQuery.value.trim(), 8)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    searching.value = false
  }
}

function formatTime(iso: string) {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

/** 文件夹导入的文件所在目录（相对路径去掉文件名） */
function directory(doc: KbDocument) {
  return doc.sourcePath?.includes('/') ? doc.sourcePath.slice(0, doc.sourcePath.lastIndexOf('/')) : null
}

function extension(filename: string) {
  return filename.split('.').pop()?.toUpperCase() ?? ''
}
</script>

<template>
  <div class="docs-page">
    <DocumentUploader :kb-id="kbId" :documents="documents" @uploaded="onUploaded" @show-failed="statusFilter = 'FAILED'" />

    <section class="stats">
      <div><b>{{ stats.total }}</b><span>文件</span></div>
      <div><b>{{ stats.ready }}</b><span>已就绪</span></div>
      <div><b>{{ stats.processing }}</b><span>处理中</span></div>
      <div><b :class="{ danger: stats.failed }">{{ stats.failed }}</b><span>失败</span></div>
      <div><b>{{ stats.chunks }}</b><span>知识片段</span></div>
    </section>

    <section class="panel">
      <div class="panel-header">
        <h3>文件列表</h3>
        <el-input v-model="keyword" placeholder="按文件名或目录筛选" clearable :prefix-icon="Search" class="filter" />
        <el-select v-model="statusFilter" placeholder="全部状态" clearable class="status-filter">
          <el-option v-for="(s, key) in STATUS" :key="key" :label="s.label" :value="key" />
        </el-select>
        <el-button :icon="Refresh" @click="refresh">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="filtered" empty-text="还没有文件，先上传一些资料吧">
        <el-table-column label="文件名" min-width="260">
          <template #default="{ row }">
            <div class="file-cell">
              <span class="ext" :data-ext="extension(row.filename)">{{ extension(row.filename) }}</span>
              <div class="file-text">
                <a :href="fileUrl(row.id, row.filename)" target="_blank" class="file-name">{{ row.filename }}</a>
                <div v-if="directory(row)" class="file-dir" :title="directory(row)!">{{ directory(row) }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tooltip v-if="row.status === 'FAILED'" :content="row.errorMsg || '未知错误'" placement="top">
              <el-tag :type="STATUS[row.status as DocumentStatus].type" effect="light">失败 ⓘ</el-tag>
            </el-tooltip>
            <el-tag v-else-if="row.status === 'OCR'" :type="STATUS.OCR.type" effect="light">
              文字识别 {{ row.ocrDone }}/{{ row.ocrPages }} 页
            </el-tag>
            <el-tooltip v-else-if="row.status === 'READY' && row.ocrPages > 0"
                        :content="`其中 ${row.ocrPages} 页是扫描页，文字由文字识别得到`" placement="top">
              <el-tag :type="STATUS.READY.type" effect="light">已就绪 · 识别 ⓘ</el-tag>
            </el-tooltip>
            <el-tag v-else :type="STATUS[row.status as DocumentStatus].type" effect="light">
              {{ STATUS[row.status as DocumentStatus].label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="片段数" prop="chunkCount" width="90" align="right" />
        <el-table-column label="大小" width="100" align="right">
          <template #default="{ row }">{{ formatSize(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="View" :disabled="row.status !== 'READY'" @click="showChunks(row)">片段</el-button>
            <el-button link type="primary" :icon="Refresh" @click="reindex(row)">重新处理</el-button>
            <el-button link type="danger" :icon="Delete" @click="remove(row)" />
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel">
      <div class="panel-header">
        <h3>检索测试</h3>
        <span class="panel-hint">只看召回了哪些片段和相似度（向量 + 关键词融合排序），不调用大模型，用来判断"答不好"是检索问题还是生成问题</span>
      </div>
      <div class="search-row">
        <el-input v-model="searchQuery" placeholder="输入一个问题，例如：年假有几天？" @keyup.enter="runSearch" />
        <el-button type="primary" :loading="searching" @click="runSearch">检索</el-button>
      </div>
      <el-empty v-if="searchResults && !searchResults.length" description="没有召回任何片段" :image-size="60" />
      <ol v-else-if="searchResults" class="search-results">
        <li v-for="r in searchResults" :key="r.chunkId">
          <div class="result-meta">
            <el-tag size="small" :type="r.score >= 0.6 ? 'success' : r.score >= 0.45 ? 'warning' : 'info'">
              {{ r.score.toFixed(3) }}
            </el-tag>
            <span v-if="r.vectorRank" class="muted">向量 #{{ r.vectorRank }}</span>
            <span v-if="r.keywordRank" class="muted">关键词 #{{ r.keywordRank }}</span>
            <span v-if="r.rerankScore != null" class="muted">精排 {{ r.rerankScore.toFixed(3) }}</span>
            <el-tag v-if="r.exactMatch" size="small" type="success" effect="plain">精确命中</el-tag>
            <el-tag v-if="!r.relevant" size="small" type="info" effect="plain">不相关</el-tag>
            <a :href="fileUrl(r.documentId, r.filename, r.location)" target="_blank">{{ r.filename }}</a>
            <span v-if="r.headingPath" class="muted">{{ r.headingPath }}</span>
            <span v-if="r.location" class="muted">{{ r.location }}</span>
          </div>
          <div class="result-content">{{ r.content }}</div>
        </li>
      </ol>
    </section>

    <el-drawer :model-value="!!chunkDoc" size="560px" @close="chunkDoc = null">
      <template #header>
        <div class="drawer-title"><el-icon><Document /></el-icon>{{ chunkDoc?.filename }}（{{ chunks.length }} 个片段）</div>
      </template>
      <div v-loading="chunksLoading" class="chunk-list">
        <div v-for="c in chunks" :key="c.id" class="chunk">
          <div class="chunk-meta">
            <span class="chunk-index">#{{ c.chunkIndex + 1 }}</span>
            <span v-if="c.headingPath">{{ c.headingPath }}</span>
            <span v-if="c.location" class="muted">{{ c.location }}</span>
            <span class="muted chunk-len">{{ c.content.length }} 字</span>
          </div>
          <div class="chunk-content">{{ c.content }}</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>
