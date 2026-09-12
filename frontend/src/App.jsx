import { useCallback, useEffect, useRef, useState } from 'react'
import Sidebar from './components/Sidebar.jsx'
import Login from './pages/Login.jsx'
import Dashboard from './pages/Dashboard.jsx'
import Logs from './pages/Logs.jsx'
import RequestLogs from './pages/RequestLogs.jsx'
import Settings from './pages/Settings.jsx'
import BasicSettings from './pages/BasicSettings.jsx'
import RoutingParams from './pages/RoutingParams.jsx'
import { api, clearToken, getToken, connectLogStream, disconnectLogStream } from './api.js'

const EMPTY_STATS = {
  totalChannels: 0,
  activeChannels: 0,
  globalTotalTokens: 0,
  globalPromptTokens: 0,
  globalCompletionTokens: 0,
  globalTotalRequests: 0,
  globalFailedRequests: 0,
  avgResponseTime: 0,
}

const THEME_KEY = 'theme'

// 首次访问跟随系统偏好，之后以用户手动选择为准
function initialTheme() {
  const saved = localStorage.getItem(THEME_KEY)
  if (saved === 'light' || saved === 'dark') return saved
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

// 极简 hash 路由
function useHashRoute() {
  const [route, setRoute] = useState(() => window.location.hash.replace(/^#\/?/, '') || 'dashboard')
  useEffect(() => {
    const onChange = () => setRoute(window.location.hash.replace(/^#\/?/, '') || 'dashboard')
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  const navigate = useCallback((r) => {
    window.location.hash = `#/${r}`
  }, [])
  return [route, navigate]
}

export default function App() {
  const [isLoggedIn, setIsLoggedIn] = useState(() => Boolean(getToken()))
  const [route, navigate] = useHashRoute()
  const [theme, setTheme] = useState(initialTheme)
  const [stats, setStats] = useState(EMPTY_STATS)
  const [channels, setChannels] = useState([])
  const [routing, setRouting] = useState(null)
  const timerRef = useRef(null)

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    document.documentElement.style.colorScheme = theme
    localStorage.setItem(THEME_KEY, theme)
  }, [theme])

  const toggleTheme = useCallback(
    () => setTheme((t) => (t === 'dark' ? 'light' : 'dark')),
    [],
  )

  const handleUnauthorized = useCallback(() => {
    clearToken()
    disconnectLogStream()
    setIsLoggedIn(false)
  }, [])

  const fetchDashboard = useCallback(async () => {
    try {
      const res = await api('/api/admin/dashboard')
      setStats(res.globalStats ?? EMPTY_STATS)
      setChannels(res.channels ?? [])
      setRouting(res.routing ?? null)
    } catch (err) {
      if (err.status === 401) handleUnauthorized()
    }
  }, [handleUnauthorized])

  const startPolling = useCallback(() => {
    clearInterval(timerRef.current)
    timerRef.current = setInterval(fetchDashboard, 2000)
  }, [fetchDashboard])

  // 登录成功 / 已持令牌：开始轮询并接入日志流
  const activateSession = useCallback(() => {
    setIsLoggedIn(true)
    fetchDashboard()
    startPolling()
    connectLogStream()
  }, [fetchDashboard, startPolling])

  useEffect(() => {
    if (!isLoggedIn) return
    // fetchDashboard 为异步函数，状态更新发生在 await 之后，不会引发级联渲染
    // oxlint-disable-next-line react/set-state-in-effect
    fetchDashboard()
    startPolling()
    connectLogStream()
    return () => clearInterval(timerRef.current)
  }, [isLoggedIn, fetchDashboard, startPolling])

  const handleLogout = useCallback(() => {
    clearInterval(timerRef.current)
    clearToken()
    disconnectLogStream()
    setChannels([])
    setStats(EMPTY_STATS)
    setRouting(null)
    setIsLoggedIn(false)
  }, [])

  if (!isLoggedIn) {
    return <Login onLogin={activateSession} />
  }

  return (
    <div className="app-shell">
      <Sidebar route={route} theme={theme} onToggleTheme={toggleTheme} onNavigate={navigate} onLogout={handleLogout} />
      <main className="main-area">
        {route === 'logs' ? (
          <Logs />
        ) : route === 'request-logs' ? (
          <RequestLogs onUnauthorized={handleUnauthorized} />
        ) : route === 'basic' ? (
          <BasicSettings onUnauthorized={handleUnauthorized} />
        ) : route === 'routing' ? (
          <RoutingParams onUnauthorized={handleUnauthorized} />
        ) : route === 'channels' ? (
          <Settings onUnauthorized={handleUnauthorized} />
        ) : (
          <Dashboard stats={stats} channels={channels} routing={routing} />
        )}
      </main>
    </div>
  )
}
