<script setup lang="ts">
// A-08 对账报告（FR-RECON.REPORT.01）
import { ref } from 'vue'
import { useMessage } from 'naive-ui'
import { settlementApi } from '@/api/modules/settlement'
import type { ReconRun } from '@/api/types'
import { ApiError, toApiError } from '@/api/http'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MoneyText from '@/components/MoneyText.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useResource } from '@/composables/useResource'
import { formatDate, today } from '@/utils/date'

const message = useMessage()
const confirm = useConfirm()

const date = ref(today())
const activeDate = ref(today())
const running = ref(false)
const runResult = ref<ReconRun | null>(null)
const actionError = ref<ApiError | null>(null)

const { data, loading, error, load } = useResource(() => settlementApi.reconReport(activeDate.value))

function search(): void {
  activeDate.value = date.value || today()
  runResult.value = null
  actionError.value = null
  void load()
}

async function runRecon(): Promise<void> {
  if (running.value) return
  const ok = await confirm({
    title: '触发对账',
    content: `确认对 ${date.value || today()} 执行一次对账？将生成或更新对账批次与差异单。`,
    positiveText: '确认执行',
  })
  if (!ok) return
  running.value = true
  actionError.value = null
  try {
    runResult.value = await settlementApi.runRecon(date.value || today())
    activeDate.value = date.value || today()
    message.success('对账已执行')
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    running.value = false
  }
}
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="RECONCILIATION"
      title="对账报告"
      desc="按对账日查看日报，或手动触发一次对账。恒等式校验由服务端计算，本页展示结果。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/admin/differences">差异单</RouterLink>
      </template>
    </PageHeader>

    <section class="card stack">
      <form class="filters" @submit.prevent="search">
        <label class="field">
          <span class="field__label">对账日期</span>
          <input v-model="date" class="input" type="date" />
        </label>
        <button class="btn btn--ink btn--sm" type="submit">查询日报</button>
        <button class="btn btn--line btn--sm" type="button" :disabled="running" @click="runRecon">
          {{ running ? '执行中' : '触发对账' }}
        </button>
      </form>
      <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
    </section>

    <div v-if="runResult" class="notice notice--info">
      <p class="notice__eyebrow">本次对账批次</p>
      <p class="notice__desc">
        批次 <span class="mono">{{ runResult.batchId }}</span> · 本地 {{ runResult.localCount }} 笔 · 通道
        {{ runResult.channelCount }} 笔 · 差异 {{ runResult.diffCount }} 笔 · 差异金额
        <MoneyText :fen="runResult.diffAmount" /> · 恒等式{{ runResult.identityOk ? '平衡' : '不平衡' }}
      </p>
    </div>

    <LoadingBlock v-if="loading && !data" :rows="4" label="正在读取对账日报" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <EmptyState v-else-if="!data" title="该日期没有对账批次" desc="可以先触发一次对账，再回来查看日报。" />

    <template v-else>
      <section class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">{{ formatDate(data.reconDate) }} 对账日报</h2>
            <p class="card__sub">
              批次 <span class="mono">{{ data.batchId ?? '—' }}</span> · 状态 {{ data.status }}
            </p>
          </div>
          <span class="stamp" :class="data.identityOk ? 'stamp--done' : 'stamp--danger'">
            {{ data.identityOk ? '恒等式平衡' : '恒等式不平衡' }}
          </span>
        </div>

        <div class="stats">
          <div class="stat">
            <span class="stat__label">期初余额</span>
            <span class="stat__value"><MoneyText :fen="data.openingTotal" /></span>
          </div>
          <div class="stat">
            <span class="stat__label">本期发生额</span>
            <span class="stat__value"><MoneyText :fen="data.turnover" /></span>
          </div>
          <div class="stat">
            <span class="stat__label">期末余额</span>
            <span class="stat__value"><MoneyText :fen="data.closingTotal" /></span>
          </div>
          <div class="stat">
            <span class="stat__label">差异笔数</span>
            <span class="stat__value">{{ data.diffCount }}</span>
            <span class="stat__note">差异金额 <MoneyText :fen="data.diffAmount" /></span>
          </div>
          <div class="stat">
            <span class="stat__label">待处理差异</span>
            <span class="stat__value">{{ data.pendingCount }}</span>
            <span class="stat__note">处理入口在差异单页</span>
          </div>
        </div>

        <div class="notice notice--info">
          <p class="notice__eyebrow">口径</p>
          <p class="notice__desc">
            期初 + 本期发生额 = 期末。恒等式不平衡说明存在未落账或通道缺失，需到差异单逐笔处理。
          </p>
        </div>
      </section>
    </template>
  </div>
</template>
