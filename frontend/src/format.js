export function formatNumber(num) {
  if (num == null) return '0'
  if (num >= 1_000_000) return (num / 1_000_000).toFixed(2) + 'M'
  if (num >= 1_000) return (num / 1_000).toFixed(1) + 'K'
  return new Intl.NumberFormat('zh-CN').format(num)
}

export function getRatio(val, total) {
  if (!total) return 0
  return (val / total) * 100
}
