import { formatNumber, getRatio } from '../format.js'

export default function ChannelCard({ channel }) {
  const ch = channel
  const tokensByModel = ch.tokensByModel ?? {}

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

      <div className="metric-grid">
        <div className="metric-cell wide">
          <div className="metric-label">模型平均延迟</div>
          <div className="metric-num">
            {ch.avgModelLatencyMs}
            <span className="unit">ms</span>
          </div>
        </div>
        <div className="metric-cell">
          <div className="metric-label">总调用</div>
          <div className="metric-num">{formatNumber(ch.totalCalls)}</div>
        </div>
        <div className="metric-cell">
          <div className="metric-label">错误次数</div>
          <div className={`metric-num ${ch.errorCount > 0 ? 'bad' : ''}`}>
            {formatNumber(ch.errorCount)}
          </div>
        </div>
        <div className="metric-cell wide">
          <div className="metric-label">当前并发</div>
          <div className="metric-num">
            <span className={ch.currentConcurrentCalls > 0 ? 'active' : ''}>
              {ch.currentConcurrentCalls}
            </span>
            <span className="unit">路请求处理中</span>
          </div>
        </div>
        <div className="metric-cell wide">
          <div className="metric-label">Token 消耗</div>
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
      </div>

      {Object.keys(tokensByModel).length > 0 && (
        <div className="model-tokens">
          {Object.entries(tokensByModel).map(([model, pair]) => (
            <div className="model-token-row" key={model}>
              <span className="name" title={model}>
                {model}
              </span>
              <span className="val">
                {formatNumber(pair.p)} / {formatNumber(pair.c)} · Σ {formatNumber(pair.t)}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
