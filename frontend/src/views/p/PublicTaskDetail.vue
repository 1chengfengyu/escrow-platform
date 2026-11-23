<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { chatApi } from '@/api/modules/chat'
import { taskApi } from '@/api/modules/task'
import { toApiError, type ApiError } from '@/api/http'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import LoginGate from '@/components/LoginGate.vue'
import MoneyText from '@/components/MoneyText.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/date'
import { statusLabel } from '@/utils/status'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const message = useMessage()

const id = computed(() => String(route.params.id))
const { data, loading, error, load } = useResource(() => taskApi.detail(id.value, true))

const contacting = ref(false)
const contactError = ref<ApiError | null>(null)

const applyTarget = computed(() => ({
  path: auth.isLoggedIn ? `/worker/tasks/${id.value}` : '/login',
  query: auth.isLoggedIn ? {} : { redirect: `/worker/tasks/${id.value}`, intent: 'worker' },
}))

const isOwner = computed(() => data.value?.partyView && auth.isEmployer)

async function contactEmployer(): Promise<void> {
  if (!auth.isLoggedIn) {
    await router.push({ path: '/login', query: { redirect: route.fullPath, intent: 'worker' } })
    return
  }
  contacting.value = true
  contactError.value = null
  try {
    const conversation = await chatApi.create({ taskId: Number(id.value), scope: 'INQUIRY' })
    message.success('已建立沟通')
    const prefix = auth.identity === 'EMPLOYER' ? '/employer' : '/worker'
    await router.push(`${prefix}/conversations/${conversation.id}`)
  } catch (raw) {
    contactError.value = toApiError(raw)
  } finally {
    contacting.value = false
  }
}
</script>

<template>
  <div class="wrap section--tight">
    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取任务详情" />

    <LoginGate v-else-if="error && error.isUnauthorized" />

    <EmptyState
      v-else-if="error && error.isNotFound"
      title="任务不存在"
      desc="链接可能已失效，或任务编号有误。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/tasks">返回任务广场</RouterLink>
      </template>
    </EmptyState>

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <div v-else-if="data" class="stack stack--lg">
      <PageHeader eyebrow="TASK" :title="data.title" :desc="data.category">
        <template #actions>
          <StatusTag kind="task" :value="data.status" />
          <RouterLink v-if="isOwner" class="btn btn--line btn--sm" :to="`/employer/tasks/${data.id}`">管理该任务</RouterLink>
        </template>
      </PageHeader>

      <div class="detail">
        <div class="stack stack--lg">
          <section class="card stack">
            <div class="row row--between row--wrap">
              <div>
                <p class="eyebrow">单个名额报酬</p>
                <p class="stat__value"><MoneyText :fen="data.unitPrice" /></p>
              </div>
              <div class="right">
                <p class="eyebrow">名额</p>
                <p class="stat__value">{{ data.slotCount }}</p>
                <p class="tiny muted">剩余 {{ data.remainingSlots }}</p>
              </div>
            </div>
            <hr class="divider" />
            <div class="kv">
              <div class="kv__row">
                <span class="kv__k">时间</span>
                <span class="kv__v">{{ formatDateTime(data.startTime) }} 至 {{ formatDateTime(data.endTime) }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">地点</span>
                <span class="kv__v">{{ data.workLocation }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">结算说明</span>
                <span class="kv__v">{{ data.settlementNote || '—' }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">发布时间</span>
                <span class="kv__v">{{ formatDateTime(data.publishedAt) }}</span>
              </div>
              <div v-if="data.partyView && data.escrowAmount" class="kv__row">
                <span class="kv__k">预算</span>
                <span class="kv__v"><MoneyText :fen="data.escrowAmount" /></span>
              </div>
            </div>
          </section>

          <section class="card stack">
            <div class="card__head">
              <div>
                <h2 class="card__title">任务说明</h2>
                <p class="card__sub">发布者填写的内容</p>
              </div>
            </div>
            <p style="white-space: pre-wrap; line-height: 1.85">{{ data.description }}</p>
          </section>

          <section class="card stack">
            <div class="card__head">
              <div>
                <h2 class="card__title">名额状态</h2>
                <p class="card__sub">每个名额独立流转</p>
              </div>
            </div>
            <div class="slots">
              <div v-for="slot in data.slotOccupancy" :key="slot.seqNo" class="slot">
                <span class="slot__no">名额 {{ slot.seqNo }}</span>
                <StatusTag kind="slot" :value="slot.status" />
              </div>
            </div>
          </section>
        </div>

        <aside class="stack">
          <section v-if="data.partyView" class="card stack">
            <p class="eyebrow">你是发布者</p>
            <p class="small ink2">查看报名、录用劳动者与验收都在工作台完成。</p>
            <RouterLink class="btn btn--ink btn--block btn--sm" to="/employer/tasks">去任务管理</RouterLink>
          </section>

          <section v-else class="card stack">
            <p class="eyebrow">怎么参与</p>
            <p class="small ink2">
              报名前请确认时间、地点与报酬。录用后会形成签约，按约开工并交付。
            </p>
            <RouterLink class="btn btn--ink btn--block btn--sm" :to="applyTarget">
              {{ auth.isLoggedIn ? '去报名承接' : '登录后报名' }}
            </RouterLink>
            <button class="btn btn--line btn--block btn--sm" type="button" :disabled="contacting" @click="contactEmployer">
              {{ contacting ? '正在建立沟通' : '联系雇主' }}
            </button>
            <p v-if="contactError" class="field__error">
              {{ contactError.message }}（{{ contactError.code }}）
            </p>
          </section>

          <section class="card stack">
            <p class="eyebrow">状态说明</p>
            <p class="small ink2">当前：{{ statusLabel('task', data.status) }}</p>
            <p v-if="data.hasOpenDispute" class="small">该任务存在未处理争议，结算会暂停。</p>
            <p v-if="data.hasSettlementAbnormal" class="small">该任务存在结算异常，治理台会跟进。</p>
            <p v-if="data.recruitingClosed" class="small muted">该任务已停止招募。</p>
          </section>
        </aside>
      </div>
    </div>
  </div>
</template>
