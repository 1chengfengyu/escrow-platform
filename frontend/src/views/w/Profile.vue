<script setup lang="ts">
// W-11 个人资料（只读）：账号信息、资质状态与 AI 服务者画像
import { computed, onMounted, reactive, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { identityApi } from '@/api/modules/identity'
import { aiApi } from '@/api/modules/ai'
import { toApiError } from '@/api/http'
import type { AiProfile, AiProfileSource, QualificationView, UserCardResponse } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import GapNotice from '@/components/GapNotice.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/date'
import { yuanToFen } from '@/utils/money'
import { statusLabel } from '@/utils/status'

const auth = useAuthStore()
const message = useMessage()

function pick<T>(results: PromiseSettledResult<unknown>[], index: number): T | null {
  const item = results[index]
  return item && item.status === 'fulfilled' ? (item.value as T) : null
}

const { data, loading, error, load } = useResource(async () => {
  const results = await Promise.allSettled([identityApi.userCard(auth.userId), identityApi.qualification()])
  return {
    card: pick<UserCardResponse>(results, 0),
    qualification: pick<QualificationView>(results, 1),
    qualificationFailed: results[1].status === 'rejected',
  }
})

const card = computed(() => data.value?.card ?? null)
const qualification = computed(() => data.value?.qualification ?? null)

const identities = computed(() => auth.identities.map((item) => statusLabel('identity', item)))

/* ---- AI 服务者画像（V10-c3）：检索与推荐理由的事实来源 ---- */
const SOURCE_LABELS: Record<AiProfileSource, string> = {
  USER_INPUT: '本人填写',
  DERIVED: '业务派生',
  MISSING: '未填写',
}

const profileForm = reactive({
  skills: '',
  experienceYears: '',
  expectedUnitPrice: '',
  location: '',
  weekendAvailable: false,
})

const aiProfile = ref<AiProfile | null>(null)
const aiProfileLoading = ref(true)
const aiProfileSaving = ref(false)
const aiProfileError = ref('')
const aiProfileMissing = ref(false)

function applyProfile(profile: AiProfile): void {
  aiProfile.value = profile
  profileForm.skills = profile.skills.join('、')
  profileForm.experienceYears = profile.experienceYears == null ? '' : String(profile.experienceYears)
  profileForm.expectedUnitPrice =
    profile.expectedUnitPrice == null ? '' : (Number(profile.expectedUnitPrice) / 100).toString()
  profileForm.location = profile.location ?? ''
  profileForm.weekendAvailable = profile.weekendAvailable === true
}

async function loadAiProfile(): Promise<void> {
  aiProfileLoading.value = true
  aiProfileError.value = ''
  aiProfileMissing.value = false
  try {
    applyProfile(await aiApi.profile())
  } catch (raw) {
    const apiError = toApiError(raw)
    if (apiError.isNotFound) aiProfileMissing.value = true
    else aiProfileError.value = apiError.message
  } finally {
    aiProfileLoading.value = false
  }
}

async function saveAiProfile(): Promise<void> {
  if (aiProfileSaving.value) return
  const skills = profileForm.skills
    .split(/[,，、;；\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
  aiProfileError.value = ''

  let years: number | null = null
  if (profileForm.experienceYears.trim()) {
    years = Number.parseInt(profileForm.experienceYears.trim(), 10)
    if (Number.isNaN(years) || years < 0 || years > 60) {
      aiProfileError.value = '从业年限需在 0~60 之间'
      return
    }
  }

  let price: number | null = null
  if (profileForm.expectedUnitPrice.trim()) {
    price = yuanToFen(profileForm.expectedUnitPrice)
    if (price == null || price <= 0) {
      aiProfileError.value = '期望单价需为大于 0 的金额'
      return
    }
  }

  aiProfileSaving.value = true
  try {
    applyProfile(
      await aiApi.saveProfile({
        skills,
        experienceYears: years,
        expectedUnitPrice: price,
        location: profileForm.location.trim() || null,
        weekendAvailable: profileForm.weekendAvailable,
      }),
    )
    aiProfileMissing.value = false
    message.success('画像已保存，检索时按最新版本计算')
  } catch (raw) {
    aiProfileError.value = toApiError(raw).message
  } finally {
    aiProfileSaving.value = false
  }
}

function sourceEntries(): Array<[string, AiProfileSource]> {
  const sources = aiProfile.value?.sources
  if (!sources) return []
  return Object.entries(sources)
}

onMounted(() => void loadAiProfile())
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="PROFILE"
      title="个人资料"
      desc="账号信息由注册资料与后台治理共同决定，本期只读。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/reputation">我的信誉</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/wallet">钱包</RouterLink>
      </template>
    </PageHeader>

    <LoadingBlock v-if="loading && !data" :rows="4" label="正在读取资料" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else>
      <EmptyState v-if="!card" title="资料暂时无法读取" desc="可以稍后刷新，或从通知进入其他页面。">
        <template #actions>
          <button class="btn btn--line btn--sm" type="button" @click="load">重试</button>
        </template>
      </EmptyState>

      <template v-else>
        <section class="card stack">
          <div class="card__head">
            <div>
              <h2 class="card__title">账号信息</h2>
              <p class="card__sub">用户编号 <span class="mono">{{ card.userId }}</span></p>
            </div>
            <StatusTag kind="identity" :value="auth.identity" />
          </div>

          <div class="form-grid">
            <label class="field">
              <span class="field__label">昵称</span>
              <input class="input" :value="card.nickname" readonly />
            </label>
            <label class="field">
              <span class="field__label">当前身份</span>
              <input class="input" :value="statusLabel('identity', auth.identity)" readonly />
            </label>
            <label class="field">
              <span class="field__label">手机号</span>
              <input class="input" :value="card.phone" readonly />
            </label>
            <label class="field">
              <span class="field__label">邮箱</span>
              <input class="input" :value="card.email" readonly />
            </label>
            <label class="field field--full">
              <span class="field__label">可切换身份</span>
              <input class="input" :value="identities.join('、') || '—'" readonly />
            </label>
          </div>
          <p class="tiny muted">身份切换在右上角的身份下拉中完成。</p>
        </section>

        <section class="card stack">
          <div class="card__head">
            <div>
              <h2 class="card__title">资质状态</h2>
              <p class="card__sub">发布任务需要资质审核通过</p>
            </div>
            <StatusTag v-if="qualification" kind="review" :value="qualification.status" />
          </div>

          <template v-if="qualification">
            <div class="kv">
              <div class="kv__row">
                <span class="kv__k">主体类型</span>
                <span class="kv__v">{{ statusLabel('subject', qualification.subjectType) }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">主体名称</span>
                <span class="kv__v">{{ qualification.subjectName || '—' }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">证件尾号</span>
                <span class="kv__v mono">{{ qualification.idLast4 || '—' }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">发布资格</span>
                <span class="kv__v">{{ qualification.publishEligible ? '已具备' : '未具备' }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">审核意见</span>
                <span class="kv__v">{{ qualification.reviewReason || '—' }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">审核时间</span>
                <span class="kv__v">{{ formatDateTime(qualification.reviewedAt) }}</span>
              </div>
            </div>
          </template>
          <p v-else-if="data?.qualificationFailed" class="small muted">
            资质信息暂时无法读取（可能当前身份不适用）。可稍后刷新。
          </p>
          <p v-else class="small muted">还没有提交资质材料。</p>
        </section>

        <section class="card stack ai-profile">
          <div class="card__head">
            <div>
              <p class="eyebrow">AI · PROFILE</p>
              <h2 class="card__title">AI 服务者画像</h2>
              <p class="card__sub">
                雇主用「一句话找人」检索时，排序与推荐理由都以此为准。只填你真实具备的条件，完单数与评分由业务派生。
              </p>
            </div>
            <span v-if="aiProfile" class="stamp stamp--muted">版本 {{ aiProfile.profileVersion }}</span>
          </div>

          <p v-if="aiProfileLoading" class="small muted">正在读取画像…</p>

          <template v-else>
            <p v-if="aiProfileMissing" class="notice__desc">
              还没有填写画像。填写后雇主检索才可能找到你，未填写不会报错。
            </p>

            <div class="form-grid">
              <label class="field field--full">
                <span class="field__label">技能</span>
                <input v-model="profileForm.skills" class="input" placeholder="用顿号或逗号分隔，如 短视频剪辑、拍摄" />
                <span class="field__hint">写入前会做提示注入清洗，只保留标签</span>
              </label>
              <label class="field">
                <span class="field__label">从业年限</span>
                <input v-model="profileForm.experienceYears" class="input" inputmode="numeric" placeholder="如 3" />
              </label>
              <label class="field">
                <span class="field__label">期望单价（元 / 名额）</span>
                <input v-model="profileForm.expectedUnitPrice" class="input" inputmode="decimal" placeholder="如 500" />
              </label>
              <label class="field">
                <span class="field__label">常驻地点</span>
                <input v-model="profileForm.location" class="input" placeholder="如 北京" />
              </label>
              <label class="field">
                <span class="field__label">周末可接</span>
                <span class="cluster">
                  <input v-model="profileForm.weekendAvailable" type="checkbox" />
                  <span class="small ink2">{{ profileForm.weekendAvailable ? '可以接周末任务' : '仅工作日' }}</span>
                </span>
              </label>
            </div>

            <div v-if="aiProfile?.completedOrders != null || aiProfile?.ratingX100 != null" class="kv">
              <div v-if="aiProfile?.completedOrders != null" class="kv__row">
                <span class="kv__k">完单数（派生）</span>
                <span class="kv__v">{{ aiProfile.completedOrders }} 单</span>
              </div>
              <div v-if="aiProfile?.ratingX100 != null" class="kv__row">
                <span class="kv__k">评分（派生）</span>
                <span class="kv__v">{{ (aiProfile.ratingX100 / 100).toFixed(2) }} 分</span>
              </div>
            </div>

            <div v-if="sourceEntries().length" class="ai-tags">
              <span class="tiny muted">字段来源</span>
              <span v-for="[field, source] in sourceEntries()" :key="field" class="ai-tag ai-tag--static">
                {{ field }} · {{ SOURCE_LABELS[source] }}
              </span>
            </div>

            <p v-if="aiProfileError" class="field__error">{{ aiProfileError }}</p>

            <div class="cluster">
              <button class="btn btn--ink btn--sm" type="button" :disabled="aiProfileSaving" @click="saveAiProfile">
                {{ aiProfileSaving ? '正在保存' : aiProfile ? '保存画像' : '创建画像' }}
              </button>
              <button class="btn btn--ghost btn--sm" type="button" :disabled="aiProfileLoading" @click="loadAiProfile">
                重新读取
              </button>
            </div>
          </template>
        </section>

        <GapNotice
          title="资料编辑未开放"
          desc="昵称、手机号、邮箱与头像当前不可修改，本页字段均为只读。"
        />
      </template>
    </template>
  </div>
</template>
