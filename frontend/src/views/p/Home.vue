<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { taskApi } from '@/api/modules/task'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const openTaskCount = ref<number | null>(null)
const momentVisible = ref(false)
const momentEl = ref<HTMLElement | null>(null)

const publishTarget = computed(() => {
  if (!auth.isLoggedIn) return { path: '/login', query: { redirect: '/employer/tasks/new', intent: 'employer' } }
  return auth.isEmployer ? '/employer/tasks/new' : '/employer/tasks/new'
})

const takeTarget = computed(() => {
  if (!auth.isLoggedIn) return { path: '/login', query: { redirect: '/worker/tasks', intent: 'worker' } }
  return auth.isWorker ? '/worker/tasks' : '/worker/tasks'
})

onMounted(async () => {
  if (momentEl.value) {
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          momentVisible.value = true
          observer.disconnect()
        }
      },
      { threshold: 0.25 },
    )
    observer.observe(momentEl.value)
  }
  try {
    const page = await taskApi.list({ page: 1, size: 1 }, true)
    openTaskCount.value = Number(page.total)
  } catch {
    openTaskCount.value = null
  }
})

function revealMoment(): void {
  momentVisible.value = true
}
</script>

<template>
    <section class="hero" data-enter>
      <div class="hero__media">
        <img src="/media/hero-market.jpg" alt="日光下的集市与来往的人" />
      </div>
      <div class="hero__inner">
        <p class="eyebrow" data-enter>灵活用工 · 报酬托管</p>
        <h1 class="hero__title" data-enter="2">活有人干，<br /><em>钱有着落</em>。</h1>
        <p class="hero__sub" data-enter="3">
          雇主发布任务时，报酬即进入平台托管；劳动者按约完成，验收通过后结算到账。双方看同一份进度，每一笔都能查到去向。
        </p>
        <div class="hero__actions" data-enter="4">
          <RouterLink class="btn btn--ink" :to="publishTarget">发布任务</RouterLink>
          <RouterLink class="btn btn--line" :to="takeTarget">找任务</RouterLink>
        </div>

        <aside class="hero__card">
          <span class="n">当前开放任务</span>
          <b v-if="openTaskCount !== null">{{ openTaskCount }} 个</b>
          <b v-else>登录后查看</b>
          <p v-if="openTaskCount !== null">实时更新</p>
          <p v-else>登录后可浏览任务广场与任务详情</p>
          <div class="row">
            <span>发布任务不收费</span>
            <span>验收后再结算</span>
          </div>
        </aside>
      </div>
    </section>

    <section class="section">
      <div class="wrap">
        <header class="section__head">
          <p class="section__num">01</p>
          <div>
            <h2 class="section__title">两种身份，各自清楚</h2>
            <p class="section__desc">雇主关心能不能找到合适的人并顺利交付，劳动者关心活儿是否清楚、报酬是否有据。这两件事共用同一份记录。</p>
          </div>
        </header>

        <figure class="section__media">
          <img src="/media/work-table.jpg" alt="开工前摊开工具与材料的工作台" loading="lazy" />
        </figure>

        <div class="roles">
          <article class="role">
            <p class="role__label">FOR EMPLOYER</p>
            <h3 class="role__title">把任务说清楚，再挑人</h3>
            <p class="ink2 small">
              发布时定清名额与每个名额的报酬，报名集中在一处，按报名资料逐个录用。
            </p>
            <ul class="stack stack--sm small ink2">
              <li>· 报酬在发布时锁定，不靠口头约定</li>
              <li>· 报名列表里逐个录用，直接形成签约</li>
              <li>· 交付物与验收意见留在任务内</li>
            </ul>
            <RouterLink class="btn btn--line btn--sm" :to="publishTarget">去发布任务</RouterLink>
          </article>

          <article class="role">
            <p class="role__label">FOR WORKER</p>
            <h3 class="role__title">看清要求再报名，按约交付</h3>
            <p class="ink2 small">
              按分类、地点、报酬与日期筛出开放任务，看清要求再报名。录用后在工作台完成开工、交付与验收。
            </p>
            <ul class="stack stack--sm small ink2">
              <li>· 任务信息完整才开放报名</li>
              <li>· 交付可附图片与说明</li>
              <li>· 验收通过后进入结算</li>
            </ul>
            <RouterLink class="btn btn--line btn--sm" :to="takeTarget">找任务</RouterLink>
          </article>
        </div>
      </div>
    </section>

    <section class="section--tight">
      <div class="wrap">
        <header class="section__head">
          <p class="section__num">02</p>
          <div>
            <h2 class="section__title">从发布到结算，四步</h2>
            <p class="section__desc">每一步都有对应的状态，双方看到的是同一份进度。</p>
          </div>
        </header>

        <figure class="section__media">
          <img src="/media/band-delivery.jpg" alt="交付材料与验收记录的近景" loading="lazy" />
        </figure>

        <div class="steps">
          <article class="step">
            <p class="step__no">STEP 01</p>
            <h3 class="step__title">发布任务</h3>
            <p class="step__desc">定名额、报酬与时间地点，报酬随即进入托管。</p>
          </article>
          <article class="step">
            <p class="step__no">STEP 02</p>
            <h3 class="step__title">报名与录用</h3>
            <p class="step__desc">劳动者报名，雇主逐个名额录用，形成签约。</p>
          </article>
          <article class="step">
            <p class="step__no">STEP 03</p>
            <h3 class="step__title">履约与交付</h3>
            <p class="step__desc">开工后提交交付说明与图片，记录留在任务里。</p>
          </article>
          <article class="step">
            <p class="step__no">STEP 04</p>
            <h3 class="step__title">验收与结算</h3>
            <p class="step__desc">验收通过即进入结算；有分歧转入争议流程。</p>
          </article>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="wrap">
        <div class="band">
          <div class="band__copy">
            <p class="eyebrow">DELIVERY</p>
            <h2>交付有据，<br />结算有期。</h2>
            <p class="ink2 small">
              交付图片、文字说明与验收意见都留在任务内。出现分歧时，双方提交的证据会被保留，由平台裁决后再继续结算。
            </p>
            <RouterLink class="btn btn--ink btn--sm" to="/how-it-works">看完整流程</RouterLink>
          </div>
          <div class="band__media">
            <img src="/media/band-work.jpg" alt="工作台上摊开的工具与材料" loading="lazy" />
          </div>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="wrap">
        <div ref="momentEl" class="moment" :class="{ 'is-in': momentVisible }" @mouseenter="revealMoment" @focusin="revealMoment">
          <div class="moment__inner">
            <p class="moment__eyebrow">SETTLEMENT</p>
            <h2 class="moment__title">每一笔结算，<br />都能查到去向。</h2>
            <p class="moment__desc">
              结算单、打款单与对账差异在治理台可查。打款状态未知时先查证，不把未知当失败，也不重复打款。
            </p>
            <div class="cluster" style="justify-content: center; margin-top: 20px">
              <RouterLink class="btn btn--line btn--sm" style="border-color: rgba(251, 248, 240, 0.5); color: var(--paper)" to="/help">
                了解结算与争议
              </RouterLink>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="section--tight">
      <div class="wrap">
        <div class="card closing">
          <div class="closing__copy">
            <p class="eyebrow">START HERE</p>
            <h2 class="section__title">第一单，从这里开始。</h2>
            <p class="page-desc">注册后一个账号同时具备雇主与劳动者身份，随时切换。</p>
            <div class="cluster">
              <RouterLink class="btn btn--ink" to="/register">免费注册</RouterLink>
              <RouterLink class="btn btn--line" :to="takeTarget">先找任务</RouterLink>
            </div>
          </div>
          <figure class="closing__media">
            <img src="/media/anchor-task.jpg" alt="任务详情页的任务说明与报名入口" loading="lazy" />
          </figure>
        </div>
      </div>
    </section>
</template>
