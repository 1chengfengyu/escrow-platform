<script setup lang="ts">
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="wrap section--tight">
    <div class="stack stack--lg">
      <PageHeader eyebrow="RECOVERY" title="账号找回" desc="当前版本未开放自助找回。下面是人工核验需要准备的材料与流程。" />

      <div class="detail">
        <div class="prose">
          <h2>人工核验流程</h2>
          <ol class="stack stack--sm" style="list-style: decimal; padding-left: 20px; color: var(--ink-2); font-size: 14px">
            <li>准备注册时使用的手机号或邮箱，以及最近一次登录的大致时间。</li>
            <li>准备可证明账号归属的材料，例如注册手机号的入网证明或绑定邮箱的收件截图。</li>
            <li>联系运营人员提交材料，核验通过后由平台重置密码。</li>
          </ol>

          <h2>安全提醒</h2>
          <p>平台不会通过沟通索要密码、短信验证码或银行卡信息。任何要求你提供上述信息的请求都应拒绝。</p>
          <p>如果账号已被禁用，登录时会提示状态；禁用原因与恢复方式由运营人员核验后处理。</p>
        </div>

        <aside class="stack">
          <GapNotice
            title="自助找回尚未开放"
            desc="没有短信验证码、邮件重置链接与安全问题找回。本期只能人工核验，验收报告会登记该缺口。"
          />
          <RouterLink class="btn btn--line btn--sm" to="/login">返回登录</RouterLink>
        </aside>
      </div>
    </div>
  </div>
</template>
