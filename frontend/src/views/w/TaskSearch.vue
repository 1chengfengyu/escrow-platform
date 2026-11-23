<script setup lang="ts">
// W-02 任务检索与报名（FR-TASK.LIST.* / FR-SIGN.APPLY.*）
import { ref } from 'vue'
import { useMessage } from 'naive-ui'
import { aiApi } from '@/api/modules/ai'
import { toApiError } from '@/api/http'
import type { AiSlotTag, AiTaskSearch, AiTaskSearchItem } from '@/api/types'
import MoneyText from '@/components/MoneyText.vue'
import PageHeader from '@/components/PageHeader.vue'
import TaskBoard from '@/components/TaskBoard.vue'
import { formatDate } from '@/utils/date'

const message = useMessage()

/* ---- 一句话找活（V12.1）：与雇主共用 parse，槽位在服务端反转 ---- */
const aiQuery = ref('')
const aiParsing = ref(false)
const aiSearching = ref(false)
const aiDraftId = ref('')
const aiTags = ref<AiSlotTag[]>([])
const aiKeywords = ref<string[]>([])
const aiRemovedKeys = ref<string[]>([])
const aiDegraded = ref(false)
const aiResult = ref<AiTaskSearch | null>(null)
const aiError = ref('')
const aiSearchedOnce = ref(false)

/** 找活语义下的标签文案：预算上限 → 报酬至少，人数不参与筛选。 */
function workerTagLabel(tag: AiSlotTag): string {
  switch (tag.key) {
    case 'priceMax':
      return '报酬至少'
    case 'weekend':
      return '周末可做'
    case 'headcount':
      return '招工人数'
    default:
      return tag.label
  }
}

function workerTagValue(tag: AiSlotTag): string {
  if (tag.key === 'weekend') return '时间窗覆盖周末'
  if (tag.key === 'headcount') return `${tag.value}（不参与筛选）`
  return tag.value
}

async function aiParse(): Promise<void> {
  const text = aiQuery.value.trim()
  if (!text || aiParsing.value) return
  aiParsing.value = true
  aiError.value = ''
  aiResult.value = null
  aiTags.value = []
  aiKeywords.value = []
  aiRemovedKeys.value = []
  aiSearchedOnce.value = false
  try {
    const parsed = await aiApi.parse(text)
    aiDraftId.value = parsed.draftId
    aiTags.value = parsed.tags
    aiKeywords.value = parsed.slots.keywords ?? []
    aiDegraded.value = parsed.degraded
    if (!parsed.tags.length && !aiKeywords.value.length) aiError.value = '没有识别出可用条件，换个说法再试一次'
  } catch (raw) {
    aiError.value = toApiError(raw).message
  } finally {
    aiParsing.value = false
  }
}

function removeAiTag(key: string): void {
  if (aiRemovedKeys.value.includes(key)) return
  aiRemovedKeys.value = [...aiRemovedKeys.value, key]
  aiTags.value = aiTags.value.filter((tag) => tag.key !== key)
}

async function aiSearch(): Promise<void> {
  if (!aiDraftId.value || aiSearching.value) return
  aiSearching.value = true
  aiError.value = ''
  try {
    if (aiSearchedOnce.value && aiResult.value?.traceNo) {
      void aiApi.feedback(aiResult.value.traceNo, false, true).catch(() => undefined)
    }
    aiResult.value = await aiApi.searchTasks(aiDraftId.value, aiRemovedKeys.value)
    aiSearchedOnce.value = true
  } catch (raw) {
    const apiError = toApiError(raw)
    if (apiError.isNotFound) {
      aiDraftId.value = ''
      aiTags.value = []
      aiKeywords.value = []
      aiError.value = '解析结果已过期，请重新解析需求'
    } else {
      aiError.value = apiError.message
    }
  } finally {
    aiSearching.value = false
  }
}

function markTask(item: AiTaskSearchItem): void {
  if (aiResult.value?.traceNo) {
    void aiApi.feedback(aiResult.value.traceNo, true).catch(() => undefined)
  }
  message.info(`已记录你对「${item.title}」的关注`)
}

const EVIDENCE_LABELS: Record<string, string> = {
  title: '标题',
  unitPrice: '报酬',
  workLocation: '地点',
  startTime: '开始时间',
  endTime: '结束时间',
  remainingSlots: '剩余名额',
  publishedAt: '发布时间',
}

function evidenceLabel(key: string): string {
  return EVIDENCE_LABELS[key] ?? key
}
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="FIND WORK"
      title="找任务"
      desc="按关键词、分类、地点、报酬与时间筛选。报名后等待雇主处理，录用即形成签约。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/applications">我的报名</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/engagements">我的履约</RouterLink>
      </template>
    </PageHeader>

    <figure class="anchor anchor--slim">
      <img src="/media/anchor-search.jpg" alt="清晨街道上自行车车把夹着折叠城市地图" />
    </figure>

    <section class="card stack">
      <div class="card__head">
        <div>
          <p class="eyebrow">AI · FIND WORK</p>
          <h2 class="card__title">一句话找活</h2>
          <p class="card__sub">
            说清能做什么、想要多少报酬、在哪、什么时间方便。先说条件，看成标签后再检索，标签可以逐个去掉。
          </p>
        </div>
        <span v-if="aiDegraded" class="stamp stamp--muted">规则解析</span>
      </div>

      <form class="stack stack--sm" @submit.prevent="aiParse">
        <textarea
          v-model="aiQuery"
          class="textarea"
          rows="2"
          maxlength="200"
          placeholder="例如：我会短视频剪辑，想找北京 300 元以上的活，周末能做"
        />
        <div class="cluster">
          <button class="btn btn--ink btn--sm" type="submit" :disabled="aiParsing || !aiQuery.trim()">
            {{ aiParsing ? '正在解析' : '解析我的条件' }}
          </button>
          <button
            v-if="aiDraftId"
            class="btn btn--line btn--sm"
            type="button"
            :disabled="aiSearching"
            @click="aiSearch"
          >
            {{ aiSearching ? '检索中' : '按条件找活' }}
          </button>
        </div>
      </form>

      <div v-if="aiTags.length || aiKeywords.length" class="ai-tags">
        <span class="tiny muted">{{ aiTags.length ? '已识别条件（点 × 可去掉）' : '按关键词检索' }}</span>
        <button
          v-for="tag in aiTags"
          :key="tag.key"
          class="ai-tag"
          type="button"
          @click="removeAiTag(tag.key)"
        >
          {{ workerTagLabel(tag) }}：{{ workerTagValue(tag) }}<span aria-hidden="true"> ×</span>
        </button>
        <span v-for="kw in aiKeywords" :key="`kw-${kw}`" class="ai-tag ai-tag--static">
          关键词：{{ kw }}
        </span>
      </div>

      <p v-if="aiTags.length" class="tiny muted">
        「预算上限」在找活里按「报酬不低于」理解；招工人数不影响筛选结果。
      </p>

      <p v-if="aiError" class="field__error">{{ aiError }}</p>

      <template v-if="aiResult">
        <div class="cluster">
          <span class="tiny muted">
            策略 {{ aiResult.strategy }} · trace <span class="mono">{{ aiResult.traceNo }}</span>
          </span>
          <span class="tiny muted">共 {{ aiResult.items.length }} 条</span>
        </div>

        <div v-if="aiResult.items.length" class="list">
          <RouterLink
            v-for="item in aiResult.items"
            :key="item.taskId"
            class="list__item ai-hit"
            :to="`/worker/tasks/${item.taskId}`"
            @click="markTask(item)"
          >
            <div class="stack stack--sm">
              <p class="strong small">
                {{ item.title }} <span class="ai-score">匹配 {{ item.score }}</span>
              </p>
              <p class="tiny muted">
                {{ item.category }} · {{ item.workLocation }} · 剩 {{ item.remainingSlots }} 个名额
              </p>
              <p class="tiny muted">
                {{ formatDate(item.startTime) }} 至 {{ formatDate(item.endTime) }}
              </p>
              <p class="small ink2">{{ item.reason }}</p>
              <p class="tiny muted">
                依据：{{ item.evidenceFields.map((key) => evidenceLabel(key)).join('、') || '—' }}
              </p>
            </div>
            <div class="right">
              <span class="task__price"><MoneyText :fen="item.unitPrice" /><span class="muted small"> / 名额</span></span>
            </div>
          </RouterLink>
        </div>
        <p v-else class="small muted">{{ aiResult.note || '没有符合条件的任务，可去掉标签放宽条件再检索。' }}</p>
      </template>
    </section>

    <div class="notice notice--info">
      <p class="notice__eyebrow">报名守卫</p>
      <p class="notice__desc">
        名额已满、重复报名、已过结束时间、报名自己发布的任务都无法报名，页面会给出具体原因。报名结果以任务详情与站内通知为准。
      </p>
    </div>

    <TaskBoard mode="worker" />
  </div>
</template>
