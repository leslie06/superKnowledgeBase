<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, type ElTree } from 'element-plus'
import { Document, Folder, FolderOpened, Search } from '@element-plus/icons-vue'
import { api } from '../api'
import type { KbDocument, SearchScope } from '../types'

/** 文件夹节点的 key 为 "f:路径"，文档节点为 "d:文档 ID" */
interface ScopeNode {
  key: string
  label: string
  folder?: string
  documentId?: number
  count: number
  children?: ScopeNode[]
}

const props = defineProps<{ kbId: number; modelValue: SearchScope }>()
const emit = defineEmits<{ 'update:modelValue': [scope: SearchScope] }>()

const visible = ref(false)
const documents = ref<KbDocument[]>([])
const keyword = ref('')
const treeRef = ref<InstanceType<typeof ElTree>>()

const isAll = (s: SearchScope) => !s.folders.length && !s.documentIds.length

async function loadDocuments() {
  try {
    documents.value = await api.listDocuments(props.kbId)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

watch(() => props.kbId, loadDocuments, { immediate: true })

/** 按 sourcePath 组成文件夹树；单独上传的文件没有路径，放在最外层 */
const tree = computed<ScopeNode[]>(() => {
  const root: ScopeNode[] = []
  const folders = new Map<string, ScopeNode>()
  for (const doc of documents.value) {
    const parts = (doc.sourcePath ?? doc.filename).split('/')
    let siblings = root
    let path = ''
    for (const part of parts.slice(0, -1)) {
      path = path ? `${path}/${part}` : part
      let node = folders.get(path)
      if (!node) {
        node = { key: `f:${path}`, label: part, folder: path, count: 0, children: [] }
        folders.set(path, node)
        siblings.push(node)
      }
      node.count++
      siblings = node.children!
    }
    siblings.push({ key: `d:${doc.id}`, label: doc.filename, documentId: doc.id, count: 1 })
  }
  const sort = (nodes: ScopeNode[]) => {
    nodes.sort((a, b) => Number(!!b.folder) - Number(!!a.folder) || a.label.localeCompare(b.label, 'zh'))
    nodes.forEach((n) => n.children && sort(n.children))
  }
  sort(root)
  return root
})

/** 只有一个顶层文件夹时（通常是导入时选的文件夹）默认展开它 */
const expandedKeys = computed(() => (tree.value.length === 1 && tree.value[0].children ? [tree.value[0].key] : []))

const label = computed(() => {
  const scope = props.modelValue
  if (isAll(scope)) return '全部文档'
  const names = [
    ...scope.folders.map((f) => f.slice(f.lastIndexOf('/') + 1)),
    ...scope.documentIds.map((id) => documents.value.find((d) => d.id === id)?.filename ?? `文档 ${id}`),
  ]
  return names.length > 2 ? `${names.slice(0, 2).join('、')} 等 ${names.length} 项` : names.join('、')
})

const title = computed(() => {
  const scope = props.modelValue
  if (isAll(scope)) return '在整个知识库中检索'
  return ['只在以下范围检索：', ...scope.folders, ...scope.documentIds.map((id) =>
    documents.value.find((d) => d.id === id)?.sourcePath ?? documents.value.find((d) => d.id === id)?.filename ?? `文档 ${id}`)]
    .join('\n')
})

async function onShow() {
  keyword.value = ''
  await loadDocuments()
  await nextTick()
  const scope = props.modelValue
  treeRef.value?.setCheckedKeys([...scope.folders.map((f) => `f:${f}`), ...scope.documentIds.map((id) => `d:${id}`)])
}

watch(keyword, (kw) => treeRef.value?.filter(kw))

function filterNode(value: string, data: ScopeNode) {
  return !value || data.label.toLowerCase().includes(value.toLowerCase())
}

/** 整个文件夹都勾选时记录文件夹（之后导入到这个文件夹的文档也在范围内），否则记录勾选的文档 */
function checkedScope(): SearchScope {
  const checked = new Set(treeRef.value?.getCheckedKeys(false) ?? [])
  if (tree.value.every((n) => checked.has(n.key))) return { folders: [], documentIds: [] }
  const scope: SearchScope = { folders: [], documentIds: [] }
  const walk = (nodes: ScopeNode[]) => {
    for (const node of nodes) {
      if (checked.has(node.key)) {
        if (node.folder) scope.folders.push(node.folder)
        else scope.documentIds.push(node.documentId!)
      } else if (node.children) {
        walk(node.children)
      }
    }
  }
  walk(tree.value)
  return scope
}

function apply(scope: SearchScope) {
  emit('update:modelValue', scope)
  visible.value = false
}
</script>

<template>
  <el-popover v-model:visible="visible" trigger="click" placement="top-start" :width="420" @show="onShow">
    <template #reference>
      <button class="scope-trigger" :class="{ active: !isAll(modelValue) }" :title="title">
        <el-icon><FolderOpened /></el-icon>
        <span>检索范围：{{ label }}</span>
      </button>
    </template>
    <div class="scope-panel">
      <el-input v-model="keyword" :prefix-icon="Search" placeholder="搜索文件夹或文件名" clearable size="small" />
      <div class="scope-tree">
        <el-tree
          ref="treeRef"
          :data="tree"
          node-key="key"
          show-checkbox
          :default-expanded-keys="expandedKeys"
          :filter-node-method="filterNode"
          :props="{ label: 'label', children: 'children' }"
        >
          <template #default="{ data }">
            <span class="scope-node">
              <el-icon><Folder v-if="data.folder" /><Document v-else /></el-icon>
              <span class="scope-label" :title="data.label">{{ data.label }}</span>
              <span v-if="data.folder" class="muted">{{ data.count }}</span>
            </span>
          </template>
        </el-tree>
        <div v-if="!tree.length" class="scope-empty">这个知识库还没有文档</div>
      </div>
      <div class="scope-hint">勾选整个文件夹时，之后导入到这个文件夹的文档也会在范围内。</div>
      <div class="scope-actions">
        <el-button size="small" @click="apply({ folders: [], documentIds: [] })">全部文档</el-button>
        <el-button size="small" type="primary" @click="apply(checkedScope())">确定</el-button>
      </div>
    </div>
  </el-popover>
</template>
