<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Download, Plus, Refresh } from '@element-plus/icons-vue'
import { api, evalCasesCsvUrl } from '../api'
import { FEEDBACK_REASONS } from '../format'
import type { EvalCase, InsightCase, InsightFlag, InsightOverview, Timing } from '../types'

/**
 * 问答分析：最近一段时间的问答统计和需要关注的回答（坏案例）。确认是检索漏召回的问题加入评测题，
 * 评测脚本可以直接读取导出地址，以后每次改动都回归这些题。
 */
const props = defineProps<{ kbId: string }>()
const router = useRouter()
const kbId = computed(() => Number(props.kbId))

const FLAGS: Record<InsightFlag, { label: string; type: 'danger' | 'warning' | 'info' | 'success' }> = {
  DOWN: { label: '没用', type: 'danger' },
  REFUSED: { label: '拒答', type: 'warning' },
  VERIFY_ISSUE: { label: '核对有问题', type: 'danger' },
  UNRERANKED: { label: '精排未生效', type: 'info' },
  SLOW: { label: '慢', type: 'info' },
  UP: { label: '有用', type: 'success' },
}
const PROBLEMS: InsightFlag[] = ['DOWN', 'REFUSED', 'VERIFY_ISSUE', 'UNRERANKED', 'SLOW']
/** 筛选项：PROBLEM 表示带任一问题标签，ALL 表示全部回答 */
type Filter = InsightFlag | 'PROBLEM' | 'ALL'

const days = ref(30)
const overview = ref<InsightOverview | null>(null)
const evalCases = ref<EvalCase[]>([])
const loading = ref(false)
const filter = ref<Filter>('PROBLEM')

const draft = ref<Partial<EvalCase> | null>(null)
/** 加入评测题时可选的文件：这条回答的参考资料 */
const fileOptions = ref<string[]>([])

async function load() {
  loading.value = true
  try {
    ;[overview.value, evalCases.value] = await Promise.all([api.insights(kbId.value, days.value), api.listEvalCases(kbId.value)])
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(days, load)

const stats = computed(() => overview.value?.stats)

const cases = computed(() => {
  const all = overview.value?.cases ?? []
  if (filter.value === 'ALL') return all
  if (filter.value === 'PROBLEM') return all.filter((c) => c.flags.some((f) => PROBLEMS.includes(f)))
  return all.filter((c) => c.flags.includes(filter.value as InsightFlag))
})

function count(f: Filter) {
  const all = overview.value?.cases ?? []
  if (f === 'ALL') return all.length
  if (f === 'PROBLEM') return all.filter((c) => c.flags.some((x) => PROBLEMS.includes(x))).length
  return all.filter((c) => c.flags.includes(f)).length
}

function percent(n: number, total: number) {
  return total ? `${Math.round(n / total * 100)}%` : '-'
}

function seconds(ms: number | null | undefined) {
  return ms == null ? '-' : `${(ms / 1000).toFixed(1)}s`
}

function timing(t: Timing | undefined) {
  return t && t.p50 != null ? `${seconds(t.p50)} / ${seconds(t.p90)}` : '-'
}

/** 从提问到出第一个字：改写 + 检索 + 首字 */
function waited(c: InsightCase) {
  const t = c.timings
  if (t.rewriteMs == null && t.retrieveMs == null) return null
  return (t.rewriteMs ?? 0) + (t.retrieveMs ?? 0) + (t.firstTokenMs ?? 0)
}

function formatTime(iso: string) {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function scopeText(c: InsightCase) {
  const s = c.scope
  if (!s.folders.length && !s.documentIds.length) return null
  return [...s.folders, ...s.documentIds.map((id) => `文档 ${id}`)].join('、')
}

function openConversation(c: InsightCase) {
  router.push(`/kb/${kbId.value}/chat/${c.conversationId}`)
}

function addEvalCase(c: InsightCase) {
  fileOptions.value = [...new Set(c.hits.map((h) => h.filename))]
  draft.value = {
    question: c.question ?? '',
    expectedFile: '',
    expectedText: '',
    scope: c.scope.folders.join('|'),
    note: [c.feedback?.reason ? FEEDBACK_REASONS[c.feedback.reason] : null, c.feedback?.note].filter(Boolean).join('：'),
    messageId: c.messageId,
  }
}

async function saveEvalCase() {
  if (!draft.value?.question?.trim()) {
    ElMessage.warning('问题不能为空')
    return
  }
  try {
    await api.createEvalCase(kbId.value, draft.value)
    draft.value = null
    evalCases.value = await api.listEvalCases(kbId.value)
    ElMessage.success('已加入评测题')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function removeEvalCase(c: EvalCase) {
  await ElMessageBox.confirm(`删除评测题「${c.question}」？`, '删除评测题', { type: 'warning', confirmButtonText: '删除' })
  await api.deleteEvalCase(c.id)
  evalCases.value = evalCases.value.filter((x) => x.id !== c.id)
}

const evalCommand = computed(() =>
  `python3 scripts/eval_retrieval.py --kb-id ${kbId.value} --csv ${location.origin}${evalCasesCsvUrl(kbId.value)}`)
</script>

<template>
  <div class="docs-page insights-page" v-loading="loading">
    <section class="panel">
      <div class="panel-header">
        <h3>问答分析</h3>
        <span class="panel-hint">统计最近的回答，找出没答好的问题；评测脚本创建的对话不计入</span>
        <el-select v-model="days" class="days-select">
          <el-option :value="7" label="最近 7 天" />
          <el-option :value="30" label="最近 30 天" />
          <el-option :value="90" label="最近 90 天" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
      <div v-if="stats" class="stats insight-stats">
        <div><b>{{ stats.answers }}</b><span>回答</span></div>
        <div><b :class="{ danger: stats.refused }">{{ stats.refused }}</b><span>拒答 · {{ percent(stats.refused, stats.answers) }}</span></div>
        <div><b :class="{ danger: stats.down }">{{ stats.down }}</b><span>没用 · 有用 {{ stats.up }}</span></div>
        <div><b :class="{ danger: stats.verifyIssues }">{{ stats.verifyIssues }}</b><span>核对发现问题</span></div>
        <div><b>{{ stats.unreranked }}</b><span>精排未生效</span></div>
        <div><b>{{ stats.slow }}</b><span>慢（出字超过 15 秒）</span></div>
        <div><b class="small">{{ timing(stats.rewrite) }}</b><span>查询改写 中位 / P90</span></div>
        <div><b class="small">{{ timing(stats.retrieve) }}</b><span>检索（含精排）中位 / P90</span></div>
        <div><b class="small">{{ timing(stats.firstToken) }}</b><span>生成首字 中位 / P90</span></div>
      </div>
      <el-collapse v-if="overview?.daily.length" class="daily">
        <el-collapse-item :title="`按天查看（${overview.daily.length} 天）`">
          <el-table :data="overview.daily" size="small" max-height="300">
            <el-table-column label="日期" prop="date" width="120" />
            <el-table-column label="回答" prop="answers" align="right" />
            <el-table-column label="拒答" align="right">
              <template #default="{ row }">{{ row.refused }}（{{ percent(row.refused, row.answers) }}）</template>
            </el-table-column>
            <el-table-column label="没用" prop="down" align="right" />
            <el-table-column label="核对发现问题" prop="verifyIssues" align="right" />
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </section>

    <section class="panel">
      <div class="panel-header">
        <h3>回答明细</h3>
        <el-radio-group v-model="filter" size="small">
          <el-radio-button value="PROBLEM">需要关注 {{ count('PROBLEM') }}</el-radio-button>
          <el-radio-button v-for="f in PROBLEMS" :key="f" :value="f">{{ FLAGS[f].label }} {{ count(f) }}</el-radio-button>
          <el-radio-button value="ALL">全部 {{ count('ALL') }}</el-radio-button>
        </el-radio-group>
      </div>
      <el-table :data="cases" row-key="messageId" empty-text="这段时间没有需要关注的回答">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="case-detail">
              <dl>
                <dt>检索查询</dt>
                <dd class="pre">{{ row.searchQuery || '（没有记录）' }}</dd>
                <dt v-if="scopeText(row)">检索范围</dt>
                <dd v-if="scopeText(row)">{{ scopeText(row) }}</dd>
                <dt>耗时</dt>
                <dd>
                  改写 {{ seconds(row.timings.rewriteMs) }} · 检索 {{ seconds(row.timings.retrieveMs) }}
                  · 首字 {{ seconds(row.timings.firstTokenMs) }} · 生成 {{ seconds(row.timings.answerMs) }}
                </dd>
                <template v-if="row.feedback">
                  <dt>用户评价</dt>
                  <dd>
                    {{ row.feedback.rating === 'UP' ? '有用' : `没用：${row.feedback.reason ? FEEDBACK_REASONS[row.feedback.reason as keyof typeof FEEDBACK_REASONS] : ''}` }}
                    <template v-if="row.feedback.note">（{{ row.feedback.note }}）</template>
                  </dd>
                </template>
                <template v-if="row.verification && (row.verification.unsupported.length || row.verification.citationErrors.length)">
                  <dt>核对</dt>
                  <dd>
                    <div v-for="(i, k) in row.verification.unsupported" :key="'u' + k">「{{ i.text }}」资料中找不到依据：{{ i.reason }}</div>
                    <div v-for="(i, k) in row.verification.citationErrors" :key="'c' + k">「{{ i.text }}」引用与内容不符：{{ i.reason }}</div>
                  </dd>
                </template>
                <dt>回答</dt>
                <dd class="pre">{{ row.answer }}</dd>
                <dt>参考资料</dt>
                <dd>
                  <div v-if="!row.hits.length" class="muted">没有相关片段，检索阶段直接拒答</div>
                  <div v-for="h in row.hits" :key="h.index" class="case-hit">
                    <div>
                      <b>[{{ h.index }}]</b> {{ h.filename }}
                      <span class="muted">{{ [h.headingPath, h.location].filter(Boolean).join(' · ') }}</span>
                      <span class="muted">· 相似度 {{ h.score?.toFixed(3) ?? '-' }} · 精排 {{ h.rerankScore?.toFixed(3) ?? '未精排' }}</span>
                    </div>
                    <div class="muted snippet">{{ h.snippet }}</div>
                  </div>
                </dd>
              </dl>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="110">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="问题" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ row.question ?? '（没有找到提问）' }}</template>
        </el-table-column>
        <el-table-column label="标签" min-width="190">
          <template #default="{ row }">
            <el-tag v-for="f in row.flags" :key="f" :type="FLAGS[f as InsightFlag].type" size="small" effect="light" class="flag">
              {{ FLAGS[f as InsightFlag].label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="出字耗时" width="90" align="right">
          <template #default="{ row }">{{ seconds(waited(row)) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openConversation(row)">打开对话</el-button>
            <el-button link type="primary" :icon="Plus" @click="addEvalCase(row)">加入评测题</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel">
      <div class="panel-header">
        <h3>评测题 {{ evalCases.length }}</h3>
        <span class="panel-hint">从真实问答中整理出的检索评测题，导出格式与 scripts/eval 下的题集相同</span>
        <a :href="evalCasesCsvUrl(kbId)" class="export-link">
          <el-button :icon="Download" :disabled="!evalCases.length">导出 CSV</el-button>
        </a>
      </div>
      <el-table :data="evalCases" size="small" empty-text="还没有评测题：在上面的回答明细中点「加入评测题」">
        <el-table-column label="问题" prop="question" min-width="220" show-overflow-tooltip />
        <el-table-column label="预期文件" prop="expectedFile" min-width="160" show-overflow-tooltip />
        <el-table-column label="预期原文" prop="expectedText" min-width="160" show-overflow-tooltip />
        <el-table-column label="范围" prop="scope" width="120" show-overflow-tooltip />
        <el-table-column label="备注" prop="note" min-width="120" show-overflow-tooltip />
        <el-table-column width="60" align="center">
          <template #default="{ row }">
            <el-button link type="danger" :icon="Delete" @click="removeEvalCase(row)" />
          </template>
        </el-table-column>
      </el-table>
      <p v-if="evalCases.length" class="plan-hint">用评测脚本直接回归这些题：<code>{{ evalCommand }}</code></p>
    </section>

    <el-dialog :model-value="!!draft" title="加入评测题" width="560px" @close="draft = null">
      <el-form v-if="draft" label-width="80px">
        <el-form-item label="问题">
          <el-input v-model="draft.question" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }" />
        </el-form-item>
        <el-form-item label="预期文件">
          <el-select v-model="draft.expectedFile" filterable allow-create clearable default-first-option
                     placeholder="答案所在的文件名，可直接输入（子串匹配）" class="full">
            <el-option v-for="f in fileOptions" :key="f" :label="f" :value="f" />
          </el-select>
        </el-form-item>
        <el-form-item label="预期原文">
          <el-input v-model="draft.expectedText" placeholder="答案片段中一定包含的一句原文（最精确，可选）" />
        </el-form-item>
        <el-form-item label="范围">
          <el-input v-model="draft.scope" placeholder="文件夹路径，| 分隔；为空表示整个知识库" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="draft.note" />
        </el-form-item>
      </el-form>
      <p class="plan-hint">预期文件和预期原文都留空，表示这是「知识库里没有答案」的题，期望系统拒答。多个可接受的值用 | 分隔。</p>
      <template #footer>
        <el-button @click="draft = null">取消</el-button>
        <el-button type="primary" @click="saveEvalCase">加入</el-button>
      </template>
    </el-dialog>
  </div>
</template>
