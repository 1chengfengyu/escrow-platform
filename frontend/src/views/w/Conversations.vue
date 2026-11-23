<script setup lang="ts">
// W-14 会话列表
import ConversationList from '@/components/ConversationList.vue'
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="MESSAGES"
      title="沟通消息"
      desc="与雇主围绕任务建立会话。咨询、报名沟通与履约沟通分别标识。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/notifications">通知中心</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/engagements">我的履约</RouterLink>
      </template>
    </PageHeader>

    <ConversationList base-path="/worker" task-path="/worker/tasks" />
  </div>
</template>
