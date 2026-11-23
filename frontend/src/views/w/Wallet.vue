<script setup lang="ts">
// W-08 钱包（FR-FUND.RECHARGE.01 / FR-FUND.BALANCE.01）
import PageHeader from '@/components/PageHeader.vue'
import WalletPanel from '@/components/WalletPanel.vue'
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="WALLET"
      title="钱包"
      desc="查看可用与冻结金额，使用模拟充值验证记账链路。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/worker/settlements">结算记录</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/worker/engagements">我的履约</RouterLink>
      </template>
    </PageHeader>

    <WalletPanel note="余额来自资金账户。充值会写入账户与分录，刷新后即可看到变化。" />
  </div>
</template>
