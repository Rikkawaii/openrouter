import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { clearLogs, logStore, subscribeLogStore } from '../api.js'

// 与后端日志格式对应的轻量高亮（先转义再匹配，避免注入）
function formatLog(text) {
  if (!text) return ''
  let html = String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  html = html.replace(/^\[(\d{2}:\d{2}:\d{2}\.\d{3})\]/, '<span class="log-time">[$1]</span>')
  html = html.replace(/\[(INFO|SUCCESS)\]/g, '<span class="log-info">[$1]</span>')
  html = html.replace(/\[(WARN)\]/g, '<span class="log-warn">[$1]</span>')
  html = html.replace(/\[(ERROR)\]/g, '<span class="log-error">[$1]</span>')
  html = html.replace(/【(.*?)】/g, '<span class="log-stage">【$1】</span>')
  html = html.replace(/&lt;([a-f0-9]{8})&gt;$/g, '<span class="log-trace-id">&lt;$1&gt;</span>')
  html = html.replace(/ (成功|完成)/g, ' <span class="log-info">$1</span>')
  html = html.replace(/ (失败|错误)/g, ' <span class="log-error">$1</span>')
  return html
}

function useLogVersion() {
  return useSyncExternalStore(subscribeLogStore, () => logStore.version)
}

export default function Logs() {
  useLogVersion()
  const bodyRef = useRef(null)
  const [autoScroll, setAutoScroll] = useState(true)

  const lines = logStore.lines
  const status = logStore.status

  useEffect(() => {
    if (autoScroll && bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight
    }
  }, [lines.length, autoScroll])

  const statusText = { open: '已连接', connecting: '连接中…', closed: '已断开' }[status]
  const statusColor =
    status === 'open' ? 'var(--emerald)' : status === 'connecting' ? 'var(--amber)' : 'var(--red)'

  return (
    <div className="logs-page">
      <div className="logs-toolbar">
        <div className="logs-toolbar-left">
          <div>
            <h1 className="page-title" style={{ fontSize: 18 }}>
              实时日志
            </h1>
            <p className="page-desc">请求全链路追踪输出，仅保留最近 2000 行。</p>
          </div>
        </div>
        <div className="logs-toolbar-right">
          <span className="ws-pill">
            <span className={`conn-dot ${status === 'open' ? 'on' : 'off'}`} style={{ background: statusColor }} />
            {statusText}
          </span>
          <span style={{ fontSize: 12, color: 'var(--text-4)', fontFamily: 'var(--font-mono)' }}>
            {lines.length} 行
          </span>
          <button type="button" className="link-btn" onClick={() => setAutoScroll((v) => !v)}>
            {autoScroll ? '自动滚动：开' : '自动滚动：关'}
          </button>
          <button type="button" className="link-btn" onClick={clearLogs}>
            清空
          </button>
        </div>
      </div>

      <div className="terminal" ref={bodyRef}>
        {lines.length === 0 ? (
          <div className="terminal-empty">等待日志输出…</div>
        ) : (
          lines.map((text, idx) => (
            <div
              className="log-line"
              key={idx}
              dangerouslySetInnerHTML={{ __html: formatLog(text) }}
            />
          ))
        )}
      </div>
    </div>
  )
}
