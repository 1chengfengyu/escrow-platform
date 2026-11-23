<script setup lang="ts">
// W-12 通知中心
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
        <RouterLink class="btn btn--line btn--sm" to="/worker/conversations">沟通消息</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/engagements">我的履约</RouterLink>
      </template>
    </PageHeader>

    <div class="notice notice--info">
      <p class="notice__eyebrow">两个入口</p>
      <p class="notice__desc">
        站内信是系统事件通知，沟通消息是与雇主或劳动者的对话，两者独立计数、互不混用。
      </p>
    </div>

    <NotificationList />
  </div>
</template>
