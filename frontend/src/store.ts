import { reactive } from 'vue'
import { api } from './api'
import type { KnowledgeBase } from './types'

export const store = reactive({
  kbs: [] as KnowledgeBase[],
  loaded: false,
})

export async function loadKbs() {
  store.kbs = await api.listKbs()
  store.loaded = true
}
