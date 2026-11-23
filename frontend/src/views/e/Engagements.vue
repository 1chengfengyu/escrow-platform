<script setup lang="ts">
// E-06 履约看板
// 后端 /api/contracts 只返回当前劳动者的签约，没有雇主侧签约列表，
// 因此本页以「编号直达」替代列表，并显式登记缺口。
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'

const router = useRouter()

const contractId = ref('')
const contractError = ref<ApiError | null>(null)
const taskId = ref('')
const taskError = ref<ApiError | null>(null)

function openAcceptance(): void {
  const id = contractId.value.trim()
  if (!id) {
    contractError.value = new ApiError(-1, '请输入签约编号')
    return
  }
  contractError.value = null
  void router.push(`/employer/engagements/${id}/acceptance`)
}

function openTask(): void {
  const id = taskId.value.trim()
  if (!id) {
    taskError.value = new ApiError(-1, '请输入任务编号')
    return
  }
  taskError.value = null
  void router.push(`/employer/tasks/${id}`)
}
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="ENGAGEMENTS"
      title="履约看板"
      desc="录用后形成签约；劳动者交付后，按签约编号进入验收。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/employer/tasks">我发布的任务</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/settlements">结算记录</RouterLink>
      </template>
    </PageHeader>

    <GapNotice
      title="雇主侧履约列表未开放"
      desc="签约列表只返回当前账号的签约，雇主看不到自己发布的签约；也没有「按任务查看签约」与「待验收列表」。因此本页不展示看板列，改用签约编号直达验收，或任务编号直达名额矩阵。"
    />

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">按签约编号验收</h2>
          <p class="card__sub">录用成功页、站内通知与消息里都有签约编号</p>
        </div>
      </div>
      <form class="filters" @submit.prevent="openAcceptance">
        <label class="field field--wide">
          <span class="field__label">签约编号</span>
          <input v-model="contractId" class="input" inputmode="numeric" placeholder="例如 1790000000000000001" />
          <span v-if="contractError" class="field__error">{{ contractError.message }}</span>
        </label>
        <button class="btn btn--ink btn--sm" type="submit">进入验收</button>
      </form>
    </section>

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">按任务编号查看名额</h2>
          <p class="card__sub">名额矩阵会显示每个名额的签约与交付状态</p>
        </div>
      </div>
      <form class="filters" @submit.prevent="openTask">
        <label class="field field--wide">
          <span class="field__label">任务编号</span>
          <input v-model="taskId" class="input" inputmode="numeric" placeholder="例如 1780000000000000001" />
          <span v-if="taskError" class="field__error">{{ taskError.message }}</span>
        </label>
        <button class="btn btn--line btn--sm" type="submit">打开任务</button>
      </form>
    </section>

    <div class="notice notice--info">
      <p class="notice__eyebrow">自动验收</p>
      <p class="notice__desc">
        交付后超时未处理会自动验收。本页不显示倒计时，避免推算时间造成误导。
      </p>
    </div>
  </div>
</template>
