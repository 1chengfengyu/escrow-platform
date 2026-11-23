<script setup lang="ts">
// E-08 结算记录
// 后端没有雇主侧结算单 / 打款单列表接口，本页按缺口说明处理。
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="SETTLEMENTS"
      title="结算记录"
      desc="验收通过后生成结算单，确认后进入打款。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/employer/wallet">钱包与余额</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/engagements">履约看板</RouterLink>
      </template>
    </PageHeader>

    <GapNotice
      title="结算单列表暂未开放"
      desc="暂不提供按雇主的结算单与打款单查询，本页因此不展示单号、金额、手续费与打款状态，避免用推算数据替代真实账务。"
    />

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">验收之后发生什么</h2>
          <p class="card__sub">通过验收会返回结算单与打款单编号</p>
        </div>
      </div>
      <div class="kv">
        <div class="kv__row">
          <span class="kv__k">通过验收</span>
          <span class="kv__v">生成结算单，结算单确认后创建打款单。</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">结果未知</span>
          <span class="kv__v">打款超时不重试，进入查证或差异单，避免重复付款。</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">对账</span>
          <span class="kv__v">渠道回执与本地记录不一致时生成差异单，由治理台处理。</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">本期可见</span>
          <span class="kv__v">验收结果页会显示本次生成的结算单号与打款单号。</span>
        </div>
      </div>
      <div class="cluster">
        <RouterLink class="btn btn--line btn--sm" to="/employer/engagements">去验收</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/wallet">查看余额</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/notifications">结算通知</RouterLink>
      </div>
    </section>

    <div class="notice notice--info">
      <p class="notice__eyebrow">核对建议</p>
      <p class="notice__desc">
        验收成功页会给出结算单号与打款单号，请记录；资金结果以站内通知为准，不要自行推断。
      </p>
    </div>
  </div>
</template>
