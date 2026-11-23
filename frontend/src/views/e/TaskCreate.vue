<script setup lang="ts">
// E-02 发布任务（FR-TASK.CREATE.01~07 / FR-ESCROW.HOLD.01）
import { computed, onMounted, reactive, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { identityApi } from '@/api/modules/identity'
import { taskApi } from '@/api/modules/task'
import { aiApi } from '@/api/modules/ai'
import { ApiError, toApiError } from '@/api/http'
import type { AiSearch, AiSearchItem, AiSlotTag, TaskListItemView } from '@/api/types'
import MoneyText from '@/components/MoneyText.vue'
import PageHeader from '@/components/PageHeader.vue'
import { formatMoney, yuanToFen } from '@/utils/money'

const message = useMessage()

function defaultStart(): string {
  const date = new Date()
  date.setDate(date.getDate() + 1)
  date.setHours(9, 0, 0, 0)
  return toInputValue(date)
}

function defaultEnd(): string {
  const date = new Date()
  date.setDate(date.getDate() + 1)
  date.setHours(18, 0, 0, 0)
  return toInputValue(date)
}

function toInputValue(date: Date): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function toIso(value: string): string {
  return value.length === 16 ? `${value}:00` : value
}

const form = reactive({
  title: '',
  description: '',
  category: '',
  unitPrice: '',
  slotCount: '1',
  startTime: defaultStart(),
  endTime: defaultEnd(),
  workLocation: '',
  settlementNote: '',
})

const errors = reactive<Record<string, string>>({})
const submitting = ref('')
const actionError = ref<ApiError | null>(null)
const draftId = ref('')
const published = ref(false)

const similar = ref<TaskListItemView[]>([])
const similarChecking = ref(false)
const qualificationReady = ref<boolean | null>(null)

/* ---- 一句话找人（V12.1）：解析 → 可编辑标签 → 检索，非自动录用 ---- */
const aiQuery = ref('')
const aiParsing = ref(false)
const aiSearching = ref(false)
const aiDraftId = ref('')
const aiTags = ref<AiSlotTag[]>([])
const aiKeywords = ref<string[]>([])
const aiRemovedKeys = ref<string[]>([])
const aiDegraded = ref(false)
const aiResult = ref<AiSearch | null>(null)
const aiError = ref('')
const aiSearchedOnce = ref(false)

const EVIDENCE_LABELS: Record<string, string> = {
  skills: '技能',
  expectedUnitPrice: '报价',
  location: '地点',
  ratingX100: '评分',
  completedOrders: '完单数',
  weekendAvailable: '周末可接',
}

function evidenceLabel(key: string): string {
  return EVIDENCE_LABELS[key] ?? key
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
    aiResult.value = await aiApi.search(aiDraftId.value, aiRemovedKeys.value)
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

function markCandidate(item: AiSearchItem): void {
  if (aiResult.value?.traceNo) {
    void aiApi.feedback(aiResult.value.traceNo, true).catch(() => undefined)
  }
  message.info(`已记录你对服务者 #${item.userId} 的关注`)
}

const unitPriceFen = computed(() => yuanToFen(form.unitPrice) ?? 0)
const slotCountNumber = computed(() => Number.parseInt(form.slotCount, 10) || 0)
const escrowPreview = computed(() => unitPriceFen.value * slotCountNumber.value)

function clearErrors(): void {
  Object.keys(errors).forEach((key) => delete errors[key])
}

function validate(): boolean {
  clearErrors()
  const title = form.title.trim()
  if (!title) errors.title = '请填写标题'
  else if (title.length > 60) errors.title = '标题不超过 60 字'

  const description = form.description.trim()
  if (!description) errors.description = '请填写任务描述'
  else if (description.length > 2000) errors.description = '描述不超过 2000 字'

  const category = form.category.trim()
  if (!category) errors.category = '请填写类别'
  else if (category.length > 32) errors.category = '类别不超过 32 字'

  if (unitPriceFen.value <= 0) errors.unitPrice = '单价必须大于 0'
  if (slotCountNumber.value < 1 || slotCountNumber.value > 20) errors.slotCount = '名额数为 1~20'

  if (!form.startTime) errors.startTime = '请选择开始时间'
  else if (new Date(form.startTime).getTime() < Date.now() - 60_000) errors.startTime = '开始时间不得早于当前时间'

  if (!form.endTime) errors.endTime = '请选择结束时间'
  else if (form.startTime && new Date(form.endTime).getTime() <= new Date(form.startTime).getTime()) {
    errors.endTime = '结束时间必须晚于开始时间'
  }

  const location = form.workLocation.trim()
  if (!location) errors.workLocation = '请填写工作地点'
  else if (location.length > 128) errors.workLocation = '工作地点不超过 128 字'

  if (form.settlementNote.length > 500) errors.settlementNote = '结算说明不超过 500 字'

  return Object.keys(errors).length === 0
}

function payload() {
  return {
    title: form.title.trim(),
    description: form.description.trim(),
    category: form.category.trim(),
    unitPrice: unitPriceFen.value,
    slotCount: slotCountNumber.value,
    startTime: toIso(form.startTime),
    endTime: toIso(form.endTime),
    workLocation: form.workLocation.trim(),
    settlementNote: form.settlementNote.trim(),
  }
}

async function saveDraft(): Promise<void> {
  if (submitting.value || !validate()) return
  submitting.value = 'draft'
  actionError.value = null
  try {
    if (draftId.value) {
      await taskApi.update(draftId.value, payload())
      message.success('草稿已更新')
    } else {
      const created = await taskApi.create(payload())
      draftId.value = created.id
      message.success('草稿已保存，其他用户不可见')
    }
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    submitting.value = ''
  }
}

async function publish(): Promise<void> {
  if (submitting.value || published.value || !validate()) return
  submitting.value = 'publish'
  actionError.value = null
  try {
    if (draftId.value) {
      await taskApi.update(draftId.value, payload())
    } else {
      const created = await taskApi.create(payload())
      draftId.value = created.id
    }
    await taskApi.publish(draftId.value)
    published.value = true
    message.success('任务已发布，招募中')
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    submitting.value = ''
  }
}

async function checkSimilar(): Promise<void> {
  const keyword = form.title.trim()
  if (keyword.length < 4) {
    message.info('标题至少 4 个字后再检查')
    return
  }
  similarChecking.value = true
  try {
    const page = await taskApi.list({ keyword, page: 1, size: 3 })
    similar.value = page.items
    if (!page.items.length) message.success('未发现同名或近似的公开任务')
  } catch {
    similar.value = []
  } finally {
    similarChecking.value = false
  }
}

onMounted(async () => {
  try {
    const view = await identityApi.qualification()
    qualificationReady.value = view.publishEligible
  } catch {
    qualificationReady.value = null
  }
})
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="NEW TASK"
      title="发布任务"
      desc="先保存草稿，再发布。发布时会按「单价 × 名额」冻结任务金额。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/employer/qualification">资质登记</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/applications">报名管理</RouterLink>
      </template>
    </PageHeader>

    <div v-if="qualificationReady === false" class="notice">
      <p class="notice__eyebrow">资质未通过</p>
      <p class="notice__desc">
        资质未通过时无法发布，草稿仍可保存。
        <RouterLink class="linkbtn" to="/employer/qualification">去查看资质</RouterLink>
      </p>
    </div>

    <div v-if="published" class="card stack">
      <p class="eyebrow">已发布</p>
      <h2 class="card__title">任务已进入招募中</h2>
      <p class="small ink2">任务编号 <span class="mono">{{ draftId }}</span>。报名会进入报名管理，录用后形成签约。</p>
      <div class="cluster">
        <RouterLink class="btn btn--ink btn--sm" :to="`/employer/tasks/${draftId}`">查看任务与名额</RouterLink>
        <RouterLink class="btn btn--line btn--sm" :to="`/employer/tasks/${draftId}/applications`">
          查看报名
        </RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/tasks/new">再发一个</RouterLink>
      </div>
    </div>

    <div v-else class="detail">
      <div class="stack stack--lg">
        <section class="card stack">
          <div class="card__head">
            <div>
              <p class="eyebrow">AI · FIND PEOPLE</p>
              <h2 class="card__title">一句话找人</h2>
              <p class="card__sub">
                说清技能、预算、地点，先解析成标签，确认后再检索。结果只做推荐，录用仍在报名管理中完成。
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
              placeholder="例如：找个会短视频剪辑的，北京，预算 500 以内，周末能接单"
            />
            <div class="cluster">
              <button class="btn btn--ink btn--sm" type="submit" :disabled="aiParsing || !aiQuery.trim()">
                {{ aiParsing ? '正在解析' : '解析需求' }}
              </button>
              <button
                v-if="aiDraftId"
                class="btn btn--line btn--sm"
                type="button"
                :disabled="aiSearching"
                @click="aiSearch"
              >
                {{ aiSearching ? '检索中' : '按条件找候选人' }}
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
              {{ tag.label }}：{{ tag.value }}<span aria-hidden="true"> ×</span>
            </button>
            <span v-for="kw in aiKeywords" :key="`kw-${kw}`" class="ai-tag ai-tag--static">
              关键词：{{ kw }}
            </span>
          </div>

          <p v-if="aiError" class="field__error">{{ aiError }}</p>

          <template v-if="aiResult">
            <div class="cluster">
              <span class="tiny muted">
                策略 {{ aiResult.strategy }} · trace <span class="mono">{{ aiResult.traceNo }}</span>
              </span>
              <span v-if="aiResult.rerankApplied" class="stamp stamp--muted">语义重排</span>
              <span v-else-if="aiResult.rerankRejected" class="stamp stamp--muted">重排已拒绝</span>
            </div>
            <p v-if="aiResult.rerankNote" class="tiny muted">{{ aiResult.rerankNote }}</p>

            <div v-if="aiResult.items.length" class="list">
              <div
                v-for="item in aiResult.items"
                :key="item.userId"
                class="list__item ai-hit"
                role="button"
                tabindex="0"
                @click="markCandidate(item)"
                @keyup.enter="markCandidate(item)"
              >
                <div class="stack stack--sm">
                  <p class="strong small">
                    服务者 #{{ item.userId }} <span class="ai-score">匹配 {{ item.score }}</span>
                  </p>
                  <p class="tiny muted">
                    {{ item.skills.join('、') || '未填技能' }} · {{ item.location || '地点未填' }} · 期望
                    <MoneyText :fen="item.expectedUnitPrice" /> / 名额
                  </p>
                  <p class="small ink2">{{ item.reason }}</p>
                  <p class="tiny muted">
                    依据：{{ item.evidenceFields.map((key) => evidenceLabel(key)).join('、') || '—' }}
                  </p>
                </div>
                <span class="tiny muted">{{ (item.ratingX100 / 100).toFixed(2) }} 分 · {{ item.completedOrders }} 单</span>
              </div>
            </div>
            <p v-else class="small muted">没有符合条件的服务者，可去掉标签放宽条件再检索。</p>
          </template>
        </section>

        <section class="card stack">
          <div class="card__head">
            <div>
              <h2 class="card__title">任务资料</h2>
              <p class="card__sub">带 * 的字段为必填</p>
            </div>
          </div>

          <label class="field">
            <span class="field__label">标题 *</span>
            <input v-model="form.title" class="input" maxlength="60" placeholder="例如 展会现场布展协助" />
            <span v-if="errors.title" class="field__error">{{ errors.title }}</span>
          </label>

          <label class="field">
            <span class="field__label">任务描述 *</span>
            <textarea
              v-model="form.description"
              class="textarea"
              maxlength="2000"
              placeholder="工作内容、交付标准、注意事项"
            />
            <span class="field__hint">{{ form.description.length }} / 2000</span>
            <span v-if="errors.description" class="field__error">{{ errors.description }}</span>
          </label>

          <div class="form-grid">
            <label class="field">
              <span class="field__label">类别 *</span>
              <input v-model="form.category" class="input" maxlength="32" placeholder="例如 展会服务" />
              <span v-if="errors.category" class="field__error">{{ errors.category }}</span>
            </label>
            <label class="field">
              <span class="field__label">工作地点 *</span>
              <input v-model="form.workLocation" class="input" maxlength="128" placeholder="例如 上海 · 国家会展中心" />
              <span v-if="errors.workLocation" class="field__error">{{ errors.workLocation }}</span>
            </label>
            <label class="field">
              <span class="field__label">单价（元 / 名额）*</span>
              <input v-model="form.unitPrice" class="input" inputmode="decimal" placeholder="例如 500" />
              <span v-if="errors.unitPrice" class="field__error">{{ errors.unitPrice }}</span>
            </label>
            <label class="field">
              <span class="field__label">名额数 *</span>
              <input v-model="form.slotCount" class="input" inputmode="numeric" placeholder="1~20" />
              <span v-if="errors.slotCount" class="field__error">{{ errors.slotCount }}</span>
            </label>
            <label class="field">
              <span class="field__label">开始时间 *</span>
              <input v-model="form.startTime" class="input" type="datetime-local" />
              <span v-if="errors.startTime" class="field__error">{{ errors.startTime }}</span>
            </label>
            <label class="field">
              <span class="field__label">结束时间 *</span>
              <input v-model="form.endTime" class="input" type="datetime-local" />
              <span v-if="errors.endTime" class="field__error">{{ errors.endTime }}</span>
            </label>
          </div>

          <label class="field">
            <span class="field__label">结算说明</span>
            <textarea v-model="form.settlementNote" class="textarea" maxlength="500" placeholder="例如 验收通过后 3 个工作日内结算" />
            <span class="field__hint">{{ form.settlementNote.length }} / 500</span>
            <span v-if="errors.settlementNote" class="field__error">{{ errors.settlementNote }}</span>
          </label>
        </section>

        <section class="card stack">
          <div class="card__head">
            <div>
              <h2 class="card__title">疑似重复检查</h2>
              <p class="card__sub">用标题检索公开任务，只做提示，不阻止发布</p>
            </div>
            <button class="btn btn--line btn--sm" type="button" :disabled="similarChecking" @click="checkSimilar">
              {{ similarChecking ? '检查中' : '检查相似任务' }}
            </button>
          </div>
          <div v-if="similar.length" class="list">
            <RouterLink v-for="item in similar" :key="item.id" class="list__item" :to="`/employer/tasks/${item.id}`">
              <div class="stack stack--sm">
                <p class="strong small">{{ item.title }}</p>
                <p class="tiny muted">{{ item.category }} · {{ item.workLocation }}</p>
              </div>
              <span class="tiny muted mono">{{ item.id }}</span>
            </RouterLink>
          </div>
          <p v-else class="small muted">还没有检查。标题至少 4 个字可用。</p>
        </section>
      </div>

      <aside class="stack">
        <figure class="anchor anchor--side">
          <img src="/media/anchor-employer.jpg" alt="晨光下的木质桌面，摊开的记事本、钥匙与卷尺" />
        </figure>

        <section class="card stack">
          <p class="eyebrow">冻结金额预览</p>
          <p class="stat__value"><MoneyText :fen="String(escrowPreview)" /></p>
          <p class="tiny muted">单价 {{ formatMoney(String(unitPriceFen)) }} × {{ slotCountNumber || 0 }} 个名额</p>
          <hr class="divider" />
          <p class="small ink2">发布成功即冻结该金额；撤单会按规则退款。</p>
          <button class="btn btn--ink btn--block btn--sm" type="button" :disabled="submitting === 'publish'" @click="publish">
            {{ submitting === 'publish' ? '正在发布' : draftId ? '重新发布' : '发布任务' }}
          </button>
          <button class="btn btn--line btn--block btn--sm" type="button" :disabled="submitting === 'draft'" @click="saveDraft">
            {{ submitting === 'draft' ? '正在保存' : draftId ? '更新草稿' : '保存草稿' }}
          </button>
          <p v-if="actionError" class="field__error">
            {{ actionError.message }}（{{ actionError.code }}）
            <span v-if="actionError.traceId" class="tiny muted">traceId {{ actionError.traceId }}</span>
          </p>
        </section>

        <section v-if="draftId && !published" class="card stack">
          <p class="eyebrow">草稿已保存</p>
          <p class="small ink2">任务编号 <span class="mono">{{ draftId }}</span></p>
          <p class="tiny muted">发布失败时内容会保留，可修改后点「重新发布」。</p>
          <RouterLink class="btn btn--line btn--block btn--sm" :to="`/employer/tasks/${draftId}`">查看草稿详情</RouterLink>
        </section>

        <section class="card stack">
          <p class="eyebrow">发布后</p>
          <div class="stack stack--sm">
            <p class="small ink2">1. 任务进入招募中，劳动者可报名。</p>
            <p class="small ink2">2. 在报名管理中录用，形成签约。</p>
            <p class="small ink2">3. 劳动者交付后，在验收页处理。</p>
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>
