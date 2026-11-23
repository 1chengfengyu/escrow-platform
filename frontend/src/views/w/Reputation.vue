<script setup lang="ts">
// W-13 我的信誉（FR-REVIEW.REPUTATION.01）
import { computed, ref } from 'vue'
import { engagementApi } from '@/api/modules/engagement'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useResource } from '@/composables/useResource'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/date'
import { formatRateBps, formatRating } from '@/utils/money'

const auth = useAuthStore()
const page = ref(1)
const size = 10

const { data, loading, error, load } = useResource(() => engagementApi.reputation(auth.userId, page.value, size))

const reviews = computed(() => data.value?.reviews.items ?? [])
const totalPages = computed(() => data.value?.reviews.totalPages ?? 0)

function goPage(next: number): void {
  page.value = next
  void load()
}
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="REPUTATION"
      title="我的信誉"
      desc="由已完成签约与收到的评价汇总，用于雇主判断合作风险。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/profile">个人资料</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/engagements">我的履约</RouterLink>
      </template>
    </PageHeader>

    <LoadingBlock v-if="loading && !data" :rows="3" label="正在读取信誉" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <div class="stats stats--ledger">
        <div class="stat">
          <span class="stat__label">平均评分</span>
          <span class="stat__value">{{ formatRating(data.averageRatingX100) }}</span>
          <span class="stat__note">基于 {{ data.reviewCount }} 条评价</span>
        </div>
        <div class="stat">
          <span class="stat__label">已完成签约</span>
          <span class="stat__value">{{ data.completedContracts }}</span>
          <span class="stat__note">验收通过并进入结算</span>
        </div>
        <div class="stat">
          <span class="stat__label">超时取消</span>
          <span class="stat__value">{{ data.timeoutCancels }}</span>
          <span class="stat__note">超时取消率 {{ formatRateBps(data.timeoutRateBps) }}</span>
        </div>
      </div>

      <section class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">收到的评价</h2>
            <p class="card__sub">按时间倒序</p>
          </div>
          <span class="tiny muted">当前身份：{{ auth.nickname }}</span>
        </div>

        <div v-if="reviews.length" class="list">
          <article v-for="item in reviews" :key="`${item.reviewerId}-${item.createdAt}`" class="list__item">
            <div class="stack stack--sm grow">
              <div class="cluster">
                <span class="strong small">{{ item.reviewerNickname }}</span>
                <span class="stamp stamp--warn">{{ item.rating }} / 5</span>
              </div>
              <p class="small ink2" style="white-space: pre-wrap">{{ item.comment || '未填写文字评价' }}</p>
            </div>
            <span class="tiny muted nowrap">{{ formatDateTime(item.createdAt) }}</span>
          </article>
        </div>

        <EmptyState v-else title="还没有收到评价" desc="完成签约并验收通过后，对方可以评价本次合作。" />

        <DataPager :page="page" :total-pages="totalPages" :total="data.reviews.total" @change="goPage" />
      </section>
    </template>
  </div>
</template>
