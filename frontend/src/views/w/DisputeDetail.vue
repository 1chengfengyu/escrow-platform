<script setup lang="ts">
// W-10 争议详情（FR-DISPUTE.EVIDENCE.01~03 / FR-DISPUTE.WITHDRAW.01）
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import DisputeDetailPanel from '@/components/DisputeDetailPanel.vue'
import PageHeader from '@/components/PageHeader.vue'

const route = useRoute()
const id = computed(() => String(route.params.id))
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="DISPUTE"
      :title="`争议 ${id}`"
      desc="查看双方证据、补充材料，或撤回由你发起的申诉。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/disputes">争议入口</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/engagements">我的履约</RouterLink>
      </template>
    </PageHeader>

    <DisputeDetailPanel :id="id" base-path="/worker" />
  </div>
</template>
