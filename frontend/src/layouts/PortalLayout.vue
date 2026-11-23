<script setup lang="ts">
import type { ShellMenu } from './menu'
import AppShell from './AppShell.vue'
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const isEmployer = computed(() => route.meta.section === 'employer')

const workerMenus: ShellMenu[] = [
  { to: '/worker', label: '工作台' },
  { to: '/worker/tasks', label: '找任务' },
  { to: '/worker/applications', label: '我的报名' },
  { to: '/worker/engagements', label: '我的履约' },
  { to: '/worker/settlements', label: '结算与打款' },
  { to: '/worker/wallet', label: '钱包' },
  { to: '/worker/disputes', label: '争议申诉' },
  { to: '/worker/conversations', label: '沟通消息', badge: 'conversations' },
  { to: '/worker/notifications', label: '通知中心', badge: 'notifications' },
  { to: '/worker/reputation', label: '信誉与评价' },
  { to: '/worker/profile', label: '个人资料' },
]

const employerMenus: ShellMenu[] = [
  { to: '/employer', label: '工作台' },
  { to: '/employer/tasks/new', label: '发布任务' },
  { to: '/employer/tasks', label: '我发布的任务' },
  { to: '/employer/engagements', label: '履约看板' },
  { to: '/employer/settlements', label: '结算记录' },
  { to: '/employer/wallet', label: '钱包' },
  { to: '/employer/disputes', label: '争议处理' },
  { to: '/employer/conversations', label: '沟通消息', badge: 'conversations' },
  { to: '/employer/notifications', label: '通知中心', badge: 'notifications' },
  { to: '/employer/qualification', label: '主体资质' },
]

const menus = computed(() => (isEmployer.value ? employerMenus : workerMenus))
</script>

<template>
  <AppShell :menus="menus" :label="isEmployer ? '雇主工作台' : '劳动者工作台'" :home="isEmployer ? '/employer' : '/worker'" />
</template>
