import { useState } from 'react'
import { api, setToken } from '../api.js'
import { IconLock } from '../components/icons.jsx'

export default function Login({ onLogin }) {
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e) => {
    e.preventDefault()
    if (!password || submitting) return
    setSubmitting(true)
    setError('')
    try {
      const res = await api('/api/admin/login', { method: 'POST', body: { password } })
      if (res.success) {
        setToken(res.token)
        onLogin()
      } else {
        setError(res.message || '登录失败')
      }
    } catch {
      setError('无法连接服务器，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="login-screen">
      <div className="login-card">
        <div className="login-brand">
          <div className="brand-mark">
            <IconLock style={{ width: 18, height: 18 }} />
          </div>
          <h1 className="login-title">OpenRouter 控制台</h1>
          <p className="login-sub">企业级 LLM 网关 · 管理员登录</p>
        </div>
        <form className="login-form" onSubmit={handleSubmit}>
          <div>
            <label className="field-label" htmlFor="admin-password">
              管理密码
            </label>
            <input
              id="admin-password"
              type="password"
              className="input input-mono"
              placeholder="请输入管理密码"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoFocus
              required
            />
          </div>
          <button type="submit" className="btn btn-primary" disabled={submitting} style={{ width: '100%' }}>
            {submitting ? '验证中…' : '进入控制台'}
          </button>
          {error && <p className="login-error">{error}</p>}
        </form>
      </div>
    </div>
  )
}
