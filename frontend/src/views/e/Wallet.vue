<script setup lang="ts">
// E-09 钱包
import PageHeader from '@/components/PageHeader.vue'
import WalletPanel from '@/components/WalletPanel.vue'
</script>

<template>
  <div class="page page--wide">
    <PageHeader
      eyebrow="WALLET"
      title="钱包"
      desc="查看可用与冻结金额。发布任务会冻结「单价 × 名额」。"
    >
      <template #actions>
        <RouterLink class="btn btn--line btn--sm" to="/employer/settlements">结算记录</RouterLink>
        <RouterLink class="btn btn--line btn--sm" to="/employer/tasks/new">发布任务</RouterLink>
      </template>
    </PageHeader>

    <WalletPanel note="余额来自资金账户。发布任务会冻结金额，撤单退款与验收结算都会改变可用与冻结。" />
  </div>
</template>
