<script setup lang="ts">
// W-15 会话详情
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ConversationThread from '@/components/ConversationThread.vue'
import PageHeader from '@/components/PageHeader.vue'

const route = useRoute()
const id = computed(() => String(route.params.id))
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="CONVERSATION"
      :title="`会话 ${id}`"
      desc="消息按时间正序展示，每 5 秒增量拉取新消息。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/conversations">返回列表</RouterLink>
      </template>
    </PageHeader>

    <ConversationThread :id="id" base-path="/worker" />
  </div>
</template>
