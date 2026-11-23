<script setup lang="ts">
// W-04 我的报名（FR-SIGN.APPLY.06/07/08）
// 后端未提供「我的报名」列表接口，本页按缺口说明处理，不伪造数据。
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="APPLICATIONS"
      title="我的报名"
      desc="报名是通过任务维度提交的，结果由雇主处理。"
    >
      <template #actions>
        <RouterLink class="btn btn--ink btn--sm" to="/worker/tasks">去找任务</RouterLink>
      </template>
    </PageHeader>

    <GapNotice
      title="报名列表暂未开放"
      desc="当前版本不提供「我的报名」列表，也不支持撤回报名。报名进度请从任务详情与站内通知查看。"
    />

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">当前怎么查报名结果</h2>
          <p class="card__sub">三条可用路径</p>
        </div>
      </div>
      <div class="kv">
        <div class="kv__row">
          <span class="kv__k">路径一</span>
          <span class="kv__v">被录用或拒绝时，站内通知会写入结果，去通知中心查看。</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">路径二</span>
          <span class="kv__v">重新打开对应任务详情，名额状态会反映是否已被占用。</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">路径三</span>
          <span class="kv__v">被录用后形成签约，在「我的履约」里可以看到并继续履约。</span>
        </div>
      </div>
      <div class="cluster">
        <RouterLink class="btn btn--line btn--sm" to="/worker/notifications">通知中心</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/engagements">我的履约</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/tasks">任务列表</RouterLink>
      </div>
    </section>

    <div class="notice notice--info">
      <p class="notice__eyebrow">报名被拒的常见原因</p>
      <p class="notice__desc">
        名额已满、重复报名、任务已过结束时间、任务已停止招募、报名自己发布的任务。提交时页面会直接给出对应原因。
      </p>
    </div>
  </div>
</template>
