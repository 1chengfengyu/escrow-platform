<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { engagementApi } from '@/api/modules/engagement'
import { taskApi } from '@/api/modules/task'
import type { TaskListItemView } from '@/api/types'
import { toApiError } from '@/api/http'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import LoginGate from '@/components/LoginGate.vue'
import MoneyText from '@/components/MoneyText.vue'
import { useResource } from '@/composables/useResource'
import { formatDate } from '@/utils/date'
import { yuanToFen } from '@/utils/money'

const props = withDefaults(defineProps<{ mode?: 'public' | 'worker' }>(), { mode: 'public' })

const router = useRouter()
const message = useMessage()

const filters = reactive({
  keyword: '',
  category: '',
  minYuan: '',
  maxYuan: '',
  from: '',
  to: '',
  location: '',
  sort: 'published_at' as 'published_at' | 'unit_price',
  order: 'desc' as 'asc' | 'desc',
  page: 1,
  size: 20,
})

const applyingId = ref('')

const { data, loading, error, load } = useResource(() =>
  taskApi.list(
    {
      keyword: filters.keyword || undefined,
      category: filters.category || undefined,
      minPrice: filters.minYuan ? (yuanToFen(filters.minYuan) ?? undefined) : undefined,
      maxPrice: filters.maxYuan ? (yuanToFen(filters.maxYuan) ?? undefined) : undefined,
      from: filters.from || undefined,
      to: filters.to || undefined,
      location: filters.location || undefined,
      page: filters.page,
      size: filters.size,
      sort: filters.sort,
      order: filters.order,
    },
    props.mode === 'public',
  ),
)

const rows = computed<TaskListItemView[]>(() => data.value?.items ?? [])

function search(): void {
  filters.page = 1
  void load()
}

function reset(): void {
  filters.keyword = ''
  filters.category = ''
  filters.minYuan = ''
  filters.maxYuan = ''
  filters.from = ''
  filters.to = ''
  filters.location = ''
  filters.sort = 'published_at'
  filters.order = 'desc'
  filters.page = 1
  void load()
}

function detailPath(task: TaskListItemView): string {
  return `${props.mode === 'worker' ? '/worker' : ''}/tasks/${task.id}`
}

async function apply(task: TaskListItemView): Promise<void> {
  if (applyingId.value) return
  applyingId.value = task.id
  try {
    await engagementApi.apply(task.id)
    message.success('报名已提交，等待雇主处理')
  } catch (raw) {
    const apiError = toApiError(raw)
    message.error(`${apiError.message}（${apiError.code}）`)
    if (apiError.isForbidden) void router.push('/403')
  } finally {
    applyingId.value = ''
  }
}
</script>

<template>
  <div class="stack stack--lg">
    <form class="filters" @submit.prevent="search">
      <div class="field field--wide">
        <label class="field__label" for="task-keyword">关键词</label>
        <input id="task-keyword" v-model="filters.keyword" class="input" placeholder="任务标题或描述" />
      </div>
      <div class="field">
        <label class="field__label" for="task-category">分类</label>
        <input id="task-category" v-model="filters.category" class="input" placeholder="如 展会服务" />
      </div>
      <div class="field">
        <label class="field__label" for="task-location">地点</label>
        <input id="task-location" v-model="filters.location" class="input" placeholder="如 上海" />
      </div>
      <div class="field">
        <label class="field__label" for="task-min">最低报酬（元）</label>
        <input id="task-min" v-model="filters.minYuan" class="input" inputmode="decimal" placeholder="不限" />
      </div>
      <div class="field">
        <label class="field__label" for="task-max">最高报酬（元）</label>
        <input id="task-max" v-model="filters.maxYuan" class="input" inputmode="decimal" placeholder="不限" />
      </div>
      <div class="field">
        <label class="field__label" for="task-from">开始不早于</label>
        <input id="task-from" v-model="filters.from" class="input" type="date" />
      </div>
      <div class="field">
        <label class="field__label" for="task-to">结束不晚于</label>
        <input id="task-to" v-model="filters.to" class="input" type="date" />
      </div>
      <div class="field">
        <label class="field__label" for="task-sort">排序</label>
        <select id="task-sort" v-model="filters.sort" class="select">
          <option value="published_at">最新发布</option>
          <option value="unit_price">按单个名额报酬</option>
        </select>
      </div>
      <div class="field">
        <label class="field__label" for="task-order">方向</label>
        <select id="task-order" v-model="filters.order" class="select">
          <option value="desc">降序</option>
          <option value="asc">升序</option>
        </select>
      </div>
      <div class="cluster">
        <button class="btn btn--ink btn--sm" type="submit">筛选</button>
        <button class="btn btn--line btn--sm" type="button" @click="reset">重置</button>
      </div>
    </form>

    <LoadingBlock v-if="loading && !data" :rows="4" label="正在读取任务" />

    <template v-else-if="error">
      <LoginGate v-if="error.isUnauthorized && mode === 'public'" />
      <ErrorBlock v-else :error="error" @retry="load" />
    </template>

    <template v-else-if="rows.length">
      <div class="task-list">
        <RouterLink v-for="task in rows" :key="task.id" class="task" :to="detailPath(task)">
          <div>
            <p class="task__title">{{ task.title }}</p>
            <p class="task__meta">
              {{ task.category }} · {{ task.workLocation }} · 剩 {{ task.remainingSlots }} 个名额
            </p>
            <p class="task__meta">{{ formatDate(task.startTime) }} 至 {{ formatDate(task.endTime) }}</p>
          </div>
          <div class="cluster">
            <span v-if="task.hasOpenDispute" class="stamp stamp--danger">争议中</span>
            <span v-else-if="task.hasSettlementAbnormal" class="stamp stamp--warn">结算异常</span>
            <span v-else-if="task.remainingSlots === 0" class="stamp stamp--muted">名额已满</span>
            <span v-else class="stamp stamp--active">可报名</span>
          </div>
          <div class="muted small">{{ task.workLocation }}</div>
          <div class="task__price"><MoneyText :fen="task.unitPrice" /><span class="muted small"> / 名额</span></div>
          <div class="right">
            <button
              v-if="mode === 'worker' && task.remainingSlots > 0"
              class="btn btn--line btn--sm"
              type="button"
              :disabled="applyingId === task.id"
              @click.prevent.stop="apply(task)"
            >
              {{ applyingId === task.id ? '提交中' : '报名' }}
            </button>
            <span v-else class="muted small">查看</span>
          </div>
        </RouterLink>
      </div>

      <DataPager
        :page="data?.page ?? 1"
        :total-pages="data?.totalPages ?? 1"
        :total="data?.total ?? '0'"
        @change="filters.page = $event; load()"
      />
    </template>

    <EmptyState v-else title="没有符合条件的任务" desc="换个关键词或放宽报酬、日期范围再试一次。">
      <template #actions>
        <button class="btn btn--line btn--sm" type="button" @click="reset">清空筛选</button>
      </template>
    </EmptyState>
  </div>
</template>
