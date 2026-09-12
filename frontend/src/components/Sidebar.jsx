import { useEffect, useState } from 'react'
import { logStore, subscribeLogStore } from '../api.js'
import {
  IconActivity,
  IconChevron,
  IconGauge,
  IconLock,
  IconLogout,
  IconMenu,
  IconMoon,
  IconSliders,
  IconSun,
  IconTerminal,
} from './icons.jsx'

const NAV_GROUPS = [
  {
    label: '监控',
    items: [
      { key: 'dashboard', label: '仪表盘', icon: IconGauge },
      { key: 'request-logs', label: '日志管理', icon: IconActivity },
      { key: 'logs', label: '实时日志', icon: IconTerminal },
    ],
  },
]

const SETTINGS_GROUP = {
  label: '系统配置',
  icon: IconSliders,
  children: [
    { key: 'basic', label: '基础设置', icon: IconLock },
    { key: 'channels', label: '渠道与模型', icon: IconSliders },
  ],
}

const SETTINGS_KEYS = SETTINGS_GROUP.children.map((c) => c.key)

function useLogStatus() {
  // 通过版本号订阅，读取实时连接状态
  const [, setTick] = useState(0)
  useEffect(() => subscribeLogStore(() => setTick((t) => t + 1)), [])
  return logStore.status
}

export default function Sidebar({ route, theme, onToggleTheme, onNavigate, onLogout }) {
  const wsStatus = useLogStatus()
  const connected = wsStatus === 'open'

  // 当前处于子页面时自动展开，否则维持用户手动开合状态
  const [settingsOpen, setSettingsOpen] = useState(() => SETTINGS_KEYS.includes(route))
  useEffect(() => {
    if (SETTINGS_KEYS.includes(route)) setSettingsOpen(true)
  }, [route])

  // 移动端抽屉开关：导航、切换主题或退出登录后自动收起
  const [drawerOpen, setDrawerOpen] = useState(false)
  const closeDrawer = () => setDrawerOpen(false)
  const handleNavigate = (key) => {
    onNavigate(key)
    closeDrawer()
  }

  return (
    <>
      <div className="mobile-topbar">
        <button
          type="button"
          className="icon-btn"
          onClick={() => setDrawerOpen(true)}
          aria-label="打开菜单"
        >
          <IconMenu />
        </button>
        <div className="brand-mark" style={{ width: 22, height: 22, borderRadius: 6 }}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden>
            <path d="M13 2 4 14h7v8l9-12h-7V2Z" fill="currentColor" />
          </svg>
        </div>
        <span className="mobile-brand">OpenRouter</span>
      </div>

      {drawerOpen && <div className="sidebar-backdrop" onClick={closeDrawer} aria-hidden />}

      <aside className={`sidebar ${drawerOpen ? 'open' : ''}`}>
      <div className="sidebar-brand">
        <div className="brand-mark">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
            <path
              d="M13 2 4 14h7v8l9-12h-7V2Z"
              fill="currentColor"
            />
          </svg>
        </div>
        <div>
          <div className="brand-name">OpenRouter</div>
          <div className="brand-sub">AI 智能路由</div>
        </div>
      </div>

      <nav className="sidebar-nav">
        {NAV_GROUPS.map((group) => (
          <div key={group.label}>
            <div className="nav-group-label">{group.label}</div>
            {group.items.map((item) => (
              <button
                key={item.key}
                type="button"
                className={`nav-item ${route === item.key ? 'active' : ''}`}
                onClick={() => handleNavigate(item.key)}
              >
                <item.icon />
                <span>{item.label}</span>
              </button>
            ))}
          </div>
        ))}

        <div>
          <div className="nav-group-label">管理</div>
          <button
            type="button"
            className={`nav-item nav-toggle ${SETTINGS_KEYS.includes(route) ? 'active' : ''}`}
            onClick={() => setSettingsOpen((open) => !open)}
            aria-expanded={settingsOpen}
          >
            <SETTINGS_GROUP.icon />
            <span>{SETTINGS_GROUP.label}</span>
            <IconChevron className={`nav-chevron ${settingsOpen ? 'open' : ''}`} />
          </button>
          {settingsOpen &&
            SETTINGS_GROUP.children.map((item) => (
              <button
                key={item.key}
                type="button"
                className={`nav-item nav-sub ${route === item.key ? 'active' : ''}`}
                onClick={() => handleNavigate(item.key)}
              >
                <item.icon />
                <span>{item.label}</span>
              </button>
            ))}
        </div>
      </nav>

      <div className="sidebar-foot">
        <div className="conn-state">
          <span className={`conn-dot ${connected ? 'on' : 'off'}`} />
          <span>{connected ? '日志流已连接' : '日志流未连接'}</span>
        </div>
        <button type="button" className="logout-btn" onClick={() => { onToggleTheme(); closeDrawer() }}>
          {theme === 'dark' ? <IconSun /> : <IconMoon />}
          <span>{theme === 'dark' ? '切换为日间模式' : '切换为夜间模式'}</span>
        </button>
        <button type="button" className="logout-btn" onClick={() => { onLogout(); closeDrawer() }}>
          <IconLogout />
          <span>退出登录</span>
        </button>
      </div>
    </aside>
    </>
  )
}
