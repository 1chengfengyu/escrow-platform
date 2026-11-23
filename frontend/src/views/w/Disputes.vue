<script setup lang="ts">
// W-09 我的争议申诉（FR-DISPUTE.OPEN.* / FR-DISPUTE.WITHDRAW.*）
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { disputeApi } from '@/api/modules/dispute'
import { ApiError, toApiError } from '@/api/http'
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'

const router = useRouter()
const message = useMessage()

const lookupId = ref('')
const lookupError = ref<ApiError | null>(null)

const contractId = ref('')
const reason = ref('')
const opening = ref(false)
const openError = ref<ApiError | null>(null)

function lookup(): void {
  const id = lookupId.value.trim()
  if (!id) {
    lookupError.value = new ApiError(-1, '请输入争议编号')
    return
  }
  lookupError.value = null
  void router.push(`/worker/disputes/${id}`)
}

async function open(): Promise<void> {
  const id = contractId.value.trim()
  if (!id) {
    openError.value = new ApiError(-1, '请输入签约编号')
    return
  }
  if (!reason.value.trim()) {
    openError.value = new ApiError(-1, '请填写申诉理由')
    return
  }
  if (opening.value) return
  opening.value = true
  openError.value = null
  try {
    const disputeId = await disputeApi.open(id, reason.value.trim())
    message.success('申诉已发起')
    reason.value = ''
    await router.push(`/worker/disputes/${disputeId}`)
  } catch (raw) {
    openError.value = toApiError(raw)
  } finally {
    opening.value = false
  }
}
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="DISPUTES"
      title="争议申诉"
      desc="对履约或验收有异议时发起申诉，由治理台裁决。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/engagements">我的履约</RouterLink>
      </template>
    </PageHeader>

    <GapNotice
      title="我的争议列表未开放"
      desc="争议按编号直接查询，不提供列表。编号可从通知或履约详情获得。"
    />

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">按编号查询</h2>
          <p class="card__sub">发起争议后会跳转到详情页，编号也会写入通知</p>
        </div>
      </div>
      <form class="filters" @submit.prevent="lookup">
        <label class="field field--wide">
          <span class="field__label">争议编号</span>
          <input v-model="lookupId" class="input" inputmode="numeric" placeholder="例如 1790000000000000001" />
          <span class="field__hint">只允许查看与自己有关的争议。</span>
          <span v-if="lookupError" class="field__error">{{ lookupError.message }}</span>
        </label>
        <button class="btn btn--ink btn--sm" type="submit">打开争议</button>
      </form>
    </section>

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">发起争议</h2>
          <p class="card__sub">需要签约编号；履约中或已交付的签约可发起</p>
        </div>
      </div>
      <form class="stack" @submit.prevent="open">
        <label class="field">
          <span class="field__label">签约编号</span>
          <input v-model="contractId" class="input" inputmode="numeric" placeholder="从「我的履约」复制签约编号" />
        </label>
        <label class="field">
          <span class="field__label">申诉理由</span>
          <textarea v-model="reason" class="textarea" maxlength="500" placeholder="说明事实、争议点与诉求" />
          <span class="field__hint">{{ reason.length }} / 500</span>
        </label>
        <div class="form-actions">
          <button class="btn btn--ink btn--sm" type="submit" :disabled="opening">
            {{ opening ? '正在发起' : '发起争议' }}
          </button>
        </div>
      </form>
      <p v-if="openError" class="field__error">{{ openError.message }}（{{ openError.code }}）</p>
    </section>

    <div class="notice notice--info">
      <p class="notice__eyebrow">争议期间</p>
      <p class="notice__desc">
        争议未裁决前，对应名额暂停结算。双方都可以补充文字与图片证据，也可以把会话中的消息作为证据提交。
      </p>
    </div>
  </div>
</template>
