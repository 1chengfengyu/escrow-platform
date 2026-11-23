<script setup lang="ts">
// W-01 劳动者工作台（FR-SIGN.* / FR-SETTLE.*）
import { computed } from 'vue'
import { chatApi } from '@/api/modules/chat'
import { engagementApi } from '@/api/modules/engagement'
import { fundApi } from '@/api/modules/fund'
import { notificationApi } from '@/api/modules/notification'
import { taskApi } from '@/api/modules/task'
import type {
  ContractListItemView,
  FundBalanceView,
  PageView,
  TaskListItemView,
  UnreadCountView,
} from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MoneyText from '@/components/MoneyText.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { useAuthStore } from '@/stores/auth'
import { formatDate } from '@/utils/date'

const auth = useAuthStore()

function pick<T>(results: PromiseSettledResult<unknown>[], index: number): T | null {
  const item = results[index]
  return item && item.status === 'fulfilled' ? (item.value as T) : null
}

const { data, loading, error, load } = useResource(async () => {
  const results = await Promise.allSettled([
    engagementApi.contracts(1, 100),
    taskApi.list({ page: 1, size: 4, sort: 'published_at', order: 'desc' }),
    fundApi.balance(),
    notificationApi.unreadCount(),
    chatApi.unreadCount(),
  ])
  const labels = ['签约记录', '推荐任务', '账户余额', '通知未读', '消息未读']
  return {
    contracts: pick<PageView<ContractListItemView>>(results, 0),
    recommended: pick<PageView<TaskListItemView>>(results, 1),
    balance: pick<FundBalanceView>(results, 2),
    notificationUnread: pick<UnreadCountView>(results, 3),
    chatUnread: pick<UnreadCountView>(results, 4),
    failed: labels.filter((_, index) => results[index].status === 'rejected'),
  }
})

const contracts = computed(() => data.value?.contracts?.items ?? [])

const todos = computed(() => {
  const rows = contracts.value
  const count = (status: string): number => rows.filter((row) => row.status === status).length
  return [
    { key: 'CONTRACT_ACTIVE', label: '待开始', value: count('CONTRACT_ACTIVE'), tip: '已录用，等待开工' },
    { key: 'CONTRACT_STARTED', label: '交付中', value: count('CONTRACT_STARTED'), tip: '履约中，按约交付' },
    { key: 'CONTRACT_DELIVERED', label: '待验收', value: count('CONTRACT_DELIVERED'), tip: '已交付，等待验收' },
    { key: 'CONTRACT_COMPLETED', label: '待收款', value: count('CONTRACT_COMPLETED'), tip: '以结算记录为准' },
  ]
})

const ongoing = computed(() =>
  contracts.value
    .filter((row) => row.status === 'CONTRACT_ACTIVE' || row.status === 'CONTRACT_STARTED' || row.status === 'CONTRACT_DELIVERED')
    .slice(0, 6),
)

const recommended = computed(() => data.value?.recommended?.items ?? [])
const pendingTotal = computed(() => todos.value.reduce((sum, item) => sum + item.value, 0))
const deliveredRatio = computed(() =>
  pendingTotal.value ? Math.round((todos.value[2].value / pendingTotal.value) * 100) : 0,
)
const hasAny = computed(
  () => contracts.value.length > 0 || recommended.value.length > 0 || Boolean(data.value?.balance),
)
</script>

<template>
  <div class="page page--wide">
    <PageHeader eyebrow="WORKER" :title="`你好，${auth.nickname || '劳动者'}`" desc="手上的活做到哪一步、今天该处理什么，这一页说清楚。">
      <template #actions>
        <RouterLink class="btn btn--ink btn--sm" to="/worker/tasks">找任务</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/conversations">沟通消息</RouterLink>
      </template>
    </PageHeader>

    <figure class="anchor anchor--strip">
      <img src="/media/anchor-worker.jpg" alt="在木质工作台上用牛皮纸与麻绳打包包裹的劳动者" />
    </figure>

    <LoadingBlock v-if="loading && !data" :rows="4" label="正在读取工作台" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <div v-if="data.failed.length" class="notice notice--info">
        <p class="notice__eyebrow">部分数据未取到</p>
        <p class="notice__desc">{{ data.failed.join('、') }}暂时无法读取，其余区块不受影响。</p>
      </div>

      <section class="kpis">
        <div class="kpi kpi--lead">
          <div class="kpi__top">
            <span class="kpi__label">待处理合计</span>
            <span class="kpi__chip kpi__chip--warm">交付中 {{ todos[1].value }}</span>
          </div>
          <p class="kpi__value">{{ pendingTotal }}<span class="kpi__unit">件</span></p>
          <p class="kpi__note">待开始、交付中、待验收与待收款四类签约之和。</p>
          <div class="kpi__meter" :class="{ 'kpi__meter--accent': deliveredRatio > 0 }">
            <i :style="{ width: `${deliveredRatio}%` }"></i>
          </div>
          <p class="kpi__note">其中待验收占 {{ deliveredRatio }}%</p>
        </div>

        <RouterLink
          v-for="todo in todos.slice(0, 3)"
          :key="todo.key"
          class="kpi kpi--tight"
          :to="{ path: '/worker/engagements', query: { status: todo.key } }"
        >
          <span class="kpi__label">{{ todo.label }}</span>
          <span class="kpi__value">{{ todo.value }}</span>
          <span class="kpi__note">{{ todo.tip }}</span>
        </RouterLink>
      </section>

      <EmptyState
        v-if="!hasAny"
        title="还没有履约记录"
        desc="先去任务广场报名，被录用后这里会出现待开始、交付中与待验收的签约。"
      >
        <template #actions>
          <RouterLink class="btn btn--ink btn--sm" to="/worker/tasks">去任务广场</RouterLink>
          <RouterLink class="btn btn--line btn--sm" to="/worker/applications">查看我的报名</RouterLink>
        </template>
      </EmptyState>

      <div v-else class="detail">
        <div class="stack stack--lg">
          <section class="card">
            <div class="card__head">
              <div>
                <h2 class="card__title">进行中的履约</h2>
                <p class="card__sub">待开始、交付中与待验收的签约</p>
              </div>
              <RouterLink class="linkbtn" to="/worker/engagements">全部履约</RouterLink>
            </div>

            <div v-if="ongoing.length" class="list">
              <RouterLink
                v-for="row in ongoing"
                :key="row.contractId"
                class="list__item"
                :to="`/worker/engagements/${row.contractId}`"
              >
                <div class="stack stack--sm">
                  <p class="strong small">签约 {{ row.contractId }}</p>
                  <p class="tiny muted">
                    任务 {{ row.taskId }} · 生效 {{ formatDate(row.effectiveAt) }}
                    <template v-if="row.deliveredAt"> · 交付 {{ formatDate(row.deliveredAt) }}</template>
                  </p>
                </div>
                <StatusTag kind="contract" :value="row.status" />
              </RouterLink>
            </div>
            <EmptyState v-else title="还没有在途履约" desc="被录用后，签约会出现在这里。" />
          </section>

          <section class="card">
            <div class="card__head">
              <div>
                <h2 class="card__title">推荐任务</h2>
                <p class="card__sub">按最新发布排序</p>
              </div>
              <RouterLink class="linkbtn" to="/worker/tasks">去筛选</RouterLink>
            </div>

            <div v-if="recommended.length" class="list">
              <RouterLink v-for="task in recommended" :key="task.id" class="list__item" :to="`/worker/tasks/${task.id}`">
                <div class="stack stack--sm">
                  <p class="strong">{{ task.title }}</p>
                  <p class="tiny muted">
                    {{ task.category }} · {{ task.workLocation }} · 剩 {{ task.remainingSlots }} 个名额
                  </p>
                </div>
                <div class="right">
                  <MoneyText :fen="task.unitPrice" />
                  <p class="tiny muted">/ 名额</p>
                </div>
              </RouterLink>
            </div>
            <EmptyState v-else title="还没有推荐任务" desc="任务广场当前没有开放名额的任务。" />
          </section>
        </div>

        <aside class="stack">
          <section class="card stack">
            <p class="eyebrow">报酬概览</p>
            <div>
              <p class="stat__value"><MoneyText :fen="data.balance?.available ?? '0'" /></p>
              <p class="tiny muted">可用余额</p>
            </div>
            <div class="kv">
              <div class="kv__row">
                <span class="kv__k">冻结</span>
                <span class="kv__v"><MoneyText :fen="data.balance?.frozen ?? '0'" /></span>
              </div>
              <div class="kv__row">
                <span class="kv__k">待收款签约</span>
                <span class="kv__v mono">{{ todos[3].value }}</span>
              </div>
            </div>
            <RouterLink class="btn btn--line btn--block btn--sm" to="/worker/wallet">查看钱包</RouterLink>
          </section>

          <section class="card stack">
            <p class="eyebrow">待处理</p>
            <div class="kv">
              <div class="kv__row">
                <span class="kv__k">未读通知</span>
                <span class="kv__v mono">{{ data.notificationUnread?.unreadCount ?? '0' }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">未读消息</span>
                <span class="kv__v mono">{{ data.chatUnread?.unreadCount ?? '0' }}</span>
              </div>
            </div>
            <div class="cluster">
              <RouterLink class="btn btn--line btn--sm" to="/worker/notifications">通知中心</RouterLink>
              <RouterLink class="btn btn--line btn--sm" to="/worker/conversations">沟通消息</RouterLink>
            </div>
          </section>

          <section class="card stack">
            <p class="eyebrow">快捷入口</p>
            <div class="stack stack--sm">
              <RouterLink class="linkbtn" to="/worker/applications">我的报名</RouterLink>
              <RouterLink class="linkbtn" to="/worker/disputes">争议申诉</RouterLink>
              <RouterLink class="linkbtn" to="/worker/profile">个人资料</RouterLink>
            </div>
            <button class="btn btn--line btn--block btn--sm" type="button" @click="load">刷新数据</button>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>
