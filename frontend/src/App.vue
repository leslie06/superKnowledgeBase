<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Folder, Plus, MoreFilled } from '@element-plus/icons-vue'
import KbDialog from './components/KbDialog.vue'
import { api } from './api'
import { loadKbs, store } from './store'
import type { KnowledgeBase } from './types'

const route = useRoute()
const router = useRouter()
const dialogVisible = ref(false)
const editing = ref<KnowledgeBase | null>(null)

const currentKbId = computed(() => (route.params.kbId ? Number(route.params.kbId) : null))
const currentKb = computed(() => store.kbs.find((kb) => kb.id === currentKbId.value) ?? null)
const tab = computed(() => (route.name === 'docs' ? 'docs' : 'chat'))

onMounted(async () => {
  try {
    await loadKbs()
  } catch (e) {
    ElMessage.error(`无法连接后端服务：${(e as Error).message}`)
  }
})

function switchKb(id: number) {
  router.push(tab.value === 'docs' ? `/kb/${id}/docs` : `/kb/${id}/chat`)
}

function switchTab(name: string) {
  if (currentKbId.value) router.push(name === 'docs' ? `/kb/${currentKbId.value}/docs` : `/kb/${currentKbId.value}/chat`)
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

async function onSaved(kb: KnowledgeBase, created: boolean) {
  await loadKbs()
  if (created) router.push(`/kb/${kb.id}/docs`)
}

async function onKbCommand(command: string) {
  const kb = currentKb.value
  if (!kb) return
  if (command === 'edit') {
    editing.value = kb
    dialogVisible.value = true
    return
  }
  await ElMessageBox.confirm(`删除知识库「${kb.name}」后，其中的所有文件、向量和对话记录都会被永久删除。`, '确认删除',
    { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' })
  await api.deleteKb(kb.id)
  ElMessage.success('已删除')
  await loadKbs()
  router.push('/')
}
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="brand" @click="router.push('/')">
        <span class="logo">知</span>
        <span>超级知识库</span>
      </div>

      <template v-if="store.kbs.length">
        <el-select :model-value="currentKbId" placeholder="选择知识库" class="kb-select" @change="switchKb">
          <el-option v-for="kb in store.kbs" :key="kb.id" :label="kb.name" :value="kb.id">
            <span>{{ kb.name }}</span>
            <span class="kb-option-count">{{ kb.documentCount }} 个文件</span>
          </el-option>
        </el-select>
        <el-dropdown v-if="currentKb" trigger="click" @command="onKbCommand">
          <el-button :icon="MoreFilled" text />
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="edit">编辑知识库</el-dropdown-item>
              <el-dropdown-item command="delete" divided>删除知识库</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
      <el-button :icon="Plus" @click="openCreate">新建知识库</el-button>

      <nav v-if="currentKbId" class="tabs">
        <button :class="{ active: tab === 'chat' }" @click="switchTab('chat')">
          <el-icon><ChatDotRound /></el-icon>智能问答
        </button>
        <button :class="{ active: tab === 'docs' }" @click="switchTab('docs')">
          <el-icon><Folder /></el-icon>文档管理
        </button>
      </nav>
    </header>

    <main class="main">
      <router-view :key="String(route.params.kbId ?? '')" />
    </main>

    <KbDialog v-model="dialogVisible" :kb="editing" @saved="onSaved" />
  </div>
</template>
