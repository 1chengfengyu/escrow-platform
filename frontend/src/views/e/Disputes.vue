<script setup lang="ts">
// E-10 争议处理
// 后端没有雇主侧争议列表接口；拒收会自动生成争议，雇主以编号查询并补充证据。
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'

const router = useRouter()
const disputeId = ref('')
const lookupError = ref<ApiError | null>(null)

function open(): void {
  const id = disputeId.value.trim()
  if (!id) {
    lookupError.value = new ApiError(-1, '请输入争议编号')
    return
  }
  lookupError.value = null
  void router.push(`/employer/disputes/${id}`)
}
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="DISPUTES"
      title="争议处理"
      desc="拒收交付物会自动生成争议；双方都可以补充证据。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/employer/engagements">履约看板</RouterLink>
      </template>
    </PageHeader>

    <GapNotice
      title="争议列表暂未开放"
      desc="争议按编号直接查询，不提供列表。编号会在拒收结果与通知中给出。"
    />

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">按编号查询争议</h2>
          <p class="card__sub">只允许查看与自己有关的争议</p>
        </div>
      </div>
      <form class="filters" @submit.prevent="open">
        <label class="field field--wide">
          <span class="field__label">争议编号</span>
          <input v-model="disputeId" class="input" inputmode="numeric" placeholder="从验收结果或通知复制" />
          <span v-if="lookupError" class="field__error">{{ lookupError.message }}</span>
        </label>
        <button class="btn btn--ink btn--sm" type="submit">打开争议</button>
      </form>
    </section>

    <div class="notice notice--info">
      <p class="notice__eyebrow">处理建议</p>
      <p class="notice__desc">
        争议期间对应名额暂停结算。补充证据时优先提交任务要求、沟通记录与交付截图，并把关键消息从会话中取证，便于治理台核对。
      </p>
    </div>
  </div>
</template>
