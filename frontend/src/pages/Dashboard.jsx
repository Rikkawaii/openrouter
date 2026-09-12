import { useEffect, useMemo, useState } from 'react'
import StatCard from '../components/StatCard.jsx'
import ChannelCard from '../components/ChannelCard.jsx'
import { formatNumber, getRatio } from '../format.js'
import { api } from '../api.js'
import { IconActivity, IconClock, IconCoins, IconZap } from '../components/icons.jsx'

const RANGES = [
  { key: 'realtime', label: '实时' },
  { key: '1h', label: '近 1 小时' },
  { key: '24h', label: '近 24 小时' },
  { key: '7d', label: '近 7 天' },
]

const COMPARE_HOURS = [
  { value: 24, label: '近 24 小时' },
  { value: 24 * 7, label: '近 7 天' },
  { value: 24 * 30, label: '近 30 天' },
]

const DEFAULT_WEIGHTS = { healthWeight: 0.5, latencyWeight: 0.3, loadWeight: 0.2 }

function formatISO(date) {
  const tzOffset = date.getTimezoneOffset() * 60000
  return new Date(date - tzOffset).toISOString().slice(0, -1)
}

const formatMs = (v) => (v == null ? '—' : `${v}ms`)

export default function Dashboard({ stats, channels, routing }) {
  const [timeRange, setTimeRange] = useState('realtime')
  const [rangeStats, setRangeStats] = useState(null)
  const [compareModel, setCompareModel] = useState('')
  const [compareHours, setCompareHours] = useState(24)
  const [comparison, setComparison] = useState(null)

  // 历史模式：按范围拉取聚合统计；实时模式直接使用 App 轮询的全局统计
  useEffect(() => {
    if (timeRange === 'realtime') return
    const end = new Date()
    const start = new Date()
    if (timeRange === '1h') start.setHours(start.getHours() - 1)
    if (timeRange === '24h') start.setHours(start.getHours() - 24)
    if (timeRange === '7d') start.setDate(start.getDate() - 7)

    api(`/api/admin/stats/range?start=${encodeURIComponent(formatISO(start))}&end=${encodeURIComponent(formatISO(end))}`)
      .then(setRangeStats)
      .catch(() => setRangeStats(null))
  }, [timeRange])

  // 所有渠道声明支持的模型（用于同模型跨渠道对比）
  const modelOptions = useMemo(() => {
    const set = new Set()
    channels.forEach((ch) => {
      ;(ch.models || '')
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean)
        .forEach((m) => set.add(m))
    })
    return [...set].sort()
  }, [channels])

  const activeModel = compareModel || modelOptions[0] || ''

  // 模型对比是独立查询：不随 2 秒轮询刷新，避免打满 SQL
  useEffect(() => {
    if (!activeModel) return
    let cancelled = false
    api(`/api/admin/model-comparison?model=${encodeURIComponent(activeModel)}&hours=${compareHours}`)
      .then((res) => {
        if (!cancelled) setComparison(res)
      })
      .catch(() => {
        if (!cancelled) setComparison(null)
      })
    return () => {
      cancelled = true
    }
  }, [activeModel, compareHours])

  const display = timeRange === 'realtime' ? stats : (rangeStats ?? stats)
  const w = routing ?? DEFAULT_WEIGHTS
  const compareRows = comparison && comparison.model === activeModel ? comparison.channels : []

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">仪表盘</h1>
          <p className="page-desc">网关运行状态与渠道调度总览。</p>
        </div>
      </div>

      <div className="page-body">
        {/* 时间范围选择器内嵌在面板里，面板边界即它的作用边界 */}
        <div className="panel" style={{ marginTop: 4 }}>
          <div className="panel-head">
            <h2 className="section-title">概览统计</h2>
          <div className="segmented" role="tablist" aria-label="统计时间范围">
            {RANGES.map((r) => (
              <button
                key={r.key}
                type="button"
                className={timeRange === r.key ? 'active' : ''}
                onClick={() => setTimeRange(r.key)}
              >
                {r.key === 'realtime' && <span className="live-dot" />}
                {r.label}
              </button>
            ))}
          </div>
        </div>

        <div className="stat-grid">
          <StatCard
            icon={IconZap}
            label="活跃渠道"
            value={display.activeChannels}
            suffix={` / ${display.totalChannels}`}
          />
          <StatCard
            icon={IconClock}
            label="全链路平均响应"
            value={display.avgResponseTime}
            unit="ms"
          />
          <StatCard
            icon={IconClock}
            label={timeRange === 'realtime' ? '全链路 P95（近 1 小时）' : '全链路 P95'}
            value={display.p95ResponseTime == null ? '—' : display.p95ResponseTime}
            unit={display.p95ResponseTime == null ? '' : 'ms'}
          />
          <StatCard
            icon={IconCoins}
            label="Token 消耗"
            value={formatNumber(display.globalTotalTokens)}
          >
            <div className="bar-track">
              <div
                style={{
                  width: `${getRatio(display.globalPromptTokens, display.globalTotalTokens)}%`,
                  background: 'var(--accent)',
                }}
              />
              <div
                style={{
                  width: `${getRatio(display.globalCompletionTokens, display.globalTotalTokens)}%`,
                  background: 'var(--accent-soft)',
                }}
              />
            </div>
            <div className="bar-meta">
              <span>输入 {formatNumber(display.globalPromptTokens)}</span>
              <span>输出 {formatNumber(display.globalCompletionTokens)}</span>
            </div>
          </StatCard>
          <StatCard
            icon={IconActivity}
            label="请求统计"
            value={formatNumber(display.globalTotalRequests)}
          >
            <div className="bar-track">
              <div
                style={{
                  width: `${100 - getRatio(display.globalFailedRequests, display.globalTotalRequests)}%`,
                  background: 'var(--green)',
                }}
              />
              <div
                style={{
                  width: `${getRatio(display.globalFailedRequests, display.globalTotalRequests)}%`,
                  background: 'var(--red)',
                }}
              />
            </div>
            <div className="bar-meta">
              <span style={{ color: 'var(--green-text)' }}>
                成功 {formatNumber(display.globalTotalRequests - display.globalFailedRequests)}
              </span>
              <span style={{ color: display.globalFailedRequests > 0 ? 'var(--red)' : undefined }}>
                失败 {formatNumber(display.globalFailedRequests)}（
                {getRatio(display.globalFailedRequests, display.globalTotalRequests).toFixed(1)}%）
              </span>
            </div>
          </StatCard>
          </div>
        </div>

        <div className="section-head">
          <h2
            className="section-title"
            title="评分/失败率/并发为此刻状态，延迟与首包为近期 EMA，调用与 Token 为累计；不随时间范围切换"
          >
            <span className="live-dot" />
            渠道矩阵
          </h2>
          <span className="section-hint">
            评分 = 基础权重 × (1 − 健康度×{w.healthWeight} − 延迟×{w.latencyWeight} − 负载×
            {w.loadWeight})，参数可在「系统配置 → 路由调参」调整
          </span>
        </div>

        {channels.length === 0 ? (
          <div className="card empty-state">暂无渠道，请到「系统配置」页添加并保存。</div>
        ) : (
          <div className="channel-grid">
            {channels.map((ch) => (
              <ChannelCard key={ch.id} channel={ch} />
            ))}
          </div>
        )}

        <div className="section-head">
          <h2 className="section-title">同模型渠道对比</h2>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span className="section-hint">
              尝试延迟 = 单次上游调用耗时；首包延迟仅统计流式请求
            </span>
            {modelOptions.length > 0 && (
              <select
                className="input cfg-select"
                style={{ width: 200 }}
                value={activeModel}
                onChange={(e) => setCompareModel(e.target.value)}
                aria-label="选择模型"
              >
                {modelOptions.map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </select>
            )}
            <select
              className="input cfg-select"
              style={{ width: 130 }}
              value={compareHours}
              onChange={(e) => setCompareHours(Number(e.target.value))}
              aria-label="统计范围"
            >
              {COMPARE_HOURS.map((h) => (
                <option key={h.value} value={h.value}>
                  {h.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {modelOptions.length === 0 ? (
          <div className="card empty-state">暂无可对比的模型，请先在「系统配置」页声明模型。</div>
        ) : (
          <div className="card table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>渠道</th>
                  <th>尝试 P50</th>
                  <th>尝试 P95</th>
                  <th>首包 P50</th>
                  <th>首包 P95</th>
                  <th>样本</th>
                </tr>
              </thead>
              <tbody>
                {compareRows.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="dim">
                      该模型在所选范围内没有成功调用记录
                    </td>
                  </tr>
                ) : (
                  compareRows.map((row) => {
                    const ch = channels.find((c) => c.id === row.channelId)
                    const insufficient = row.latencySamples < (comparison?.minSamples ?? 5)
                    return (
                      <tr key={row.channelId}>
                        <td className="mono">
                          {row.channelId}
                          {ch && !ch.enabled && <span className="dim"> · 已停用</span>}
                        </td>
                        <td className="mono">{formatMs(row.latencyP50Ms)}</td>
                        <td className="mono">{formatMs(row.latencyP95Ms)}</td>
                        <td className="mono">{row.ttftSamples === 0 ? '—' : formatMs(row.ttftP50Ms)}</td>
                        <td className="mono">{row.ttftSamples === 0 ? '—' : formatMs(row.ttftP95Ms)}</td>
                        <td className="dim">
                          {formatNumber(row.latencySamples)}
                          {insufficient && ' · 样本不足'}
                          {row.ttftSamples === 0 ? ' · 无流式样本' : ` · 首包 ${formatNumber(row.ttftSamples)}`}
                        </td>
                      </tr>
                    )
                  })
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  )
}
