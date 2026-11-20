<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../api'
import { state, setAuth, toast } from '../state'
import { formatDate, formatDateTime, initialOf } from '../utils'

const tab = ref('profile')
const form = ref({ nickname: '', bio: '', avatar: '' })
const pwd = ref({ oldPassword: '', newPassword: '', confirm: '' })
const saving = ref(false)
const uploading = ref(false)
const fileInput = ref(null)

const favorites = ref([])
const likes = ref([])
const favLoaded = ref(false)
const likeLoaded = ref(false)

const roleText = computed(() => (state.user?.role === 'ADMIN' ? '管理员' : '读者'))

function fillForm() {
  form.value = {
    nickname: state.user?.nickname || '',
    bio: state.user?.bio || '',
    avatar: state.user?.avatar || ''
  }
}

async function save() {
  if (pwd.value.newPassword) {
    if (pwd.value.newPassword.length < 6) {
      toast('新密码至少 6 位', 'error')
      return
    }
    if (pwd.value.newPassword !== pwd.value.confirm) {
      toast('两次输入的新密码不一致', 'error')
      return
    }
    if (!pwd.value.oldPassword) {
      toast('请输入原密码', 'error')
      return
    }
  }
  saving.value = true
  try {
    const res = await http.put('/auth/profile', {
      nickname: form.value.nickname,
      bio: form.value.bio,
      avatar: form.value.avatar,
      oldPassword: pwd.value.newPassword ? pwd.value.oldPassword : null,
      newPassword: pwd.value.newPassword || null
    })
    setAuth(state.token, res.data)
    pwd.value = { oldPassword: '', newPassword: '', confirm: '' }
    toast('资料已保存')
    fillForm()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

function pickFile() {
  fileInput.value?.click()
}

async function onFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const data = new FormData()
  data.append('file', file)
  uploading.value = true
  try {
    const res = await http.post('/upload', data, { headers: { 'Content-Type': 'multipart/form-data' } })
    form.value.avatar = res.data.url
    toast('头像已上传，记得保存')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

async function switchTab(next) {
  tab.value = next
  if (next === 'favorites' && !favLoaded.value) {
    try {
      const res = await http.get('/me/favorites')
      favorites.value = res.data
      favLoaded.value = true
    } catch (e) {
      toast(e.message, 'error')
    }
  }
  if (next === 'likes' && !likeLoaded.value) {
    try {
      const res = await http.get('/me/likes')
      likes.value = res.data
      likeLoaded.value = true
    } catch (e) {
      toast(e.message, 'error')
    }
  }
}

onMounted(() => {
  fillForm()
})
</script>

<template>
  <div>
    <div class="page-head">
      <h1>个人中心</h1>
      <p>管理你的资料与密码，查看收藏和喜欢过的文章。</p>
    </div>

    <div class="profile-card">
      <span class="avatar lg">
        <img v-if="state.user?.avatar" :src="state.user.avatar" alt="" />
        <template v-else>{{ initialOf(state.user?.nickname || state.user?.username) }}</template>
      </span>
      <div class="who">
        <h2>{{ state.user?.nickname || state.user?.username }}</h2>
        <p>@{{ state.user?.username }} · {{ roleText }} · 加入于 {{ formatDate(state.user?.createdAt) }}</p>
      </div>
    </div>

    <div class="tabs">
      <button type="button" :class="{ on: tab === 'profile' }" @click="switchTab('profile')">资料设置</button>
      <button type="button" :class="{ on: tab === 'favorites' }" @click="switchTab('favorites')">我的收藏</button>
      <button type="button" :class="{ on: tab === 'likes' }" @click="switchTab('likes')">我的喜欢</button>
    </div>

    <div v-if="tab === 'profile'" style="max-width: 620px; padding-bottom: 70px">
      <div class="form-grid">
        <div class="field span-2">
          <label>头像</label>
          <div style="display: flex; align-items: center; gap: 16px">
            <span class="avatar lg">
              <img v-if="form.avatar" :src="form.avatar" alt="" />
              <template v-else>{{ initialOf(form.nickname || state.user?.username) }}</template>
            </span>
            <button class="btn btn-ghost btn-sm" type="button" :disabled="uploading" @click="pickFile">
              {{ uploading ? '上传中…' : '上传头像' }}
            </button>
            <span class="meta">支持 jpg / png / webp，20MB 以内</span>
            <input ref="fileInput" type="file" accept="image/*" style="display: none" @change="onFile" />
          </div>
        </div>
        <div class="field">
          <label for="nickname">昵称</label>
          <input id="nickname" v-model="form.nickname" type="text" maxlength="32" />
        </div>
        <div class="field">
          <label>账号</label>
          <input :value="state.user?.username" type="text" disabled />
        </div>
        <div class="field span-2">
          <label for="bio">个人简介</label>
          <textarea id="bio" v-model="form.bio" maxlength="512" style="min-height: 80px"></textarea>
        </div>
        <div class="field">
          <label for="oldpwd">原密码（修改密码时填写）</label>
          <input id="oldpwd" v-model="pwd.oldPassword" type="password" autocomplete="current-password" />
        </div>
        <div class="field">
          <label for="newpwd">新密码</label>
          <input id="newpwd" v-model="pwd.newPassword" type="password" autocomplete="new-password" />
        </div>
        <div class="field">
          <label for="confirmpwd">确认新密码</label>
          <input id="confirmpwd" v-model="pwd.confirm" type="password" autocomplete="new-password" />
        </div>
        <div class="field">
          <label>邮箱</label>
          <input :value="state.user?.email" type="text" disabled />
        </div>
      </div>
      <button class="btn" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存修改' }}</button>
    </div>

    <div v-else-if="tab === 'favorites'" style="padding-bottom: 70px">
      <div v-if="!favorites.length" class="empty">还没有收藏的文章。遇到喜欢的，点一下书签就好。</div>
      <div class="simple-list">
        <div v-for="post in favorites" :key="post.id" class="simple-row">
          <div>
            <router-link class="t" :to="`/post/${post.id}`">{{ post.title }}</router-link>
            <div class="s">{{ post.summary }}</div>
          </div>
          <span class="meta">{{ formatDate(post.createdAt) }}</span>
        </div>
      </div>
    </div>

    <div v-else-if="tab === 'likes'" style="padding-bottom: 70px">
      <div v-if="!likes.length" class="empty">还没有喜欢过的文章。</div>
      <div class="simple-list">
        <div v-for="post in likes" :key="post.id" class="simple-row">
          <div>
            <router-link class="t" :to="`/post/${post.id}`">{{ post.title }}</router-link>
            <div class="s">{{ post.summary }}</div>
          </div>
          <span class="meta">{{ formatDate(post.createdAt) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
