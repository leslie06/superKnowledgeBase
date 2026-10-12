import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import ChatView from './views/ChatView.vue'
import DocumentsView from './views/DocumentsView.vue'

export const router = createRouter({
  // hash 路由：打包进 Spring Boot 后无需配置 SPA 回退
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/kb/:kbId/chat/:convId?', name: 'chat', component: ChatView, props: true },
    { path: '/kb/:kbId/docs', name: 'docs', component: DocumentsView, props: true },
  ],
})
