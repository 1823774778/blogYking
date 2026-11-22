export function formatDate(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}.${m}.${d}`
}

export function formatDateTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const h = String(date.getHours()).padStart(2, '0')
  const mi = String(date.getMinutes()).padStart(2, '0')
  return `${formatDate(value)} ${h}:${mi}`
}

export function formatCount(n) {
  const num = Number(n || 0)
  if (num >= 10000) return `${(num / 10000).toFixed(1)}w`
  if (num >= 1000) return `${(num / 1000).toFixed(1)}k`
  return String(num)
}

export function splitTags(tags) {
  if (!tags) return []
  return tags.split(',').map((t) => t.trim()).filter(Boolean)
}

export function initialOf(name) {
  return (name || '客').trim().charAt(0)
}

export function readingMinutes(content) {
  const length = (content || '').replace(/\s/g, '').length
  return Math.max(1, Math.round(length / 400))
}

export function markdownHeadings(content) {
  const lines = String(content || '').split('\n')
  const headings = []
  let fenced = false
  let index = 0
  for (const line of lines) {
    if (/^\s*(```|~~~)/.test(line)) {
      fenced = !fenced
      continue
    }
    if (fenced) continue
    const match = line.match(/^(#{1,6})\s+(.+?)\s*#*\s*$/)
    if (!match) continue
    const id = `heading-${index}`
    const level = match[1].length
    const text = match[2]
      .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
      .replace(/[`*_~]/g, '')
      .trim()
    index += 1
    if (level >= 2 && level <= 3) headings.push({ id, level, text })
  }
  return headings
}
