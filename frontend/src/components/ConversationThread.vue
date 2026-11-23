<script setup lang="ts">
// 会话详情（W-15 / E-15 共用）：正序消息 + afterId 增量轮询 + 幂等发送
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { chatApi } from '@/api/modules/chat'
import { toApiError, type ApiError } from '@/api/http'
import type { MessageView } from '@/api/types'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { useAuthStore } from '@/stores/auth'
import { useBadgeStore } from '@/stores/badges'
import { formatDateTime, fromNow } from '@/utils/date'

const props = defineProps<{ id: string; basePath: string }>()

const auth = useAuthStore()
const badges = useBadgeStore()

const { data, loading, error, load } = useResource(
  async () => {
    const [conversation, page] = await Promise.all([
      chatApi.detail(props.id),
      chatApi.messages(props.id, 1, 50),
    ])
    return { conversation, page }
  },
  { immediate: false },
)

const conversation = computed(() => data.value?.conversation ?? null)
const messages = ref<MessageView[]>([])
const lastId = ref('')
const box = ref<HTMLElement | null>(null)

const draft = ref('')
const sending = ref(false)
const sendError = ref<ApiError | null>(null)
const polling = ref(false)
let timer: number | undefined

const counterpart = computed(() => {
  const row = conversation.value
  if (!row) return ''
  return row.employerId === auth.userId ? row.workerNickname : row.employerNickname
})

const archived = computed(() => conversation.value?.status === 'ARCHIVED')

function scrollToBottom(): void {
  void nextTick(() => {
    if (box.value) box.value.scrollTop = box.value.scrollHeight
  })
}

async function markRead(): Promise<void> {
  try {
    await chatApi.markRead(props.id)
    void badges.refresh()
  } catch {
    // 已读失败不打断阅读
  }
}

watch(
  data,
  (value) => {
    if (!value) return
    messages.value = value.page.items
    lastId.value = value.page.items.length ? value.page.items[value.page.items.length - 1].id : ''
    scrollToBottom()
    void markRead()
  },
  { immediate: true },
)

async function poll(): Promise<void> {
  if (polling.value || !lastId.value) return
  if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
  polling.value = true
  try {
    const page = await chatApi.messages(props.id, 1, 50, lastId.value)
    const fresh = page.items.filter((item) => !messages.value.some((seen) => seen.id === item.id))
    if (!fresh.length) return
    messages.value = [...messages.value, ...fresh]
    lastId.value = fresh[fresh.length - 1].id
    scrollToBottom()
    if (fresh.some((item) => item.senderId !== auth.userId)) void markRead()
  } catch {
    // 轮询失败静默，下一轮再试
  } finally {
    polling.value = false
  }
}

function clientMessageId(): string {
  const cryptoApi = globalThis.crypto
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') return cryptoApi.randomUUID()
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function send(): Promise<void> {
  const content = draft.value.trim()
  if (!content || sending.value || archived.value) return
  sending.value = true
  sendError.value = null
  try {
    const created = await chatApi.send(props.id, { content, clientMessageId: clientMessageId() })
    if (!messages.value.some((item) => item.id === created.id)) {
      messages.value = [...messages.value, created]
      lastId.value = created.id
    }
    draft.value = ''
    await nextTick()
    scrollToBottom()
    void markRead()
  } catch (raw) {
    sendError.value = toApiError(raw)
  } finally {
    sending.value = false
  }
}

function onKeydown(event: KeyboardEvent): void {
  if (event.isComposing) return
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void send()
  }
}

function senderName(item: MessageView): string {
  if (item.senderId === auth.userId) return '我'
  return counterpart.value || '对方'
}

onMounted(() => {
  void load()
  timer = window.setInterval(poll, 5000)
})

watch(
  () => props.id,
  () => {
    messages.value = []
    lastId.value = ''
    draft.value = ''
    void load()
  },
)

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="stack stack--lg">
    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取会话" />

    <template v-else-if="error">
      <ErrorBlock :error="error" @retry="load">
        <template #actions>
          <RouterLink class="btn btn--line btn--sm" :to="`${basePath}/conversations`">返回会话列表</RouterLink>
        </template>
      </ErrorBlock>
    </template>

    <template v-else-if="conversation">
      <div class="card">
        <div class="row row--between row--wrap">
          <div class="stack stack--sm">
            <div class="cluster">
              <StatusTag kind="chatScope" :value="conversation.scope" />
              <StatusTag kind="conversation" :value="conversation.status" />
              <span class="tiny muted mono">任务 {{ conversation.taskId }}</span>
            </div>
            <h2 class="card__title">{{ conversation.taskTitle }}</h2>
            <p class="small muted">
              与 <span class="strong">{{ counterpart }}</span> 沟通 · 最近 {{ fromNow(conversation.lastMessageAt) }}
            </p>
          </div>
          <div class="cluster">
            <RouterLink class="btn btn--line btn--sm" :to="`${basePath}/tasks/${conversation.taskId}`">
              查看任务
            </RouterLink>
            <RouterLink class="btn btn--line btn--sm" :to="`${basePath}/conversations`">返回列表</RouterLink>
          </div>
        </div>
      </div>

      <div class="card">
        <div ref="box" class="messages">
          <div
            v-for="item in messages"
            :key="item.id"
            class="message"
            :class="{
              'message--mine': item.senderId === auth.userId,
              'message--system': item.msgType === 'SYSTEM',
            }"
          >
            <div class="message__meta">
              {{ senderName(item) }} · {{ formatDateTime(item.createdAt) }}
              <span v-if="item.contactHint" class="stamp stamp--warn" style="margin-left: 6px">含联系方式</span>
            </div>
            <div class="message__body">{{ item.content }}</div>
          </div>
        </div>

        <p v-if="!messages.length" class="small muted" style="padding: 8px 0">
          还没有消息，发第一条开始沟通。
        </p>

        <div v-if="archived" class="notice" style="margin-top: 12px">
          <p class="notice__eyebrow">会话已归档</p>
          <p class="notice__desc">归档会话只读，不能继续发送消息。</p>
        </div>

        <form v-else class="composer" @submit.prevent="send">
          <label class="field grow">
            <span class="field__label">发送消息</span>
            <textarea
              v-model="draft"
              class="textarea"
              maxlength="2000"
              placeholder="Enter 发送，Shift + Enter 换行"
              @keydown="onKeydown"
            />
            <span class="field__hint">{{ draft.length }} / 2000 · 发送使用客户端消息号，重复提交不会产生重复消息</span>
          </label>
          <button class="btn btn--ink btn--sm" type="submit" :disabled="!draft.trim() || sending">
            {{ sending ? '发送中' : '发送' }}
          </button>
        </form>
        <p v-if="sendError" class="field__error">{{ sendError.message }}（{{ sendError.code }}）</p>
      </div>

      <div class="notice notice--info">
        <p class="notice__eyebrow">联系方式</p>
        <p class="notice__desc">
          平台会拦截带联系方式的消息并提示。履约、验收与结算均在站内完成，避免线下交易失去凭证。
        </p>
      </div>
    </template>
  </div>
</template>
