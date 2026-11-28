<script setup lang="ts">
// 余额与模拟充值面板（W-08 / E-09 共用）
// 提现与资金流水后端未提供接口，按缺口说明展示，不伪造记录。
import { computed, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { fundApi } from '@/api/modules/fund'
import { ApiError, toApiError } from '@/api/http'
import ErrorBlock from '@/components/ErrorBlock.vue'
import GapNotice from '@/components/GapNotice.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MoneyText from '@/components/MoneyText.vue'
import { useResource } from '@/composables/useResource'
import { formatMoney, yuanToFen } from '@/utils/money'

withDefaults(defineProps<{ note?: string }>(), { note: '' })

const message = useMessage()
const { data, loading, error, load } = useResource(() => fundApi.balance())

const amountYuan = ref('')
const recharging = ref(false)
const rechargeError = ref<ApiError | null>(null)
const lastRecharge = ref('')

// 幂等键标识「这一笔充值意图」，不是「这一次点击」：
// 成功后换新的，失败保持不变——用户点重试时仍是同一笔，服务端才认得出是重复提交。
//
// 已知边界：失败后若改了金额再提交，requestId 仍是同一个，服务端当作同一笔意图、
// 直接返回余额，新金额被忽略（不会多充，但也不会按新金额充）。
// 不在这里主动换键，是因为换了之后「超时但服务端其实已成功」的那次重试会变成第二笔充值。
// 两害相权取轻：宁可让用户重试后看到余额没变，也不重复扣款。
function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '')
  }
  // http + 内网 IP 属于非安全上下文，没有 randomUUID，退化成时间戳加随机串，仍在 32 字符以内
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}
const requestId = ref(newRequestId())

const canRecharge = computed(() => {
  const fen = yuanToFen(amountYuan.value)
  return fen !== null && fen > 0
})

async function recharge(): Promise<void> {
  if (!canRecharge.value || recharging.value) return
  const fen = yuanToFen(amountYuan.value)
  if (fen === null || fen <= 0) {
    rechargeError.value = new ApiError(-1, '请输入大于 0 的充值金额')
    return
  }
  recharging.value = true
  rechargeError.value = null
  try {
    const balance = await fundApi.recharge(fen, requestId.value)
    requestId.value = newRequestId()
    amountYuan.value = ''
    lastRecharge.value = String(fen)
    message.success('模拟充值成功')
    if (data.value) data.value = balance
    else await load()
  } catch (raw) {
    rechargeError.value = toApiError(raw)
  } finally {
    recharging.value = false
  }
}
</script>

<template>
  <div class="stack stack--lg">
    <LoadingBlock v-if="loading && !data" :rows="2" label="正在读取余额" />

    <ErrorBlock v-else-if="error" :error="error" @retry="load" />

    <template v-else>
      <div class="stats stats--ledger">
        <div class="stat">
          <span class="stat__label">可用余额</span>
          <span class="stat__value"><MoneyText :fen="data?.available ?? '0'" /></span>
          <span class="stat__note">可直接用于平台内结算</span>
        </div>
        <div class="stat">
          <span class="stat__label">冻结金额</span>
          <span class="stat__value"><MoneyText :fen="data?.frozen ?? '0'" /></span>
          <span class="stat__note">结算或争议处理中暂时冻结</span>
        </div>
        <div class="stat">
          <span class="stat__label">本次充值</span>
          <span class="stat__value"><MoneyText :fen="lastRecharge || '0'" /></span>
          <span class="stat__note">{{ lastRecharge ? '已到账' : '尚未充值' }}</span>
        </div>
      </div>

      <div class="detail">
        <section class="card stack">
          <div class="card__head">
            <div>
              <h2 class="card__title">模拟充值</h2>
              <p class="card__sub">仅用于本地演示，不接入真实支付通道</p>
            </div>
          </div>

          <form class="filters" @submit.prevent="recharge">
            <div class="field field--wide">
              <label class="field__label" for="wallet-amount">充值金额（元）</label>
              <input
                id="wallet-amount"
                v-model="amountYuan"
                class="input"
                inputmode="decimal"
                placeholder="例如 1000"
                autocomplete="off"
              />
              <span class="field__hint">提交时按「分」记账。</span>
            </div>
            <button class="btn btn--ink btn--sm" type="submit" :disabled="!canRecharge || recharging">
              {{ recharging ? '正在充值' : '确认充值' }}
            </button>
          </form>
          <p v-if="rechargeError" class="field__error">{{ rechargeError.message }}（{{ rechargeError.code }}）</p>
          <p v-if="data" class="tiny muted">
            当前可用 {{ formatMoney(data.available) }}，冻结 {{ formatMoney(data.frozen) }}。
          </p>

          <hr class="divider" />
          <div class="row row--between row--wrap">
            <div>
              <p class="strong small">提现</p>
              <p class="tiny muted">提现暂未开放，按钮不可用。</p>
            </div>
            <button class="btn btn--line btn--sm" type="button" disabled>申请提现</button>
          </div>
        </section>

        <aside class="stack">
          <section class="card stack">
            <p class="eyebrow">账户说明</p>
            <p class="small ink2">{{ note || '余额与冻结金额来自资金账户，充值后立即刷新。' }}</p>
            <button class="btn btn--line btn--block btn--sm" type="button" @click="load">刷新余额</button>
          </section>
        </aside>
      </div>

      <GapNotice
        title="资金流水与提现未开放"
        desc="本页展示可用与冻结余额，并提供模拟充值。资金流水与提现暂未开放。"
      />
    </template>
  </div>
</template>
