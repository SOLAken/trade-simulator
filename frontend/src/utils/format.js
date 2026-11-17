export const yuan = (cents) => (cents == null ? '--' : (cents / 100).toFixed(2))

export const pct = (last, prevClose) =>
  !prevClose ? '--' : `${last >= prevClose ? '+' : ''}${(((last - prevClose) / prevClose) * 100).toFixed(2)}%`

export const trend = (last, prevClose) => (last > prevClose ? 'up' : last < prevClose ? 'down' : '')

export const volume = (v) =>
  v == null ? '--' : v >= 1e8 ? `${(v / 1e8).toFixed(2)}亿` : v >= 1e4 ? `${(v / 1e4).toFixed(1)}万` : `${v}`
