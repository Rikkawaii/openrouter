export default function StatCard({ icon: Icon, label, value, unit, suffix, children }) {
  return (
    <div className="card stat-card">
      <div className="stat-label">
        {Icon && <Icon />}
        {label}
      </div>
      <div className="stat-value">
        {value}
        {unit && <span className="unit">{unit}</span>}
        {suffix && <span className="dim">{suffix}</span>}
      </div>
      {children}
    </div>
  )
}
