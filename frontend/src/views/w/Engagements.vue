<script setup lang="ts">
// W-05 履约列表（FR-SIGN.START.* / FR-DELIVER.*）
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { engagementApi } from '@/api/modules/engagement'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { formatDate } from '@/utils/date'
import { statusLabel } from '@/utils/status'

const route = useRoute()

const tabs = [
  { value: '', label: '全部' },
  { value: 'CONTRACT_ACTIVE', label: statusLabel('contract', 'CONTRACT_ACTIVE') },
  { value: 'CONTRACT_STARTED', label: statusLabel('contract', 'CONTRACT_STARTED') },
  { value: 'CONTRACT_DELIVERED', label: statusLabel('contract', 'CONTRACT_DELIVERED') },
  { value: 'CONTRACT_COMPLETED', label: statusLabel('contract', 'CONTRACT_COMPLETED') },
  { value: 'CONTRACT_CANCELLED', label: statusLabel('contract', 'CONTRACT_CANCELLED') },
]

const active = ref(typeof route.query.status === 'string' ? route.query.status : '')
const page = ref(1)
const size = 10

const { data, loading, error, load } = useResource(() => engagementApi.contracts(1, 100))

const all = computed(() => data.value?.items ?? [])
const filtered = computed(() => (active.value ? all.value.filter((row) => row.status === active.value) : all.value))
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / size)))
const rows = computed(() => filtered.value.slice((page.value - 1) * size, page.value * size))

function countOf(status: string): number {
  return status ? all.value.filter((row) => row.status === status).length : all.value.length
}

watch(active, () => {
  page.value = 1
})
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="ENGAGEMENTS"
      title="我的履约"
      desc="被录用后形成签约。待开始可开工，履约中可交付，已交付等待雇主验收。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/tasks">找任务</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/disputes">争议申诉</RouterLink>
      </template>
    </PageHeader>

    <div class="tabs">
      <button
        v-for="tab in tabs"
        :key="tab.value"
        class="tab"
        :class="{ 'is-active': active === tab.value }"
        type="button"
        @click="active = tab.value"
      >
        {{ tab.label }}
        <span class="mono tiny muted">{{ countOf(tab.value) }}</span>
      </button>
    </div>

    <LoadingBlock v-if="loading && !data" :rows="4" label="正在读取签约" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else>
      <div v-if="Number(data?.total ?? 0) > 100" class="notice notice--info">
        <p class="notice__eyebrow">分页提示</p>
        <p class="notice__desc">本页只展示最近 100 条签约，更多记录请从通知或任务进入。</p>
      </div>

      <div v-if="rows.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>签约编号</th>
              <th>任务编号</th>
              <th>状态</th>
              <th>生效时间</th>
              <th>开工时间</th>
              <th>交付时间</th>
              <th class="num">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.contractId">
              <td class="mono">{{ row.contractId }}</td>
              <td class="mono">{{ row.taskId }}</td>
              <td><StatusTag kind="contract" :value="row.status" /></td>
              <td>{{ formatDate(row.effectiveAt) }}</td>
              <td>{{ formatDate(row.startedAt) }}</td>
              <td>{{ formatDate(row.deliveredAt) }}</td>
              <td class="num">
                <RouterLink class="btn btn--line btn--sm" :to="`/worker/engagements/${row.contractId}`">
                  查看详情
                </RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <EmptyState
        v-else
        title="没有匹配的履约记录"
        desc="被录用后，签约会出现在这里。也可以先去任务广场报名。"
      >
        <template #actions>
          <RouterLink class="btn btn--ink btn--sm" to="/worker/tasks">去任务广场</RouterLink>
        </template>
      </EmptyState>

      <DataPager :page="page" :total-pages="totalPages" :total="filtered.length" @change="page = $event" />
    </template>
  </div>
</template>
