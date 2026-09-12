import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'

// 与后端 RoutingConfig 的默认值保持一致
const ROUTING_DEFAULTS = {
  healthWeight: 0.5,
  latencyWeight: 0.3,
  loadWeight: 0.2,
  latencyReferenceMs: 1500,
  concurrencyCapacity: 8,
  ewmaAlpha: 0.1,
  errorDecaySeconds: 60,
  errorDecayFactor: 0.5,
  coldStartLatencyRatio: 0.5,
  minLatencySamples: 5,
  explorationRate: 0,
}

function NumField({ label, hint, value, onChange, step = 'any' }) {
  return (
    <div>
      <label className="field-label">{label}</label>
      <input
        className="input input-mono"
        type="number"
        step={step}
        value={value}
        onChange={(e) => {
          const n = Number(e.target.value)
          onChange(Number.isFinite(n) ? n : 0)
        }}
      />
      {hint && (
        <div className="dim" style={{ fontSize: 12, color: 'var(--text-4)', marginTop: 6 }}>
          {hint}
        </div>
      )}
    </div>
  )
}

export default function RoutingParams({ onUnauthorized }) {
  const [form, setForm] = useState(null)
  const [savedJson, setSavedJson] = useState('')
  const [saving, setSaving] = useState(false)
  const [status, setStatus] = useState(null)

  const load = useCallback(async () => {
    try {
      const res = await api('/api/admin/basic-settings')
      const normalized = { ...ROUTING_DEFAULTS, ...(res.routing ?? {}) }
      setForm(normalized)
      setSavedJson(JSON.stringify(normalized))
      setStatus(null)
    } catch (err) {
      if (err.status === 401) onUnauthorized?.()
      else setStatus({ type: 'err', text: `加载路由参数失败: ${err.message}` })
    }
  }, [onUnauthorized])

  useEffect(() => {
    load()
  }, [load])

  const update = (patch) => setForm((prev) => ({ ...prev, ...patch }))

  const save = async () => {
    setSaving(true)
    setStatus(null)
    try {
      // 只提交 routing 段，其余基础设置由后端沿用当前生效值
      const res = await api('/api/admin/basic-settings', { method: 'PUT', body: { routing: form } })
      if (res?.success) {
        await load()
        setStatus({ type: 'ok', text: '路由参数已保存并即时生效' })
      } else {
        setStatus({ type: 'err', text: res?.message || '保存失败' })
      }
    } catch (err) {
      if (err.status === 401) onUnauthorized?.()
      else setStatus({ type: 'err', text: err.message })
    } finally {
      setSaving(false)
    }
  }

  if (!form) {
    return (
      <>
        <div className="page-head">
          <div>
            <h1 className="page-title">路由调参</h1>
            <p className="page-desc">调度打分的权重与阈值，保存后写入配置文件并即时生效。</p>
          </div>
        </div>
        <div className="page-body">
          <div className="card empty-state">{status ? status.text : '正在加载路由参数…'}</div>
        </div>
      </>
    )
  }

  const dirty = JSON.stringify(form) !== savedJson
  const weightSum = Number(form.healthWeight) + Number(form.latencyWeight) + Number(form.loadWeight)

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">路由调参</h1>
          <p className="page-desc">
            调度打分的权重与阈值，保存后写入配置文件并即时生效。指标口径与调参示例见
            <code style={{ fontFamily: 'var(--font-mono)' }}> docs/routing-score.md</code>。
          </p>
        </div>
        <div className="cfg-actions">
          {dirty && <span className="cfg-dirty">有未保存的更改</span>}
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => update({ ...ROUTING_DEFAULTS })}
            disabled={saving}
          >
            恢复默认值
          </button>
          <button type="button" className="btn btn-ghost" onClick={load} disabled={saving || !dirty}>
            放弃更改
          </button>
          <button type="button" className="btn btn-primary" onClick={save} disabled={saving || !dirty}>
            {saving ? '保存中…' : '保存更改'}
          </button>
        </div>
      </div>

      <div className="page-body">
        {status && <div className={`cfg-status ${status.type === 'ok' ? 'ok' : 'err'}`}>{status.text}</div>}

        <div className="section-head" style={{ marginTop: 4 }}>
          <h2 className="section-title">打分公式</h2>
          <span className="section-hint">
            得分 = 基础权重 × (1 − 健康度×w₁ − 延迟×w₂ − 负载×w₃)；三个权重之和当前为{' '}
            {Number.isFinite(weightSum) ? weightSum.toFixed(2) : '—'}（需 ≤ 1）
          </span>
        </div>

        <div className="card cfg-card">
          <div className="cfg-grid">
            <NumField
              label="健康度权重"
              hint="近期失败率对得分的最大扣减比例（0~1）"
              value={form.healthWeight}
              onChange={(v) => update({ healthWeight: v })}
            />
            <NumField
              label="延迟权重"
              hint="延迟子分的最大扣减比例（0~1）"
              value={form.latencyWeight}
              onChange={(v) => update({ latencyWeight: v })}
            />
            <NumField
              label="负载权重"
              hint="并发子分的最大扣减比例（0~1）"
              value={form.loadWeight}
              onChange={(v) => update({ loadWeight: v })}
            />
          </div>
        </div>

        <div className="section-head">
          <h2 className="section-title">子分参考值</h2>
          <span className="section-hint">子分采用饱和函数，参考值处约为 0.5</span>
        </div>

        <div className="card cfg-card">
          <div className="cfg-grid">
            <NumField
              label="延迟参考值（ms）"
              hint="延迟达到该值时延迟子分约为 0.5，越小对延迟越敏感"
              step={100}
              value={form.latencyReferenceMs}
              onChange={(v) => update({ latencyReferenceMs: v })}
            />
            <NumField
              label="并发容量参考值"
              hint="并发达到该值时负载子分约为 0.5"
              step={1}
              value={form.concurrencyCapacity}
              onChange={(v) => update({ concurrencyCapacity: v })}
            />
            <NumField
              label="最小延迟样本数"
              hint="样本不足时按冷启动处理，默认 5"
              step={1}
              value={form.minLatencySamples}
              onChange={(v) => update({ minLatencySamples: v })}
            />
            <NumField
              label="冷启动延迟子分"
              hint="[0,1]：无延迟样本时使用，默认 0.5；设为 0 会让新渠道天然最优"
              value={form.coldStartLatencyRatio}
              onChange={(v) => update({ coldStartLatencyRatio: v })}
            />
          </div>
        </div>

        <div className="section-head">
          <h2 className="section-title">样本与衰减</h2>
          <span className="section-hint">影响指标对新数据的敏感程度</span>
        </div>

        <div className="card cfg-card">
          <div className="cfg-grid">
            <NumField
              label="延迟平滑系数 α"
              hint="(0,1]：越小越平滑，越不受单次抖动影响，默认 0.1"
              value={form.ewmaAlpha}
              onChange={(v) => update({ ewmaAlpha: v })}
            />
            <NumField
              label="失败率半衰期（秒）"
              hint="渠道闲置该时长后近期失败率减半，默认 60"
              step={10}
              value={form.errorDecaySeconds}
              onChange={(v) => update({ errorDecaySeconds: v })}
            />
            <NumField
              label="失败率衰减系数"
              hint="(0,1)：每发生一次调用，历史失败率乘该系数，默认 0.5"
              value={form.errorDecayFactor}
              onChange={(v) => update({ errorDecayFactor: v })}
            />
            <NumField
              label="探索概率"
              hint="[0,0.5]：以前两名随机打破流量锁定，0 表示关闭"
              value={form.explorationRate}
              onChange={(v) => update({ explorationRate: v })}
            />
          </div>
        </div>

        <div className="card stat-card" style={{ lineHeight: 1.8 }}>
          <p style={{ margin: 0, color: 'var(--text-3)', fontSize: 13 }}>
            交互提醒：延迟与失败率的惩罚是实时指标，改大权重会让"最近的快慢/故障"更快影响选路；渠道之间的用量偏好请用「渠道与模型」里的
            <strong> 基础权重 </strong>
            表达。参数非法（如三个权重之和大于 1、参考值为 0）时保存会被拒绝，磁盘与内存都不会被改动。
          </p>
        </div>
      </div>
    </>
  )
}
