<script setup lang="ts">
// E-05 报名管理（FR-SIGN.APPLY.07/08 / FR-SIGN.HIRE.01~04）
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { engagementApi } from '@/api/modules/engagement'
import { taskApi } from '@/api/modules/task'
import { ApiError, toApiError } from '@/api/http'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import GapNotice from '@/components/GapNotice.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const confirm = useConfirm()

const initial = String(route.params.id ?? '')
const taskIdInput = ref(initial)
const activeTaskId = ref(initial)
const page = ref(1)

const { data, loading, error, load } = useResource(
  async () => {
    if (!activeTaskId.value) return null
    const [taskResult, applicationResult] = await Promise.allSettled([
      taskApi.detail(activeTaskId.value),
      engagementApi.applications(activeTaskId.value, page.value, 20),
    ])
    if (applicationResult.status === 'rejected') throw applicationResult.reason
    return {
      task: taskResult.status === 'fulfilled' ? taskResult.value : null,
      applications: applicationResult.value,
    }
  },
  { immediate: false },
)

const rows = computed(() => data.value?.applications.items ?? [])
const totalPages = computed(() => data.value?.applications.totalPages ?? 0)

const busy = ref('')
const actionError = ref<ApiError | null>(null)
const hired = ref<{ applicationId: string; contractId: string } | null>(null)

function search(): void {
  const id = taskIdInput.value.trim()
  if (!id) {
    actionError.value = new ApiError(-1, '请输入任务编号')
    return
  }
  actionError.value = null
  if (id !== activeTaskId.value) {
    void router.push(`/employer/tasks/${id}/applications`)
    return
  }
  page.value = 1
  void load()
}

function goPage(next: number): void {
  page.value = next
  void load()
}

async function hire(applicationId: string, nickname: string): Promise<void> {
  if (busy.value) return
  const ok = await confirm({
    title: '录用报名者',
    content: `确认录用「${nickname}」？录用会占用一个名额并形成签约。`,
    positiveText: '确认录用',
  })
  if (!ok) return
  busy.value = applicationId
  actionError.value = null
  try {
    const result = await engagementApi.hire(activeTaskId.value, applicationId)
    hired.value = { applicationId, contractId: result.contractId }
    message.success('录用成功，已形成签约')
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    busy.value = ''
  }
}

onMounted(() => {
  if (activeTaskId.value) void load()
})

watch(
  () => route.params.id,
  (next) => {
    const id = String(next ?? '')
    if (id === activeTaskId.value) return
    activeTaskId.value = id
    taskIdInput.value = id
    page.value = 1
    hired.value = null
    if (id) void load()
  },
)
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="APPLICATIONS"
      title="报名管理"
      desc="输入任务编号查看报名者。录用会占用名额并形成签约。"
    >
      <template #actions>
        <RouterLink class="btn btn--ink btn--sm" to="/employer/tasks/new">发布任务</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/engagements">验收入口</RouterLink>
      </template>
    </PageHeader>

    <section class="card stack">
      <form class="filters" @submit.prevent="search">
        <label class="field field--wide">
          <span class="field__label">任务编号</span>
          <input v-model="taskIdInput" class="input" inputmode="numeric" placeholder="从发布成功页或任务详情复制" />
          <span class="field__hint">列表按报名时间倒序，分页 20 条。</span>
        </label>
        <button class="btn btn--ink btn--sm" type="submit">查看报名</button>
      </form>
      <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
    </section>

    <div v-if="hired" class="notice notice--info">
      <p class="notice__eyebrow">录用成功</p>
      <p class="notice__desc">
        签约编号 <span class="mono">{{ hired.contractId }}</span>。
        <RouterLink class="linkbtn" :to="`/employer/engagements/${hired.contractId}/acceptance`">去验收页</RouterLink>
        ·
        <RouterLink class="linkbtn" to="/employer/conversations">去沟通</RouterLink>
      </p>
    </div>

    <LoadingBlock v-if="loading" :rows="4" label="正在读取报名" />

    <EmptyState
      v-else-if="!activeTaskId"
      title="先输入任务编号"
      desc="按任务查看报名，暂不提供跨任务的报名汇总。"
    />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <section v-if="data.task" class="card">
        <div class="row row--between row--wrap">
          <div class="stack stack--sm">
            <p class="strong">{{ data.task.title }}</p>
            <p class="tiny muted">
              {{ data.task.category }} · {{ data.task.workLocation }} · 剩 {{ data.task.remainingSlots }} 个名额
            </p>
          </div>
          <div class="cluster">
            <StatusTag kind="task" :value="data.task.status" />
            <RouterLink class="btn btn--line btn--sm" :to="`/employer/tasks/${data.task.id}`">任务详情</RouterLink>
          </div>
        </div>
      </section>

      <div v-if="rows.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>报名编号</th>
              <th>劳动者</th>
              <th>用户编号</th>
              <th>状态</th>
              <th>报名时间</th>
              <th class="num">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td class="mono">{{ row.id }}</td>
              <td>{{ row.workerNickname }}</td>
              <td class="mono">{{ row.workerId }}</td>
              <td><StatusTag kind="application" :value="row.status" /></td>
              <td>{{ formatDateTime(row.appliedAt) }}</td>
              <td class="num">
                <button
                  v-if="row.status === 'APPLY_PENDING'"
                  class="btn btn--ink btn--sm"
                  type="button"
                  :disabled="busy === row.id"
                  @click="hire(row.id, row.workerNickname)"
                >
                  {{ busy === row.id ? '录用中' : '录用' }}
                </button>
                <span v-else class="tiny muted">已处理</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <EmptyState v-else title="该任务还没有报名" desc="分享任务或稍后再来查看。">
        <template #actions>
          <RouterLink class="btn btn--line btn--sm" :to="`/employer/tasks/${activeTaskId}`">查看任务</RouterLink>
        </template>
      </EmptyState>

      <DataPager
        :page="page"
        :total-pages="totalPages"
        :total="data.applications.total"
        @change="goPage"
      />

      <GapNotice
        title="拒绝报名与跨任务筛选未开放"
        desc="名额录满后，其余待处理报名会自动拒绝；本页只支持按任务编号查看。"
      />
    </template>
  </div>
</template>
