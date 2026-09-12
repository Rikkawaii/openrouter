import { useEffect, useState } from 'react'
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

function formatISO(date) {
  const tzOffset = date.getTimezoneOffset() * 60000
  return new Date(date - tzOffset).toISOString().slice(0, -1)
}

export default function Dashboard({ stats, channels }) {
  const [timeRange, setTimeRange] = useState('realtime')
  const [rangeStats, setRangeStats] = useState(null)

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

  const display = timeRange === 'realtime' ? stats : (rangeStats ?? stats)

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">仪表盘</h1>
          <p className="page-desc">网关运行状态与渠道调度总览，实时数据每 2 秒刷新。</p>
        </div>
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

      <div className="page-body">
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

        <div className="section-head">
          <h2 className="section-title">渠道矩阵</h2>
          <span className="section-hint">
            评分 = 基础权重 − 近期错误×10 − 模型延迟×0.05 − 并发×5
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
      </div>
    </>
  )
}
