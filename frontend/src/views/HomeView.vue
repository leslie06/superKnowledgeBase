<script setup lang="ts">
import { watchEffect } from 'vue'
import { useRouter } from 'vue-router'
import { store } from '../store'

const router = useRouter()

// 有知识库时直接进入第一个知识库的问答页
watchEffect(() => {
  if (store.loaded && store.kbs.length) router.replace(`/kb/${store.kbs[0].id}/chat`)
})
</script>

<template>
  <div class="home">
    <el-empty v-if="store.loaded && !store.kbs.length" description="还没有知识库">
      <p class="home-tip">点击右上角「新建知识库」，然后上传 Word、Excel、PPT、PDF 文件，就可以基于这些资料提问了。</p>
    </el-empty>
  </div>
</template>
