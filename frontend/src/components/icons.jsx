// 16px 线性图标（stroke 1.6，与 Linear 的细线风格一致）
const base = {
  width: 16,
  height: 16,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
}

export const IconGauge = (props) => (
  <svg {...base} {...props}>
    <path d="M12 4a9 9 0 0 1 9 9 9 9 0 0 1-.6 3.2c-.3.8-1 1.3-1.9 1.3H5.5c-.9 0-1.6-.5-1.9-1.3A9 9 0 0 1 3 13a9 9 0 0 1 9-9Z" />
    <path d="M12 13l3.5-3.5" />
    <circle cx="12" cy="13" r="1" fill="currentColor" stroke="none" />
  </svg>
)

export const IconTerminal = (props) => (
  <svg {...base} {...props}>
    <path d="M5 20h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2Z" />
    <path d="m7 9 3 3-3 3M13 15h4" />
  </svg>
)

export const IconSliders = (props) => (
  <svg {...base} {...props}>
    <path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3" />
    <path d="M1 14h6M9 8h6M17 16h6" />
  </svg>
)

export const IconLogout = (props) => (
  <svg {...base} {...props}>
    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
  </svg>
)

export const IconZap = (props) => (
  <svg {...base} {...props}>
    <path d="M13 2 4 14h7v8l9-12h-7V2Z" />
  </svg>
)

export const IconClock = (props) => (
  <svg {...base} {...props}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 3" />
  </svg>
)

export const IconCoins = (props) => (
  <svg {...base} {...props}>
    <ellipse cx="12" cy="6" rx="8" ry="3" />
    <path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6" />
    <path d="M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
  </svg>
)

export const IconActivity = (props) => (
  <svg {...base} {...props}>
    <path d="M22 12h-4l-3 8-6-16-3 8H2" />
  </svg>
)

export const IconLock = (props) => (
  <svg {...base} {...props}>
    <rect x="4" y="11" width="16" height="10" rx="2" />
    <path d="M8 11V7a4 4 0 0 1 8 0v4" />
  </svg>
)

export const IconSun = (props) => (
  <svg {...base} {...props}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </svg>
)

export const IconMoon = (props) => (
  <svg {...base} {...props}>
    <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
  </svg>
)

export const IconPlus = (props) => (
  <svg {...base} {...props}>
    <path d="M12 5v14M5 12h14" />
  </svg>
)

export const IconTrash = (props) => (
  <svg {...base} {...props}>
    <path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
    <path d="M10 11v6M14 11v6" />
  </svg>
)

export const IconChevron = (props) => (
  <svg {...base} {...props}>
    <path d="m6 9 6 6 6-6" />
  </svg>
)

export const IconMenu = (props) => (
  <svg {...base} {...props}>
    <path d="M4 6h16M4 12h16M4 18h16" />
  </svg>
)
