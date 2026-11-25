export const MUSIC_PLATFORMS = [
  { value: 'netease', label: '网易云音乐', short: '网易云', tone: '#e60026', mark: '云' },
  { value: 'qq', label: 'QQ 音乐', short: 'QQ 音乐', tone: '#31c27c', mark: 'Q' },
  { value: 'kugou', label: '酷狗音乐', short: '酷狗', tone: '#2a90ff', mark: '酷' },
  { value: 'kuwo', label: '酷我音乐', short: '酷我', tone: '#ff7a00', mark: 'K' },
  { value: 'audio', label: '云端音频', short: '音频', tone: '#2f6fed', mark: '♪' },
  { value: 'custom', label: '云端歌单', short: '云端', tone: '#7c8798', mark: '♪' }
]

export function platformMeta(value) {
  return MUSIC_PLATFORMS.find((item) => item.value === value) || MUSIC_PLATFORMS[MUSIC_PLATFORMS.length - 1]
}

export function detectMusicPlatform(url = '') {
  const text = String(url || '').toLowerCase()
  if (isAudioUrl(text)) return 'audio'
  if (text.includes('music.163.com') || text.includes('163cn.tv')) return 'netease'
  if (text.includes('y.qq.com') || text.includes('qqmusic') || text.includes('i.y.qq.com')) return 'qq'
  if (text.includes('kugou.com')) return 'kugou'
  if (text.includes('kuwo.cn') || text.includes('kuwo.com')) return 'kuwo'
  return 'custom'
}

export function isAudioUrl(url = '') {
  try {
    const pathname = url.startsWith('/') ? new URL(url, 'https://local.invalid').pathname : new URL(String(url)).pathname
    return /\.(mp3|m4a|aac|wav|ogg|flac|opus)$/i.test(pathname)
  } catch {
    return /\.(mp3|m4a|aac|wav|ogg|flac|opus)(?:[?#]|$)/i.test(String(url))
  }
}

export function extractMusicId(url) {
  const text = String(url || '')
  const byQuery = text.match(/[?&#](?:id|specialid|playlistId|disstid)=([a-zA-Z0-9_-]+)/)
  if (byQuery) return byQuery[1]
  const byPath = text.match(/\/(?:playlist|taoge|special|single|plist)\/([a-zA-Z0-9_-]+)/)
  return byPath ? byPath[1] : ''
}

export function playlistEmbedUrl(platform, url, height = 430) {
  const id = extractMusicId(url)
  if (!id) return ''
  if (platform === 'netease') {
    return `https://music.163.com/outchain/player?type=0&id=${id}&auto=0&height=${height}`
  }
  if (platform === 'qq') {
    return `https://i.y.qq.com/n2/m/share/details/taoge.html?platform=11&appshare=android&songmid=&id=${id}`
  }
  return ''
}

export function playlistOpenUrl(platform, url) {
  const value = String(url || '').trim()
  if (!value) return ''
  if (platform === 'netease') {
    const id = extractMusicId(value)
    return id ? `https://music.163.com/#/playlist?id=${id}` : value
  }
  if (platform === 'qq') {
    const id = extractMusicId(value)
    return id ? `https://y.qq.com/n/ryqq/playlist/${id}` : value
  }
  return value
}
