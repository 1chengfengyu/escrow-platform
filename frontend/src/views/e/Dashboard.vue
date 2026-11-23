<script setup lang="ts">
// E-01 雇主工作台
// 后端没有「我发布的任务 / 报名 / 签约 / 结算」的雇主侧聚合列表，
// 概览数字不可用，因此本页展示可真实读取的账户与待处理信息，并显式登记缺口。
import { computed } from 'vue'
import { chatApi } from '@/api/modules/chat'
import { fundApi } from '@/api/modules/fund'
import { identityApi } from '@/api/modules/identity'
import { notificationApi } from '@/api/modules/notification'
import type { FundBalanceView, QualificationView, UnreadCountView } from '@/api/types'
import ErrorBlock from '@/components/ErrorBlock.vue'
import GapNotice from '@/components/GapNotice.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MoneyText from '@/components/MoneyText.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { useAuthStore } from '@/stores/auth'
import { statusLabel } from '@/utils/status'

const auth = useAuthStore()

function pick<T>(results: PromiseSettledResult<unknown>[], index: number): T | null {
  const item = results[index]
  return item && item.status === 'fulfilled' ? (item.value as T) : null
}

const { data, loading, error, load } = useResource(async () => {
  const results = await Promise.allSettled([
    identityApi.qualification(),
    fundApi.balance(),
    notificationApi.unreadCount(),
    chatApi.unreadCount(),
  ])
  return {
    qualification: pick<QualificationView>(results, 0),
    balance: pick<FundBalanceView>(results, 1),
    notificationUnread: pick<UnreadCountView>(results, 2),
    chatUnread: pick<UnreadCountView>(results, 3),
  }
})

const qualification = computed(() => data.value?.qualification ?? null)
const publishReady = computed(() => qualification.value?.publishEligible === true)

const availableNum = computed(() => Number(data.value?.balance?.available ?? '0'))
const frozenNum = computed(() => Number(data.value?.balance?.frozen ?? '0'))
const totalNum = computed(() => availableNum.value + frozenNum.value)
const frozenShare = computed(() => (totalNum.value > 0 ? Math.round((frozenNum.value / totalNum.value) * 100) : 0))
const availableShare = computed(() => (totalNum.value > 0 ? 100 - frozenShare.value : 0))
const unreadTotal = computed(
  () => Number(data.value?.notificationUnread?.unreadCount ?? 0) + Number(data.value?.chatUnread?.unreadCount ?? 0),
)

const shortcuts = [
  { to: '/employer/tasks/new', label: '发布任务', desc: '填写资料，保存草稿或直接发布' },
  { to: '/employer/tasks', label: '我发布的任务', desc: '输入任务编号，查看详情与名额' },
  { to: '/employer/engagements', label: '验收', desc: '输入签约编号，通过或拒收' },
  { to: '/employer/wallet', label: '钱包', desc: '查看余额与模拟充值' },
  { to: '/employer/disputes', label: '争议', desc: '按编号查询争议与证据' },
  { to: '/employer/qualification', label: '资质', desc: '提交或更新主体信息' },
]
</script>

<template>
  <div class="page page--wide">
    <PageHeader eyebrow="EMPLOYER" :title="`你好，${auth.nickname || '雇主'}`" desc="发任务、挑人、验收，结算在验收之后。">
      <template #actions>
        <RouterLink class="btn btn--ink btn--sm" to="/employer/tasks/new">发布任务</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/conversations">沟通消息</RouterLink>
      </template>
    </PageHeader>

    <figure class="anchor anchor--strip">
      <img src="/media/anchor-employer.jpg" alt="晨光下的木质桌面，摊开的记事本、钥匙与卷尺" />
    </figure>

    <LoadingBlock v-if="loading && !data" :rows="3" label="正在读取工作台" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <div class="notice" :class="publishReady ? 'notice--info' : ''">
        <p class="notice__eyebrow">发布资格</p>
        <p class="notice__desc">
          <template v-if="publishReady">资质已通过，可以发布任务。发布时按「单价 × 名额」冻结任务金额。</template>
          <template v-else>
            资质当前为「{{ qualification ? statusLabel('review', qualification.status) : '未读取' }}」，未通过时无法发布任务。
            <RouterLink class="linkbtn" to="/employer/qualification">去资质登记</RouterLink>
          </template>
        </p>
      </div>

      <section class="kpis">
        <div class="kpi kpi--lead">
          <div class="kpi__top">
            <span class="kpi__label">可用余额</span>
            <span class="kpi__chip">冻结 {{ frozenShare }}%</span>
          </div>
          <p class="kpi__value"><MoneyText :fen="data.balance?.available ?? '0'" /></p>
          <p class="kpi__note">发布任务时按「单价 × 名额」冻结，验收通过后从冻结中结算。</p>
          <div class="kpi__meter">
            <i :style="{ width: `${availableShare}%` }"></i>
          </div>
          <p class="kpi__note">可用占账户总额 {{ availableShare }}%</p>
        </div>

        <div class="kpi">
          <span class="kpi__label">冻结金额</span>
          <span class="kpi__value"><MoneyText :fen="data.balance?.frozen ?? '0'" /></span>
          <span class="kpi__note">发布中的任务占用</span>
        </div>

        <div class="kpi">
          <div class="kpi__top">
            <span class="kpi__label">未读合计</span>
            <span class="kpi__chip">通知 {{ data.notificationUnread?.unreadCount ?? 0 }}</span>
          </div>
          <span class="kpi__value">{{ unreadTotal }}</span>
          <span class="kpi__note">报名、验收、结算、争议与沟通消息</span>
        </div>
      </section>

      <section class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">常用入口</h2>
            <p class="card__sub">本版本部分列表暂未开放，多数操作以「编号 + 详情」进行</p>
          </div>
        </div>
        <div class="roles">
          <RouterLink v-for="item in shortcuts" :key="item.to" class="role" :to="item.to">
            <p class="strong">{{ item.label }}</p>
            <p class="tiny muted">{{ item.desc }}</p>
          </RouterLink>
        </div>
      </section>

      <section v-if="qualification" class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">资质状态</h2>
            <p class="card__sub">主体信息与审核结论</p>
          </div>
          <StatusTag kind="review" :value="qualification.status" />
        </div>
        <div class="kv">
          <div class="kv__row">
            <span class="kv__k">主体类型</span>
            <span class="kv__v">{{ qualification.subjectType ? statusLabel('subject', qualification.subjectType) : '—' }}</span>
          </div>
          <div class="kv__row">
            <span class="kv__k">主体名称</span>
            <span class="kv__v">{{ qualification.subjectName || '—' }}</span>
          </div>
          <div class="kv__row">
            <span class="kv__k">审核意见</span>
            <span class="kv__v">{{ qualification.reviewReason || '—' }}</span>
          </div>
        </div>
      </section>

      <GapNotice
        title="概览统计暂不可用"
        desc="待处理数量暂未汇总，本页不展示推算数字。报名、验收、结算与争议可从对应入口按编号进入。"
      />
    </template>
  </div>
</template>
