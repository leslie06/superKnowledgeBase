<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { KnowledgeBase } from '../types'

const props = defineProps<{ kb: KnowledgeBase | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [kb: KnowledgeBase, created: boolean] }>()

const form = reactive({ name: '', description: '' })
const saving = ref(false)

watch(visible, (open) => {
  if (open) {
    form.name = props.kb?.name ?? ''
    form.description = props.kb?.description ?? ''
  }
})

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  saving.value = true
  try {
    const kb = props.kb
      ? await api.updateKb(props.kb.id, form.name, form.description)
      : await api.createKb(form.name, form.description)
    emit('saved', kb, !props.kb)
    visible.value = false
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="kb ? '编辑知识库' : '新建知识库'" width="460px">
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="100" placeholder="例如：公司制度、产品资料" autofocus />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" placeholder="可选" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
