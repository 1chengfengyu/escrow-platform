<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import TaskBoard from '@/components/TaskBoard.vue'
</script>

<template>
  <div class="wrap section--tight">
    <div class="stack stack--lg">
      <PageHeader
        eyebrow="MARKET"
        title="任务广场"
        desc="按分类、地点、报酬与日期筛选开放任务。广场与任务详情需要登录后查看。"
      />
      <TaskBoard mode="public" />
    </div>
  </div>
</template>
