<script setup lang="ts">
// A-05 争议裁决（FR-DISPUTE.RESOLVE.01）
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import { disputeApi } from '@/api/modules/dispute'
import type { DisputeResolution } from '@/api/types'
import { ApiError, toApiError } from '@/api/http'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'

const route = useRoute()
const message = useMessage()
const confirm = useConfirm()

const disputeId = String(route.params.id ?? '')

const resolution = ref<DisputeResolution>('RELEASE')
const basis = ref('')
const submitting = ref(false)
const actionError = ref<ApiError | null>(null)
const outcome = ref<{ payoutId: string | null } | null>(null)

const { data, loading, error, load } = useResource(() => disputeApi.adminDetail(disputeId))

const canResolve = computed(() => data.value?.status === 'DISPUTE_PENDING')

async function submit(): Promise<void> {
  if (submitting.value) return
  const trimmed = basis.value.trim()
  if (!trimmed) {
    actionError.value = new ApiError(-1, '请填写裁决依据')
    return
  }
  const verb = resolution.value === 'RELEASE' ? '放款' : '退款'
  const ok = await confirm({
    title: `裁决${verb}`,
    content: `确认对争议 ${disputeId} 判定${verb}？裁决后不可撤销。`,
    positiveText: `确认${verb}`,
    danger: resolution.value === 'REFUND',
  })
  if (!ok) return
  submitting.value = true
  actionError.value = null
  try {
    const result = await disputeApi.adminResolve(disputeId, { resolution: resolution.value, basis: trimmed })
    outcome.value = { payoutId: result.payoutId ?? null }
    message.success(`已判定${verb}`)
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
    <PageHeader eyebrow="DECISION" title="争议裁决" :desc="`争议编号 ${disputeId}`">
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/admin/disputes">返回列表</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/admin/payouts">打款处置台</RouterLink>
      </template>
    </PageHeader>

    <LoadingBlock v-if="loading && !data" :rows="4" label="正在读取争议详情" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <EmptyState v-else-if="!data" title="没有找到该争议" desc="确认争议编号是否正确。" />

    <template v-else>
      <div v-if="outcome" class="notice notice--info">
        <p class="notice__eyebrow">裁决已提交</p>
        <p class="notice__desc">
          裁决结果已落库。
          <template v-if="outcome.payoutId">
            生成打款单 <span class="mono">{{ outcome.payoutId }}</span>，
            <RouterLink class="linkbtn" to="/admin/payouts">去打款处置台</RouterLink>
          </template>
          <template v-else>本次裁决没有生成新的打款单。</template>
        </p>
      </div>

      <section class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">争议信息</h2>
            <p class="card__sub">名额 {{ data.slotId }} · 发起时间 {{ formatDateTime(data.createdAt) }}</p>
          </div>
          <StatusTag kind="dispute" :value="data.status" />
        </div>
        <div class="kv">
          <div class="kv__row">
            <span class="kv__k">发起方</span>
            <span class="kv__v"><StatusTag kind="initiator" :value="data.initiatorType" /></span>
          </div>
          <div class="kv__row">
            <span class="kv__k">争议理由</span>
            <span class="kv__v">{{ data.reason }}</span>
          </div>
          <div class="kv__row">
            <span class="kv__k">撤回次数</span>
            <span class="kv__v">{{ data.withdrawCount }}</span>
          </div>
          <div class="kv__row">
            <span class="kv__k">裁决结果</span>
            <span class="kv__v">
              <StatusTag v-if="data.resolution" kind="resolution" :value="data.resolution" />
              <span v-else>—</span>
            </span>
          </div>
          <div class="kv__row">
            <span class="kv__k">裁决依据</span>
            <span class="kv__v">{{ data.resolutionBasis || '—' }}</span>
          </div>
          <div class="kv__row">
            <span class="kv__k">裁决时间</span>
            <span class="kv__v">{{ formatDateTime(data.resolvedAt) }}</span>
          </div>
        </div>
      </section>

      <section class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">双方证据</h2>
            <p class="card__sub">共 {{ data.evidence.length }} 条，按提交顺序展示</p>
          </div>
        </div>
        <div v-if="data.evidence.length" class="list">
          <article v-for="item in data.evidence" :key="item.id" class="list__item">
            <div class="row row--between row--wrap">
              <p class="strong">
                <StatusTag kind="initiator" :value="item.submitterType" />
                <span class="mono" style="margin-left: 8px">#{{ item.id }}</span>
              </p>
              <span class="tiny muted">{{ formatDateTime(item.createdAt) }}</span>
            </div>
            <p>{{ item.content || '（无文字说明）' }}</p>
            <div v-if="item.imageUrls.length" class="cluster">
              <a
                v-for="(url, index) in item.imageUrls"
                :key="url"
                class="linkbtn"
                :href="url"
                target="_blank"
                rel="noreferrer"
              >
                证据图 {{ index + 1 }}
              </a>
            </div>
          </article>
        </div>
        <EmptyState v-else title="双方都还没有提交证据" desc="裁决前可要求补充，或按现有信息判断。" />
      </section>

      <section v-if="canResolve" class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">给出裁决</h2>
            <p class="card__sub">放款进入结算与打款链路；退款回到雇主账户</p>
          </div>
        </div>

        <div class="tabs">
          <button class="tab" :class="{ 'is-active': resolution === 'RELEASE' }" type="button" @click="resolution = 'RELEASE'">
            判定放款
          </button>
          <button class="tab" :class="{ 'is-active': resolution === 'REFUND' }" type="button" @click="resolution = 'REFUND'">
            判定退款
          </button>
        </div>

        <label class="field">
          <span class="field__label">裁决依据 *</span>
          <textarea v-model="basis" class="textarea" rows="4" maxlength="500" placeholder="写明事实认定与依据，将写入审计流水" />
        </label>

        <div class="form-actions">
          <button class="btn btn--ink btn--sm" type="button" :disabled="submitting" @click="submit">
            {{ submitting ? '提交中' : '提交裁决' }}
          </button>
        </div>
        <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
      </section>

      <div v-else class="notice notice--info">
        <p class="notice__eyebrow">不可裁决</p>
        <p class="notice__desc">只有「待裁决」状态可以提交结论；已撤回或已裁决的争议仅可查看。</p>
      </div>
    </template>
  </div>
</template>
