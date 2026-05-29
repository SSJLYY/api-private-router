/**
 * formatScaled formats a per-token (or per-request) USD price scaled by `scale`.
 *
 *   formatScaled(0.000003, 1_000_000) 鈫?"$3"        // per 1M tokens
 *   formatScaled(0.5,        1)        鈫?"$0.5"      // per request
 *   formatScaled(null,       1_000_000) 鈫?"-"
 *
 * Uses toPrecision(10) then strips trailing zeros to avoid IEEE 754 display noise.
 */
export function formatScaled(value: number | null, scale: number): string {
  if (value == null) return ‘-’
  const raw = value * scale
  const str = raw.toPrecision(10)
  if (str.includes('e')) return `$${raw}`
  return `$${str.replace(/\.?0+$/, '')}`
}
