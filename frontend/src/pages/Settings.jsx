import { useCallback, useEffect, useState } from 'react'
import Toggle from '../components/Toggle.jsx'
import { api } from '../api.js'
import { IconPlus, IconTrash } from '../components/icons.jsx'

const CHANNEL_TYPES = ['openai', 'gemini']

const CAPABILITIES = [
  { key: 'vision', label: '视觉识别' },
  { key: 'functionCalling', label: '工具调用' },
  { key: 'longContext', label: '长上下文' },
]

const emptyChannel = () => ({
  id: '',
  type: 'openai',
  baseUrl: '',
  apiKey: '',
  baseWeight: 100,
  enabled: true,
})

const emptyModel = (channelIds) => ({
  name: '',
  channels: channelIds,
  capabilities: { vision: false, functionCalling: false, longContext: false },
})

export default function Settings({ onUnauthorized }) {
  const [cfg, setCfg] = useState(null)
  const [savedJson, setSavedJson] = useState('')
  const [saving, setSaving] = useState(false)
  const [status, setStatus] = useState(null)

  const load = useCallback(async () => {
    try {
      const res = await api('/api/admin/channels-config')
      const normalized = {
        settings: {
          apiKeyEnabled: Boolean(res.settings?.apiKeyEnabled),
          apiKey: res.settings?.apiKey ?? '',
          adminPassword: res.settings?.adminPassword ?? '',
          mentorModel: res.settings?.mentorModel ?? '',
        },
        channels: res.channels ?? [],
        models: (res.models ?? []).map((m) => ({
          name: m.name ?? '',
          channels: m.channels ?? [],
          capabilities: {
            vision: false,
            functionCalling: false,
            longContext: false,
            ...(m.capabilities ?? {}),
          },
        })),
      }
      setCfg(normalized)
      setSavedJson(JSON.stringify(normalized))
      setStatus(null)
    } catch (err) {
      if (err.status === 401) onUnauthorized?.()
      else setStatus({ type: 'err', text: `加载配置失败: ${err.message}` })
    }
  }, [onUnauthorized])

  useEffect(() => {
    load()
  }, [load])

  if (!cfg) {
    return (
      <>
        <div className="page-head">
          <div>
            <h1 className="page-title">系统配置</h1>
            <p className="page-desc">编辑渠道、模型声明与调度策略，保存后写入配置文件并立即生效，无需重启。</p>
          </div>
        </div>
        <div className="page-body">
          <div className="card empty-state">{status ? status.text : '正在加载配置…'}</div>
        </div>
      </>
    )
  }

  const dirty = JSON.stringify(cfg) !== savedJson
  const channelIds = cfg.channels.map((c) => c.id).filter(Boolean)

  const mutate = (fn) => setCfg((prev) => {
    const next = JSON.parse(JSON.stringify(prev))
    fn(next)
    return next
  })

  const updateChannel = (idx, patch) =>
    mutate((next) => {
      const ch = next.channels[idx]
      const oldId = ch.id
      Object.assign(ch, patch)
      // 渠道改名时同步更新模型里的引用
      if (patch.id !== undefined && patch.id !== oldId) {
        next.models.forEach((m) => {
          m.channels = m.channels.map((cid) => (cid === oldId ? patch.id : cid))
        })
      }
    })

  const removeChannel = (idx) =>
    mutate((next) => {
      const [removed] = next.channels.splice(idx, 1)
      next.models.forEach((m) => {
        m.channels = m.channels.filter((cid) => cid !== removed.id)
      })
    })

  const updateModel = (idx, patch) =>
    mutate((next) => {
      Object.assign(next.models[idx], patch)
    })

  const removeModel = (idx) =>
    mutate((next) => {
      next.models.splice(idx, 1)
    })

  const toggleModelChannel = (idx, cid) =>
    mutate((next) => {
      const list = next.models[idx].channels
      const at = list.indexOf(cid)
      if (at >= 0) list.splice(at, 1)
      else list.push(cid)
    })

  const toggleCapability = (idx, key) =>
    mutate((next) => {
      next.models[idx].capabilities[key] = !next.models[idx].capabilities[key]
    })

  const addChannel = () => mutate((next) => next.channels.push(emptyChannel()))
  const addModel = () => mutate((next) => next.models.push(emptyModel(channelIds)))

  const setMentorModel = (v) => mutate((next) => { next.settings.mentorModel = v })

  const save = async () => {
    setSaving(true)
    setStatus(null)
    try {
      await api('/api/admin/channels-config', {
        method: 'PUT',
        body: {
          settings: cfg.settings,
          channels: cfg.channels,
          models: cfg.models,
        },
      })
      await load()
      setStatus({ type: 'ok', text: '已保存并即时生效' })
    } catch (err) {
      if (err.status === 401) onUnauthorized?.()
      else setStatus({ type: 'err', text: err.message })
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">系统配置</h1>
          <p className="page-desc">编辑渠道、模型声明与调度策略，保存后写入配置文件并立即生效，无需重启。</p>
        </div>
        <div className="cfg-actions">
          {dirty && <span className="cfg-dirty">有未保存的更改</span>}
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

        <div className="section-head">
          <h2 className="section-title">渠道</h2>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span className="section-hint">
              共 {cfg.channels.length} 个 · 启用 {cfg.channels.filter((c) => c.enabled).length} 个
            </span>
            <button type="button" className="btn btn-ghost" onClick={addChannel}>
              <IconPlus /> 添加渠道
            </button>
          </div>
        </div>

        {cfg.channels.length === 0 ? (
          <div className="card empty-state">暂无渠道，点击「添加渠道」接入第一个上游节点。</div>
        ) : (
          <div className="cfg-grid">
            {cfg.channels.map((ch, idx) => (
              <div className="card cfg-card" key={idx}>
                <div className="cfg-card-head">
                  <input
                    className="input input-mono cfg-id"
                    value={ch.id}
                    placeholder="渠道 ID"
                    onChange={(e) => updateChannel(idx, { id: e.target.value.trim() })}
                    aria-label="渠道 ID"
                  />
                  <select
                    className="input cfg-select"
                    value={ch.type}
                    onChange={(e) => updateChannel(idx, { type: e.target.value })}
                    aria-label="协议类型"
                  >
                    {CHANNEL_TYPES.map((t) => (
                      <option key={t} value={t}>
                        {t}
                      </option>
                    ))}
                  </select>
                  <Toggle
                    checked={ch.enabled}
                    onChange={(v) => updateChannel(idx, { enabled: v })}
                    label={`启用渠道 ${ch.id || idx + 1}`}
                  />
                  <button
                    type="button"
                    className="icon-btn danger"
                    onClick={() => removeChannel(idx)}
                    aria-label={`删除渠道 ${ch.id || idx + 1}`}
                    title="删除渠道"
                  >
                    <IconTrash />
                  </button>
                </div>

                <label className="field-label">Base URL</label>
                <input
                  className="input input-mono"
                  value={ch.baseUrl}
                  placeholder="https://api.example.com（结尾不带 /v1）"
                  onChange={(e) => updateChannel(idx, { baseUrl: e.target.value.trim() })}
                />

                <label className="field-label">API Key</label>
                <input
                  className="input input-mono"
                  type="password"
                  value={ch.apiKey}
                  placeholder={ch.apiKey ? '留空 / 掩码不变则保持原 Key' : 'sk-...'}
                  onChange={(e) => updateChannel(idx, { apiKey: e.target.value })}
                  autoComplete="off"
                />

                <label className="field-label">基础权重</label>
                <input
                  className="input"
                  type="number"
                  min="0"
                  max="500"
                  value={ch.baseWeight}
                  onChange={(e) => updateChannel(idx, { baseWeight: Number(e.target.value) })}
                />
              </div>
            ))}
          </div>
        )}

        <div className="section-head">
          <h2 className="section-title">模型</h2>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span className="section-hint">共 {cfg.models.length} 个</span>
            <button type="button" className="btn btn-ghost" onClick={addModel}>
              <IconPlus /> 添加模型
            </button>
          </div>
        </div>

        {cfg.models.length === 0 ? (
          <div className="card empty-state">暂无模型声明。模型通过渠道引用接入路由，能力未勾选时默认为 false。</div>
        ) : (
          <div className="cfg-grid">
            {cfg.models.map((m, idx) => (
              <div className="card cfg-card" key={idx}>
                <div className="cfg-card-head">
                  <input
                    className="input input-mono cfg-id"
                    value={m.name}
                    placeholder="模型名，如 deepseek-v4-pro"
                    onChange={(e) => updateModel(idx, { name: e.target.value.trim() })}
                    aria-label="模型名"
                  />
                  <button
                    type="button"
                    className="icon-btn danger"
                    onClick={() => removeModel(idx)}
                    aria-label={`删除模型 ${m.name || idx + 1}`}
                    title="删除模型"
                  >
                    <IconTrash />
                  </button>
                </div>

                <label className="field-label">归属渠道</label>
                {channelIds.length === 0 ? (
                  <div className="dim" style={{ fontSize: 12 }}>先添加渠道，再选择归属。</div>
                ) : (
                  <div className="chip-group">
                    {cfg.channels.map((c) => (
                      <button
                        key={c.id}
                        type="button"
                        className={`chip ${m.channels.includes(c.id) ? 'on' : ''}`}
                        onClick={() => toggleModelChannel(idx, c.id)}
                      >
                        {c.id || '(未命名)'}
                      </button>
                    ))}
                  </div>
                )}

                <label className="field-label">能力声明</label>
                <div className="chip-group">
                  {CAPABILITIES.map((cap) => (
                    <button
                      key={cap.key}
                      type="button"
                      className={`chip ${m.capabilities[cap.key] ? 'on' : ''}`}
                      onClick={() => toggleCapability(idx, cap.key)}
                    >
                      {cap.label}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
        <div className="section-head">
          <h2 className="section-title">调度策略</h2>
        </div>

        <div className="card cfg-card" style={{ maxWidth: 560 }}>
          <label className="field-label">导师模型</label>
          <select
              className="input"
              value={cfg.settings.mentorModel}
              onChange={(e) => setMentorModel(e.target.value)}
              aria-label="导师模型"
          >
            <option value="">关闭（不强制指派）</option>
            {cfg.models.map((m) => (
                <option key={m.name} value={m.name}>
                  {m.name}
                </option>
            ))}
          </select>
          <div className="dim" style={{ fontSize: 12, color: 'var(--text-4)', marginTop: 6 }}>
            当请求没有任何上下文（全新一句话）时，强制优先指派给该模型。候选来自下方声明的模型列表。
          </div>
        </div>
        <div className="section-head">
          <h2 className="section-title">调度说明</h2>
        </div>
        <div className="card stat-card" style={{ lineHeight: 1.8 }}>
          <p style={{ margin: 0, color: 'var(--text-3)', fontSize: 13 }}>
            网关按「实时评分」为每次请求选择渠道：评分 = 基础权重 ×（1 − 健康度权重×近期失败率 −
            延迟权重×延迟子分 − 负载权重×并发子分）。三项子分均已归一化，得分越高越优先；权重与参考值可在「系统配置 →
            路由调参」中热更新。请求会优先收敛到「归属渠道中已启用」的节点；勾选能力后，不具备该能力的渠道会在打分前被过滤。保存后配置写入
            channels.json 并热生效，重启不丢失。
          </p>
        </div>
      </div>
    </>
  )
}
