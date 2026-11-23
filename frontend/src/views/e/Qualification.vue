<script setup lang="ts">
// E-12 主体资质（FR-QUAL.SUBMIT.01 / FR-QUAL.REVIEW.01）
import { computed, reactive, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { identityApi } from '@/api/modules/identity'
import { ApiError, toApiError } from '@/api/http'
import type { SubjectType } from '@/api/types'
import ErrorBlock from '@/components/ErrorBlock.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useResource } from '@/composables/useResource'
import { formatDateTime } from '@/utils/date'
import { statusLabel } from '@/utils/status'

const message = useMessage()

const { data, loading, error, load } = useResource(() => identityApi.qualification())

const form = reactive({
  subjectType: '' as SubjectType | '',
  subjectName: '',
  idLast4: '',
})

const errors = reactive<Record<string, string>>({})
const submitting = ref(false)
const actionError = ref<ApiError | null>(null)

const subjectOptions: Array<{ value: SubjectType; label: string }> = [
  { value: 'INDIVIDUAL', label: '个人' },
  { value: 'SOLE_PROPRIETOR', label: '个体工商户' },
]

const prefilled = computed(() => {
  const view = data.value
  if (!view) return false
  return Boolean(view.subjectType || view.subjectName || view.idLast4)
})

function fillFromServer(): void {
  const view = data.value
  if (!view) return
  form.subjectType = view.subjectType ?? ''
  form.subjectName = view.subjectName ?? ''
  form.idLast4 = view.idLast4 ?? ''
}

function validate(): boolean {
  Object.keys(errors).forEach((key) => delete errors[key])
  if (!form.subjectType) errors.subjectType = '请选择主体类型'
  const name = form.subjectName.trim()
  if (!name) errors.subjectName = '请填写主体名称'
  else if (name.length < 2 || name.length > 128) errors.subjectName = '主体名称长度为 2~128 字'
  if (!/^\d{4}$/.test(form.idLast4.trim())) errors.idLast4 = '请填写证件号后 4 位数字'
  return Object.keys(errors).length === 0
}

async function submit(): Promise<void> {
  if (submitting.value || !validate()) return
  submitting.value = true
  actionError.value = null
  try {
    await identityApi.submitQualification({
      subjectType: form.subjectType as SubjectType,
      subjectName: form.subjectName.trim(),
      idLast4: form.idLast4.trim(),
    })
    message.success('资质已提交，等待审核')
    await load()
    fillFromServer()
  } catch (raw) {
    actionError.value = toApiError(raw)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="QUALIFICATION"
      title="主体资质"
      desc="发布任务需要资质审核通过。证件只保留后 4 位，原文不落库。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/employer/tasks/new">发布任务</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer">工作台</RouterLink>
      </template>
    </PageHeader>

    <LoadingBlock v-if="loading && !data" :rows="3" label="正在读取资质" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else-if="data">
      <section class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">当前状态</h2>
            <p class="card__sub">审核由治理台执行，结果写入站内通知</p>
          </div>
          <StatusTag kind="review" :value="data.status" />
        </div>
        <div class="kv">
          <div class="kv__row">
            <span class="kv__k">主体类型</span>
            <span class="kv__v">{{ data.subjectType ? statusLabel('subject', data.subjectType) : '—' }}</span>
          </div>
          <div class="kv__row">
            <span class="kv__k">主体名称</span>
            <span class="kv__v">{{ data.subjectName || '—' }}</span>
          </div>
          <div class="kv__row">
            <span class="kv__k">证件尾号</span>
            <span class="kv__v mono">{{ data.idLast4 || '—' }}</span>
          </div>
          <div class="kv__row">
            <span class="kv__k">发布资格</span>
            <span class="kv__v">{{ data.publishEligible ? '已具备' : '未具备' }}</span>
          </div>
          <div class="kv__row">
            <span class="kv__k">审核意见</span>
            <span class="kv__v">{{ data.reviewReason || '—' }}</span>
          </div>
          <div class="kv__row">
            <span class="kv__k">审核时间</span>
            <span class="kv__v">{{ formatDateTime(data.reviewedAt) }}</span>
          </div>
        </div>
        <p v-if="data.status === 'REJECTED'" class="field__error">
          上次审核未通过，请按审核意见修改后重新提交。
        </p>
      </section>

      <section class="card stack">
        <div class="card__head">
          <div>
            <h2 class="card__title">提交 / 更新资质</h2>
            <p class="card__sub">提交后会进入待审核，审核通过才能发布任务</p>
          </div>
          <button v-if="prefilled && !form.subjectName" class="btn btn--line btn--sm" type="button" @click="fillFromServer">
            带入当前资料
          </button>
        </div>

        <div class="form-grid">
          <label class="field">
            <span class="field__label">主体类型 *</span>
            <select v-model="form.subjectType" class="select">
              <option value="">请选择</option>
              <option v-for="option in subjectOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
            <span v-if="errors.subjectType" class="field__error">{{ errors.subjectType }}</span>
          </label>
          <label class="field">
            <span class="field__label">证件尾号 *</span>
            <input v-model="form.idLast4" class="input" maxlength="4" inputmode="numeric" placeholder="后 4 位数字" />
            <span v-if="errors.idLast4" class="field__error">{{ errors.idLast4 }}</span>
          </label>
          <label class="field field--full">
            <span class="field__label">主体名称 *</span>
            <input v-model="form.subjectName" class="input" maxlength="128" placeholder="个人姓名或个体工商户名称" />
            <span v-if="errors.subjectName" class="field__error">{{ errors.subjectName }}</span>
          </label>
        </div>

        <div class="form-actions">
          <button class="btn btn--ink btn--sm" type="button" :disabled="submitting" @click="submit">
            {{ submitting ? '正在提交' : '提交审核' }}
          </button>
        </div>
        <p v-if="actionError" class="field__error">{{ actionError.message }}（{{ actionError.code }}）</p>
      </section>

      <div class="notice notice--info">
        <p class="notice__eyebrow">数据口径</p>
        <p class="notice__desc">
          只提交主体名称与证件后 4 位，前端不收集证件照片与完整号码；审核状态与意见以服务端结果为准。
        </p>
      </div>
    </template>
  </div>
</template>
