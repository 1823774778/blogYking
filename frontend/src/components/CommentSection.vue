<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api'
import { state, toast } from '../state'
import { formatDateTime, initialOf } from '../utils'

const props = defineProps({
  postId: { type: [String, Number], required: true }
})

const route = useRoute()
const comments = ref([])
const loading = ref(false)
const content = ref('')
const submitting = ref(false)
const replyTarget = ref(null)
const replyContent = ref('')

const roots = computed(() => comments.value.filter((c) => !c.parentId))
const loginLink = computed(() => `/login?redirect=${encodeURIComponent(route.fullPath)}`)

function repliesOf(id) {
  return comments.value.filter((c) => c.parentId === id)
}

async function load() {
  loading.value = true
  try {
    const res = await http.get(`/posts/${props.postId}/comments`)
    comments.value = res.data
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function submitNew() {
  const text = content.value.trim()
  if (!text) return
  submitting.value = true
  try {
    const res = await http.post(`/posts/${props.postId}/comments`, { content: text })
    comments.value.push(res.data)
    content.value = ''
    toast('评论已发布')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    submitting.value = false
  }
}

async function submitReply() {
  const text = replyContent.value.trim()
  if (!text || !replyTarget.value) return
  submitting.value = true
  try {
    const res = await http.post(`/posts/${props.postId}/comments`, {
      content: text,
      parentId: replyTarget.value.id
    })
    comments.value.push(res.data)
    replyContent.value = ''
    replyTarget.value = null
    toast('回复已发布')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    submitting.value = false
  }
}

async function remove(comment) {
  if (!window.confirm('确定删除这条评论吗？')) return
  try {
    await http.delete(`/comments/${comment.id}`)
    comments.value = comments.value.filter((c) => c.id !== comment.id && c.parentId !== comment.id)
    toast('已删除')
  } catch (e) {
    toast(e.message, 'error')
  }
}

function canDelete(comment) {
  return comment.mine || state.user?.role === 'ADMIN'
}

onMounted(load)
</script>

<template>
  <section class="comments">
    <div class="section-title">评论 · {{ comments.length }}</div>

    <div v-if="state.user" class="comment-form">
      <textarea v-model="content" placeholder="说点什么……"></textarea>
      <div class="comment-form-foot">
        <span class="hint">以 {{ state.user.nickname || state.user.username }} 的身份评论</span>
        <button class="btn btn-sm" type="button" :disabled="submitting || !content.trim()" @click="submitNew">
          发表评论
        </button>
      </div>
    </div>
    <div v-else class="comment-form">
      <div class="empty" style="padding: 24px 0">
        <router-link :to="loginLink" style="color: var(--accent)">登录</router-link>
        后即可参与评论
      </div>
    </div>

    <div v-if="loading" class="empty">评论加载中…</div>
    <div v-else-if="!roots.length" class="empty">还没有评论，来坐第一排。</div>

    <div v-for="c in roots" :key="c.id" class="comment">
      <span class="avatar">
        <img v-if="c.avatar" :src="c.avatar" alt="" />
        <template v-else>{{ initialOf(c.nickname) }}</template>
      </span>
      <div style="min-width: 0">
        <div class="comment-head">
          <span class="name">{{ c.nickname }}</span>
          <span class="time">{{ formatDateTime(c.createdAt) }}</span>
        </div>
        <div class="comment-body">{{ c.content }}</div>
        <div class="comment-ops">
          <button v-if="state.user" type="button" @click="replyTarget = replyTarget?.id === c.id ? null : c">
            {{ replyTarget?.id === c.id ? '取消回复' : '回复' }}
          </button>
          <button v-if="canDelete(c)" type="button" @click="remove(c)">删除</button>
        </div>

        <div v-if="replyTarget?.id === c.id" class="reply-box">
          <textarea v-model="replyContent" placeholder="回复这条评论……" style="min-height: 70px"></textarea>
          <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 8px">
            <button class="btn btn-ghost btn-sm" type="button" @click="replyTarget = null">取消</button>
            <button class="btn btn-sm" type="button" :disabled="submitting || !replyContent.trim()" @click="submitReply">
              发布回复
            </button>
          </div>
        </div>

        <div v-if="repliesOf(c.id).length" class="replies">
          <div v-for="r in repliesOf(c.id)" :key="r.id" class="comment" style="border-bottom: none; padding: 12px 0">
            <span class="avatar" style="width: 30px; height: 30px; font-size: 13px">
              <img v-if="r.avatar" :src="r.avatar" alt="" />
              <template v-else>{{ initialOf(r.nickname) }}</template>
            </span>
            <div style="min-width: 0">
              <div class="comment-head">
                <span class="name">{{ r.nickname }}</span>
                <span class="time">{{ formatDateTime(r.createdAt) }}</span>
              </div>
              <div class="comment-body">{{ r.content }}</div>
              <div v-if="canDelete(r)" class="comment-ops">
                <button type="button" @click="remove(r)">删除</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
