<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api'
import { state, setAuth, toast } from '../state'

const router = useRouter()

const form = reactive({
  username: '',
  email: '',
  nickname: '',
  password: '',
  confirm: '',
  dbType: 'h2',
  mysql: { host: '127.0.0.1', port: 3306, database: 'blog', username: 'root', password: '' },
  redisEnabled: false,
  redis: { host: '127.0.0.1', port: 6379, password: '', database: 0 }
})

const status = ref(null)
const loading = ref(false)
const error = ref('')
const done = ref(false)
const restartNeeded = ref(false)
const mysqlMsg = ref('')
const mysqlMsgOk = ref(false)
const redisMsg = ref('')
const redisMsgOk = ref(false)
const testingMysql = ref(false)
const testingRedis = ref(false)

const configPath = computed(() => status.value?.configFile || './config/application.yml')
const currentDb = computed(() => status.value?.database || 'H2（文件库）')
const configCommand = computed(() => {
  const parts = []
  if (form.dbType === 'mysql') parts.push('MySQL')
  if (form.redisEnabled) parts.push('Redis')
  return parts.length ? parts.join(' + ') : ''
})

async function loadStatus() {
  try {
    const res = await http.get('/setup/status')
    status.value = res.data
    if (!res.data.required && !done.value) {
      router.replace('/')
    }
  } catch (e) {
    error.value = e.message
  }
}

async function testMysql() {
  mysqlMsg.value = ''
  testingMysql.value = true
  try {
    const res = await http.post('/setup/test/mysql', { ...form.mysql, enabled: true })
    mysqlMsg.value = res.data.message
    mysqlMsgOk.value = true
  } catch (e) {
    mysqlMsg.value = e.message
    mysqlMsgOk.value = false
  } finally {
    testingMysql.value = false
  }
}

async function testRedis() {
  redisMsg.value = ''
  testingRedis.value = true
  try {
    const res = await http.post('/setup/test/redis', { ...form.redis, enabled: true })
    redisMsg.value = res.data.message
    redisMsgOk.value = true
  } catch (e) {
    redisMsg.value = e.message
    redisMsgOk.value = false
  } finally {
    testingRedis.value = false
  }
}

async function submit() {
  error.value = ''
  if (!/^[A-Za-z0-9_]{3,20}$/.test(form.username.trim())) {
    error.value = '管理员用户名需为 3-20 位字母、数字或下划线'
    return
  }
  if (!/^[\w.+-]+@[\w-]+(\.[\w-]+)+$/.test(form.email.trim())) {
    error.value = '管理员邮箱格式不正确'
    return
  }
  if (form.password.length < 6) {
    error.value = '管理员密码至少 6 位'
    return
  }
  if (form.password !== form.confirm) {
    error.value = '两次输入的密码不一致'
    return
  }
  if (form.dbType === 'mysql' && (!form.mysql.host.trim() || !form.mysql.database.trim() || !form.mysql.username.trim())) {
    error.value = '启用 MySQL 时，地址、数据库名、用户名必须填写'
    return
  }
  if (form.redisEnabled && !form.redis.host.trim()) {
    error.value = '启用 Redis 时，地址必须填写'
    return
  }

  loading.value = true
  try {
    const payload = {
      admin: {
        username: form.username.trim(),
        email: form.email.trim(),
        nickname: form.nickname.trim(),
        password: form.password
      },
      mysql: {
        enabled: form.dbType === 'mysql',
        host: form.mysql.host.trim(),
        port: Number(form.mysql.port) || 3306,
        database: form.mysql.database.trim(),
        username: form.mysql.username.trim(),
        password: form.mysql.password
      },
      redis: {
        enabled: form.redisEnabled,
        host: form.redis.host.trim(),
        port: Number(form.redis.port) || 6379,
        password: form.redis.password,
        database: Number(form.redis.database) || 0
      }
    }
    const res = await http.post('/setup/complete', payload)
    setAuth(res.data.token, res.data.user)
    state.setupRequired = false
    restartNeeded.value = payload.mysql.enabled || payload.redis.enabled
    done.value = true
    toast('初始化完成')
    if (!restartNeeded.value) {
      router.push('/')
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(loadStatus)
</script>

<template>
  <div class="setup-page">
    <div class="setup-shell">
      <header class="setup-hero">
        <div class="logo" style="margin-bottom: 26px">
          <span class="logo-seal">墨</span>
          <span class="logo-name">{{ state.site.siteName }}</span>
        </div>
        <span class="hero-eyebrow">First run · 首次启动</span>
        <h1>先把地基打好。</h1>
        <p class="sub">
          创建管理员账号，MySQL 与 Redis 按需连接。<br />
          数据库与缓存配置会写入 <code>{{ configPath }}</code>，重启后生效；管理员账号立即生效。
        </p>
      </header>

      <div v-if="done" class="setup-card">
        <div class="section-title">初始化完成</div>
        <p style="margin: 0 0 14px">
          管理员 <strong>{{ form.username }}</strong> 已创建，演示文章已写入。
        </p>
        <div v-if="restartNeeded" class="setup-note">
          你选择了 {{ configCommand }}。配置已写入 <code>{{ configPath }}</code>，
          <strong>需要重启服务</strong>才会连接新的数据库 / Redis。
          重启后请用刚才设置的管理员账号登录。
        </div>
        <div v-else class="setup-note">
          当前使用内置 H2 文件库（<code>./data/blog</code>），无需重启，已直接进入站点。
        </div>
        <div style="display: flex; gap: 10px; margin-top: 22px">
          <button class="btn" type="button" @click="router.push('/')">进入博客</button>
          <router-link class="btn btn-ghost" to="/about">稍后再看</router-link>
        </div>
      </div>

      <form v-else @submit.prevent="submit">
        <div class="setup-card">
          <div class="setup-step">
            <span class="setup-step-no">01</span>
            <div>
              <h2>管理员账号</h2>
              <p>这个账号拥有后台全部权限，请使用你自己的邮箱，密码至少 6 位。</p>
            </div>
          </div>
          <div class="form-grid">
            <div class="field">
              <label for="username">用户名</label>
              <input id="username" v-model="form.username" type="text" autocomplete="username" placeholder="如 deek" />
            </div>
            <div class="field">
              <label for="email">邮箱</label>
              <input id="email" v-model="form.email" type="email" autocomplete="email" placeholder="you@example.com" />
            </div>
            <div class="field">
              <label for="nickname">昵称（可选）</label>
              <input id="nickname" v-model="form.nickname" type="text" placeholder="显示在文章和评论里" />
            </div>
            <div class="field">
              <label for="password">密码</label>
              <input id="password" v-model="form.password" type="password" autocomplete="new-password" placeholder="至少 6 位" />
            </div>
            <div class="field">
              <label for="confirm">确认密码</label>
              <input id="confirm" v-model="form.confirm" type="password" autocomplete="new-password" placeholder="再输一次" />
            </div>
          </div>
        </div>

        <div class="setup-card">
          <div class="setup-step">
            <span class="setup-step-no">02</span>
            <div>
              <h2>数据库</h2>
              <p>先用内置 H2 体验最快；已经有 MySQL 就直接填连接信息。</p>
            </div>
          </div>

          <div class="segmented">
            <button type="button" :class="{ on: form.dbType === 'h2' }" @click="form.dbType = 'h2'">内置 H2（推荐先体验）</button>
            <button type="button" :class="{ on: form.dbType === 'mysql' }" @click="form.dbType = 'mysql'">MySQL</button>
          </div>

          <div v-if="form.dbType === 'h2'" class="setup-hint">
            当前数据库：{{ currentDb }}。数据保存在运行目录下的 <code>data/</code>，备份这个目录即可。
          </div>

          <div v-else style="margin-top: 18px">
            <div class="form-grid">
              <div class="field">
                <label for="dbhost">主机</label>
                <input id="dbhost" v-model="form.mysql.host" type="text" placeholder="127.0.0.1" />
              </div>
              <div class="field">
                <label for="dbport">端口</label>
                <input id="dbport" v-model="form.mysql.port" type="number" placeholder="3306" />
              </div>
              <div class="field">
                <label for="dbname">数据库名</label>
                <input id="dbname" v-model="form.mysql.database" type="text" placeholder="blog" />
              </div>
              <div class="field">
                <label for="dbuser">用户名</label>
                <input id="dbuser" v-model="form.mysql.username" type="text" placeholder="root" />
              </div>
              <div class="field">
                <label for="dbpwd">密码</label>
                <input id="dbpwd" v-model="form.mysql.password" type="password" autocomplete="off" />
              </div>
            </div>
            <div class="conn-row">
              <button class="btn btn-ghost btn-sm" type="button" :disabled="testingMysql" @click="testMysql">
                {{ testingMysql ? '测试中…' : '测试连接' }}
              </button>
              <span v-if="mysqlMsg" class="conn-msg" :class="{ ok: mysqlMsgOk, bad: !mysqlMsgOk }">{{ mysqlMsg }}</span>
            </div>
            <div class="setup-note">
              数据库需提前创建（建议 utf8mb4）；表结构会自动初始化。
              从 H2 切到 MySQL 时，<strong>已有数据不会自动迁移</strong>，重启后会按上面的管理员账号新建库表。
            </div>
          </div>
        </div>

        <div class="setup-card">
          <div class="setup-step">
            <span class="setup-step-no">03</span>
            <div>
              <h2>Redis（可选）</h2>
              <p>用来做浏览量去重和缓存；不装也能跑，会自动退回内存实现。</p>
            </div>
          </div>

          <label class="switch-row">
            <input v-model="form.redisEnabled" type="checkbox" />
            <span>启用 Redis</span>
          </label>

          <div v-if="form.redisEnabled" style="margin-top: 16px">
            <div class="form-grid">
              <div class="field">
                <label for="redis-host">主机</label>
                <input id="redis-host" v-model="form.redis.host" type="text" placeholder="127.0.0.1" />
              </div>
              <div class="field">
                <label for="redis-port">端口</label>
                <input id="redis-port" v-model="form.redis.port" type="number" placeholder="6379" />
              </div>
              <div class="field">
                <label for="redis-pwd">密码（可选）</label>
                <input id="redis-pwd" v-model="form.redis.password" type="password" autocomplete="off" />
              </div>
              <div class="field">
                <label for="redis-db">数据库编号</label>
                <input id="redis-db" v-model="form.redis.database" type="number" placeholder="0" />
              </div>
            </div>
            <div class="conn-row">
              <button class="btn btn-ghost btn-sm" type="button" :disabled="testingRedis" @click="testRedis">
                {{ testingRedis ? '测试中…' : '测试连接' }}
              </button>
              <span v-if="redisMsg" class="conn-msg" :class="{ ok: redisMsgOk, bad: !redisMsgOk }">{{ redisMsg }}</span>
            </div>
          </div>

          <div class="setup-note">Redis 配置同样写入 {{ configPath }}，重启后生效。</div>
        </div>

        <div v-if="error" class="form-error">{{ error }}</div>

        <div class="setup-actions">
          <button class="btn" type="submit" :disabled="loading">
            {{ loading ? '初始化中…' : '完成初始化' }}
          </button>
          <span class="meta">初始化完成后会自动登录</span>
        </div>

        <p class="setup-foot">
          提示：首次启动只会出现一次这个页面。部署到公网时，请先完成初始化再开放访问。
        </p>
      </form>
    </div>
  </div>
</template>
