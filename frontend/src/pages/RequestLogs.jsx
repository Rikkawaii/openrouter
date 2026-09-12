import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'
import { formatNumber } from '../format.js'

const MAX_RANGE_DAYS = 30
const PAGE_SIZES = [20, 50, 100]

function fmtDate(d) {
  const tzOffset = d.getTimezoneOffset() * 60000
  return new Date(d - tzOffset).toISOString().slice(0, 10)
}

function daysBetween(a, b) {
  return Math.round((new Date(b) - new Date(a)) / 86400000)
}

export default function RequestLogs({ onUnauthorized }) {
  const today = fmtDate(new Date())
  const [filters, setFilters] = useState({
    start: fmtDate(new Date(Date.now() - 6 * 86400000)),
    end: today,
    model: '',
    channel: '',
  })
  const [modelOptions, setModelOptions] = useState([])
  const [channelOptions, setChannelOptions] = useState([])
  const [applied, setApplied] = useState({ ...filters, page: 1, size: 20 })
  const [data, setData] = useState({ items: [], total: 0 })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [detail, setDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)

  const openDetail = useCallback(async (row) => {
    setDetail({ ...row, loading: true })
    setDetailLoading(true)
    try {
      const res = await api(`/api/admin/request-logs/${row.id}`)
      setDetail(res)
    } catch (err) {
      if (err.status === 401) onUnauthorized?.()
      else setError(err.message)
      setDetail(null)
    } finally {
      setDetailLoading(false)
    }
  }, [onUnauthorized])

  // 模型/渠道下拉选项来自当前配置
  useEffect(() => {
    api('/api/admin/channels-config')
      .then((cfg) => {
        setModelOptions((cfg.models ?? []).map((m) => m.name))
        setChannelOptions((cfg.channels ?? []).map((c) => c.id))
      })
      .catch((err) => {
        if (err.status === 401) onUnauthorized?.()
      })
  }, [onUnauthorized])

  const query = useCallback(async (q) => {
    setLoading(true)
    setError(null)
    try {
      const params = new URLSearchParams({
        start: q.start,
        end: q.end,
        page: q.page,
        size: q.size,
      })
      if (q.model) params.set('model', q.model)
      if (q.channel) params.set('channel', q.channel)
      const res = await api(`/api/admin/request-logs?${params}`)
      setData({ items: res.items ?? [], total: res.total ?? 0 })
    } catch (err) {
      if (err.status === 401) onUnauthorized?.()
      else setError(err.message)
    } finally {
      setLoading(false)
    }
  }, [onUnauthorized])

  useEffect(() => {
    query(applied)
  }, [applied, query])

  const update = (patch) => setFilters((prev) => ({ ...prev, ...patch }))

  const search = () => {
    if (daysBetween(filters.start, filters.end) >= MAX_RANGE_DAYS) {
      setError(`日期范围最多 ${MAX_RANGE_DAYS} 天`)
      return
    }
    setApplied({ ...filters, page: 1, size: applied.size })
  }

  const totalPages = Math.max(1, Math.ceil(data.total / applied.size))

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">日志管理</h1>
          <p className="page-desc">查询历史请求日志，支持按日期（最多 {MAX_RANGE_DAYS} 天）、模型、渠道筛选。</p>
        </div>
      </div>

      <div className="page-body">
        <div className="card filter-bar">
          <div className="filter-field">
            <label className="field-label">开始日期</label>
            <input
              className="input"
              type="date"
              value={filters.start}
              max={filters.end}
              onChange={(e) => update({ start: e.target.value })}
            />
          </div>
          <div className="filter-field">
            <label className="field-label">结束日期</label>
            <input
              className="input"
              type="date"
              value={filters.end}
              min={filters.start}
              max={today}
              onChange={(e) => update({ end: e.target.value })}
            />
          </div>
          <div className="filter-field">
            <label className="field-label">模型</label>
            <select
              className="input"
              value={filters.model}
              onChange={(e) => update({ model: e.target.value })}
            >
              <option value="">全部模型</option>
              {modelOptions.map((m) => (
                <option key={m} value={m}>{m}</option>
              ))}
            </select>
          </div>
          <div className="filter-field">
            <label className="field-label">渠道</label>
            <select
              className="input"
              value={filters.channel}
              onChange={(e) => update({ channel: e.target.value })}
            >
              <option value="">全部渠道</option>
              {channelOptions.map((c) => (
                <option key={c} value={c}>{c}</option>
              ))}
            </select>
          </div>
          <button type="button" className="btn btn-primary filter-submit" onClick={search} disabled={loading}>
            {loading ? '查询中…' : '查询'}
          </button>
        </div>

        {error && <div className="cfg-status err" style={{ marginBottom: 12 }}>{error}</div>}

        <div className="card table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th style={{ width: 150 }}>时间</th>
                <th style={{ width: 100 }}>渠道</th>
                <th>模型</th>
                <th style={{ width: 80 }}>状态</th>
                <th style={{ width: 90, textAlign: 'right' }}>耗时</th>
                <th style={{ width: 150, textAlign: 'right' }}>Tokens (入/出/Σ)</th>
                <th style={{ width: 60, textAlign: 'right' }}>重试</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((row) => (
                <tr
                  key={row.id}
                  className="row-clickable"
                  title="点击查看详情"
                  onClick={() => openDetail(row)}
                >
                  <td className="mono dim">{row.created_at}</td>
                  <td className="mono">{row.channel_id || <span className="dim">—</span>}</td>
                  <td className="mono">
                    {row.model}
                    {row.error_msg && (
                      <span className="dim" style={{ marginLeft: 6 }}>⚠</span>
                    )}
                  </td>
                  <td>
                    <span className="status-text">
                      <span className={`conn-dot ${row.success ? 'on' : 'off'}`} />
                      {row.success ? '成功' : '失败'}
                    </span>
                  </td>
                  <td className="mono" style={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                    {row.total_duration_ms ?? '—'}
                  </td>
                  <td className="mono" style={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                    {formatNumber(row.prompt_tokens)} / {formatNumber(row.completion_tokens)} / {formatNumber(row.total_tokens)}
                  </td>
                  <td className="mono" style={{ textAlign: 'right' }}>
                    {row.retry_count || 0}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {data.items.length === 0 && (
            <div className="empty-state">{loading ? '查询中…' : '该条件下暂无请求日志。'}</div>
          )}
        </div>

        <div className="pager">
          <span className="dim" style={{ fontSize: 12 }}>
            共 {formatNumber(data.total)} 条 · 第 {applied.page} / {totalPages} 页
          </span>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <select
              className="input pager-size"
              value={applied.size}
              onChange={(e) => setApplied((prev) => ({ ...prev, size: Number(e.target.value), page: 1 }))}
              aria-label="每页条数"
            >
              {PAGE_SIZES.map((s) => (
                <option key={s} value={s}>{s} 条/页</option>
              ))}
            </select>
            <button
              type="button"
              className="btn btn-ghost"
              disabled={applied.page <= 1 || loading}
              onClick={() => setApplied((prev) => ({ ...prev, page: prev.page - 1 }))}
            >
              上一页
            </button>
            <button
              type="button"
              className="btn btn-ghost"
              disabled={applied.page >= totalPages || loading}
              onClick={() => setApplied((prev) => ({ ...prev, page: prev.page + 1 }))}
            >
              下一页
            </button>
          </div>
        </div>

        {detail && (
          <div className="modal-overlay" onClick={() => setDetail(null)}>
            <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
              <div className="modal-head">
                <div>
                  <h3 style={{ margin: 0, fontSize: 15, fontWeight: 590, color: 'var(--text-1)' }}>
                    请求详情
                    <span className={`conn-dot ${detail.success ? 'on' : 'off'}`} style={{ marginLeft: 8 }} />
                    <span style={{ fontSize: 13, fontWeight: 400, color: 'var(--text-3)', marginLeft: 4 }}>
                      {detail.success ? '成功' : '失败'}
                    </span>
                  </h3>
                  <div className="dim mono" style={{ fontSize: 12, marginTop: 4 }}>
                    {detail.created_at} · {detail.channel_id || '未路由'} · {detail.model} ·{' '}
                    {detail.total_duration_ms ?? '—'}ms · tokens {formatNumber(detail.prompt_tokens)}/
                    {formatNumber(detail.completion_tokens)}/{formatNumber(detail.total_tokens)} · 重试{' '}
                    {detail.retry_count || 0} · trace {detail.trace_id || '—'}
                  </div>
                  {detail.error_msg && (
                    <div style={{ fontSize: 12, color: 'var(--red)', marginTop: 6 }}>{detail.error_msg}</div>
                  )}
                </div>
                <button type="button" className="icon-btn" onClick={() => setDetail(null)} aria-label="关闭">
                  ✕
                </button>
              </div>

              {detailLoading && <div className="dim" style={{ fontSize: 13 }}>加载中…</div>}
              {!detailLoading && (
                <>
                  <JsonSection title="完整请求" text={detail.full_request} />
                  <JsonSection title="完整响应" text={detail.full_response} />
                  <JsonSection title="Trace 事件" text={detail.trace_events} />
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </>
  )
}

function JsonSection({ title, text }) {
  if (!text) return null
  let display = text
  try {
    display = JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    /* 非 JSON 原样展示 */
  }
  return (
    <div className="json-section">
      <div className="json-title">{title}</div>
      <pre className="json-block">{display}</pre>
    </div>
  )
}
