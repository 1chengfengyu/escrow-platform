<script setup lang="ts">
// E-13 通知中心
import NotificationList from '@/components/NotificationList.vue'
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="NOTIFICATIONS"
      title="通知中心"
      desc="报名、录用、验收、结算与争议进展都会写入站内信。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/employer/conversations">沟通消息</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/engagements">履约看板</RouterLink>
      </template>
    </PageHeader>

    <NotificationList />
  </div>
</template>
