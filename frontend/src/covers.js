import editorialJournals from './assets/editorial-journals.webp'
import editorialManuscript from './assets/editorial-manuscript.webp'
import editorialNight from './assets/editorial-night.webp'
import editorialNib from './assets/editorial-nib.webp'
import editorialPress from './assets/editorial-press.webp'
import editorialMorning from './assets/editorial-morning.webp'

export const heroCover = editorialJournals
export const editorialCovers = [
  editorialJournals,
  editorialManuscript,
  editorialNight,
  editorialNib,
  editorialPress,
  editorialMorning
]

const techCovers = [editorialManuscript, editorialNib]
const lifeCovers = [editorialNight, editorialMorning]
const buildCovers = [editorialPress, editorialJournals, editorialMorning]
const aboutCovers = [editorialPress, editorialJournals]

export function coverForPost(post, tags = []) {
  if (post?.cover) return post.cover
  const tagText = Array.isArray(tags) ? tags.join(' ') : String(tags || '')
  const id = Number(post?.id || 0)

  if (/关于|About|about/.test(tagText)) return aboutCovers[id % aboutCovers.length]
  if (/Java|Spring|安全|技术|代码|前端|后端/.test(tagText)) return techCovers[id % techCovers.length]
  if (/建站|博客|效率|工具/.test(tagText)) return buildCovers[id % buildCovers.length]
  if (/写作|随笔|记录/.test(tagText)) return editorialNib
  if (/生活|思考|夜|坚持/.test(tagText)) return lifeCovers[id % lifeCovers.length]

  return editorialCovers[id % editorialCovers.length]
}

export function heroCoverForPost(post) {
  return post?.cover || heroCover
}
