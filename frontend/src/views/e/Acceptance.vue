<script setup lang="ts">
// E-07 验收（FR-ACCEPT.PASS.01 / FR-ACCEPT.REJECT.01）
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import { engagementApi } from '@/api/modules/engagement'
import { ApiError, toApiError } from '@/api/http'
import type { AcceptanceView } from '@/api/types'
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useConfirm } from '@/composables/useConfirm'
import { statusLabel } from '@/utils/status'

const route = useRoute()
const message = useMessage()
const confirm = useConfirm()

const contractId = computed(() => String(route.params.id))
const decision = ref<'PASS' | 'REJECT'>('PASS')
const reason = ref('')
const submitting = ref(false)
const actionError = ref<ApiError | null>(null)
const result = ref<AcceptanceView | null>(null)

async function submit(): Promise<void> {
  if (submitting.value || result.value) return
  if (decision.value === 'REJECT' && !reason.value.trim()) {
    actionError.value = new ApiError(-1, '拒收必须填写理由')
    return
  }
  if (reason.value.length > 500) {
    actionError.value = new ApiError(-1, '理由不能超过 500 字')
    return
  }
  const ok = await confirm({
    title: decision.value === 'PASS' ? '通过验收' : '拒收交付物',
    content:
      decision.value === 'PASS'
        ? '通过后签约完成并生成结算单，操作不可逆。确定通过吗？'
        : '拒收会发起争议并暂停结算，操作不可逆。确定拒收吗？',
    positiveText: decision.value === 'PASS' ? '确定通过' : '确定拒收',
    danger: decision.value === 'REJECT',
  })
  if (!ok) return
  submitting.value = true
  actionError.value = null
  try {
    result.value = await engagementApi.acceptance(
      contractId.value,
      decision.value,
      decision.value === 'REJECT' ? reason.value.trim() : undefined,
    )
    message.success(decision.value === 'PASS' ? '验收通过，已进入结算' : '已拒收并发起争议')
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="ACCEPTANCE"
      :title="`验收签约 ${contractId}`"
      desc="核对交付物后通过或拒收。通过触发结算，拒收触发争议。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/employer/engagements">履约看板</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/disputes">争议处理</RouterLink>
      </template>
    </PageHeader>

    <div v-if="result" class="card stack">
      <p class="eyebrow">验收结果</p>
      <div class="cluster">
        <StatusTag kind="contract" :value="result.contractStatus" />
        <StatusTag kind="slot" :value="result.slotStatus" />
      </div>
      <div class="kv">
        <div class="kv__row">
          <span class="kv__k">签约状态</span>
          <span class="kv__v">{{ statusLabel('contract', result.contractStatus) }}</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">名额状态</span>
          <span class="kv__v">{{ statusLabel('slot', result.slotStatus) }}</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">结算单</span>
          <span class="kv__v mono">{{ result.settlementId || '—' }}</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">打款单</span>
          <span class="kv__v mono">{{ result.payoutId || '—' }}</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">争议单</span>
          <span class="kv__v mono">{{ result.disputeId || '—' }}</span>
        </div>
      </div>
      <div class="cluster">
        <RouterLink v-if="result.settlementId" class="btn btn--ink btn--sm" to="/employer/settlements">查看结算记录</RouterLink>
        <RouterLink v-if="result.disputeId" class="btn btn--line btn--sm" :to="`/employer/disputes/${result.disputeId}`">
          查看争议
        </RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/wallet">查看余额</RouterLink>
      </div>
    </div>

    <template v-else>
      <section class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">验收结论</h2>
            <p class="card__sub">二选一，提交后不可撤回</p>
          </div>
        </div>

        <div class="tabs">
          <button class="tab" :class="{ 'is-active': decision === 'PASS' }" type="button" @click="decision = 'PASS'">
            通过验收
          </button>
          <button class="tab" :class="{ 'is-active': decision === 'REJECT' }" type="button" @click="decision = 'REJECT'">
            拒收交付物
          </button>
        </div>
        <p class="tiny muted">
          {{
            decision === 'PASS'
              ? '通过后签约完成，生成结算单并进入打款流程。'
              : '拒收会发起争议并暂停结算，由治理台裁决。'
          }}
        </p>

        <label v-if="decision === 'REJECT'" class="field">
          <span class="field__label">拒收理由 *</span>
          <textarea v-model="reason" class="textarea" maxlength="500" placeholder="说明不符合要求的具体项" />
          <span class="field__hint">{{ reason.length }} / 500</span>
        </label>

        <div class="form-actions">
          <button class="btn btn--ink btn--sm" type="button" :disabled="submitting" @click="submit">
            {{ submitting ? '正在提交' : decision === 'PASS' ? '确认通过' : '确认拒收' }}
          </button>
        </div>
        <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
      </section>

      <div class="notice">
        <p class="notice__eyebrow">提交前确认</p>
        <p class="notice__desc">
          通过验收会触发结算与打款，拒收会生成争议单。两者都会改变名额终态，请确认已核对交付内容。
        </p>
      </div>

      <GapNotice
        title="交付物明细与自动验收倒计时未开放"
        desc="验收页只提交结论，不展示交付图片与说明，也不显示自动验收倒计时。交付内容请在沟通消息或站内通知中核对。"
      />
    </template>
  </div>
</template>
