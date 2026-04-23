<script setup>
import { ref, nextTick } from 'vue'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

// ==================== State ====================
const userInput = ref('')
const loading = ref(false)
const step = ref('input') // input | chatting | checklist | generating | result

const chatReply = ref('')
const checklist = ref([])
const checklistAnswers = ref({})

const sessionId = ref('')
const vueCode = ref('')
const reviewPassed = ref(false)
const reviewFeedback = ref('')
const retryCount = ref(0)
const copySuccess = ref(false)

// ==================== Methods ====================

async function handleStart() {
  if (!userInput.value.trim()) return
  loading.value = true
  step.value = 'chatting'

  try {
    const res = await fetch('/api/generate/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ input: userInput.value })
    })
    if (!res.ok) {
      const errData = await res.json().catch(() => ({}))
      throw new Error(errData.message || `请求失败 (${res.status})`)
    }
    const data = await res.json()

    sessionId.value = data.sessionId
    chatReply.value = data.chatReply || ''

    if (data.intentType === 'chat') {
      step.value = 'chatting'
    } else {
      try {
        checklist.value = JSON.parse(data.checklist || '[]')
      } catch {
        checklist.value = []
      }
      // 初始化答案对象
      checklistAnswers.value = {}
      checklist.value.forEach(item => {
        checklistAnswers.value[item.field] = ''
      })
      step.value = 'checklist'
    }
  } catch (err) {
    alert('请求失败: ' + err.message)
    step.value = 'input'
  } finally {
    loading.value = false
  }
}

async function handleResume() {
  loading.value = true
  step.value = 'generating'

  try {
    const res = await fetch('/api/generate/resume', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId: sessionId.value,
        answers: checklistAnswers.value
      })
    })
    if (!res.ok) {
      const errData = await res.json().catch(() => ({}))
      throw new Error(errData.message || `请求失败 (${res.status})`)
    }
    const data = await res.json()

    vueCode.value = data.vueCode || ''
    reviewPassed.value = data.reviewPassed
    reviewFeedback.value = data.reviewFeedback || ''
    retryCount.value = data.retryCount || 0
    step.value = 'result'

    // 高亮代码
    await nextTick()
    document.querySelectorAll('pre code').forEach(block => {
      hljs.highlightElement(block)
    })
  } catch (err) {
    alert('请求失败: ' + err.message)
    step.value = 'checklist'
  } finally {
    loading.value = false
  }
}

function reset() {
  userInput.value = ''
  step.value = 'input'
  chatReply.value = ''
  checklist.value = []
  checklistAnswers.value = {}
  sessionId.value = ''
  vueCode.value = ''
  reviewPassed.value = false
  reviewFeedback.value = ''
  retryCount.value = 0
  copySuccess.value = false
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(vueCode.value)
    copySuccess.value = true
    setTimeout(() => copySuccess.value = false, 2000)
  } catch {
    alert('复制失败')
  }
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleStart()
  }
}
</script>

<template>
  <div class="min-h-screen bg-slate-50 text-slate-800">
    <!-- Header -->
    <header class="bg-white border-b border-slate-200 sticky top-0 z-10">
      <div class="max-w-5xl mx-auto px-6 py-4 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 bg-indigo-600 rounded-lg flex items-center justify-center text-white font-bold text-sm">
            AI
          </div>
          <h1 class="text-lg font-semibold text-slate-900">零代码网站生成平台</h1>
        </div>
        <button
          v-if="step !== 'input'"
          @click="reset"
          class="text-sm text-slate-500 hover:text-indigo-600 transition-colors"
        >
          重新开始
        </button>
      </div>
    </header>

    <main class="max-w-3xl mx-auto px-6 py-10">
      <!-- Step 1: Input -->
      <div v-if="step === 'input'" class="space-y-6">
        <div class="text-center space-y-2">
          <h2 class="text-3xl font-bold text-slate-900">描述你想要的网站</h2>
          <p class="text-slate-500">输入你的想法，AI 将引导你完善需求并生成 Vue 代码</p>
        </div>

        <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-2">
          <textarea
            v-model="userInput"
            @keydown="handleKeydown"
            placeholder="例如：我想做一个个人博客网站，用来展示我的摄影作品集..."
            class="w-full h-32 p-4 resize-none outline-none text-slate-700 placeholder-slate-400 bg-transparent"
          />
          <div class="flex justify-end px-2 pb-2">
            <button
              @click="handleStart"
              :disabled="!userInput.trim() || loading"
              class="bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed text-white px-6 py-2.5 rounded-xl font-medium transition-colors flex items-center gap-2"
            >
              <span v-if="loading" class="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></span>
              <span>{{ loading ? '分析中...' : '开始生成' }}</span>
            </button>
          </div>
        </div>
      </div>

      <!-- Loading Overlay for chatting -->
      <div v-if="step === 'chatting' && loading" class="flex flex-col items-center justify-center py-20 space-y-4">
        <div class="w-10 h-10 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin"></div>
        <p class="text-slate-500">AI 正在分析你的需求...</p>
      </div>

      <!-- Chat Reply -->
      <div v-if="step === 'chatting' && !loading" class="space-y-6">
        <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6">
          <div class="flex items-start gap-4">
            <div class="w-10 h-10 bg-indigo-100 rounded-full flex items-center justify-center text-indigo-600 shrink-0">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"/></svg>
            </div>
            <div class="space-y-2">
              <p class="text-slate-700 leading-relaxed">{{ chatReply || '你好！我是你的 AI 建站助手。如果你想创建一个网站，请描述你的想法，我来帮你实现。' }}</p>
            </div>
          </div>
        </div>

        <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-2">
          <textarea
            v-model="userInput"
            @keydown="handleKeydown"
            placeholder="尝试描述一个网站需求..."
            class="w-full h-24 p-4 resize-none outline-none text-slate-700 placeholder-slate-400 bg-transparent"
          />
          <div class="flex justify-end px-2 pb-2">
            <button
              @click="handleStart"
              :disabled="!userInput.trim() || loading"
              class="bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed text-white px-6 py-2.5 rounded-xl font-medium transition-colors"
            >
              发送
            </button>
          </div>
        </div>
      </div>

      <!-- Step 2: Checklist Form -->
      <div v-if="step === 'checklist'" class="space-y-6">
        <div class="text-center space-y-2">
          <h2 class="text-2xl font-bold text-slate-900">完善你的需求</h2>
          <p class="text-slate-500">请填写以下信息，帮助 AI 更精准地生成你的网站</p>
        </div>

        <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-5">
          <div
            v-for="item in checklist"
            :key="item.field"
            class="space-y-2"
          >
            <label class="block text-sm font-medium text-slate-700">
              {{ item.label }}
              <span v-if="item.description" class="text-slate-400 font-normal text-xs ml-1">({{ item.description }})</span>
            </label>

            <input
              v-if="item.type === 'text'"
              v-model="checklistAnswers[item.field]"
              type="text"
              class="w-full px-4 py-2.5 border border-slate-300 rounded-xl focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
              :placeholder="item.description || '请输入'"
            />

            <textarea
              v-else-if="item.type === 'textarea'"
              v-model="checklistAnswers[item.field]"
              rows="3"
              class="w-full px-4 py-2.5 border border-slate-300 rounded-xl focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all resize-none"
              :placeholder="item.description || '请输入'"
            />

            <select
              v-else-if="item.type === 'select'"
              v-model="checklistAnswers[item.field]"
              class="w-full px-4 py-2.5 border border-slate-300 rounded-xl focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all bg-white"
            >
              <option value="">请选择</option>
              <option v-for="opt in item.options" :key="opt" :value="opt">{{ opt }}</option>
            </select>
          </div>

          <div class="pt-4">
            <button
              @click="handleResume"
              :disabled="loading"
              class="w-full bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed text-white py-3 rounded-xl font-medium transition-colors flex items-center justify-center gap-2"
            >
              <span v-if="loading" class="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></span>
              <span>{{ loading ? '生成中...' : '确认并生成代码' }}</span>
            </button>
          </div>
        </div>
      </div>

      <!-- Step 3: Generating -->
      <div v-if="step === 'generating'" class="flex flex-col items-center justify-center py-20 space-y-6">
        <div class="relative">
          <div class="w-16 h-16 border-4 border-indigo-100 rounded-full"></div>
          <div class="absolute top-0 left-0 w-16 h-16 border-4 border-indigo-600 border-t-transparent rounded-full animate-spin"></div>
        </div>
        <div class="text-center space-y-2">
          <p class="text-lg font-medium text-slate-900">AI 正在为你生成网站代码</p>
          <p class="text-slate-500 text-sm">正在执行素材收集 → 代码生成 → 自动审查...</p>
        </div>
        <div class="w-64 h-1.5 bg-slate-200 rounded-full overflow-hidden">
          <div class="h-full bg-indigo-600 rounded-full animate-pulse w-3/4"></div>
        </div>
      </div>

      <!-- Step 4: Result -->
      <div v-if="step === 'result'" class="space-y-6">
        <div class="flex items-center justify-between">
          <div>
            <h2 class="text-2xl font-bold text-slate-900">生成完成</h2>
            <p class="text-slate-500 text-sm mt-1">
              代码审查状态
              <span :class="reviewPassed ? 'text-green-600' : 'text-amber-600'">
                {{ reviewPassed ? '已通过' : '未通过（已达最大重试次数）' }}
              </span>
              <span v-if="retryCount > 0" class="text-slate-400"> | 自动修复 {{ retryCount }} 次</span>
            </p>
          </div>
          <button
            @click="copyCode"
            class="flex items-center gap-2 px-4 py-2 bg-white border border-slate-300 rounded-xl hover:border-indigo-500 hover:text-indigo-600 transition-colors text-sm font-medium"
          >
            <svg v-if="!copySuccess" class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"/></svg>
            <svg v-else class="w-4 h-4 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/></svg>
            <span>{{ copySuccess ? '已复制' : '复制代码' }}</span>
          </button>
        </div>

        <!-- 审查结果卡片 -->
        <div
          v-if="reviewFeedback"
          class="rounded-xl border p-4"
          :class="reviewPassed
            ? 'bg-green-50 border-green-200 text-green-800'
            : 'bg-amber-50 border-amber-200 text-amber-800'"
        >
          <div class="flex items-start gap-3">
            <div class="shrink-0 mt-0.5">
              <!-- 通过图标 -->
              <svg v-if="reviewPassed" class="w-5 h-5 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/>
              </svg>
              <!-- 未通过图标 -->
              <svg v-else class="w-5 h-5 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
              </svg>
            </div>
            <div class="space-y-1 min-w-0">
              <p class="font-semibold text-sm">
                {{ reviewPassed ? '代码审查通过' : '代码审查未通过' }}
              </p>
              <p class="text-sm leading-relaxed whitespace-pre-wrap">{{ reviewFeedback }}</p>
              <p v-if="!reviewPassed" class="text-xs text-amber-600/80 mt-1">
                AI 已尝试自动修复 {{ retryCount }} 次，但仍未完全通过审查。建议下载代码后手动调整。
              </p>
            </div>
          </div>
        </div>

        <div class="bg-[#1f2028] rounded-2xl overflow-hidden shadow-lg">
          <div class="flex items-center gap-2 px-4 py-3 bg-[#16171d] border-b border-[#2e303a]">
            <div class="w-3 h-3 rounded-full bg-red-500"></div>
            <div class="w-3 h-3 rounded-full bg-yellow-500"></div>
            <div class="w-3 h-3 rounded-full bg-green-500"></div>
            <span class="ml-2 text-xs text-slate-400">App.vue</span>
          </div>
          <pre class="p-5 overflow-x-auto text-sm leading-relaxed"><code class="language-xml">{{ vueCode }}</code></pre>
        </div>

        <div class="text-center">
          <button
            @click="reset"
            class="text-indigo-600 hover:text-indigo-700 font-medium text-sm"
          >
            ← 重新生成新网站
          </button>
        </div>
      </div>
    </main>
  </div>
</template>
