<script setup lang="ts">
// A-07 打款处置台（FR-PAY.MANUAL.01~.03 / FR-JOB.AGING.01）
import { computed, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { settlementApi } from '@/api/modules/settlement'
import type { PayoutAdminView, PayoutManualDecisionType } from '@/api/types'
import { ApiError, toApiError } from '@/api/http'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MoneyText from '@/components/MoneyText.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'
import { yuanToFen } from '@/utils/money'
import { statusLabel, statusOptions } from '@/utils/status'

const message = useMessage()
const confirm = useConfirm()

const status = ref('')
const page = ref(1)
const statusChoices = statusOptions('payout')
const decisionChoices = statusOptions('payoutDecision')

const selected = ref<PayoutAdminView | null>(null)
const decision = ref<PayoutManualDecisionType>('CONFIRM_ACTUAL')
const actualYuan = ref('')
const remark = ref('')
const submitting = ref(false)
const actionError = ref<ApiError | null>(null)

const { data, loading, error, load } = useResource(async () => {
  const [listResult, agingResult] = await Promise.allSettled([
    settlementApi.payouts(status.value || undefined, page.value, 20),
    settlementApi.aging(),
  ])
  if (listResult.status === 'rejected') throw listResult.reason
  return {
    list: listResult.value,
    alerts: agingResult.status === 'fulfilled' ? agingResult.value : [],
  }
})

const needActualAmount = computed(() => decision.value === 'CONFIRM_ACTUAL')

const canDecide = (row: PayoutAdminView): boolean =>
  row.status === 'PAYOUT_PENDING_VERIFICATION' || row.status === 'PAYOUT_MANUAL_REVIEW'

function search(): void {
  page.value = 1
  selected.value = null
  actionError.value = null
  void load()
}

function goPage(next: number): void {
  page.value = next
  void load()
}

function pick(row: PayoutAdminView): void {
  selected.value = row
  decision.value = 'CONFIRM_ACTUAL'
  actualYuan.value = ''
  remark.value = ''
  actionError.value = null
}

function setDecision(value: string): void {
  decision.value = value as PayoutManualDecisionType
}

async function submit(): Promise<void> {
  if (submitting.value || !selected.value) return
  let actualAmount: number | undefined
  if (needActualAmount.value) {
    const fen = yuanToFen(actualYuan.value)
    if (fen === null) {
      actionError.value = new ApiError(-1, '请填写有效的实际到账金额（元）')
      return
    }
    actualAmount = fen
  }
  const verb = statusLabel('payoutDecision', decision.value)
  const ok = await confirm({
    title: '提交打款处置',
    content: `确认对打款单 ${selected.value.requestNo} 执行「${verb}」？`,
    positiveText: '确认提交',
    danger: decision.value === 'TO_DIFFERENCE',
  })
  if (!ok) return
  submitting.value = true
  actionError.value = null
  try {
    await settlementApi.decide(selected.value.id, {
      decision: decision.value,
      actualAmount,
      remark: remark.value.trim() || undefined,
    })
    message.success('处置已提交')
    selected.value = null
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="PAYOUTS"
      title="打款处置台"
      desc="未知态不等于失败。查证、人工与待执行队列按三选一结论处置，重试由状态机控制。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/admin/differences">差异单</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/admin/disputes">争议裁决</RouterLink>
      </template>
    </PageHeader>

    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取打款单" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <section v-if="data.alerts.length" class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">账龄告警</h2>
            <p class="card__sub">长时间停在执行中或待查证的打款单</p>
          </div>
          <span class="count">{{ data.alerts.length }} 条</span>
        </div>
        <div class="list">
          <article v-for="alert in data.alerts" :key="alert.payoutId" class="list__item">
            <div class="row row--between row--wrap">
              <p class="strong mono">{{ alert.requestNo }}</p>
              <StatusTag kind="payout" :value="alert.status" />
            </div>
            <p class="tiny muted">
              结算单 {{ alert.settlementId }} · 金额 <MoneyText :fen="alert.amount" /> · 已停留
              {{ alert.ageMinutes }} 分钟 · 进入于 {{ formatDateTime(alert.statusEnteredAt) }}
            </p>
          </article>
        </div>
      </section>

      <section class="card stack">
        <form class="filters" @submit.prevent="search">
          <label class="field">
            <span class="field__label">打款状态</span>
            <select v-model="status" class="select">
              <option value="">全部</option>
              <option v-for="item in statusChoices" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
          <button class="btn btn--ink btn--sm" type="submit">查询</button>
        </form>
      </section>

      <div v-if="data.list.items.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>打款编号</th>
              <th>请求号</th>
              <th>结算单</th>
              <th>第几次</th>
              <th class="num">金额</th>
              <th class="num">实际到账</th>
              <th>状态</th>
              <th>通道结果</th>
              <th>停留分钟</th>
              <th>进入时间</th>
              <th class="num">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in data.list.items" :key="row.id">
              <td class="mono">{{ row.id }}</td>
              <td class="mono">{{ row.requestNo }}</td>
              <td class="mono">{{ row.settlementId }}</td>
              <td>{{ row.attemptSeq }}</td>
              <td class="num"><MoneyText :fen="row.amount" /></td>
              <td class="num"><MoneyText :fen="row.actualAmount" /></td>
              <td><StatusTag kind="payout" :value="row.status" /></td>
              <td class="mono">{{ row.channelResult || '—' }}</td>
              <td class="mono">{{ row.ageMinutes }}</td>
              <td>{{ formatDateTime(row.statusEnteredAt) }}</td>
              <td class="num">
                <button v-if="canDecide(row)" class="btn btn--line btn--sm" type="button" @click="pick(row)">
                  处置
                </button>
                <span v-else class="tiny muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <EmptyState v-else title="没有匹配的打款单" desc="切换状态后重试。" />

      <DataPager :page="page" :total-pages="data.list.totalPages" :total="data.list.total" @change="goPage" />

      <section v-if="selected" class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">处置打款单 {{ selected.requestNo }}</h2>
            <p class="card__sub">
              金额 <MoneyText :fen="selected.amount" /> · 状态 {{ statusLabel('payout', selected.status) }}
            </p>
          </div>
          <StatusTag kind="payout" :value="selected.status" />
        </div>

        <div class="tabs">
          <button
            v-for="item in decisionChoices"
            :key="item.value"
            class="tab"
            :class="{ 'is-active': decision === item.value }"
            type="button"
            @click="setDecision(item.value)"
          >
            {{ item.label }}
          </button>
        </div>

        <label v-if="needActualAmount" class="field">
          <span class="field__label">实际到账金额（元）*</span>
          <input v-model="actualYuan" class="input" inputmode="decimal" placeholder="例如 1280.00" />
          <span class="field__hint">按分入库，提交时四舍五入到分。</span>
        </label>

        <label class="field">
          <span class="field__label">备注</span>
          <textarea v-model="remark" class="textarea" rows="3" maxlength="255" placeholder="可留空，将写入审计" />
        </label>

        <div class="form-actions">
          <button class="btn btn--ink btn--sm" type="button" :disabled="submitting" @click="submit">
            {{ submitting ? '提交中' : '提交处置' }}
          </button>
          <button class="btn btn--line btn--sm" type="button" @click="selected = null">取消</button>
        </div>
        <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
      </section>

      <div class="notice notice--info">
        <p class="notice__eyebrow">状态机口径</p>
        <p class="notice__desc">
          不提供独立的重试操作；失败与未知态只能通过三选一处置结论推进，避免把未知态当作失败直接重发造成重复打款。
        </p>
      </div>
    </template>
  </div>
</template>
