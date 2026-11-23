<script setup lang="ts">
// 争议详情与取证（W-10 / E-11 共用）：补充证据、聊天取证、撤回
import { computed, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { chatApi } from '@/api/modules/chat'
import { disputeApi } from '@/api/modules/dispute'
import { ApiError, toApiError } from '@/api/http'
import type { MessageView } from '@/api/types'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'

const props = defineProps<{ id: string; basePath: string }>()

const message = useMessage()
const confirm = useConfirm()

const { data, loading, error, load } = useResource(() => disputeApi.detail(props.id))

const busy = ref('')
const actionError = ref<ApiError | null>(null)

const evidenceContent = ref('')
const evidenceImages = ref('')

const chatConversationId = ref('')
const chatMessages = ref<MessageView[]>([])
const chatLoading = ref(false)
const chatError = ref<ApiError | null>(null)
const selected = ref<string[]>([])

const pending = computed(() => data.value?.status === 'DISPUTE_PENDING')

function parseImages(raw: string): string[] {
  return raw
    .split(/[\n,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

async function addEvidence(): Promise<void> {
  if (busy.value) return
  const content = evidenceContent.value.trim()
  if (!content) {
    actionError.value = new ApiError(-1, '请填写证据说明')
    return
  }
  const images = parseImages(evidenceImages.value)
  if (images.some((url) => !/^https?:\/\//.test(url))) {
    actionError.value = new ApiError(-1, '图片需要是 http(s) 链接')
    return
  }
  busy.value = 'evidence'
  actionError.value = null
  try {
    await disputeApi.addEvidence(props.id, { content, imageUrls: images })
    evidenceContent.value = ''
    evidenceImages.value = ''
    message.success('证据已提交')
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    busy.value = ''
  }
}

async function withdraw(): Promise<void> {
  if (busy.value) return
  const ok = await confirm({
    title: '撤回申诉',
    content: '撤回后争议结束，按原流程继续。确定撤回吗？',
    positiveText: '确定撤回',
    danger: true,
  })
  if (!ok) return
  busy.value = 'withdraw'
  actionError.value = null
  try {
    await disputeApi.withdraw(props.id)
    message.success('申诉已撤回')
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    busy.value = ''
  }
}

async function loadChat(): Promise<void> {
  const id = chatConversationId.value.trim()
  if (!id) {
    chatError.value = new ApiError(-1, '请填写会话编号')
    return
  }
  chatLoading.value = true
  chatError.value = null
  selected.value = []
  try {
    const page = await chatApi.messages(id, 1, 50)
    chatMessages.value = page.items
    if (!page.items.length) chatError.value = new ApiError(-1, '该会话没有可取证的消息')
  } catch (raw) {
    chatMessages.value = []
    chatError.value = toApiError(raw)
  } finally {
    chatLoading.value = false
  }
}

function toggle(id: string): void {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((item) => item !== id)
    : [...selected.value, id]
}

async function submitChatEvidence(): Promise<void> {
  if (busy.value) return
  if (!selected.value.length) {
    actionError.value = new ApiError(-1, '请至少选择一条消息')
    return
  }
  busy.value = 'from-chat'
  actionError.value = null
  try {
    await disputeApi.evidenceFromChat(props.id, selected.value)
    message.success('聊天记录已作为证据提交')
    selected.value = []
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
    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取争议" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load">
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" :to="`${basePath}/disputes`">返回争议</RouterLink>
      </template>
    </ErrorBlock>

    <template v-else-if="data">
      <div class="detail">
        <div class="stack stack--lg">
          <section class="card">
            <div class="row row--between row--wrap">
              <div class="cluster">
                <StatusTag kind="dispute" :value="data.status" />
                <StatusTag kind="initiator" :value="data.initiatorType" />
                <span class="tiny muted mono">名额 {{ data.slotId }}</span>
              </div>
              <span class="tiny muted">发起 {{ formatDateTime(data.createdAt) }}</span>
            </div>
            <hr class="divider" />
            <p class="eyebrow">申诉理由</p>
            <p style="white-space: pre-wrap; line-height: 1.8">{{ data.reason }}</p>
            <div v-if="data.resolution" class="notice" style="margin-top: 12px">
              <p class="notice__eyebrow">裁决结果</p>
              <p class="notice__title">
                <StatusTag kind="resolution" :value="data.resolution" />
              </p>
              <p class="notice__desc">{{ data.resolutionBasis || '—' }}</p>
              <p class="tiny muted">裁决时间 {{ formatDateTime(data.resolvedAt) }} · 撤回次数 {{ data.withdrawCount }}</p>
            </div>
          </section>

          <section class="card stack">
            <div class="card__head">
              <div>
                <h2 class="card__title">证据</h2>
                <p class="card__sub">共 {{ data.evidence.length }} 条，按提交时间排序</p>
              </div>
            </div>

            <div v-if="data.evidence.length" class="list">
              <article v-for="item in data.evidence" :key="item.id" class="list__item">
                <div class="stack stack--sm grow">
                  <div class="cluster">
                    <StatusTag kind="initiator" :value="item.submitterType" />
                    <span class="tiny muted">{{ formatDateTime(item.createdAt) }}</span>
                  </div>
                  <p class="small" style="white-space: pre-wrap">{{ item.content }}</p>
                  <div v-if="item.imageUrls.length" class="cluster">
                    <a
                      v-for="url in item.imageUrls"
                      :key="url"
                      :href="url"
                      target="_blank"
                      rel="noreferrer"
                    >
                      <img
                        :src="url"
                        alt="证据图片"
                        style="width: 120px; height: 90px; object-fit: cover; border: 1px solid var(--line)"
                      />
                    </a>
                  </div>
                </div>
              </article>
            </div>
            <p v-else class="small muted">还没有证据。双方都可以提交说明与图片。</p>
          </section>
        </div>

        <aside class="stack">
          <section class="card stack">
            <p class="eyebrow">提交证据</p>
            <template v-if="pending">
              <label class="field">
                <span class="field__label">证据说明</span>
                <textarea v-model="evidenceContent" class="textarea" maxlength="500" placeholder="说明证据来源与证明内容" />
              </label>
              <label class="field">
                <span class="field__label">图片链接（每行一个）</span>
                <textarea v-model="evidenceImages" class="textarea" placeholder="https://example.com/evidence.jpg" />
              </label>
              <button class="btn btn--ink btn--block btn--sm" type="button" :disabled="busy === 'evidence'" @click="addEvidence">
                {{ busy === 'evidence' ? '正在提交' : '提交证据' }}
              </button>
            </template>
            <p v-else class="small muted">争议已结束，不能再补充证据。</p>
            <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
          </section>

          <section class="card stack">
            <p class="eyebrow">从聊天记录取证</p>
            <p class="tiny muted">填写会话编号，勾选消息后作为证据提交。会话编号可在消息列表的链接中找到。</p>
            <div class="row">
              <input v-model="chatConversationId" class="input grow" placeholder="会话编号" inputmode="numeric" />
              <button class="btn btn--line btn--sm" type="button" :disabled="chatLoading" @click="loadChat">
                {{ chatLoading ? '读取中' : '读取' }}
              </button>
            </div>
            <p v-if="chatError" class="field__error">{{ chatError.message }}（{{ chatError.code }}）</p>

            <div v-if="chatMessages.length" class="stack stack--sm" style="max-height: 260px; overflow-y: auto">
              <label v-for="item in chatMessages" :key="item.id" class="check">
                <input type="checkbox" :checked="selected.includes(item.id)" @change="toggle(item.id)" />
                <span class="tiny">
                  <span class="mono muted">#{{ item.id }}</span>
                  {{ item.content.slice(0, 80) }}{{ item.content.length > 80 ? '…' : '' }}
                </span>
              </label>
            </div>

            <button
              class="btn btn--line btn--block btn--sm"
              type="button"
              :disabled="!selected.length || busy === 'from-chat' || !pending"
              @click="submitChatEvidence"
            >
              {{ busy === 'from-chat' ? '正在提交' : `提交选中的 ${selected.length} 条` }}
            </button>
          </section>

          <section v-if="pending" class="card stack">
            <p class="eyebrow">撤回</p>
            <p class="small ink2">仅发起方可以撤回。撤回后争议结束，撤回次数会记录。</p>
            <button class="btn btn--line btn--block btn--sm" type="button" :disabled="busy === 'withdraw'" @click="withdraw">
              {{ busy === 'withdraw' ? '正在撤回' : '撤回申诉' }}
            </button>
          </section>

          <section class="card stack">
            <p class="eyebrow">相关入口</p>
            <div class="stack stack--sm">
              <RouterLink class="linkbtn" :to="`${basePath}/disputes`">争议入口</RouterLink>
              <RouterLink class="linkbtn" :to="`${basePath}/engagements`">我的履约</RouterLink>
              <RouterLink class="linkbtn" :to="`${basePath}/conversations`">沟通消息</RouterLink>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>
