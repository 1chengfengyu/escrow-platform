<script setup lang="ts">
// 全站 AI 规则问答（V10-c5）：答案必须带引用；无依据时渲染弃答，不伪造答案。
import { nextTick, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { aiApi } from '@/api/modules/ai'
import type { AiQa, AiTrace } from '@/api/types'
import { toApiError } from '@/api/http'

const message = useMessage()

const open = ref(false)
const question = ref('')
const asking = ref(false)
const result = ref<AiQa | null>(null)
const errorText = ref('')
const feedbackSent = ref(false)
const traceOpen = ref(false)
const trace = ref<AiTrace | null>(null)
const traceLoading = ref(false)
const traceError = ref('')
const inputRef = ref<HTMLTextAreaElement | null>(null)

async function toggle(): Promise<void> {
  open.value = !open.value
  if (open.value) {
    await nextTick()
    inputRef.value?.focus()
  }
}

async function ask(): Promise<void> {
  const text = question.value.trim()
  if (!text || asking.value) return
  asking.value = true
  errorText.value = ''
  feedbackSent.value = false
  traceOpen.value = false
  trace.value = null
  traceError.value = ''
  const previous = result.value
  try {
    // 同一会话内再次提问：先把上一条链路标记为「二次搜索」，这是反馈口径的一部分
    if (previous?.traceNo) {
      void aiApi.feedback(previous.traceNo, false, true).catch(() => undefined)
    }
    result.value = await aiApi.ask(text)
  } catch (raw) {
    errorText.value = toApiError(raw).message
  } finally {
    asking.value = false
  }
}

async function markHelpful(): Promise<void> {
  if (!result.value || feedbackSent.value) return
  try {
    await aiApi.feedback(result.value.traceNo, true)
    feedbackSent.value = true
    message.success('已记录，感谢反馈')
  } catch {
    message.error('反馈没有提交成功，可稍后再试')
  }
}

/** 回放本次答案的链路：只返回本人 trace，问一次只需读一次。 */
async function toggleTrace(): Promise<void> {
  if (!result.value) return
  if (traceOpen.value) {
    traceOpen.value = false
    return
  }
  traceOpen.value = true
  if (trace.value?.traceNo === result.value.traceNo) return
  traceLoading.value = true
  traceError.value = ''
  try {
    trace.value = await aiApi.trace(result.value.traceNo)
  } catch (raw) {
    traceError.value = toApiError(raw).message
  } finally {
    traceLoading.value = false
  }
}

function reset(): void {
  question.value = ''
  result.value = null
  errorText.value = ''
  feedbackSent.value = false
  traceOpen.value = false
  trace.value = null
  traceError.value = ''
}
</script>

<template>
  <div class="ai-dock">
    <button
      class="ai-fab"
      type="button"
      :aria-expanded="open"
      aria-controls="ai-qa-panel"
      @click="toggle"
    >
      <span class="ai-fab__dot" aria-hidden="true" />
      AI 助手
    </button>

    <section v-if="open" id="ai-qa-panel" class="ai-panel" aria-label="AI 规则问答">
      <header class="ai-panel__head">
        <div>
          <p class="eyebrow">ASK · RULES</p>
          <h2 class="ai-panel__title">规则问答</h2>
        </div>
        <button class="linkbtn" type="button" @click="open = false">收起</button>
      </header>

      <p class="tiny muted">
        只回答平台规则（托管、结算、退款、争议）。答案附引用条目；知识库没有依据时会直接说明，不编造。
      </p>

      <form class="stack stack--sm" @submit.prevent="ask">
        <textarea
          ref="inputRef"
          v-model="question"
          class="textarea"
          rows="3"
          maxlength="200"
          placeholder="例如：验收通过后多久打款？"
        />
        <div class="cluster">
          <button class="btn btn--ink btn--sm" type="submit" :disabled="asking || !question.trim()">
            {{ asking ? '正在检索依据' : '提问' }}
          </button>
          <button v-if="result" class="btn btn--ghost btn--sm" type="button" @click="reset">清空</button>
        </div>
      </form>

      <p v-if="errorText" class="field__error">{{ errorText }}</p>

      <article v-if="result" class="ai-answer">
        <div class="cluster">
          <span v-if="result.abstain" class="stamp stamp--warn">弃答 · 无依据</span>
          <span v-else class="stamp stamp--active">有依据</span>
          <span v-if="result.degraded" class="stamp stamp--muted">降级回答</span>
        </div>

        <p class="ai-answer__text">{{ result.answer }}</p>

        <div v-if="result.citations.length" class="ai-cites">
          <p class="tiny muted">引用条目</p>
          <ul class="ai-cites__list">
            <li v-for="(cite, index) in result.citations" :key="`${index}-${cite}`" class="ai-cites__item">
              {{ cite }}
            </li>
          </ul>
        </div>
        <p v-else class="tiny muted">本次回答没有引用条目——按规则视为无依据，请以页面说明与客服口径为准。</p>

        <div class="cluster ai-answer__foot">
          <span class="tiny muted mono">trace {{ result.traceNo }}</span>
          <button
            class="btn btn--line btn--sm"
            type="button"
            :disabled="feedbackSent"
            @click="markHelpful"
          >
            {{ feedbackSent ? '已反馈' : '答案有帮助' }}
          </button>
        </div>

        <div class="cluster">
          <button class="btn btn--ghost btn--sm" type="button" @click="toggleTrace">
            {{ traceOpen ? '收起链路' : '查看本次链路' }}
          </button>
          <span v-if="traceOpen && traceLoading" class="tiny muted">正在读取链路…</span>
        </div>
        <p v-if="traceError" class="field__error">{{ traceError }}</p>

        <ol v-if="traceOpen && trace" class="ai-trace">
          <li
            v-for="(stage, index) in trace.stages"
            :key="`${index}-${stage.phase}`"
            class="ai-trace__item"
          >
            <div class="cluster">
              <span class="ai-trace__phase mono">{{ stage.phase }}</span>
              <span class="tiny muted">{{ stage.ms }} ms · {{ stage.tokens }} tokens</span>
              <span v-if="stage.degraded" class="stamp stamp--muted">降级</span>
            </div>
            <p class="tiny muted">{{ stage.note }}</p>
          </li>
        </ol>
      </article>
    </section>
  </div>
</template>
