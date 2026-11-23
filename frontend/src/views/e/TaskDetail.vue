<script setup lang="ts">
// E-04 任务详情与名额（FR-TASK.DETAIL.02 / S-03 1 任务 N 名额）
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import { taskApi } from '@/api/modules/task'
import { ApiError, toApiError } from '@/api/http'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MoneyText from '@/components/MoneyText.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'
import { statusLabel } from '@/utils/status'

const route = useRoute()
const message = useMessage()
const confirm = useConfirm()
const id = computed(() => String(route.params.id))

const { data, loading, error, load } = useResource(() => taskApi.detail(id.value))

const busy = ref('')
const actionError = ref<ApiError | null>(null)

const terminal = computed(() => data.value?.status === 'TASK_COMPLETED' || data.value?.status === 'TASK_CANCELLED' || data.value?.status === 'TASK_CLOSED')

async function cancel(): Promise<void> {
  if (busy.value || !data.value) return
  const ok = await confirm({
    title: '撤销任务',
    content: '撤单会退回未占用名额的冻结金额。仍有名额未走到终态时无法撤单。确定撤单吗？',
    positiveText: '确定撤单',
    danger: true,
  })
  if (!ok) return
  busy.value = 'cancel'
  actionError.value = null
  try {
    await taskApi.cancel(id.value)
    message.success('任务已撤销')
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    busy.value = ''
  }
}

async function expire(): Promise<void> {
  if (busy.value || !data.value) return
  const ok = await confirm({
    title: '到期收口',
    content: '仅当全部名额已终态时可收口。确定执行吗？',
    positiveText: '确定收口',
  })
  if (!ok) return
  busy.value = 'expire'
  actionError.value = null
  try {
    await taskApi.expire(id.value)
    message.success('任务已到期收口')
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="page page--wide">
    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取任务" />

    <EmptyState v-else-if="error && error.isNotFound" title="任务不存在或无权查看" desc="草稿只对发布者可见。">
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/employer/tasks">返回任务入口</RouterLink>
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

      <div v-if="!data.partyView" class="notice notice--info">
        <p class="notice__eyebrow">非发布者视角</p>
        <p class="notice__desc">当前账号不是该任务的发布者，资金与撤单信息已隐藏。</p>
      </div>

      <div class="detail">
        <div class="stack stack--lg">
          <section class="card">
            <div class="row row--between row--wrap">
              <div>
                <p class="eyebrow">单个名额报酬</p>
                <p class="stat__value"><MoneyText :fen="data.unitPrice" /></p>
              </div>
              <div class="right">
                <p class="eyebrow">冻结金额</p>
                <p class="stat__value">
                  <MoneyText v-if="data.escrowAmount" :fen="data.escrowAmount" />
                  <span v-else>—</span>
                </p>
              </div>
              <div class="right">
                <p class="eyebrow">可报名名额</p>
                <p class="stat__value">{{ data.remainingSlots }}</p>
              </div>
            </div>
            <hr class="divider" />
            <div class="kv">
              <div class="kv__row">
                <span class="kv__k">履约时间</span>
                <span class="kv__v">{{ formatDateTime(data.startTime) }} 至 {{ formatDateTime(data.endTime) }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">发布时间</span>
                <span class="kv__v">{{ formatDateTime(data.publishedAt) }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">招募状态</span>
                <span class="kv__v">{{ data.recruitingClosed ? '已停止招募' : '招募中' }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">结算说明</span>
                <span class="kv__v">{{ data.settlementNote || '—' }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">争议 / 结算异常</span>
                <span class="kv__v">
                  {{ data.hasOpenDispute ? '存在未处理争议' : '无争议' }} ·
                  {{ data.hasSettlementAbnormal ? '存在结算异常' : '结算正常' }}
                </span>
              </div>
            </div>
          </section>

          <section class="card stack">
            <div class="card__head">
              <div>
                <h2 class="card__title">任务描述</h2>
                <p class="card__sub">劳动者报名时看到的内容</p>
              </div>
            </div>
            <p style="white-space: pre-wrap; line-height: 1.85">{{ data.description }}</p>
          </section>

          <section class="card stack">
            <div class="card__head">
              <div>
                <h2 class="card__title">名额矩阵</h2>
                <p class="card__sub">每个名额独立流转，互不协作</p>
              </div>
              <span class="tiny muted">共 {{ data.slotCount }} 个</span>
            </div>
            <div class="slots">
              <div v-for="slot in data.slotOccupancy" :key="slot.seqNo" class="slot">
                <span class="slot__no">名额 {{ slot.seqNo }}</span>
                <StatusTag kind="slot" :value="slot.status" />
              </div>
            </div>
            <div class="cluster">
              <RouterLink
                class="btn btn--line btn--sm"
                :to="`/employer/tasks/${data.id}/applications`"
              >
                查看报名
              </RouterLink>
            </div>
          </section>
        </div>

        <aside class="stack">
          <section v-if="data.partyView" class="card stack">
            <p class="eyebrow">任务操作</p>
            <template v-if="terminal">
              <p class="small muted">任务已结束，无需继续操作。</p>
            </template>
            <template v-else>
              <p class="small ink2">撤单会退回未占用名额的冻结金额；存在非终态名额时会被拒绝。</p>
              <button class="btn btn--line btn--block btn--sm" type="button" :disabled="busy !== ''" @click="cancel">
                {{ busy === 'cancel' ? '正在撤单' : '撤销任务' }}
              </button>
              <button class="btn btn--line btn--block btn--sm" type="button" :disabled="busy !== ''" @click="expire">
                {{ busy === 'expire' ? '正在收口' : '到期收口' }}
              </button>
            </template>
            <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
          </section>

          <section class="card stack">
            <p class="eyebrow">关联入口</p>
            <div class="stack stack--sm">
              <RouterLink class="linkbtn" :to="`/employer/tasks/${data.id}/applications`">报名管理</RouterLink>
              <RouterLink class="linkbtn" to="/employer/engagements">验收入口</RouterLink>
              <RouterLink class="linkbtn" to="/employer/wallet">钱包与余额</RouterLink>
            </div>
          </section>

          <section class="card stack">
            <p class="eyebrow">状态参考</p>
            <p class="small ink2">
              任务当前为「{{ statusLabel('task', data.status) }}」。名额状态以矩阵为准，资金动作以余额与结算页为准。
            </p>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>
