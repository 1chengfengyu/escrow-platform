<script setup lang="ts">
// W-07 我的结算记录（FR-SETTLE.*）
// 后端未提供劳动者侧结算单列表接口，本页按缺口说明处理。
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="SETTLEMENTS"
      title="结算记录"
      desc="雇主验收通过后生成结算单，再按打款通道执行。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/wallet">钱包与余额</RouterLink>
        <RouterLink class="btn btn--ink btn--sm" to="/worker/engagements">我的履约</RouterLink>
      </template>
    </PageHeader>

    <GapNotice
      title="结算单列表暂未开放"
      desc="暂不提供按劳动者的结算记录查询，本页因此不展示结算单号、打款状态与到账金额，避免用推算数据替代真实账务。"
    />

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">结算怎么发生</h2>
          <p class="card__sub">从验收通过到到账的链路</p>
        </div>
      </div>
      <div class="kv">
        <div class="kv__row">
          <span class="kv__k">第一步</span>
          <span class="kv__v">劳动者提交交付物，签约进入「已交付」。</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">第二步</span>
          <span class="kv__v">雇主验收通过，签约完成，系统生成结算单。</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">第三步</span>
          <span class="kv__v">结算单确认后执行打款，资金进入可用余额。</span>
        </div>
        <div class="kv__row">
          <span class="kv__k">异常</span>
          <span class="kv__v">打款结果未知时不重试，进入查证或人工队列。</span>
        </div>
      </div>
      <div class="cluster">
        <RouterLink class="btn btn--line btn--sm" to="/worker/wallet">查看可用余额</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/notifications">结算通知</RouterLink>
      </div>
    </section>

    <div class="notice notice--info">
      <p class="notice__eyebrow">本期可核对的字段</p>
      <p class="notice__desc">
        履约详情里可以看到签约状态与交付时间；余额页可以看到可用与冻结金额。结算单号与打款流水号暂不展示。
      </p>
    </div>
  </div>
</template>
