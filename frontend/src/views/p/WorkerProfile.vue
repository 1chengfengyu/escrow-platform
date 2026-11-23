<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { chatApi } from '@/api/modules/chat'
import { engagementApi } from '@/api/modules/engagement'
import { identityApi } from '@/api/modules/identity'
import { toApiError, type ApiError } from '@/api/http'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import GapNotice from '@/components/GapNotice.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import LoginGate from '@/components/LoginGate.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useResource } from '@/composables/useResource'
import { useAuthStore } from '@/stores/auth'
import { formatDate, fromNow } from '@/utils/date'
import { formatRating, toNumber } from '@/utils/money'
import { statusLabel } from '@/utils/status'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const message = useMessage()

const id = computed(() => String(route.params.id))
const reviewPage = ref(1)
const contactTaskId = ref('')
const contacting = ref(false)
const contactError = ref<ApiError | null>(null)

const { data, loading, error, load } = useResource(async () => {
  const [card, reputation] = await Promise.all([
    identityApi.userCard(id.value),
    engagementApi.reputation(id.value, reviewPage.value, 8, true),
  ])
  return { card, reputation }
})

const isSelf = computed(() => auth.isLoggedIn && auth.userId === id.value)

async function contact(): Promise<void> {
  if (!auth.isLoggedIn) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  const taskId = Number(contactTaskId.value)
  if (!Number.isInteger(taskId) || taskId <= 0) {
    contactError.value = toApiError(new Error('请先填写需要关联的任务编号'))
    return
  }
  contacting.value = true
  contactError.value = null
  try {
    const conversation = await chatApi.create({ taskId, scope: 'INQUIRY', targetUserId: Number(id.value) })
    message.success('已建立沟通')
    const prefix = auth.identity === 'EMPLOYER' ? '/employer' : '/worker'
    await router.push(`${prefix}/conversations/${conversation.id}`)
  } catch (raw) {
    contactError.value = toApiError(raw)
  } finally {
    contacting.value = false
  }
}

function changeReviewPage(page: number): void {
  reviewPage.value = page
  void load()
}
</script>

<template>
  <div class="wrap section--tight">
    <LoadingBlock v-if="loading && !data" :rows="4" label="正在读取名片" />

    <LoginGate v-else-if="error && error.isUnauthorized" desc="服务者名片与信誉记录需要登录后查看。" />

    <EmptyState v-else-if="error && error.isNotFound" title="用户不存在" desc="链接可能已失效，或用户编号有误。" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <div v-else-if="data" class="stack stack--lg">
      <PageHeader eyebrow="PROFILE" :title="data.card.nickname" :desc="`身份：${statusLabel('identity', data.card.role)}`">
        <template #actions>
          <span class="stamp stamp--active">编号 {{ data.card.userId }}</span>
        </template>
      </PageHeader>

      <div class="stats stats--ledger">
        <div class="stat">
          <span class="stat__label">完成签约</span>
          <span class="stat__value">{{ data.reputation.completedContracts }}</span>
        </div>
        <div class="stat">
          <span class="stat__label">平均评分</span>
          <span class="stat__value">{{ formatRating(data.reputation.averageRatingX100) }}</span>
        </div>
        <div class="stat">
          <span class="stat__label">评价数</span>
          <span class="stat__value">{{ data.reputation.reviewCount }}</span>
        </div>
        <div class="stat">
          <span class="stat__label">超时取消率</span>
          <span class="stat__value">{{ (toNumber(data.reputation.timeoutRateBps) / 100).toFixed(2) }}%</span>
          <span class="stat__note">超时取消 {{ data.reputation.timeoutCancels }} 次</span>
        </div>
      </div>

      <div class="detail">
        <div class="stack stack--lg">
          <section class="card stack">
            <div class="card__head">
              <div>
                <h2 class="card__title">评价记录</h2>
                <p class="card__sub">按时间倒序，来自已完成签约</p>
              </div>
            </div>

            <div v-if="data.reputation.reviews.items.length" class="list">
              <article v-for="review in data.reputation.reviews.items" :key="review.createdAt + review.reviewerId" class="list__item">
                <div class="stack stack--sm">
                  <div class="cluster">
                    <span class="strong small">{{ review.reviewerNickname }}</span>
                    <span class="mono small" style="color: var(--accent)">
                      {{ '★'.repeat(review.rating) }}{{ '☆'.repeat(Math.max(0, 5 - review.rating)) }}
                    </span>
                  </div>
                  <p class="small ink2">{{ review.comment || '未填写文字评价' }}</p>
                  <p class="tiny muted">{{ formatDate(review.createdAt) }} · {{ fromNow(review.createdAt) }}</p>
                </div>
              </article>
            </div>
            <EmptyState v-else title="还没有评价" desc="完成签约并互相评价后，这里会出现记录。" />

            <DataPager
              :page="data.reputation.reviews.page"
              :total-pages="data.reputation.reviews.totalPages"
              :total="data.reputation.reviews.total"
              @change="changeReviewPage"
            />
          </section>

          <GapNotice
            title="技能、期望报酬与完整简历未开放"
            desc="当前名片只展示昵称、联系方式与角色。技能标签、期望报酬、可工作时间与项目经历暂未提供，本期不展示。"
          />
        </div>

        <aside class="stack">
          <section class="card stack">
            <p class="eyebrow">账号信息</p>
            <div class="kv">
              <div class="kv__row">
                <span class="kv__k">手机号</span>
                <span class="kv__v mono">{{ data.card.phone || '—' }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">邮箱</span>
                <span class="kv__v">{{ data.card.email || '—' }}</span>
              </div>
              <div class="kv__row">
                <span class="kv__k">角色</span>
                <span class="kv__v">{{ statusLabel('identity', data.card.role) }}</span>
              </div>
            </div>
            <p class="tiny muted">联系方式按身份脱敏展示。</p>
          </section>

          <section class="card stack">
            <p class="eyebrow">发起沟通</p>
            <template v-if="isSelf">
              <p class="small ink2">这是你自己的名片，不需要联系自己。</p>
              <RouterLink class="btn btn--line btn--sm" to="/worker/profile">去个人资料</RouterLink>
            </template>
            <template v-else>
              <p class="small ink2">
                沟通必须关联具体任务。请填写你正在关注的任务编号，再发起咨询。
              </p>
              <label class="field">
                <span class="field__label">关联任务编号</span>
                <input v-model="contactTaskId" class="input" inputmode="numeric" placeholder="例如 1900000000000000001" />
              </label>
              <button class="btn btn--ink btn--sm" type="button" :disabled="contacting" @click="contact">
                {{ contacting ? '正在建立沟通' : auth.isLoggedIn ? '发起沟通' : '登录后沟通' }}
              </button>
              <p v-if="contactError" class="field__error">{{ contactError.message }}（{{ contactError.code }}）</p>
            </template>
          </section>
        </aside>
      </div>
    </div>
  </div>
</template>
