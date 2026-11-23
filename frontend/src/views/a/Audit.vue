<script setup lang="ts">
// A-09 操作日志（FR-ADMIN.AUDIT.01）
import { ref } from 'vue'
import { adminApi, type AuditQuery } from '@/api/modules/admin'
import { ApiError, toApiError } from '@/api/http'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'

const bizType = ref('')
const bizNo = ref('')
const operatorId = ref('')
const from = ref('')
const to = ref('')
const page = ref(1)
const exporting = ref(false)
const actionError = ref<ApiError | null>(null)

function toIso(value: string): string | undefined {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  return trimmed.length === 16 ? `${trimmed}:00` : trimmed
}

function query(): AuditQuery {
  return {
    bizType: bizType.value.trim() || undefined,
    bizNo: bizNo.value.trim() || undefined,
    operatorId: operatorId.value.trim() || undefined,
    from: toIso(from.value),
    to: toIso(to.value),
    page: page.value,
    size: 20,
  }
}

const { data, loading, error, load } = useResource(() => adminApi.audit(query()))

function search(): void {
  page.value = 1
  actionError.value = null
  void load()
}

function goPage(next: number): void {
  page.value = next
  void load()
}

async function exportCsv(): Promise<void> {
  if (exporting.value) return
  exporting.value = true
  actionError.value = null
  try {
    const blob = await adminApi.exportAudit({ ...query(), page: undefined, size: undefined })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `audit-${Date.now()}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="AUDIT"
      title="操作日志"
      desc="按业务类型、业务单号、操作人与时间范围过滤。导出沿用当前筛选条件，不含分页。"
    >
      <template #actions>
        <button class="btn btn--line btn--sm" type="button" :disabled="exporting" @click="exportCsv">
          {{ exporting ? '导出中' : '导出 CSV' }}
        </button>
      </template>
    </PageHeader>

    <section class="card stack">
      <form class="filters" @submit.prevent="search">
        <label class="field">
          <span class="field__label">业务类型</span>
          <input v-model="bizType" class="input" placeholder="如 SETTLEMENT / PAYOUT" />
        </label>
        <label class="field field--wide">
          <span class="field__label">业务单号</span>
          <input v-model="bizNo" class="input" placeholder="任务号 / 结算单号 / 打款单号" />
        </label>
        <label class="field">
          <span class="field__label">操作人编号</span>
          <input v-model="operatorId" class="input" inputmode="numeric" placeholder="可空" />
        </label>
        <label class="field">
          <span class="field__label">起始时间</span>
          <input v-model="from" class="input" type="datetime-local" />
        </label>
        <label class="field">
          <span class="field__label">结束时间</span>
          <input v-model="to" class="input" type="datetime-local" />
        </label>
        <button class="btn btn--ink btn--sm" type="submit">查询</button>
      </form>
      <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
    </section>

    <LoadingBlock v-if="loading && !data" :rows="6" label="正在读取审计流水" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <div v-if="data.items.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>流水号</th>
              <th>业务类型</th>
              <th>业务单号</th>
              <th>动作</th>
              <th>状态变化</th>
              <th>操作人</th>
              <th>角色</th>
              <th>详情</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in data.items" :key="row.id">
              <td class="mono">{{ row.id }}</td>
              <td class="mono">{{ row.bizType }}</td>
              <td class="mono">{{ row.bizNo }}</td>
              <td>{{ row.action }}</td>
              <td class="mono">{{ row.fromStatus || '—' }} → {{ row.toStatus || '—' }}</td>
              <td class="mono">{{ row.operatorId }}</td>
              <td>{{ row.operatorRole }}</td>
              <td>{{ row.detail || '—' }}</td>
              <td>{{ formatDateTime(row.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <EmptyState v-else title="没有匹配的审计流水" desc="放宽时间范围或清空条件后重试。" />

      <DataPager :page="page" :total-pages="data.totalPages" :total="data.total" @change="goPage" />
    </template>
  </div>
</template>
