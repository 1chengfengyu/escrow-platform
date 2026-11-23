<script setup lang="ts">
// A-02 用户治理（FR-ADMIN.USER.01）
import { ref } from 'vue'
import { useMessage } from 'naive-ui'
import { adminApi } from '@/api/modules/admin'
import type { AdminUserView } from '@/api/types'
import { ApiError, toApiError } from '@/api/http'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'
import { statusLabel, statusOptions } from '@/utils/status'

const message = useMessage()
const confirm = useConfirm()

const keyword = ref('')
const status = ref('')
const reason = ref('')
const page = ref(1)
const busy = ref('')
const actionError = ref<ApiError | null>(null)

const statusChoices = statusOptions('user')

const { data, loading, error, load } = useResource(() =>
  adminApi.users(keyword.value.trim() || undefined, status.value || undefined, page.value, 20),
)

function search(): void {
  page.value = 1
  actionError.value = null
  void load()
}

function goPage(next: number): void {
  page.value = next
  void load()
}

async function changeStatus(row: AdminUserView, action: 'disable' | 'enable'): Promise<void> {
  if (busy.value) return
  const trimmed = reason.value.trim()
  if (!trimmed) {
    actionError.value = new ApiError(-1, '请先填写操作原因（1~200 字）')
    return
  }
  const verb = action === 'disable' ? '禁用' : '启用'
  const ok = await confirm({
    title: `${verb}账号`,
    content: `确认${verb}「${row.nickname}」？原因：${trimmed}`,
    positiveText: `确认${verb}`,
    danger: action === 'disable',
  })
  if (!ok) return
  busy.value = row.userId
  actionError.value = null
  try {
    if (action === 'disable') await adminApi.disableUser(row.userId, trimmed)
    else await adminApi.enableUser(row.userId, trimmed)
    reason.value = ''
    message.success(`已${verb}`)
    await load()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="USERS"
      title="用户治理"
      desc="按昵称、手机号或邮箱模糊查询。禁用与启用都必须写明原因，原因会进入审计流水。"
    />

    <section class="card stack">
      <form class="filters" @submit.prevent="search">
        <label class="field field--wide">
          <span class="field__label">关键字</span>
          <input v-model="keyword" class="input" placeholder="昵称 / 手机号 / 邮箱" />
        </label>
        <label class="field">
          <span class="field__label">账号状态</span>
          <select v-model="status" class="select">
            <option value="">全部</option>
            <option v-for="item in statusChoices" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </label>
        <label class="field field--wide">
          <span class="field__label">操作原因（必填）</span>
          <input v-model="reason" class="input" maxlength="200" placeholder="例如：资质造假，暂停发布权限" />
          <span class="field__hint">仅用于本次禁用 / 启用，成功后清空。</span>
        </label>
        <button class="btn btn--ink btn--sm" type="submit">查询</button>
      </form>
      <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
    </section>

    <LoadingBlock v-if="loading && !data" :rows="5" label="正在读取账号" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <div v-if="data.items.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>用户编号</th>
              <th>昵称</th>
              <th>手机号</th>
              <th>邮箱</th>
              <th>角色</th>
              <th>状态</th>
              <th>注册时间</th>
              <th class="num">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in data.items" :key="row.userId">
              <td class="mono">{{ row.userId }}</td>
              <td>{{ row.nickname }}</td>
              <td class="mono">{{ row.phone || '—' }}</td>
              <td>{{ row.email || '—' }}</td>
              <td>{{ statusLabel('identity', row.role) }}</td>
              <td><StatusTag kind="user" :value="row.status" /></td>
              <td>{{ formatDateTime(row.createdAt) }}</td>
              <td class="num">
                <button
                  v-if="row.status === 'ACTIVE'"
                  class="btn btn--line btn--sm"
                  type="button"
                  :disabled="busy === row.userId"
                  @click="changeStatus(row, 'disable')"
                >
                  禁用
                </button>
                <button
                  v-else
                  class="btn btn--ink btn--sm"
                  type="button"
                  :disabled="busy === row.userId"
                  @click="changeStatus(row, 'enable')"
                >
                  启用
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <EmptyState v-else title="没有匹配的账号" desc="换关键字或清空状态筛选后重试。" />

      <DataPager :page="page" :total-pages="data.totalPages" :total="data.total" @change="goPage" />
    </template>
  </div>
</template>
