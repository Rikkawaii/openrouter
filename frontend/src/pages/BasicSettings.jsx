import { useCallback, useEffect, useState } from 'react'
import Toggle from '../components/Toggle.jsx'
import { api } from '../api.js'

// 掩码值：提交时原样带回，后端识别为"保持原值"
const MASK_HINT = '已保存，留空或保持掩码则不变'

export default function BasicSettings({ onUnauthorized }) {
  const [form, setForm] = useState(null)
  const [savedJson, setSavedJson] = useState('')
  const [saving, setSaving] = useState(false)
  const [status, setStatus] = useState(null)

  const load = useCallback(async () => {
    try {
      const res = await api('/api/admin/basic-settings')
      const normalized = {
        apiKeyEnabled: Boolean(res.apiKeyEnabled),
        apiKey: res.apiKey ?? '',
        adminPassword: res.adminPassword ?? '',
        mentorModel: res.mentorModel ?? '',
      }
      setForm(normalized)
      setSavedJson(JSON.stringify(normalized))
      setStatus(null)
    } catch (err) {
      if (err.status === 401) onUnauthorized?.()
      else setStatus({ type: 'err', text: `加载设置失败: ${err.message}` })
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
      const res = await api('/api/admin/basic-settings', { method: 'PUT', body: form })
      if (res?.success) {
        await load()
        setStatus({ type: 'ok', text: '已保存并即时生效' })
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
            <h1 className="page-title">基础设置</h1>
            <p className="page-desc">网关鉴权密钥与管理页登录密码，保存后写入配置文件并即时生效。</p>
          </div>
        </div>
        <div className="page-body">
          <div className="card empty-state">{status ? status.text : '正在加载设置…'}</div>
        </div>
      </>
    )
  }

  const dirty = JSON.stringify(form) !== savedJson

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">基础设置</h1>
          <p className="page-desc">网关鉴权密钥与管理页登录密码，保存后写入配置文件并即时生效。</p>
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

        <div className="section-head" style={{ marginTop: 4 }}>
          <h2 className="section-title">网关鉴权</h2>
          <span className="section-hint">保护 /v1/** 模型接口，防止密钥泄露后被恶意调用</span>
        </div>

        <div className="card cfg-card" style={{ maxWidth: 560 }}>
          <div className="cfg-row-between">
            <div>
              <div className="field-label" style={{ marginBottom: 2 }}>
                启用 API Key 鉴权
              </div>
              <div className="dim" style={{ fontSize: 12, color: 'var(--text-4)' }}>
                关闭后网关公开访问，无需密钥即可调用模型接口
              </div>
            </div>
            <Toggle
              checked={form.apiKeyEnabled}
              onChange={(v) => update({ apiKeyEnabled: v })}
              label="启用网关 API Key 鉴权"
            />
          </div>

          {form.apiKeyEnabled && (
            <>
              <label className="field-label" style={{ marginTop: 14 }}>
                API Key
              </label>
              <input
                className="input input-mono"
                type="password"
                value={form.apiKey}
                placeholder={MASK_HINT}
                onChange={(e) => update({ apiKey: e.target.value })}
                autoComplete="off"
              />
              <div className="dim" style={{ fontSize: 12, color: 'var(--text-4)', marginTop: 6 }}>
                调用时通过请求头传入：<code style={{ fontFamily: 'var(--font-mono)' }}>Authorization: Bearer &lt;你的 Key&gt;</code>
              </div>
            </>
          )}
        </div>

        <div className="section-head">
          <h2 className="section-title">管理页登录</h2>
        </div>

        <div className="card cfg-card" style={{ maxWidth: 560 }}>
          <label className="field-label">登录密码</label>
          <input
            className="input input-mono"
            type="password"
            value={form.adminPassword}
            placeholder={MASK_HINT}
            onChange={(e) => update({ adminPassword: e.target.value })}
            autoComplete="new-password"
          />
          <div className="dim" style={{ fontSize: 12, color: 'var(--text-4)', marginTop: 6 }}>
            修改保存后，已登录的会话会立即失效，需要用新密码重新登录。
          </div>
        </div>
      </div>
    </>
  )
}
