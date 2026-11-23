<script setup lang="ts">
// A-10 系统配置（本期未开放写入；只读展示前端运行参数）
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'

const runtimeRows = [
  { key: '服务基地址', value: '同源请求（Vite 代理到 http://localhost:8080）' },
  { key: '登录方式', value: '会话 Cookie（JSESSIONID）' },
  { key: '列表分页', value: '默认 20 条 / 页' },
  { key: '会话消息分页', value: '50 条 / 页，增量按 afterId' },
  { key: '会话刷新', value: '5 秒轮询，无 WebSocket' },
  { key: '金额口径', value: '整数分；页面只读展示，不做运算入库' },
  { key: '时间展示', value: '本地时区，精确到分钟' },
]
</script>

<template>
  <div class="page page--narrow">
    <PageHeader
      eyebrow="CONFIG"
      title="系统配置"
      desc="当前版本不提供配置读写。以下为前端运行参数，只读展示。"
    />

    <section class="card stack">
      <div class="card__head">
        <div>
          <h2 class="card__title">前端运行参数</h2>
          <p class="card__sub">来自构建与请求层，不是业务配置</p>
        </div>
      </div>
      <div class="kv">
        <div v-for="row in runtimeRows" :key="row.key" class="kv__row">
          <span class="kv__k">{{ row.key }}</span>
          <span class="kv__v">{{ row.value }}</span>
        </div>
      </div>
    </section>

    <GapNotice
      title="系统配置读写未开放"
      desc="当前版本不提供配置读写，本页没有任何可保存项，也不伪造业务默认值。需要调整运行参数时，由部署侧修改配置并重启。"
    />
  </div>
</template>
