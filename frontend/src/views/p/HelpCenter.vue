<script setup lang="ts">
import GapNotice from '@/components/GapNotice.vue'
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="wrap section--tight">
    <div class="stack stack--lg">
      <PageHeader eyebrow="HELP" title="帮助中心" desc="注册登录、发布报名、履约验收、结算打款与争议处理的常见问题。" />

      <div class="detail">
        <div class="prose">
          <h2 id="account">注册与登录</h2>
          <h3>用什么账号登录？</h3>
          <p>用注册时填写的手机号或邮箱登录。一个账号可以同时拥有雇主与劳动者身份，登录后在工作台右上角切换。</p>
          <h3>忘记密码怎么办？</h3>
          <p>
            当前版本未开放自助找回与短信验证码。<a class="linkbtn" href="/help/account-recovery">账号找回</a>
            页面说明了人工核验所需的材料。
          </p>

          <h2 id="publish">发布与报名</h2>
          <h3>发布任务收费吗？</h3>
          <p>发布任务不收费。任务需要写清内容、名额、单个名额报酬、时间与地点，发布后进入招募。</p>
          <h3>报名后多久有结果？</h3>
          <p>由雇主在报名列表中逐个名额录用，平台不承诺固定时长。录用后会形成签约并出现在“我的履约”。</p>

          <h2 id="delivery">履约与验收</h2>
          <h3>交付需要提交什么？</h3>
          <p>交付说明必填，图片可选。交付内容会保存在签约里，验收时双方看到的是同一份记录。</p>
          <h3>验收不通过怎么办？</h3>
          <p>雇主验收时可以填写理由。若双方无法达成一致，可以在签约内发起争议并提交证据。</p>

          <h2 id="settlement">结算与打款</h2>
          <h3>什么时候结算？</h3>
          <p>验收通过后生成结算单，再进入打款流程。打款状态未知时只进入查证或人工队列，不会直接重试。</p>
          <h3>为什么看到“待查证”？</h3>
          <p>表示通道结果尚未确定。此时重复发起打款可能造成重复付款，因此需要等待查证结论。</p>

          <h2 id="dispute">争议处理</h2>
          <h3>怎么发起争议？</h3>
          <p>在签约详情内发起，填写原因；随后可以补充文字与图片证据，也可以从沟通记录中选取消息作为证据。</p>
          <h3>裁决后会怎样？</h3>
          <p>按裁决结果继续放款或退款，处理依据与裁决人记录在争议详情中。</p>

          <h2 id="security">账号与安全</h2>
          <p>不要向任何人透露密码。平台不会在沟通中索要密码、验证码或银行卡信息。</p>
        </div>

        <aside class="stack">
          <GapNotice
            info
            title="本期未开放在线客服与工单"
            desc="演示环境没有在线客服、工单系统与短信通道。遇到账号问题请按“账号找回”页面的说明联系运营人员。"
          />
          <section class="card stack">
            <p class="eyebrow">快速入口</p>
            <RouterLink class="linkbtn" to="/help/account-recovery">账号找回说明</RouterLink>
            <RouterLink class="linkbtn" to="/how-it-works">完整流程</RouterLink>
            <RouterLink class="linkbtn" to="/login">返回登录</RouterLink>
          </section>
        </aside>
      </div>
    </div>
  </div>
</template>
