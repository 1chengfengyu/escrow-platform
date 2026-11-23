<script setup lang="ts">
// A-06 对账差异单（FR-RECON.HANDLE.01）
import { ref } from 'vue'
import { useMessage } from 'naive-ui'
import { settlementApi } from '@/api/modules/settlement'
import type { DifferenceResolution, ReconDifferenceView } from '@/api/types'
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
import { formatDate, formatDateTime } from '@/utils/date'
import { statusLabel, statusOptions } from '@/utils/status'

const message = useMessage()
const confirm = useConfirm()

const status = ref('SETTLEMENT_DIFF_PENDING')
const date = ref('')
const page = ref(1)

const statusChoices = statusOptions('difference')
const resolutionChoices = statusOptions('differenceResolution')

const selected = ref<ReconDifferenceView | null>(null)
const resolution = ref<DifferenceResolution>('RECON_BOOK')
const remark = ref('')
const submitting = ref(false)
const actionError = ref<ApiError | null>(null)

const { data, loading, error, load } = useResource(() =>
  settlementApi.differences(status.value || undefined, date.value || undefined, page.value, 20),
)

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

function pick(row: ReconDifferenceView): void {
  selected.value = row
  resolution.value = 'RECON_BOOK'
  remark.value = ''
  actionError.value = null
}

async function submit(): Promise<void> {
  if (submitting.value || !selected.value) return
  const verb = statusLabel('differenceResolution', resolution.value)
  const ok = await confirm({
    title: '处理差异单',
    content: `确认按「${verb}」处理差异单 ${selected.value.id}？`,
    positiveText: '确认处理',
    danger: resolution.value !== 'RECON_IGNORE',
  })
  if (!ok) return
  submitting.value = true
  actionError.value = null
  try {
    await settlementApi.handleDifference(selected.value.id, {
      resolution: resolution.value,
      remark: remark.value.trim() || undefined,
    })
    message.success('差异单已处理')
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
      eyebrow="DIFFERENCES"
      title="对账差异单"
      desc="默认只看待处理。补记、冲正、差额调整与忽略都会写审计，处理后状态即时回显。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/admin/reconciliation">对账报告</RouterLink>
      </template>
    </PageHeader>

    <section class="card stack">
      <form class="filters" @submit.prevent="search">
        <label class="field">
          <span class="field__label">状态</span>
          <select v-model="status" class="select">
            <option value="">全部</option>
            <option v-for="item in statusChoices" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">对账日</span>
          <input v-model="date" class="input" type="date" />
        </label>
        <button class="btn btn--ink btn--sm" type="submit">查询</button>
      </form>
    </section>

    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取差异单" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <div v-if="data.items.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>差异编号</th>
              <th>对账日</th>
              <th>差异类型</th>
              <th>本地流水</th>
              <th>通道流水</th>
              <th class="num">本地金额</th>
              <th class="num">通道金额</th>
              <th>状态</th>
              <th>处置方式</th>
              <th class="num">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in data.items" :key="row.id">
              <td class="mono">{{ row.id }}</td>
              <td>{{ formatDate(row.reconDate) }}</td>
              <td><StatusTag kind="differenceType" :value="row.diffType" /></td>
              <td class="mono">{{ row.localRecordNo || '—' }}</td>
              <td class="mono">{{ row.channelRecordNo || '—' }}</td>
              <td class="num"><MoneyText :fen="row.localAmount" /></td>
              <td class="num"><MoneyText :fen="row.channelAmount" /></td>
              <td><StatusTag kind="difference" :value="row.status" /></td>
              <td>
                <StatusTag v-if="row.resolution" kind="differenceResolution" :value="row.resolution" />
                <span v-else class="tiny muted">—</span>
              </td>
              <td class="num">
                <button
                  v-if="row.status === 'SETTLEMENT_DIFF_PENDING'"
                  class="btn btn--line btn--sm"
                  type="button"
                  @click="pick(row)"
                >
                  处理
                </button>
                <span v-else class="tiny muted">{{ formatDateTime(row.resolvedAt) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <EmptyState v-else title="没有匹配的差异单" desc="调整状态或对账日后重试。" />

      <DataPager :page="page" :total-pages="data.totalPages" :total="data.total" @change="goPage" />

      <section v-if="selected" class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">处理差异单 {{ selected.id }}</h2>
            <p class="card__sub">
              {{ statusLabel('differenceType', selected.diffType) }} · 对账日 {{ formatDate(selected.reconDate) }}
            </p>
          </div>
          <StatusTag kind="difference" :value="selected.status" />
        </div>

        <label class="field">
          <span class="field__label">处置方式 *</span>
          <select v-model="resolution" class="select">
            <option v-for="item in resolutionChoices" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">备注</span>
          <textarea v-model="remark" class="textarea" rows="3" maxlength="255" placeholder="可留空，将写入差异单与审计" />
        </label>

        <div class="form-actions">
          <button class="btn btn--ink btn--sm" type="button" :disabled="submitting" @click="submit">
            {{ submitting ? '提交中' : '提交处置' }}
          </button>
          <button class="btn btn--line btn--sm" type="button" @click="selected = null">取消</button>
        </div>
        <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
      </section>
    </template>
  </div>
</template>
