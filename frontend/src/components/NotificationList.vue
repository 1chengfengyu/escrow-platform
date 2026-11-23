<script setup lang="ts">
// 站内信列表（W-12 / E-13 共用）
import { computed, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { notificationApi } from '@/api/modules/notification'
import { ApiError, toApiError } from '@/api/http'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'

const message = useMessage()
const filter = ref<'' | 'unread'>('')
const page = ref(1)
const size = 20

const { data, loading, error, load } = useResource(async () => {
  const [list, unread] = await Promise.all([
    notificationApi.list(filter.value === 'unread' ? 0 : undefined, page.value, size),
    notificationApi.unreadCount(),
  ])
  return { list, unread }
})

const items = computed(() => data.value?.list.items ?? [])
const unreadCount = computed(() => data.value?.unread.unreadCount ?? '0')
const totalPages = computed(() => data.value?.list.totalPages ?? 0)

const busy = ref('')
const actionError = ref<ApiError | null>(null)

function setFilter(next: '' | 'unread'): void {
  if (filter.value === next) return
  filter.value = next
  page.value = 1
  void load()
}

function goPage(next: number): void {
  page.value = next
  void load()
}

async function markRead(id: string): Promise<void> {
  if (busy.value) return
  busy.value = id
  actionError.value = null
  try {
    await notificationApi.markRead(id)
    message.success('已标记为已读')
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    busy.value = ''
  }
}

async function markAll(): Promise<void> {
  if (busy.value) return
  busy.value = 'all'
  actionError.value = null
  try {
    const result = await notificationApi.markAllRead()
    message.success(`已全部标记为已读（${result.updated} 条）`)
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="stack stack--lg">
    <div class="row row--between row--wrap">
      <div class="tabs">
        <button class="tab" :class="{ 'is-active': filter === '' }" type="button" @click="setFilter('')">
          全部
        </button>
        <button class="tab" :class="{ 'is-active': filter === 'unread' }" type="button" @click="setFilter('unread')">
          未读
          <span v-if="Number(unreadCount) > 0" class="count">{{ unreadCount }}</span>
        </button>
      </div>
      <button
        class="btn btn--line btn--sm"
        type="button"
        :disabled="Number(unreadCount) === 0 || busy === 'all'"
        @click="markAll"
      >
        {{ busy === 'all' ? '正在处理' : '全部标记已读' }}
      </button>
    </div>

    <LoadingBlock v-if="loading && !data" :rows="4" label="正在读取通知" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else>
      <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>

      <div v-if="items.length" class="list">
        <article v-for="item in items" :key="item.id" class="list__item">
          <div class="stack stack--sm grow">
            <div class="cluster">
              <span class="stamp" :class="item.read ? 'stamp--muted' : 'stamp--warn'">
                {{ item.read ? '已读' : '未读' }}
              </span>
              <span class="tiny muted mono">{{ item.bizType }} · {{ item.bizNo }}</span>
            </div>
            <p class="strong">{{ item.title }}</p>
            <p class="small ink2" style="white-space: pre-wrap">{{ item.content }}</p>
            <p class="tiny muted">{{ formatDateTime(item.createdAt) }}</p>
          </div>
          <button
            v-if="!item.read"
            class="btn btn--line btn--sm"
            type="button"
            :disabled="busy === item.id"
            @click="markRead(item.id)"
          >
            {{ busy === item.id ? '处理中' : '标记已读' }}
          </button>
        </article>
      </div>

      <EmptyState
        v-else
        :title="filter === 'unread' ? '没有未读通知' : '暂时没有通知'"
        desc="报名结果、验收、结算与争议进展都会写入站内信。"
      />

      <DataPager :page="page" :total-pages="totalPages" :total="data?.list.total ?? '0'" @change="goPage" />
    </template>
  </div>
</template>
