<script setup lang="ts">
// E-14 会话列表
import ConversationList from '@/components/ConversationList.vue'
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="MESSAGES"
      title="沟通消息"
      desc="与劳动者围绕任务建立会话。咨询、报名沟通与履约沟通分别标识。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/employer/notifications">通知中心</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/tasks">我发布的任务</RouterLink>
      </template>
    </PageHeader>

    <ConversationList base-path="/employer" task-path="/employer/tasks" />
  </div>
</template>
