<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="wrap section--tight">
    <div class="stack stack--lg">
      <PageHeader
        eyebrow="HOW IT WORKS"
        title="从发布到结算，每一步都有据可查"
        desc="雇主与劳动者看到同一份状态。下面按身份拆开写，避免口头约定带来的分歧。"
      />

      <div class="detail">
        <div class="stack stack--lg">
          <section class="card stack">
            <p class="eyebrow">FOR EMPLOYER</p>
            <h2 class="card__title">雇主：发布、录用、验收</h2>
            <ol class="stack stack--sm small ink2">
              <li><span class="mono">01</span> 写清任务内容、分类、名额、单个名额报酬、时间与地点。</li>
              <li><span class="mono">02</span> 发布后任务进入招募，劳动者可以报名。</li>
              <li><span class="mono">03</span> 在报名列表中查看昵称与报名时间，逐个名额录用。</li>
              <li><span class="mono">04</span> 劳动者交付后查看交付说明与图片，验收通过或驳回并说明理由。</li>
            </ol>
          </section>

          <section class="card stack">
            <p class="eyebrow">FOR WORKER</p>
            <h2 class="card__title">劳动者：报名、履约、交付</h2>
            <ol class="stack stack--sm small ink2">
              <li><span class="mono">01</span> 在任务广场按分类、地点、报酬与日期筛选。</li>
              <li><span class="mono">02</span> 确认任务信息后报名，等待雇主处理。</li>
              <li><span class="mono">03</span> 被录用后形成签约，按约开工。</li>
              <li><span class="mono">04</span> 提交交付说明与图片，等待验收与结算。</li>
            </ol>
          </section>

          <section class="card stack">
            <p class="eyebrow">SETTLEMENT</p>
            <h2 class="card__title">结算与打款</h2>
            <p class="small ink2">
              验收通过后生成结算单，再进入打款流程。打款状态未知时只进入查证，不会把未知当失败直接重试，避免重复打款。
            </p>
            <div class="kv">
              <div class="kv__row">
                <span class="kv__k">结算单</span>
                <span class="kv__v">按签约与验收结果生成，金额与费率可追溯</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">打款单</span>
                <span class="kv__v">每次尝试独立记录，未知态进入人工或查证队列</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">对账</span>
                <span class="kv__v">差异单登记并处理，处理结果留痕</span>
              </div>
            </div>
          </section>

          <section class="card stack">
            <p class="eyebrow">DISPUTE</p>
            <h2 class="card__title">出现分歧时</h2>
            <p class="small ink2">
              任一方可以在签约内发起争议并提交文字与图片证据，也可以从沟通记录中选取消息作为证据。平台裁决后，按判定结果继续放款或退款。
            </p>
            <p class="small muted">争议处理期间，相关结算会暂停，避免在结论未定时继续打款。</p>
          </section>
        </div>

        <aside class="stack">
          <section class="card stack">
            <p class="eyebrow">开始使用</p>
            <p class="small ink2">选择你的身份，进入对应工作台。</p>
            <RouterLink class="btn btn--ink btn--block btn--sm" to="/register">免费注册</RouterLink>
            <RouterLink class="btn btn--line btn--block btn--sm" to="/tasks">去任务广场</RouterLink>
          </section>

          <section class="photo">
            <img src="/media/work-florist.webp" alt="整理花材的工作场景" />
          </section>
        </aside>
      </div>
    </div>
  </div>
</template>
