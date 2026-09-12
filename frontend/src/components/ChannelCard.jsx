import { Fragment } from 'react'
import { formatNumber, getRatio } from '../format.js'

export default function ChannelCard({ channel }) {
  const ch = channel
  const modelStats = ch.modelStats ?? []
  const failureRate = ch.failureRate ?? 0

  return (
    <div className={`card channel-card ${ch.enabled ? '' : 'disabled'}`}>
      <div className="channel-head">
        <div style={{ minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <h3 className="channel-name" title={ch.id}>
              {ch.id}
            </h3>
            <span className="score-pill" title="实时调度评分">
              {ch.currentScore.toFixed(1)}
            </span>
          </div>
          <div className="channel-models" title={ch.models}>
            {ch.models || '未配置模型'}
          </div>
          <div style={{ display: 'flex', gap: 6, marginTop: 8, alignItems: 'center' }}>
            <span className="badge">{String(ch.type).toUpperCase()}</span>
            <span className="status-text" style={{ fontSize: 12, color: 'var(--text-4)' }}>
              <span className={`conn-dot ${ch.enabled ? 'on' : 'off'}`} />
              {ch.enabled ? '已启用' : '已停用'}
            </span>
          </div>
        </div>
      </div>

      {/* 分组一：与路由决策同源的实时/近期指标 */}
      <div className="metric-group">
        <div className="metric-group-label">
          <span className="live-dot" />
          实时调度指标
        </div>
        <div className="metric-grid">
          <div className="metric-cell wide">
            <div
              className="metric-label"
              title="该渠道所有模型成功尝试的 EWMA（近因加权），仅作诊断；路由打分用的是 (渠道, 模型) 级延迟"
            >
              渠道平均延迟
            </div>
            <div className="metric-num">
              {ch.latencySamples > 0 ? ch.avgModelLatencyMs : '—'}
              {ch.latencySamples > 0 && <span className="unit">ms</span>}
            </div>
          </div>
          <div className="metric-cell">
            <div
              className="metric-label"
              title="进程启动以来重新统计，重启即清零；请求侧错误（如 4xx）不计入"
            >
              近期失败率
            </div>
            <div className={`metric-num ${failureRate > 0.05 ? 'bad' : failureRate > 0 ? 'warn' : ''}`}>
              {(failureRate * 100).toFixed(1)}
              <span className="unit">%</span>
            </div>
          </div>
          <div className="metric-cell">
            <div className="metric-label" title="此刻正在处理的上游请求数">
              当前并发
            </div>
            <div className="metric-num">
              <span className={ch.currentConcurrentCalls > 0 ? 'active' : ''}>
                {ch.currentConcurrentCalls}
              </span>
              <span className="unit">路</span>
            </div>
          </div>
        </div>
      </div>

      {/* 分组二：从数据库恢复并与实时增量相加的累计值 */}
      <div className="metric-group">
        <div className="metric-group-label">累计用量</div>
        <div className="metric-grid">
          <div className="metric-cell wide">
            <div className="metric-label" title="从 request_log 全量恢复 + 实时累加">
              Token 消耗
            </div>
            <div className="token-bar">
              <div
                style={{
                  width: `${getRatio(ch.promptTokensUsed, ch.totalTokensUsed)}%`,
                  background: 'var(--accent)',
                }}
                title={`输入 ${formatNumber(ch.promptTokensUsed)}`}
              />
              <div
                style={{
                  width: `${getRatio(ch.completionTokensUsed, ch.totalTokensUsed)}%`,
                  background: 'var(--accent-soft)',
                }}
                title={`输出 ${formatNumber(ch.completionTokensUsed)}`}
              />
            </div>
            <div className="token-legend">
              <span>输入 {formatNumber(ch.promptTokensUsed)}</span>
              <span>输出 {formatNumber(ch.completionTokensUsed)}</span>
              <span>合计 {formatNumber(ch.totalTokensUsed)}</span>
            </div>
          </div>
          <div className="metric-cell">
            <div className="metric-label" title="从 model_call_log 全量恢复 + 实时累加，含失败尝试">
              总调用
            </div>
            <div className="metric-num">{formatNumber(ch.totalCalls)}</div>
          </div>
          <div className="metric-cell">
            <div className="metric-label" title="从 model_call_log 全量恢复的错误总数，含重启前">
              错误次数
            </div>
            <div className={`metric-num ${ch.errorCount > 0 ? 'bad' : ''}`}>
              {formatNumber(ch.errorCount)}
            </div>
          </div>
        </div>
      </div>

      {/* 分组三：按模型明细。两级表头把「近期 / 累计」直接编码进表格结构 */}
      {modelStats.length > 0 && (
        <div className="metric-group">
          <div className="model-table">
            <span />
            <span className="grp" style={{ gridColumn: '2 / span 2' }}>
              近期
            </span>
            <span className="grp" style={{ gridColumn: '4 / span 2' }}>
              累计
            </span>
            <span className="head name">模型</span>
            <span className="head">延迟</span>
            <span className="head">首包</span>
            <span className="head">调用</span>
            <span className="head">Token</span>
            {modelStats.map((m) => (
              <Fragment key={m.model}>
                <span className="name" title={m.model}>
                  {m.model}
                </span>
                <span className="num">{m.latencySamples > 0 ? `${m.latencyEwmaMs}ms` : '—'}</span>
                <span className="num">{m.ttftEwmaMs == null ? '—' : `${m.ttftEwmaMs}ms`}</span>
                <span className="num">{formatNumber(m.calls)}</span>
                <span className="num">{formatNumber(m.totalTokens)}</span>
              </Fragment>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
