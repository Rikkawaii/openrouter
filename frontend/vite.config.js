import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 后端 Spring Boot 运行在 10086 端口，开发时通过代理转发，
// 前端代码里直接用相对路径（如 /api/admin/...）即可，无需处理 CORS。
const backend = 'http://localhost:10086'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // REST 接口：模型路由 /v1/chat/completions、/v1/models，管理台 /api/admin/*
      '/v1': backend,
      '/api': {
        target: backend,
        ws: true, // /api/admin/logs/ws 是 WebSocket 日志推送
      },
    },
  },
})
