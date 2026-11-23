<script setup lang="ts">
// A-11 会话审查（只读；无会话列表接口）
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { chatApi } from '@/api/modules/chat'
import { ApiError } from '@/api/http'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import GapNotice from '@/components/GapNotice.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'

const route = useRoute()
const router = useRouter()

const initial = String(route.params.id ?? '')
const idInput = ref(initial)
const activeId = ref(initial)
const page = ref(1)
const actionError = ref<ApiError | null>(null)

const { data, loading, error, load } = useResource(
  () => chatApi.adminMessages(activeId.value, page.value, 50),
  { immediate: false },
)

const hasId = computed(() => Boolean(activeId.value))

function openConversation(): void {
  const id = idInput.value.trim()
  if (!id) {
    actionError.value = new ApiError(-1, '请输入会话编号')
    return
  }
  actionError.value = null
  if (id !== activeId.value) {
    void router.push(`/admin/conversations/${id}`)
    return
  }
  page.value = 1
  void load()
}

function goPage(next: number): void {
  page.value = next
  void load()
}

watch(
  () => route.params.id,
  (next) => {
    const id = String(next ?? '')
    if (id === activeId.value) return
    activeId.value = id
    idInput.value = id
    page.value = 1
    if (id) void load()
  },
)

if (initial) void load()
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="CONVERSATIONS"
      title="会话审查"
      desc="只读读取指定会话的消息，每次查询都会写一条取证审计。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/admin/disputes">从争议进入</RouterLink>
      </template>
    </PageHeader>

    <section class="card stack">
      <form class="filters" @submit.prevent="openConversation">
        <label class="field field--wide">
          <span class="field__label">会话编号</span>
          <input v-model="idInput" class="input" inputmode="numeric" placeholder="从争议、履约或通知中获取" />
          <span class="field__hint">消息按时间正序，每页 50 条。</span>
        </label>
        <button class="btn btn--ink btn--sm" type="submit">读取消息</button>
      </form>
      <p v-if="actionError" class="field__error">{{ actionError.message }}</p>
    </section>

    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取会话消息" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <EmptyState v-else-if="!hasId" title="先输入会话编号" desc="按会话编号读取。暂不提供全站会话列表。" />

    <template v-else-if="data">
      <div v-if="data.items.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>消息编号</th>
              <th>发送者</th>
              <th>类型</th>
              <th>内容</th>
              <th>联系方式提示</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in data.items" :key="row.id">
              <td class="mono">{{ row.id }}</td>
              <td class="mono">{{ row.senderId }}</td>
              <td><StatusTag kind="message" :value="row.msgType" /></td>
              <td>{{ row.content }}</td>
              <td>{{ row.contactHint ? '是' : '否' }}</td>
              <td>{{ formatDateTime(row.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <EmptyState v-else title="该会话还没有消息" desc="确认会话编号是否正确。" />

      <DataPager :page="page" :total-pages="data.totalPages" :total="data.total" @change="goPage" />

      <GapNotice
        title="管理端会话列表未开放"
        desc="按会话编号读取消息与取证，不提供全站会话列表。请从争议详情、履约或通知中的编号进入。"
      />
    </template>
  </div>
</template>
