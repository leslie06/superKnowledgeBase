<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { FolderOpened, UploadFilled } from '@element-plus/icons-vue'
import { api } from '../api'
import { formatDuration, formatSize } from '../format'
import type { KbDocument } from '../types'

/**
 * 上传区：选择或拖入文件 / 文件夹。文件夹先弹出预览（按格式统计、将跳过的文件），确认后分批上传，
 * 进度面板实时显示上传和后台处理（解析、向量化）的进度。处理状态来自父组件轮询的文档列表。
 */
const props = defineProps<{ kbId: number; documents: KbDocument[] }>()
const emit = defineEmits<{ uploaded: []; showFailed: [] }>()

const FORMATS = [
  { label: 'Word', exts: ['doc', 'docx'] },
  { label: 'Excel', exts: ['xls', 'xlsx'] },
  { label: 'PPT', exts: ['ppt', 'pptx'] },
  { label: 'PDF', exts: ['pdf'] },
  { label: '文本', exts: ['txt', 'md'] },
]
const ACCEPT = FORMATS.flatMap((f) => f.exts).map((e) => '.' + e).join(',')
const SUPPORTED = new Set(FORMATS.flatMap((f) => f.exts))
const UPLOAD_BATCH = 10
/** 单批请求的字节上限，避免超过后端 max-request-size（1GB） */
const UPLOAD_BATCH_BYTES = 100 * 1024 * 1024
/** 与后端 spring.servlet.multipart.max-file-size 一致 */
const MAX_FILE_BYTES = 200 * 1024 * 1024
const SKIP_EXAMPLES = 3
/** 完成数达到这个值后才估算剩余时间，太少时估不准 */
const MIN_DONE_FOR_ESTIMATE = 3

/** 选中的文件及其在所选文件夹内的相对路径（单独选的文件就是文件名） */
interface Picked {
  file: File
  path: string
}

interface Plan {
  name: string
  files: Picked[]
  bytes: number
  formats: { label: string; count: number; bytes: number; exts: string }[]
  skipped: { reason: string; count: number; examples: string }[]
  skippedCount: number
}

interface Job {
  name: string
  total: number
  uploaded: number
  accepted: number
  duplicates: number
  skipped: number
  /** 未加入的文件及原因（已存在、被拒绝、上传失败） */
  problems: string[]
  docIds: Set<number>
  uploading: boolean
  startedAt: number
  finishedAt: number | null
}

const fileInput = ref<HTMLInputElement>()
const folderInput = ref<HTMLInputElement>()
const dragging = ref(false)
const reading = ref(false)
const plan = ref<Plan | null>(null)
const job = ref<Job | null>(null)
const now = ref(Date.now())
let clock: number | undefined

onMounted(() => {
  // webkitdirectory 不在 Vue 模板的属性类型里，直接设置 DOM 属性
  if (folderInput.value) folderInput.value.webkitdirectory = true
  window.addEventListener('beforeunload', confirmLeave)
})
onBeforeUnmount(() => {
  window.clearInterval(clock)
  window.removeEventListener('beforeunload', confirmLeave)
})

/** 上传中关闭或刷新页面时提示，否则剩下的文件不会再上传 */
function confirmLeave(e: BeforeUnloadEvent) {
  if (job.value?.uploading) e.preventDefault()
}

/** 不上传的文件返回原因；隐藏文件（如 .DS_Store、.git 目录）返回 null 并静默忽略 */
function skipReason({ file, path }: Picked): string | null | undefined {
  if (path.split('/').some((s) => s.startsWith('.'))) return null
  if (file.name.startsWith('~$')) return 'Office 临时文件'
  const ext = extension(file.name)
  if (!SUPPORTED.has(ext)) return `不支持的类型（${ext ? '.' + ext : '无扩展名'}）`
  if (file.size === 0) return '空文件'
  if (file.size > MAX_FILE_BYTES) return '超过 200MB'
  return undefined
}

function extension(filename: string) {
  return filename.includes('.') ? filename.split('.').pop()!.toLowerCase() : ''
}

function makePlan(picked: Picked[]): Plan {
  const files: Picked[] = []
  const skipped = new Map<string, string[]>()
  for (const p of picked) {
    const reason = skipReason(p)
    if (reason === undefined) files.push(p)
    else if (reason) skipped.set(reason, [...(skipped.get(reason) ?? []), p.path])
  }
  const formats = FORMATS.map((f) => {
    const group = files.filter(({ file }) => f.exts.includes(extension(file.name)))
    const exts = f.exts.map((e) => [e, group.filter(({ file }) => extension(file.name) === e).length] as const)
      .filter(([, n]) => n > 0)
    return {
      label: f.label,
      count: group.length,
      bytes: group.reduce((sum, { file }) => sum + file.size, 0),
      exts: exts.map(([e, n]) => `.${e} ${n}`).join('、'),
    }
  }).filter((f) => f.count > 0)
  // 只选了一个文件夹时用它的名字，示例路径也去掉这一层；否则统称"所选文件"
  const roots = new Set(picked.filter((p) => p.path.includes('/')).map((p) => p.path.split('/')[0]))
  const strip = (path: string) => (roots.size === 1 ? path.slice(path.indexOf('/') + 1) : path)
  const skippedRows = [...skipped].map(([reason, paths]) => ({
    reason,
    count: paths.length,
    examples: paths.slice(0, SKIP_EXAMPLES).map(strip).join('、') + (paths.length > SKIP_EXAMPLES ? ' 等' : ''),
  })).sort((a, b) => b.count - a.count)
  return {
    name: roots.size === 1 ? [...roots][0] : '所选文件',
    files,
    bytes: files.reduce((sum, { file }) => sum + file.size, 0),
    formats,
    skipped: skippedRows,
    skippedCount: skippedRows.reduce((sum, r) => sum + r.count, 0),
  }
}

/** 文件夹先预览再导入；单独选的几个文件直接导入 */
function receive(picked: Picked[]) {
  if (!picked.length) return
  const p = makePlan(picked)
  if (picked.some((x) => x.path.includes('/'))) plan.value = p
  else start(p)
}

function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  receive(Array.from(input.files ?? []).map((file) => ({ file, path: file.webkitRelativePath || file.name })))
  input.value = ''
}

async function onDrop(e: DragEvent) {
  dragging.value = false
  if (job.value?.uploading) return
  // 拖进来的文件夹要通过 entry 递归读取；entry 必须在事件处理的同步阶段取出
  const entries = Array.from(e.dataTransfer?.items ?? [])
    .map((item) => item.webkitGetAsEntry())
    .filter((entry): entry is FileSystemEntry => !!entry)
  if (!entries.length) return
  reading.value = true
  const picked: Picked[] = []
  try {
    for (const entry of entries) await collect(entry, picked)
  } catch (err) {
    ElMessage.error(`读取文件夹失败：${(err as Error).message}`)
    return
  } finally {
    reading.value = false
  }
  receive(picked)
}

async function collect(entry: FileSystemEntry, out: Picked[]) {
  const path = entry.fullPath.replace(/^\//, '')
  if (entry.isFile) {
    const file = await new Promise<File>((resolve, reject) => (entry as FileSystemFileEntry).file(resolve, reject))
    out.push({ file, path })
  } else if (entry.isDirectory && !entry.name.startsWith('.')) {
    const reader = (entry as FileSystemDirectoryEntry).createReader()
    // readEntries 每次最多返回一批（Chrome 为 100 个），要读到空为止
    for (;;) {
      const children = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject))
      if (!children.length) break
      for (const child of children) await collect(child, out)
    }
  }
}

/** 按数量和总大小分批，每批一个请求 */
function batches(files: Picked[]): Picked[][] {
  const result: Picked[][] = []
  let current: Picked[] = []
  let bytes = 0
  for (const p of files) {
    if (current.length && (current.length >= UPLOAD_BATCH || bytes + p.file.size > UPLOAD_BATCH_BYTES)) {
      result.push(current)
      current = []
      bytes = 0
    }
    current.push(p)
    bytes += p.file.size
  }
  if (current.length) result.push(current)
  return result
}

async function start(p: Plan) {
  plan.value = null
  if (!p.files.length) {
    ElMessage.warning('没有可导入的文件')
    return
  }
  job.value = {
    name: p.name, total: p.files.length, uploaded: 0, accepted: 0, duplicates: 0, skipped: p.skippedCount,
    problems: [], docIds: new Set(), uploading: true, startedAt: Date.now(), finishedAt: null,
  }
  const current = job.value
  window.clearInterval(clock)
  clock = window.setInterval(() => (now.value = Date.now()), 1000)
  for (const batch of batches(p.files)) {
    try {
      // 相对路径随文件一起上传，后端记录下来并拼进向量文本；单独选的文件路径就是文件名，后端会忽略
      const results = await api.uploadDocuments(props.kbId, batch.map((p) => p.file), batch.map((p) => p.path))
      for (const r of results) {
        if (r.status === 'ACCEPTED' && r.documentId != null) {
          current.accepted++
          current.docIds.add(r.documentId)
        } else {
          if (r.status === 'DUPLICATE') current.duplicates++
          current.problems.push(`${r.filename}：${r.message}`)
        }
      }
    } catch (e) {
      // 一批失败不影响后面的批次
      current.problems.push(...batch.map((p) => `${p.path}：上传失败，${(e as Error).message}`))
    }
    current.uploaded += batch.length
    emit('uploaded')
  }
  current.uploading = false
}

const progress = computed(() => {
  const j = job.value
  if (!j) return null
  let ready = 0
  let failed = 0
  let chunks = 0
  for (const d of props.documents) {
    if (!j.docIds.has(d.id)) continue
    if (d.status === 'READY') ready++
    else if (d.status === 'FAILED') failed++
    chunks += d.chunkCount
  }
  // 刚上传、文档列表还没刷新到的也算处理中
  const done = ready + failed
  return { ready, failed, chunks, done, processing: j.accepted - done }
})

const finished = computed(() => !!job.value && !job.value.uploading && progress.value!.processing === 0)

watch(finished, (value) => {
  if (value && job.value) {
    job.value.finishedAt = Date.now()
    window.clearInterval(clock)
  }
})

const elapsed = computed(() => (job.value ? (job.value.finishedAt ?? now.value) - job.value.startedAt : 0))

/** 按目前的平均处理速度估算剩余时间 */
const remaining = computed(() => {
  const j = job.value
  const pr = progress.value
  if (!j || !pr || finished.value || pr.done < MIN_DONE_FOR_ESTIMATE) return null
  // 未加入的文件不需要处理；还没上传的文件按会被加入估算
  return elapsed.value / pr.done * (j.total - j.problems.length - pr.done)
})

const statusText = computed(() => {
  if (!job.value) return ''
  if (finished.value) return `已完成，用时 ${formatDuration(elapsed.value)}`
  const phase = job.value.uploading ? '正在上传' : '后台解析与向量化中，可以离开此页面'
  const eta = remaining.value != null ? `，预计还需 ${formatDuration(remaining.value)}` : ''
  return `${phase} · 已用 ${formatDuration(elapsed.value)}${eta}`
})

function percent(done: number, total: number) {
  return total ? Math.floor(done / total * 100) : 100
}

function close() {
  job.value = null
}
</script>

<template>
  <section
    class="dropzone"
    :class="{ dragging, disabled: job?.uploading }"
    @click="!job?.uploading && fileInput?.click()"
    @dragover.prevent="dragging = true"
    @dragleave.prevent="dragging = false"
    @drop.prevent="onDrop"
  >
    <input ref="fileInput" type="file" multiple :accept="ACCEPT" hidden @change="onPick" />
    <input ref="folderInput" type="file" hidden @change="onPick" />
    <el-icon class="dropzone-icon"><UploadFilled /></el-icon>
    <div v-if="reading" class="dropzone-title">正在读取文件夹…</div>
    <div v-else-if="job?.uploading" class="dropzone-title">正在上传，完成后可以继续导入</div>
    <template v-else>
      <div class="dropzone-title">拖拽文件或文件夹到这里，或<em>点击选择文件</em></div>
      <div class="dropzone-hint">
        支持 Word、Excel、PPT、PDF（以及 txt、md），可多选；选文件夹时包含所有子文件夹，导入前会先列出文件清单
      </div>
      <el-button class="folder-button" :icon="FolderOpened" @click.stop="folderInput?.click()">选择文件夹</el-button>
    </template>
  </section>

  <section v-if="job && progress" class="panel import-panel">
    <div class="panel-header">
      <h3>导入「{{ job.name }}」</h3>
      <span class="panel-hint">{{ statusText }}</span>
      <el-button v-if="finished" class="import-close" link @click="close">关闭</el-button>
    </div>
    <div class="import-row">
      <span class="import-label">上传</span>
      <el-progress :percentage="percent(job.uploaded, job.total)" :show-text="false"
                   :status="job.uploading ? undefined : 'success'" />
      <span class="import-count">{{ job.uploaded }} / {{ job.total }} · {{ percent(job.uploaded, job.total) }}%</span>
    </div>
    <div class="import-row">
      <span class="import-label">解析与向量化</span>
      <el-progress
        :percentage="percent(progress.done, job.accepted)"
        :show-text="false"
        :status="finished ? (progress.failed ? 'warning' : 'success') : undefined"
      />
      <span class="import-count">{{ progress.done }} / {{ job.accepted }} · {{ percent(progress.done, job.accepted) }}%</span>
    </div>
    <div class="import-summary">
      <span>新增 <b>{{ job.accepted }}</b></span>
      <span>已就绪 <b>{{ progress.ready }}</b></span>
      <span>处理中 <b>{{ progress.processing }}</b></span>
      <span>
        失败 <b :class="{ danger: progress.failed }">{{ progress.failed }}</b>
        <el-button v-if="progress.failed" link type="primary" @click="emit('showFailed')">查看</el-button>
      </span>
      <span>已存在 <b>{{ job.duplicates }}</b></span>
      <span v-if="job.skipped">跳过 <b>{{ job.skipped }}</b></span>
      <span>知识片段 <b>{{ progress.chunks }}</b></span>
    </div>
    <el-collapse v-if="job.problems.length" class="import-problems">
      <el-collapse-item :title="`未加入的文件（${job.problems.length} 个）`">
        <div v-for="(p, i) in job.problems" :key="i">{{ p }}</div>
      </el-collapse-item>
    </el-collapse>
  </section>

  <el-dialog :model-value="!!plan" :title="`导入「${plan?.name}」`" width="620px" @close="plan = null">
    <template v-if="plan">
      <p class="plan-total">
        共 {{ plan.files.length + plan.skippedCount }} 个文件，可导入 <b>{{ plan.files.length }}</b> 个（{{ formatSize(plan.bytes) }}）<template v-if="plan.skippedCount">，跳过 {{ plan.skippedCount }} 个</template>
      </p>
      <el-table v-if="plan.formats.length" :data="plan.formats" size="small">
        <el-table-column label="格式" prop="label" width="90" />
        <el-table-column label="文件数" prop="count" width="80" align="right" />
        <el-table-column label="大小" width="100" align="right">
          <template #default="{ row }">{{ formatSize(row.bytes) }}</template>
        </el-table-column>
        <el-table-column label="扩展名" prop="exts" />
      </el-table>
      <template v-if="plan.skipped.length">
        <div class="plan-subtitle">将跳过</div>
        <el-table :data="plan.skipped" size="small">
          <el-table-column label="原因" prop="reason" width="190" />
          <el-table-column label="文件数" prop="count" width="80" align="right" />
          <el-table-column label="示例" prop="examples" show-overflow-tooltip />
        </el-table>
      </template>
      <p class="plan-hint">
        与知识库中已有文件内容相同的会在上传时自动跳过。上传完成后在后台解析和向量化，几百个文件可能需要几十分钟，期间可以离开此页面。
      </p>
    </template>
    <template #footer>
      <el-button @click="plan = null">取消</el-button>
      <el-button type="primary" :disabled="!plan?.files.length" @click="plan && start(plan)">
        开始导入 {{ plan?.files.length }} 个文件
      </el-button>
    </template>
  </el-dialog>
</template>
