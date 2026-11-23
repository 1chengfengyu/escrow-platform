<script setup lang="ts">
// A-01 治理概览（FR-ADMIN.DASHBOARD.01）
import { computed } from 'vue'
import { adminApi } from '@/api/modules/admin'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useResource } from '@/composables/useResource'

const { data, loading, error, load } = useResource(() => adminApi.dashboard())

const counts = computed(() => {
  const view = data.value
  return {
    users: Number(view?.userCount ?? 0),
    tasks: Number(view?.taskCount ?? 0),
    payouts: Number(view?.pendingPayoutCount ?? 0),
    disputes: Number(view?.pendingDisputeCount ?? 0),
    differences: Number(view?.pendingDifferenceCount ?? 0),
  }
})

const attentionTotal = computed(() => counts.value.payouts + counts.value.disputes + counts.value.differences)
const payoutShare = computed(() =>
  attentionTotal.value ? Math.round((counts.value.payouts / attentionTotal.value) * 100) : 0,
)
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="GOVERNANCE"
      title="治理概览"
      desc="平台整体情况与待办入口。金额与计数按服务端返回值展示。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/admin/audit">操作日志</RouterLink>
        <RouterLink class="btn btn--ink btn--sm" to="/admin/reconciliation">对账报告</RouterLink>
      </template>
    </PageHeader>

    <LoadingBlock v-if="loading && !data" :rows="3" label="正在读取治理概览" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <section class="kpis">
        <div class="kpi kpi--lead">
          <div class="kpi__top">
            <span class="kpi__label">待处置合计</span>
            <span class="kpi__chip kpi__chip--warm">打款 {{ counts.payouts }}</span>
          </div>
          <p class="kpi__value">{{ attentionTotal }}<span class="kpi__unit">项</span></p>
          <p class="kpi__note">待处理打款、待裁决争议与待处理差异单之和。</p>
          <div class="kpi__meter kpi__meter--accent">
            <i :style="{ width: `${payoutShare}%` }"></i>
          </div>
          <p class="kpi__note">其中打款占 {{ payoutShare }}%</p>
        </div>

        <div class="kpi">
          <span class="kpi__label">账号总数</span>
          <span class="kpi__value">{{ counts.users }}</span>
          <span class="kpi__note">含雇主、劳动者与管理员</span>
        </div>

        <div class="kpi">
          <span class="kpi__label">任务总数</span>
          <span class="kpi__value">{{ counts.tasks }}</span>
          <span class="kpi__note">全量任务，含已完成与已取消</span>
        </div>
      </section>

      <section class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">待处置清单</h2>
            <p class="card__sub">资金相关条目优先处理，避免账龄继续增长</p>
          </div>
          <span class="live">实时读取</span>
        </div>
        <div class="attention">
          <RouterLink class="attention__item" to="/admin/payouts">
            <div class="stack stack--sm">
              <span class="strong">待处理打款</span>
              <span class="tiny muted">待执行、查证与人工队列；未知态只进入查证，不重试</span>
            </div>
            <span class="attention__num" :class="{ 'is-hot': counts.payouts > 0 }">{{ counts.payouts }}</span>
          </RouterLink>
          <RouterLink class="attention__item" to="/admin/disputes">
            <div class="stack stack--sm">
              <span class="strong">待裁决争议</span>
              <span class="tiny muted">放款或退款二选一，裁决后进入结算与打款链路</span>
            </div>
            <span class="attention__num" :class="{ 'is-hot': counts.disputes > 0 }">{{ counts.disputes }}</span>
          </RouterLink>
          <RouterLink class="attention__item" to="/admin/differences">
            <div class="stack stack--sm">
              <span class="strong">待处理差异单</span>
              <span class="tiny muted">补记、冲正、差额调整或忽略，处理后有状态回显</span>
            </div>
            <span class="attention__num" :class="{ 'is-hot': counts.differences > 0 }">{{ counts.differences }}</span>
          </RouterLink>
        </div>
      </section>
    </template>
  </div>
</template>
