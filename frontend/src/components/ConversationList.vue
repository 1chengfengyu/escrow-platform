<script setup lang="ts">
// 会话列表（W-14 / E-14 共用）
import { computed, ref } from 'vue'
import { chatApi } from '@/api/modules/chat'
import type { ChatScope } from '@/api/types'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { fromNow } from '@/utils/date'

const props = withDefaults(defineProps<{ basePath: string; taskPath?: string }>(), { taskPath: '' })

const scope = ref<'' | ChatScope>('')
const page = ref(1)
const size = 20

const { data, loading, error, load } = useResource(async () => {
  const [list, unread] = await Promise.all([
    chatApi.list(scope.value || undefined, page.value, size),
    chatApi.unreadCount(),
  ])
  return { list, unread }
})

const items = computed(() => data.value?.list.items ?? [])
const unreadTotal = computed(() => data.value?.unread.unreadCount ?? '0')
const totalPages = computed(() => data.value?.list.totalPages ?? 0)
const taskHref = computed(() => props.taskPath || `${props.basePath}/tasks`)

const tabs: Array<{ value: '' | ChatScope; label: string }> = [
  { value: '', label: '全部' },
  { value: 'INQUIRY', label: '咨询' },
  { value: 'APPLICATION', label: '报名沟通' },
  { value: 'CONTRACT', label: '履约沟通' },
]

function setScope(next: '' | ChatScope): void {
  if (scope.value === next) return
  scope.value = next
  page.value = 1
  void load()
}

function goPage(next: number): void {
  page.value = next
  void load()
}
</script>

<template>
  <div class="stack stack--lg">
    <div class="row row--between row--wrap">
      <div class="tabs">
        <button
          v-for="tab in tabs"
          :key="tab.value"
          class="tab"
          :class="{ 'is-active': scope === tab.value }"
          type="button"
          @click="setScope(tab.value)"
        >
          {{ tab.label }}
        </button>
      </div>
      <span class="small ink2">
        未读消息 <span class="mono strong">{{ unreadTotal }}</span>
      </span>
    </div>

    <LoadingBlock v-if="loading && !data" :rows="4" label="正在读取会话" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else>
      <div v-if="items.length" class="list">
        <RouterLink
          v-for="item in items"
          :key="item.id"
          class="list__item"
          :to="`${basePath}/conversations/${item.id}`"
        >
          <div class="stack stack--sm grow">
            <div class="cluster">
              <StatusTag kind="chatScope" :value="item.scope" />
              <span class="tiny muted mono">任务 {{ item.taskId }}</span>
            </div>
            <p class="small muted">最近消息 {{ fromNow(item.lastMessageAt) }}</p>
          </div>
          <span v-if="Number(item.unreadCount) > 0" class="count">{{ item.unreadCount }}</span>
          <span v-else class="tiny muted">无未读</span>
        </RouterLink>
      </div>

      <EmptyState v-else title="还没有沟通会话" desc="从任务详情点击「联系」后，会在这里建立会话。">
        <template #actions>
          <RouterLink class="btn btn--ink btn--sm" :to="taskHref">去看任务</RouterLink>
        </template>
      </EmptyState>

      <DataPager :page="page" :total-pages="totalPages" :total="data?.list.total ?? '0'" @change="goPage" />
    </template>
  </div>
</template>
