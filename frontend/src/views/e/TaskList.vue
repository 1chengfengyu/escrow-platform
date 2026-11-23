<script setup lang="ts">
// E-03 我发布的任务
// 后端没有「按雇主分页查询我发布的任务」的接口，本页改用任务编号直达详情。
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'

const router = useRouter()
const taskId = ref('')
const lookupError = ref<ApiError | null>(null)

function open(): void {
  const id = taskId.value.trim()
  if (!id) {
    lookupError.value = new ApiError(-1, '请输入任务编号')
    return
  }
  lookupError.value = null
  void router.push(`/employer/tasks/${id}`)
}
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="MY TASKS"
      title="我发布的任务"
      desc="任务详情、名额矩阵、撤单与到期收口都在详情页完成。"
    >
      <template #actions>
        <RouterLink class="btn btn--ink btn--sm" to="/employer/tasks/new">发布任务</RouterLink>
      </template>
    </PageHeader>

    <GapNotice
      title="任务列表暂未开放"
      desc="公开任务检索只返回招募中的任务，不支持「按当前雇主筛选」；草稿、进行中、已完结、已取消的任务无法分页列出。本页不伪造列表，改用任务编号直达详情。"
    />

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">按编号打开任务</h2>
          <p class="card__sub">任务编号在发布成功页与站内通知里</p>
        </div>
      </div>
      <form class="filters" @submit.prevent="open">
        <label class="field field--wide">
          <span class="field__label">任务编号</span>
          <input v-model="taskId" class="input" inputmode="numeric" placeholder="例如 1780000000000000001" />
          <span v-if="lookupError" class="field__error">{{ lookupError.message }}</span>
        </label>
        <button class="btn btn--ink btn--sm" type="submit">打开任务</button>
      </form>
    </section>

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">撤单与到期</h2>
          <p class="card__sub">破坏性操作需要二次确认，失败原因会显示在页面上</p>
        </div>
      </div>
      <div class="kv">
        <div class="kv__row">
          <span class="kv__k">撤单</span>
          <span class="kv__v">存在非终态名额时会被拒绝，需等名额完结或释放。</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">到期</span>
          <span class="kv__v">全部名额已终态时可手动收口，系统定时任务也会处理。</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">退款</span>
          <span class="kv__v">撤单成功后按未占用名额退回冻结金额。</span>
        </div>
      </div>
      <div class="cluster">
        <RouterLink class="btn btn--line btn--sm" to="/employer/tasks/new">发布新任务</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/engagements">验收入口</RouterLink>
      </div>
    </section>
  </div>
</template>
