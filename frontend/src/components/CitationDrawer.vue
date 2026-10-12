<script setup lang="ts">
import { Document, TopRight } from '@element-plus/icons-vue'
import { fileUrl } from '../api'
import type { Citation } from '../types'

defineProps<{ citation: Citation | null }>()
defineEmits<{ close: [] }>()
</script>

<template>
  <el-drawer :model-value="!!citation" size="520px" @close="$emit('close')">
    <template #header>
      <div class="drawer-title"><el-icon><Document /></el-icon>引用 [{{ citation?.index }}]</div>
    </template>
    <template v-if="citation">
      <dl class="cite-meta">
        <dt>文件</dt>
        <dd>{{ citation.filename }}</dd>
        <template v-if="citation.headingPath">
          <dt>章节</dt>
          <dd>{{ citation.headingPath }}</dd>
        </template>
        <template v-if="citation.location">
          <dt>位置</dt>
          <dd>{{ citation.location }}</dd>
        </template>
        <dt>相似度</dt>
        <dd>{{ citation.score.toFixed(3) }}</dd>
      </dl>
      <div class="cite-content">{{ citation.content }}</div>
      <el-button type="primary" plain :icon="TopRight" tag="a"
                 :href="fileUrl(citation.documentId, citation.filename, citation.location)" target="_blank">
        打开原文
      </el-button>
    </template>
  </el-drawer>
</template>
