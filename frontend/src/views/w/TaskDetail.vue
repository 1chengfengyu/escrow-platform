<script setup lang="ts">
// W-03 任务详情（登录后）+ 报名（FR-TASK.DETAIL.01 / FR-SIGN.APPLY.01 / 补遗沟通）
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { chatApi } from '@/api/modules/chat'
import { engagementApi } from '@/api/modules/engagement'
import { taskApi } from '@/api/modules/task'
import { toApiError, type ApiError } from '@/api/http'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MoneyText from '@/components/MoneyText.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'
import { statusLabel } from '@/utils/status'

const route = useRoute()
const router = useRouter()
const message = useMessage()

const id = computed(() => String(route.params.id))
const { data, loading, error, load } = useResource(() => taskApi.detail(id.value))

const applying = ref(false)
const applyError = ref<ApiError | null>(null)
const appliedId = ref('')
const contacting = ref(false)
const contactError = ref<ApiError | null>(null)

const isOwner = computed(() => data.value?.partyView === true)
const canApply = computed(() => {
  const task = data.value
  if (!task || task.partyView) return false
  if (task.recruitingClosed) return false
  return task.remainingSlots > 0 && task.status === 'TASK_RECRUITING'
})

const applyHint = computed(() => {
  const task = data.value
  if (!task) return ''
  if (task.partyView) return '这是你发布的任务，不能报名自己发布的任务。'
  if (task.recruitingClosed) return '该任务已停止招募。'
  if (task.remainingSlots <= 0) return '名额已满，暂时无法报名。'
  if (task.status !== 'TASK_RECRUITING') return `任务当前为「${statusLabel('task', task.status)}」，不可报名。`
  return ''
})

async function apply(): Promise<void> {
  if (!canApply.value || applying.value) return
  applying.value = true
  applyError.value = null
  try {
    const created = await engagementApi.apply(id.value)
    appliedId.value = created.id
    message.success('报名已提交，等待雇主处理')
    await load()
  } catch (raw) {
    applyError.value = toApiError(raw)
  } finally {
    applying.value = false
  }
}

async function contactEmployer(): Promise<void> {
  if (!data.value || contacting.value) return
  contacting.value = true
  contactError.value = null
  try {
    const conversation = await chatApi.create({ taskId: Number(id.value), scope: 'INQUIRY' })
    message.success('已建立沟通')
    await router.push(`/worker/conversations/${conversation.id}`)
  } catch (raw) {
    contactError.value = toApiError(raw)
  } finally {
    contacting.value = false
  }
}
</script>

<template>
  <div class="page page--wide">
    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取任务详情" />

    <EmptyState v-else-if="error && error.isNotFound" title="任务不存在" desc="链接可能已失效，或任务编号有误。">
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/tasks">返回任务列表</RouterLink>
      </template>
    </EmptyState>

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <PageHeader eyebrow="TASK" :title="data.title" :desc="`${data.category} · ${data.workLocation}`">
        <template #actions>
          <StatusTag kind="task" :value="data.status" />
          <span class="stamp" :class="data.remainingSlots > 0 ? 'stamp--active' : 'stamp--muted'">
            剩 {{ data.remainingSlots }} / {{ data.slotCount }} 个名额
          </span>
        </template>
      </PageHeader>

      <figure class="anchor anchor--slim">
        <img src="/media/anchor-task.jpg" alt="明亮会场里正在摆放折叠椅与布幔的布展现场" />
      </figure>

      <div class="detail">
        <div class="stack stack--lg">
          <section class="card">
            <div class="row row--between row--wrap">
              <div>
                <p class="eyebrow">单个名额报酬</p>
                <p class="stat__value"><MoneyText :fen="data.unitPrice" /></p>
              </div>
              <div class="right">
                <p class="eyebrow">履约时间</p>
                <p class="small">{{ formatDateTime(data.startTime) }}</p>
                <p class="small">至 {{ formatDateTime(data.endTime) }}</p>
              </div>
            </div>
            <hr class="divider" />
            <div class="kv">
              <div class="kv__row">
                <span class="kv__k">工作地点</span>
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
              <div class="kv__row">
                <span class="kv__k">招募状态</span>
                <span class="kv__v">{{ data.recruitingClosed ? '已停止招募' : '招募中' }}</span>
              </div>
            </div>
          </section>

          <section class="card stack">
            <div class="card__head">
              <div>
                <h2 class="card__title">任务要求与交付说明</h2>
                <p class="card__sub">报名前请逐条确认</p>
              </div>
            </div>
            <p style="white-space: pre-wrap; line-height: 1.85">{{ data.description }}</p>
            <div v-if="data.hasOpenDispute" class="notice">
              <p class="notice__eyebrow">提示</p>
              <p class="notice__desc">该任务存在未处理争议，结算会暂停。报名前请留意。</p>
            </div>
          </section>

          <section class="card">
            <div class="card__head">
              <div>
                <h2 class="card__title">名额状态</h2>
                <p class="card__sub">每个名额独立流转，录用即锁定一个名额</p>
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
          <section v-if="isOwner" class="card stack">
            <p class="eyebrow">发布者视角</p>
            <p class="small ink2">你正在查看自己发布的任务。报名、录用与验收在雇主工作台完成。</p>
            <RouterLink class="btn btn--ink btn--block btn--sm" :to="`/employer/tasks/${data.id}`">去管理任务</RouterLink>
          </section>

          <section v-else class="card stack">
            <p class="eyebrow">报名</p>
            <template v-if="appliedId">
              <p class="small ink2">报名已提交，报名编号 <span class="mono">{{ appliedId }}</span>。</p>
              <p class="tiny muted">雇主处理后，结果会出现在站内通知里。</p>
            </template>
            <p v-else class="small ink2">报名后等待雇主筛选；被录用会形成签约并要求按约开工。</p>

            <button class="btn btn--ink btn--block btn--sm" type="button" :disabled="!canApply || applying" @click="apply">
              {{ applying ? '正在提交' : canApply ? '提交报名' : '暂不可报名' }}
            </button>
            <p v-if="applyHint" class="field__hint">{{ applyHint }}</p>
            <p v-if="applyError" class="field__error">{{ applyError.message }}（{{ applyError.code }}）</p>

            <hr class="divider" />
            <p class="eyebrow">沟通</p>
            <p class="small ink2">对任务有疑问，可先与雇主建立咨询会话。</p>
            <button class="btn btn--line btn--block btn--sm" type="button" :disabled="contacting" @click="contactEmployer">
              {{ contacting ? '正在建立沟通' : '联系雇主' }}
            </button>
            <p v-if="contactError" class="field__error">{{ contactError.message }}（{{ contactError.code }}）</p>
          </section>

          <section class="card stack">
            <p class="eyebrow">下一步</p>
            <div class="stack stack--sm">
              <RouterLink class="linkbtn" to="/worker/applications">我的报名</RouterLink>
              <RouterLink class="linkbtn" to="/worker/engagements">我的履约</RouterLink>
              <RouterLink class="linkbtn" to="/worker/notifications">通知中心</RouterLink>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>
