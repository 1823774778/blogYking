const SEEN_KEY = 'blog_notice_seen'
const READ_IDS_KEY = 'blog_notice_read_ids'
const CHANGE_EVENT = 'blog:notices-changed'

function numericId(value) {
  const id = Number(value)
  return Number.isFinite(id) && id > 0 ? id : 0
}

export function seenNoticeId() {
  if (typeof localStorage === 'undefined') return 0
  return numericId(localStorage.getItem(SEEN_KEY))
}

function readNoticeIds() {
  if (typeof localStorage === 'undefined') return new Set()
  try {
    const parsed = JSON.parse(localStorage.getItem(READ_IDS_KEY) || '[]')
    return new Set(Array.isArray(parsed) ? parsed.map(numericId).filter(Boolean) : [])
  } catch {
    return new Set()
  }
}

function writeReadNoticeIds(ids) {
  if (typeof localStorage === 'undefined') return
  localStorage.setItem(READ_IDS_KEY, JSON.stringify([...ids].slice(-400)))
}

function emitChange() {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT))
  }
}

export function isNoticeNew(notice) {
  const id = numericId(notice?.id)
  if (!id) return false
  return id > seenNoticeId() && !readNoticeIds().has(id)
}

export function markNoticeRead(notice) {
  const id = numericId(notice?.id)
  if (!id || id <= seenNoticeId()) return
  const ids = readNoticeIds()
  if (ids.has(id)) return
  ids.add(id)
  writeReadNoticeIds(ids)
  emitChange()
}

export function markNoticesSeen(latestId) {
  const id = numericId(latestId)
  if (!id) return
  const current = seenNoticeId()
  if (id > current) {
    localStorage.setItem(SEEN_KEY, String(id))
  }
  writeReadNoticeIds(new Set())
  emitChange()
}

export const noticeChangeEvent = CHANGE_EVENT
