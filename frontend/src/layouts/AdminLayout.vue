<script setup lang="ts">
import type { ShellMenu } from './menu'
import AppShell from './AppShell.vue'

const menus: ShellMenu[] = [
  { to: '/admin', label: '治理概览' },
  { to: '/admin/users', label: '用户治理' },
  { to: '/admin/qualifications', label: '资质审核' },
  { to: '/admin/disputes', label: '争议裁决' },
  { to: '/admin/differences', label: '差异单' },
  { to: '/admin/payouts', label: '打款处理' },
  { to: '/admin/reconciliation', label: '对账报告' },
  { to: '/admin/audit', label: '操作日志' },
  { to: '/admin/config', label: '系统配置' },
  { to: '/admin/conversations', label: '会话审查' },
]
</script>

<template>
  <AppShell :menus="menus" label="平台治理台" home="/admin" :show-messages="false" />
</template>
