<script setup lang="ts">
// W-06 履约详情 / 提交交付物（FR-SIGN.START.01 / FR-DELIVER.SUBMIT.01~03）
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { disputeApi } from '@/api/modules/dispute'
import { engagementApi } from '@/api/modules/engagement'
import { taskApi } from '@/api/modules/task'
import { ApiError, toApiError } from '@/api/http'
import type { ContractListItemView, TaskDetailView } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import GapNotice from '@/components/GapNotice.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MoneyText from '@/components/MoneyText.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'
import { can, statusLabel } from '@/utils/status'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const confirm = useConfirm()

const id = computed(() => String(route.params.id))

async function findContract(contractId: string): Promise<ContractListItemView | null> {
  for (let page = 1; page <= 5; page += 1) {
    const result = await engagementApi.contracts(page, 100)
    const hit = result.items.find((item) => item.contractId === contractId)
    if (hit) return hit
    if (page >= result.totalPages) break
  }
  return null
}

const { data, loading, error, load } = useResource(async () => {
  const contract = await findContract(id.value)
  if (!contract) return { contract: null, task: null, taskError: null as ApiError | null }
  try {
    const task = await taskApi.detail(contract.taskId)
    return { contract, task, taskError: null as ApiError | null }
  } catch (raw) {
    return { contract, task: null, taskError: toApiError(raw) as ApiError | null }
  }
})

const contract = computed(() => data.value?.contract ?? null)
const task = computed<TaskDetailView | null>(() => data.value?.task ?? null)

const busy = ref('')
const actionError = ref<ApiError | null>(null)

const imageInput = ref('')
const deliverDescription = ref('')
const reviewRating = ref(5)
const reviewComment = ref('')
const disputeReason = ref('')

const images = computed(() => parseImages(imageInput.value))
const imageInvalid = computed(() => {
  if (!images.value.length) return '至少填写 1 张交付图片链接'
  if (images.value.length > 9) return '最多 9 张交付图片'
  if (images.value.some((url) => !/^https?:\/\//.test(url))) return '图片需要是 http(s) 链接'
  return ''
})

function parseImages(raw: string): string[] {
  return raw
    .split(/[\n,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

async function runAction(name: string, task: () => Promise<unknown>, success: string): Promise<void> {
  if (busy.value) return
  busy.value = name
  actionError.value = null
  try {
    await task()
    message.success(success)
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    busy.value = ''
  }
}

async function start(): Promise<void> {
  if (!contract.value) return
  await runAction('start', () => engagementApi.start(contract.value!.contractId), '已开工，按约完成并交付')
}

async function deliver(): Promise<void> {
  if (!contract.value) return
  if (imageInvalid.value) {
    actionError.value = new ApiError(-1, imageInvalid.value)
    return
  }
  if (deliverDescription.value.length > 500) {
    actionError.value = new ApiError(-1, '交付说明不能超过 500 字')
    return
  }
  await runAction(
    'deliver',
    () =>
      engagementApi.deliver(contract.value!.contractId, {
        images: images.value,
        description: deliverDescription.value.trim(),
      }),
    '交付物已提交，等待雇主验收',
  )
}

async function cancel(): Promise<void> {
  if (!contract.value) return
  const ok = await confirm({
    title: '取消签约',
    content: '取消后该名额会释放，可能影响信誉记录。确定取消吗？',
    positiveText: '确定取消',
    danger: true,
  })
  if (!ok) return
  await runAction('cancel', () => engagementApi.cancel(contract.value!.contractId), '签约已取消')
}

async function review(): Promise<void> {
  if (!contract.value) return
  await runAction(
    'review',
    () => engagementApi.review(contract.value!.contractId, { rating: reviewRating.value, comment: reviewComment.value.trim() }),
    '评价已提交',
  )
  reviewComment.value = ''
}

async function openDispute(): Promise<void> {
  if (!contract.value) return
  const reason = disputeReason.value.trim()
  if (!reason) {
    actionError.value = new ApiError(-1, '请填写申诉理由')
    return
  }
  if (busy.value) return
  busy.value = 'dispute'
  actionError.value = null
  try {
    const disputeId = await disputeApi.open(contract.value.contractId, reason)
    message.success('申诉已发起')
    disputeReason.value = ''
    await router.push(`/worker/disputes/${disputeId}`)
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="page page--wide">
    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取履约详情" />

    <EmptyState
      v-else-if="data && !data.contract"
      title="未找到该签约"
      desc="签约编号可能不属于当前账号，或链接已失效。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/engagements">返回履约列表</RouterLink>
      </template>
    </EmptyState>

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="contract">
      <PageHeader eyebrow="ENGAGEMENT" :title="`签约 ${contract.contractId}`" :desc="`任务 ${contract.taskId}`">
        <template #actions>
          <StatusTag kind="contract" :value="contract.status" />
          <RouterLink class="btn btn--line btn--sm" :to="`/worker/tasks/${contract.taskId}`">查看任务</RouterLink>
        </template>
      </PageHeader>

      <div class="detail">
        <div class="stack stack--lg">
          <section class="card">
            <div class="card__head">
              <div>
                <h2 class="card__title">时间线</h2>
                <p class="card__sub">签约状态按流程自动流转</p>
              </div>
            </div>
            <div class="kv">
              <div class="kv__row">
                <span class="kv__k">当前状态</span>
                <span class="kv__v">{{ statusLabel('contract', contract.status) }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">生效时间</span>
                <span class="kv__v">{{ formatDateTime(contract.effectiveAt) }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">开工时间</span>
                <span class="kv__v">{{ formatDateTime(contract.startedAt) }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">交付时间</span>
                <span class="kv__v">{{ formatDateTime(contract.deliveredAt) }}</span>
              </div>
            </div>
          </section>

          <section class="card stack">
            <div class="card__head">
              <div>
                <h2 class="card__title">需求逐条对照</h2>
                <p class="card__sub">交付前请确认任务要求</p>
              </div>
            </div>

            <template v-if="task">
              <div class="kv">
                <div class="kv__row">
                  <span class="kv__k">任务标题</span>
                  <span class="kv__v">{{ task.title }}</span>
                </div>
                <div class="kv__row">
                  <span class="kv__k">单个名额报酬</span>
                  <span class="kv__v"><MoneyText :fen="task.unitPrice" /></span>
                </div>
                <div class="kv__row">
                  <span class="kv__k">履约时间</span>
                  <span class="kv__v">{{ formatDateTime(task.startTime) }} 至 {{ formatDateTime(task.endTime) }}</span>
                </div>
                <div class="kv__row">
                  <span class="kv__k">工作地点</span>
                  <span class="kv__v">{{ task.workLocation }}</span>
                </div>
                <div class="kv__row">
                  <span class="kv__k">结算说明</span>
                  <span class="kv__v">{{ task.settlementNote || '—' }}</span>
                </div>
              </div>
              <hr class="divider" />
              <p style="white-space: pre-wrap; line-height: 1.85">{{ task.description }}</p>
            </template>
            <div v-else class="notice notice--info">
              <p class="notice__eyebrow">任务详情未取到</p>
              <p class="notice__desc">
                {{ data?.taskError?.message || '任务详情暂时无法读取' }}，可点击右上角「查看任务」重试。
              </p>
            </div>
          </section>

          <section v-if="can('contract', contract.status, 'deliver')" class="card stack">
            <div class="card__head">
              <div>
                <h2 class="card__title">提交交付物</h2>
                <p class="card__sub">图片 1~9 张，文字说明不超过 500 字</p>
              </div>
            </div>
            <label class="field">
              <span class="field__label">图片链接（每行一个）</span>
              <textarea
                v-model="imageInput"
                class="textarea"
                placeholder="https://example.com/delivery-1.jpg"
                spellcheck="false"
              />
              <span class="field__hint">当前版本不支持文件上传，请填写可访问的图片链接。</span>
              <span v-if="imageInput && imageInvalid" class="field__error">{{ imageInvalid }}</span>
            </label>
            <label class="field">
              <span class="field__label">交付说明</span>
              <textarea
                v-model="deliverDescription"
                class="textarea"
                maxlength="500"
                placeholder="说明完成情况、注意事项等"
              />
              <span class="field__hint">{{ deliverDescription.length }} / 500</span>
            </label>
            <div class="form-actions">
              <button class="btn btn--ink btn--sm" type="button" :disabled="busy === 'deliver'" @click="deliver">
                {{ busy === 'deliver' ? '正在提交' : '提交交付物' }}
              </button>
            </div>
          </section>

          <section v-if="can('contract', contract.status, 'review')" class="card stack">
            <div class="card__head">
              <div>
                <h2 class="card__title">评价本次合作</h2>
                <p class="card__sub">评价会进入双方信誉记录</p>
              </div>
            </div>
            <div class="form-grid">
              <label class="field">
                <span class="field__label">评分</span>
                <select v-model.number="reviewRating" class="select">
                  <option v-for="value in 5" :key="value" :value="value">{{ value }} 分</option>
                </select>
              </label>
              <label class="field field--full">
                <span class="field__label">文字评价</span>
                <textarea v-model="reviewComment" class="textarea" maxlength="500" placeholder="可选" />
              </label>
            </div>
            <div class="form-actions">
              <button class="btn btn--ink btn--sm" type="button" :disabled="busy === 'review'" @click="review">
                {{ busy === 'review' ? '正在提交' : '提交评价' }}
              </button>
            </div>
          </section>

          <GapNotice
            title="历史交付记录未开放"
            desc="签约只保留当前交付状态，不提供历史交付记录。"
            info
          />
        </div>

        <aside class="stack">
          <section class="card stack">
            <p class="eyebrow">下一步</p>
            <template v-if="can('contract', contract.status, 'start')">
              <p class="small ink2">已录用，确认后开始履约。</p>
              <button class="btn btn--ink btn--block btn--sm" type="button" :disabled="busy === 'start'" @click="start">
                {{ busy === 'start' ? '正在开工' : '开始履约' }}
              </button>
            </template>
            <template v-else-if="contract.status === 'CONTRACT_STARTED'">
              <p class="small ink2">履约中。完成后在左侧提交交付物。</p>
            </template>
            <template v-else-if="contract.status === 'CONTRACT_DELIVERED'">
              <p class="small ink2">已交付，等待雇主验收。验收通过后进入结算。</p>
            </template>
            <template v-else-if="contract.status === 'CONTRACT_COMPLETED'">
              <p class="small ink2">签约已完成，可在左侧提交评价。</p>
            </template>
            <template v-else>
              <p class="small ink2">签约已结束，无需继续操作。</p>
            </template>
            <button
              v-if="can('contract', contract.status, 'cancel')"
              class="btn btn--line btn--block btn--sm"
              type="button"
              :disabled="busy === 'cancel'"
              @click="cancel"
            >
              {{ busy === 'cancel' ? '正在取消' : '取消签约' }}
            </button>
            <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
          </section>

          <section class="card stack">
            <p class="eyebrow">申诉</p>
            <template v-if="contract.status === 'CONTRACT_STARTED' || contract.status === 'CONTRACT_DELIVERED'">
              <p class="small ink2">对履约或验收有异议时，可发起争议，由治理台裁决。</p>
              <label class="field">
                <span class="field__label">申诉理由</span>
                <textarea v-model="disputeReason" class="textarea" maxlength="500" placeholder="说明事实与诉求" />
              </label>
              <button class="btn btn--line btn--block btn--sm" type="button" :disabled="busy === 'dispute'" @click="openDispute">
                {{ busy === 'dispute' ? '正在发起' : '发起争议' }}
              </button>
            </template>
            <p v-else class="small muted">当前状态不可发起争议。</p>
          </section>

          <section class="card stack">
            <p class="eyebrow">相关入口</p>
            <div class="stack stack--sm">
              <RouterLink class="linkbtn" to="/worker/engagements">返回履约列表</RouterLink>
              <RouterLink class="linkbtn" to="/worker/conversations">沟通消息</RouterLink>
              <RouterLink class="linkbtn" to="/worker/wallet">钱包与余额</RouterLink>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>
