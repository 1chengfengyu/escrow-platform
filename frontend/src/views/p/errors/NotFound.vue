<template>
  <div class="wrap">
    <div class="error-page">
      <p class="error-page__code">404</p>
      <h1 class="error-page__title">页面不存在</h1>
      <p class="state__desc">链接可能已失效，或地址输入有误。</p>
      <div class="cluster" style="justify-content: center; margin-top: 8px">
        <RouterLink class="btn btn--ink" to="/">返回首页</RouterLink>
        <RouterLink class="btn btn--line" to="/tasks">去任务广场</RouterLink>
      </div>
    </div>
  </div>
</template>
