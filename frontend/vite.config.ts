import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    // 构建产物直接放进 Spring Boot 的静态资源目录，打包后一个 jar 即可运行
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})
