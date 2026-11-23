<script setup lang="ts">
// E-11 争议详情
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
        <RouterLink class="btn btn--line btn--sm" to="/employer/disputes">争议入口</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/engagements">履约看板</RouterLink>
      </template>
    </PageHeader>

    <DisputeDetailPanel :id="id" base-path="/employer" />
  </div>
</template>
