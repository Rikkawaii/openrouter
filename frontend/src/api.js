// 后端 API 封装：fetch + 管理令牌 + WebSocket 日志流
// 开发时经由 vite 代理转发到 localhost:10086，代码里一律使用相对路径。

const TOKEN_KEY = 'admin_token'

export const getToken = () => localStorage.getItem(TOKEN_KEY)
export const setToken = (t) => localStorage.setItem(TOKEN_KEY, t)
export const clearToken = () => localStorage.removeItem(TOKEN_KEY)

export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}

export async function api(path, { method = 'GET', body } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const token = getToken()
  if (token) headers.Authorization = token

  const res = await fetch(path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  if (res.status === 401) throw new ApiError('未授权或会话已过期', 401)

  const data = await res.json().catch(() => null)
  if (!res.ok) throw new ApiError(data?.message || `请求失败（${res.status}）`, res.status)
  return data
}

/* ---------- 实时日志流 ---------- */

const MAX_LOG_LINES = 2000

// 模块级缓冲：切换页面不丢日志
export const logStore = {
  lines: [],
  status: 'closed', // connecting | open | closed
  version: 0,
}

const listeners = new Set()

function notify() {
  logStore.version++
  listeners.forEach((fn) => fn())
}

export function subscribeLogStore(fn) {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

export function pushLog(line) {
  logStore.lines.push(line)
  if (logStore.lines.length > MAX_LOG_LINES) {
    logStore.lines.splice(0, logStore.lines.length - MAX_LOG_LINES)
  }
  notify()
}

export function clearLogs() {
  logStore.lines = []
  notify()
}

let ws = null
let retryTimer = null

export function connectLogStream() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
  clearTimeout(retryTimer)

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  ws = new WebSocket(`${protocol}//${window.location.host}/api/admin/logs/ws`)

  logStore.status = 'connecting'
  notify()

  ws.onopen = () => {
    logStore.status = 'open'
    notify()
  }

  ws.onmessage = (event) => pushLog(event.data)

  ws.onclose = () => {
    logStore.status = 'closed'
    notify()
    retryTimer = setTimeout(connectLogStream, 5000)
  }

  ws.onerror = () => ws.close()
}

export function disconnectLogStream() {
  clearTimeout(retryTimer)
  if (ws) {
    ws.onclose = null
    ws.close()
    ws = null
  }
  logStore.status = 'closed'
  notify()
}
