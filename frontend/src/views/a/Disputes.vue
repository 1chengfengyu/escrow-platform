<script setup lang="ts">
// A-04 争议列表（FR-DISPUTE.CLOSE.02 / FR-DISPUTE.RESOLVE.01）
import { ref } from 'vue'
import { disputeApi } from '@/api/modules/dispute'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import GapNotice from '@/components/GapNotice.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'
import { statusOptions } from '@/utils/status'

const status = ref('DISPUTE_PENDING')
const overdue = ref(false)
const days = ref(7)

const statusChoices = statusOptions('dispute')

const { data, loading, error, load } = useResource(() =>
  overdue.value
    ? disputeApi.adminOverdue(days.value)
    : disputeApi.adminList(status.value || undefined),
)

function search(): void {
  void load()
}
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="DISPUTES"
      title="争议裁决"
      desc="默认列出待裁决。超期视图按等待天数倒序，用于优先处理长期未闭环的争议。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/admin/payouts">打款处置台</RouterLink>
      </template>
    </PageHeader>

    <section class="card stack">
      <form class="filters" @submit.prevent="search">
        <label class="field">
          <span class="field__label">视图</span>
          <select v-model="overdue" class="select">
            <option :value="false">按状态</option>
            <option :value="true">超期待裁决</option>
          </select>
        </label>
        <label v-if="!overdue" class="field">
          <span class="field__label">争议状态</span>
          <select v-model="status" class="select">
            <option value="">全部</option>
            <option v-for="item in statusChoices" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </label>
        <label v-else class="field">
          <span class="field__label">超期阈值（天）</span>
          <input v-model.number="days" class="input" type="number" min="1" max="90" />
        </label>
        <button class="btn btn--ink btn--sm" type="submit">查询</button>
      </form>
    </section>

    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取争议" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <div v-if="data.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>争议编号</th>
              <th>名额编号</th>
              <th>发起方</th>
              <th>理由</th>
              <th>状态</th>
              <th>撤回次数</th>
              <th>等待天数</th>
              <th>发起时间</th>
              <th class="num">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in data" :key="row.id">
              <td class="mono">{{ row.id }}</td>
              <td class="mono">{{ row.slotId }}</td>
              <td><StatusTag kind="initiator" :value="row.initiatorType" /></td>
              <td>{{ row.reason }}</td>
              <td><StatusTag kind="dispute" :value="row.status" /></td>
              <td>{{ row.withdrawCount }}</td>
              <td class="mono">{{ row.pendingDays }}</td>
              <td>{{ formatDateTime(row.createdAt) }}</td>
              <td class="num">
                <RouterLink class="btn btn--line btn--sm" :to="`/admin/disputes/${row.id}`">
                  {{ row.status === 'DISPUTE_PENDING' ? '裁决' : '查看' }}
                </RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <EmptyState v-else title="没有匹配的争议" desc="切换状态或调整超期阈值后重试。" />

      <GapNotice
        title="争议列表不分页"
        desc="列表按状态或超期阈值筛选，不提供分页；排序口径固定。"
      />
    </template>
  </div>
</template>
