<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { identityApi } from '@/api/modules/identity'
import { toApiError, type ApiError } from '@/api/http'
import { statusLabel } from '@/utils/status'

const step = ref<1 | 2>(1)
const role = ref<'EMPLOYER' | 'WORKER'>('WORKER')
const form = reactive({ phone: '', email: '', nickname: '', password: '', confirm: '' })
const submitting = ref(false)
const error = ref<ApiError | null>(null)
const createdPhone = ref('')

const phoneValid = computed(() => /^1\d{10}$/.test(form.phone.trim()))
const passwordValid = computed(() => form.password.length >= 8)
const canSubmit = computed(
  () =>
    phoneValid.value &&
    passwordValid.value &&
    form.password === form.confirm &&
    form.nickname.trim().length >= 2,
)

function chooseRole(value: 'EMPLOYER' | 'WORKER'): void {
  role.value = value
  step.value = 2
}

async function submit(): Promise<void> {
  if (submitting.value || !canSubmit.value) return
  submitting.value = true
  error.value = null
  try {
    await identityApi.register({
      phone: form.phone.trim(),
      email: form.email.trim(),
      password: form.password,
      nickname: form.nickname.trim(),
      role: role.value,
    })
    createdPhone.value = form.phone.trim()
    step.value = 2
  } catch (raw) {
    error.value = toApiError(raw)
  } finally {
    submitting.value = false
  }
}

function restart(): void {
  createdPhone.value = ''
  form.password = ''
  form.confirm = ''
  step.value = 1
}
</script>

<template>
  <div class="wrap">
    <div class="auth">
      <header class="auth__head">
        <p class="eyebrow">JOIN</p>
        <h1 class="auth__title">一个账号，两种身份</h1>
        <p class="ink2 small">雇主与劳动者共用同一个账号，注册后在工作台右上角随时切换。</p>
      </header>

      <div v-if="createdPhone" class="card stack">
        <p class="eyebrow">注册成功</p>
        <p class="card__title">账号已创建</p>
        <p class="small ink2">手机号 {{ createdPhone }}。请用该账号登录。</p>
        <div class="cluster">
          <RouterLink class="btn btn--ink btn--sm" :to="{ path: '/login', query: { account: createdPhone } }">
            去登录
          </RouterLink>
          <button class="btn btn--line btn--sm" type="button" @click="restart">再注册一个</button>
        </div>
      </div>

      <template v-else-if="step === 1">
        <div class="roles">
          <button class="role" type="button" @click="chooseRole('EMPLOYER')">
            <p class="role__label">EMPLOYER</p>
            <h2 class="role__title">我是雇主</h2>
            <p class="small ink2">发布任务、查看报名、录用劳动者，完成验收。</p>
            <span class="btn btn--ink btn--sm">选择雇主身份</span>
          </button>
          <button class="role" type="button" @click="chooseRole('WORKER')">
            <p class="role__label">WORKER</p>
            <h2 class="role__title">我是劳动者</h2>
            <p class="small ink2">浏览任务、报名承接、按约交付，等待验收。</p>
            <span class="btn btn--line btn--sm">选择劳动者身份</span>
          </button>
        </div>
        <p class="auth__aside">管理员账号由平台开通，不开放自助注册。</p>
      </template>

      <template v-else>
        <form class="card stack" @submit.prevent="submit">
          <div class="row row--between">
            <p class="small ink2">注册身份：<span class="strong">{{ statusLabel('identity', role) }}</span></p>
            <button class="linkbtn" type="button" @click="step = 1">重选身份</button>
          </div>

          <div class="form-grid">
            <div class="field">
              <label class="field__label" for="reg-phone">手机号</label>
              <input id="reg-phone" v-model="form.phone" class="input" inputmode="numeric" placeholder="11 位手机号" />
              <span v-if="form.phone && !phoneValid" class="field__error">手机号格式不正确</span>
            </div>
            <div class="field">
              <label class="field__label" for="reg-email">邮箱（可选）</label>
              <input id="reg-email" v-model="form.email" class="input" type="email" placeholder="用于接收通知" />
            </div>
            <div class="field field--full">
              <label class="field__label" for="reg-nickname">昵称</label>
              <input id="reg-nickname" v-model="form.nickname" class="input" placeholder="对方在任务里看到的称呼" />
            </div>
            <div class="field">
              <label class="field__label" for="reg-password">密码</label>
              <input id="reg-password" v-model="form.password" class="input" type="password" autocomplete="new-password" />
              <span v-if="form.password && !passwordValid" class="field__error">密码至少 8 位</span>
            </div>
            <div class="field">
              <label class="field__label" for="reg-confirm">确认密码</label>
              <input id="reg-confirm" v-model="form.confirm" class="input" type="password" autocomplete="new-password" />
              <span v-if="form.confirm && form.password !== form.confirm" class="field__error">两次输入不一致</span>
            </div>
          </div>

          <div v-if="error" class="notice">
            <p class="notice__eyebrow">注册失败</p>
            <p class="notice__title">{{ error.message }}</p>
            <p class="notice__desc">
              错误码 <span class="mono">{{ error.code }}</span>
              <template v-if="error.traceId"> · 追踪号 {{ error.traceId }}</template>
            </p>
          </div>

          <div class="form-actions">
            <button class="btn btn--ink" type="submit" :disabled="submitting || !canSubmit">
              {{ submitting ? '提交中' : '创建账号' }}
            </button>
            <RouterLink class="btn btn--line" to="/login">已有账号，去登录</RouterLink>
          </div>
        </form>
      </template>
    </div>
  </div>
</template>
