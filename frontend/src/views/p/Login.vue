<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, toApiError, type ApiError as ApiErrorType } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { statusLabel } from '@/utils/status'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const form = reactive({
  account: typeof route.query.account === 'string' ? route.query.account : '',
  password: '',
})
const submitting = ref(false)
const error = ref<ApiErrorType | null>(null)

const redirect = computed(() => (typeof route.query.redirect === 'string' ? route.query.redirect : ''))
const intent = computed(() => (typeof route.query.intent === 'string' ? route.query.intent : ''))

async function submit(): Promise<void> {
  if (submitting.value) return
  error.value = null
  if (!form.account.trim() || !form.password) {
    error.value = new ApiError(-1, '请输入账号与密码')
    return
  }
  submitting.value = true
  try {
    const result = await auth.login(form.account.trim(), form.password)
    const wanted =
      intent.value === 'employer'
        ? 'EMPLOYER'
        : intent.value === 'worker'
          ? 'WORKER'
          : redirect.value.startsWith('/employer')
            ? 'EMPLOYER'
            : redirect.value.startsWith('/worker')
              ? 'WORKER'
              : ''
    if (wanted && result.identities.includes(wanted) && result.currentIdentity !== wanted) {
      await auth.switchIdentity(wanted)
    }
    await router.replace(redirect.value || auth.homePath())
  } catch (raw) {
    error.value = toApiError(raw)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <div class="auth-split">
      <figure class="auth-split__media">
        <img src="/media/anchor-login.jpg" alt="傍晚城市街道上整理送货背包的劳动者背影" />
      </figure>

      <div class="auth">
        <header class="auth__head">
          <p class="eyebrow">SIGN IN</p>
          <h1 class="auth__title">回到你的工作台</h1>
          <p class="ink2 small">用注册时的手机号或邮箱登录。雇主与劳动者共用同一个账号，登录后可切换。</p>
        </header>

        <div v-if="auth.isLoggedIn" class="card stack">
          <p class="mono small">当前已登录：{{ auth.nickname }}</p>
          <p class="small ink2">身份：{{ statusLabel('identity', auth.identity) }}</p>
          <div class="cluster">
            <RouterLink class="btn btn--ink btn--sm" :to="auth.homePath()">进入工作台</RouterLink>
            <button class="btn btn--line btn--sm" type="button" @click="auth.logout()">切换账号</button>
          </div>
        </div>

        <template v-else>
          <form class="card stack" @submit.prevent="submit">
            <div class="field">
              <label class="field__label" for="login-account">手机号 / 邮箱</label>
              <input id="login-account" v-model="form.account" class="input" autocomplete="username" placeholder="请输入账号" />
            </div>
            <div class="field">
              <label class="field__label" for="login-password">密码</label>
              <input
                id="login-password"
                v-model="form.password"
                class="input"
                type="password"
                autocomplete="current-password"
                placeholder="请输入密码"
              />
            </div>

            <div v-if="error" class="notice">
              <p class="notice__eyebrow">登录失败</p>
              <p class="notice__title">{{ error.message }}</p>
              <p class="notice__desc">
                错误码 <span class="mono">{{ error.code }}</span>
                <template v-if="error.traceId"> · 追踪号 {{ error.traceId }}</template>
              </p>
            </div>

            <button class="btn btn--ink btn--block" type="submit" :disabled="submitting">
              {{ submitting ? '登录中' : '登录' }}
            </button>

            <p class="auth__aside">
              登录即表示同意按平台规则履约与结算。演示环境的数据仅用于验收。
            </p>
          </form>

          <div class="auth__switch">
            <RouterLink class="linkbtn" to="/register">还没有账号？注册</RouterLink>
            <RouterLink class="linkbtn" to="/help/account-recovery">忘记密码</RouterLink>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
