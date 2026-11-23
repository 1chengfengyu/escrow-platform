<script setup lang="ts">
// A-03 资质审核（FR-ADMIN.QUAL.01）
import { ref } from 'vue'
import { useMessage } from 'naive-ui'
import { adminApi } from '@/api/modules/admin'
import type { QualificationListItem } from '@/api/types'
import { ApiError, toApiError } from '@/api/http'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import GapNotice from '@/components/GapNotice.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'

const message = useMessage()
const confirm = useConfirm()

const status = ref('PENDING')
const selected = ref<QualificationListItem | null>(null)
const decision = ref<'APPROVED' | 'REJECTED'>('APPROVED')
const reason = ref('')
const submitting = ref(false)
const actionError = ref<ApiError | null>(null)

const statusChoices = [
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
]

const { data, loading, error, load } = useResource(() => adminApi.qualifications(status.value))

function reload(): void {
  selected.value = null
  reason.value = ''
  actionError.value = null
  void load()
}

function pick(row: QualificationListItem): void {
  selected.value = row
  decision.value = 'APPROVED'
  reason.value = ''
  actionError.value = null
}

async function submit(): Promise<void> {
  if (submitting.value || !selected.value) return
  const trimmed = reason.value.trim()
  if (decision.value === 'REJECTED' && !trimmed) {
    actionError.value = new ApiError(-1, '驳回必须填写理由')
    return
  }
  const verb = decision.value === 'APPROVED' ? '通过' : '驳回'
  const ok = await confirm({
    title: `${verb}资质`,
    content: `确认${verb}「${selected.value.subjectName}」的资质？${trimmed ? `理由：${trimmed}` : ''}`,
    positiveText: `确认${verb}`,
    danger: decision.value === 'REJECTED',
  })
  if (!ok) return
  submitting.value = true
  actionError.value = null
  try {
    await adminApi.reviewQualification(selected.value.userId, {
      decision: decision.value,
      reason: trimmed || undefined,
    })
    message.success(`已${verb}`)
    selected.value = null
    reason.value = ''
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
      eyebrow="QUALIFICATIONS"
      title="资质审核"
      desc="按状态列出雇主资质，固定最多 50 条。驳回必须写理由，通过时备注可空。"
    >
      <template #actions>
        <select v-model="status" class="select" @change="reload">
          <option v-for="item in statusChoices" :key="item.value" :value="item.value">{{ item.label }}</option>
        </select>
      </template>
    </PageHeader>

    <LoadingBlock v-if="loading && !data" :rows="4" label="正在读取资质" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <div v-if="data.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>用户编号</th>
              <th>昵称</th>
              <th>主体类型</th>
              <th>主体名称</th>
              <th>证件尾号</th>
              <th>提交时间</th>
              <th class="num">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in data" :key="row.userId">
              <td class="mono">{{ row.userId }}</td>
              <td>{{ row.nickname }}</td>
              <td><StatusTag kind="subject" :value="row.subjectType" /></td>
              <td>{{ row.subjectName }}</td>
              <td class="mono">{{ row.idLast4 }}</td>
              <td>{{ formatDateTime(row.submittedAt) }}</td>
              <td class="num">
                <button class="btn btn--line btn--sm" type="button" @click="pick(row)">
                  {{ status === 'PENDING' ? '审核' : '查看' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <EmptyState v-else title="当前状态下没有资质记录" desc="切换状态筛选或等待雇主提交。" />

      <section v-if="selected" class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">审核：{{ selected.subjectName }}</h2>
            <p class="card__sub">用户 {{ selected.nickname }} · 编号 {{ selected.userId }}</p>
          </div>
          <StatusTag kind="subject" :value="selected.subjectType" />
        </div>

        <div class="tabs">
          <button
            class="tab"
            :class="{ 'is-active': decision === 'APPROVED' }"
            type="button"
            @click="decision = 'APPROVED'"
          >
            通过
          </button>
          <button
            class="tab"
            :class="{ 'is-active': decision === 'REJECTED' }"
            type="button"
            @click="decision = 'REJECTED'"
          >
            驳回
          </button>
        </div>

        <label class="field">
          <span class="field__label">{{ decision === 'REJECTED' ? '驳回理由 *' : '审核备注' }}</span>
          <textarea
            v-model="reason"
            class="textarea"
            maxlength="255"
            rows="3"
            :placeholder="decision === 'REJECTED' ? '说明不通过的原因，将写入站内通知' : '可留空'"
          />
          <span class="field__hint">驳回时 trim 后 1~255 字。</span>
        </label>

        <div class="form-actions">
          <button class="btn btn--ink btn--sm" type="button" :disabled="submitting" @click="submit">
            {{ submitting ? '提交中' : '提交审核结论' }}
          </button>
          <button class="btn btn--line btn--sm" type="button" @click="selected = null">取消</button>
        </div>
        <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
      </section>

      <GapNotice
        title="资质列表不分页、无详情"
        desc="列表按状态最多返回 50 条，不提供分页与单条详情；本页不展示证件照片与完整号码。"
      />
    </template>
  </div>
</template>
