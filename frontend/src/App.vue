<script setup>
import { ref, nextTick, computed } from 'vue'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

// ==================== State ====================
const userInput = ref('')
const loading = ref(false)
const step = ref('chatting') // chatting | checklist | generating | result

const chatReply = ref('')
const checklist = ref([])
const checklistAnswers = ref({})

const sessionId = ref('')
const htmlCode = ref('')
const designConcept = ref('')
const designTokensStr = ref('')
const reviewPassed = ref(false)
const reviewFeedback = ref('')
const retryCount = ref(0)
const copySuccess = ref(false)

// SSE 流式生成
const streamingHtml = ref('')
const currentStage = ref('')

// Tweaks
const showTweaks = ref(false)
const tweakValues = ref({})
const showPreviewModal = ref(false)

// Model selection
const selectedModel = ref('qwen3.6-plus')

// 多页面预览 URL
const previewUrl = ref('')

// Agent 聊天消息流
const messages = ref([])

// ==================== Computed ====================

const tweakedHtmlCode = computed(() => {
  let code = htmlCode.value
  const tweaks = tweakValues.value
  for (const [name, value] of Object.entries(tweaks)) {
    if (value) {
      const regex = new RegExp(`(${name}\\s*:\\s*)[^;]+`, 'g')
      code = code.replace(regex, `$1${value}`)
    }
  }
  return code
})

const parsedDesignConcept = computed(() => {
  try {
    return JSON.parse(designConcept.value)
  } catch {
    return null
  }
})

const tweakList = computed(() => {
  const list = []
  const rootMatch = htmlCode.value.match(/:root\s*\{([^}]*)\}/s)
  if (rootMatch) {
    const vars = rootMatch[1].match(/--[\w-]+\s*:\s*[^;]+/g)
    if (vars) {
      vars.forEach(v => {
        const idx = v.indexOf(':')
        const name = v.substring(0, idx).trim()
        const value = v.substring(idx + 1).trim().replace(';', '')
        const current = tweakValues.value[name] || value
        const isColor = /^#([0-9A-Fa-f]{3,8})$/.test(current) ||
                        current.startsWith('rgb') ||
                        current.startsWith('oklch')
        list.push({ name, value, current, isColor })
      })
    }
  }
  return list
})

const highlightedHtmlCode = computed(() => {
  const code = simpleBreakHtml(tweakedHtmlCode.value)
  if (!code) return ''
  try {
    return hljs.highlight(code, { language: 'html' }).value
  } catch {
    return code.replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }
})

function simpleBreakHtml(html) {
  if (!html) return ''
  return html.replace(/>(?=<)/g, '>\n')
}

// ==================== Methods ====================

async function handleStart() {
  if (!userInput.value.trim()) return
  loading.value = true
  step.value = 'chatting'
  messages.value.push({ role: 'user', content: userInput.value })

  try {
    const res = await fetch('/api/generate/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ input: userInput.value, model: selectedModel.value })
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
      messages.value.push({ role: 'assistant', content: chatReply.value })
    } else {
      try {
        checklist.value = JSON.parse(data.checklist || '[]')
      } catch {
        checklist.value = []
      }
      checklistAnswers.value = {}
      checklist.value.forEach(item => {
        checklistAnswers.value[item.field] = item.type === 'multi-select' ? [] : ''
      })
      step.value = 'checklist'
      messages.value.push({ role: 'assistant', content: chatReply.value || '为了更好地为你生成网站，请完善以下信息。' })
    }
  } catch (err) {
    alert('请求失败: ' + err.message)
    step.value = 'chatting'
  } finally {
    loading.value = false
  }
}

async function handleResume() {
  loading.value = true
  step.value = 'generating'
  streamingHtml.value = ''
  currentStage.value = '正在准备...'

  try {
    const res = await fetch('/api/generate/resume-stream', {
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

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      const events = parseSseEvents(buffer)
      buffer = events.remainder

      for (const event of events.list) {
        if (event.name === 'stage') {
          currentStage.value = stageToLabel(event.data)
        } else if (event.name === 'html_token') {
          streamingHtml.value += event.data
        } else if (event.name === 'complete') {
          const data = JSON.parse(event.data)
          htmlCode.value = data.htmlCode || ''
          designConcept.value = data.designConcept || ''
          designTokensStr.value = data.designTokens || ''
          reviewPassed.value = data.reviewPassed
          reviewFeedback.value = data.reviewFeedback || ''
          retryCount.value = data.retryCount || 0
          previewUrl.value = data.previewUrl || ''
          console.log('[SSE] complete event, previewUrl=', previewUrl.value)
          initTweaks()
          step.value = 'result'
          await nextTick()
          document.querySelectorAll('pre code').forEach(block => {
            hljs.highlightElement(block)
          })
        } else if (event.name === 'error') {
          throw new Error(event.data)
        }
      }
    }
  } catch (err) {
    alert('请求失败: ' + err.message)
    step.value = 'checklist'
  } finally {
    loading.value = false
  }
}

function stageToLabel(stage) {
  const map = {
    'asset_collector': '正在收集素材...',
    'design_concept': '正在生成设计概念...',
    'html_generator': '正在生成 HTML 代码...',
    'code_reviewer': '正在审查代码...'
  }
  return map[stage] || '处理中...'
}

function isStageActive(stageId) {
  const map = {
    'asset_collector': '正在收集素材...',
    'design_concept': '正在生成设计概念...',
    'html_generator': '正在生成 HTML 代码...',
    'code_reviewer': '正在审查代码...'
  }
  return currentStage.value === map[stageId]
}

function isStageDone(stageId) {
  if (step.value === 'result') return true
  const order = ['asset_collector', 'design_concept', 'html_generator', 'code_reviewer']
  const map = {
    'asset_collector': '正在收集素材...',
    'design_concept': '正在生成设计概念...',
    'html_generator': '正在生成 HTML 代码...',
    'code_reviewer': '正在审查代码...'
  }
  const currentIdx = order.findIndex(id => currentStage.value === map[id])
  const stageIdx = order.indexOf(stageId)
  return currentIdx > stageIdx
}

function parseSseEvents(buffer) {
  const events = []
  let remainder = buffer
  while (true) {
    const idx = remainder.indexOf('\n\n')
    if (idx < 0) break
    const block = remainder.substring(0, idx)
    remainder = remainder.substring(idx + 2)
    const lines = block.split('\n')
    let name = 'message'
    let data = ''
    for (const line of lines) {
      if (line.startsWith('event:')) {
        name = line.substring(6).trim()
      } else if (line.startsWith('data:')) {
        data += line.substring(5).trim() + '\n'
      }
    }
    if (data) {
      events.push({ name, data: data.trim() })
    }
  }
  return { list: events, remainder }
}

function initTweaks() {
  tweakValues.value = {}
  const rootMatch = htmlCode.value.match(/:root\s*\{([^}]*)\}/s)
  if (rootMatch) {
    const vars = rootMatch[1].match(/--[\w-]+\s*:\s*[^;]+/g)
    if (vars) {
      vars.forEach(v => {
        const idx = v.indexOf(':')
        const name = v.substring(0, idx).trim()
        const value = v.substring(idx + 1).trim().replace(';', '')
        tweakValues.value[name] = value
      })
    }
  }
}

function reset() {
  userInput.value = ''
  step.value = 'chatting'
  chatReply.value = ''
  checklist.value = []
  checklistAnswers.value = {}
  sessionId.value = ''
  htmlCode.value = ''
  designConcept.value = ''
  designTokensStr.value = ''
  reviewPassed.value = false
  reviewFeedback.value = ''
  retryCount.value = 0
  copySuccess.value = false
  tweakValues.value = {}
  showTweaks.value = false
  streamingHtml.value = ''
  currentStage.value = ''
  messages.value = []
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(tweakedHtmlCode.value)
    copySuccess.value = true
    setTimeout(() => copySuccess.value = false, 2000)
  } catch {
    alert('复制失败')
  }
}

function downloadHtml() {
  const blob = new Blob([tweakedHtmlCode.value], { type: 'text/html' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'design.html'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleStart()
  }
}
</script>

<template>
  <div class="h-screen flex flex-col bg-[#f5f5f0] text-stone-800" style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
    <!-- Header -->
    <header class="bg-[#fafaf8] border-b border-stone-200 shrink-0">
      <div class="flex items-center justify-between px-5 py-2.5">
        <div class="flex items-center gap-3">
          <div class="w-7 h-7 bg-stone-800 rounded-lg flex items-center justify-center text-white font-bold text-[10px]">AI</div>
          <span class="text-sm font-semibold text-stone-700 tracking-tight">WebsiteMother</span>
        </div>
        <button
          @click="reset"
          class="text-xs text-stone-500 hover:text-stone-800 px-3 py-1.5 rounded-lg hover:bg-stone-100 transition-colors"
        >
          新对话
        </button>
      </div>
    </header>

    <!-- Sidebar + Canvas -->
    <div class="flex-1 flex overflow-hidden">
      <!-- Left Sidebar -->
      <aside class="w-[32%] min-w-[380px] max-w-[520px] border-r border-stone-200 bg-white flex flex-col overflow-hidden shrink-0">
        <div class="flex-1 overflow-y-auto p-4 space-y-4">
          <!-- Agent Chat Messages -->
          <div v-for="(msg, i) in messages" :key="i" class="space-y-1">
            <!-- User Message -->
            <div v-if="msg.role === 'user'" class="flex justify-end">
              <div class="max-w-[85%] bg-stone-800 text-white rounded-2xl rounded-tr-sm px-4 py-2.5 text-sm leading-relaxed">
                {{ msg.content }}
              </div>
            </div>
            <!-- Assistant Message -->
            <div v-else class="flex justify-start">
              <div class="flex gap-2.5 max-w-[92%]">
                <div class="w-7 h-7 rounded-full bg-stone-800 flex items-center justify-center shrink-0 mt-0.5">
                  <span class="text-[10px] font-bold text-white">AI</span>
                </div>
                <div class="bg-stone-50 rounded-2xl rounded-tl-sm px-4 py-2.5 text-sm text-stone-700 leading-relaxed">
                  {{ msg.content }}
                </div>
              </div>
            </div>
          </div>

          <!-- Loading Indicator in Chat -->
          <div v-if="step === 'chatting' && loading" class="flex justify-start">
            <div class="flex gap-2.5 max-w-[92%]">
              <div class="w-7 h-7 rounded-full bg-stone-800 flex items-center justify-center shrink-0">
                <span class="text-[10px] font-bold text-white">AI</span>
              </div>
              <div class="bg-stone-50 rounded-2xl rounded-tl-sm px-4 py-3">
                <div class="flex gap-1.5">
                  <div class="w-2 h-2 bg-stone-400 rounded-full animate-bounce"></div>
                  <div class="w-2 h-2 bg-stone-400 rounded-full animate-bounce" style="animation-delay: 0.15s"></div>
                  <div class="w-2 h-2 bg-stone-400 rounded-full animate-bounce" style="animation-delay: 0.3s"></div>
                </div>
              </div>
            </div>
          </div>

          <!-- Checklist Summary -->
          <div v-if="checklist.length > 0 && step !== 'checklist'" class="bg-white rounded-xl p-3.5 border border-stone-200 shadow-sm space-y-2">
            <p class="text-[11px] font-semibold text-stone-400 mb-1 uppercase tracking-wider">需求清单</p>
            <div v-for="item in checklist" :key="item.field" class="text-sm flex gap-2">
              <span class="text-stone-500 shrink-0">{{ item.label }}:</span>
              <span class="text-stone-700">
                {{ item.type === 'multi-select'
                  ? (checklistAnswers[item.field]?.length ? checklistAnswers[item.field].join('、') : '-')
                  : (checklistAnswers[item.field] || '-') }}
              </span>
            </div>
          </div>

          <!-- Execution Flow -->
          <div v-if="step === 'generating' || step === 'result'" class="pt-2">
            <p class="text-[11px] font-semibold text-stone-400 mb-2 uppercase tracking-wider">执行流程</p>
            <div class="space-y-1">
              <div class="flex items-center gap-2.5 px-2 py-1.5 rounded-lg text-sm transition-colors"
                   :class="isStageActive('asset_collector') ? 'bg-indigo-50 text-indigo-700' : (isStageDone('asset_collector') ? 'text-stone-500' : 'text-stone-300')">
                <span v-if="isStageDone('asset_collector')" class="text-emerald-500">✓</span>
                <span v-else-if="isStageActive('asset_collector')" class="w-1.5 h-1.5 bg-indigo-500 rounded-full animate-pulse"></span>
                <span v-else class="w-1.5 h-1.5 bg-stone-300 rounded-full"></span>
                <span>素材收集</span>
              </div>
              <div class="flex items-center gap-2.5 px-2 py-1.5 rounded-lg text-sm transition-colors"
                   :class="isStageActive('design_concept') ? 'bg-indigo-50 text-indigo-700' : (isStageDone('design_concept') ? 'text-stone-500' : 'text-stone-300')">
                <span v-if="isStageDone('design_concept')" class="text-emerald-500">✓</span>
                <span v-else-if="isStageActive('design_concept')" class="w-1.5 h-1.5 bg-indigo-500 rounded-full animate-pulse"></span>
                <span v-else class="w-1.5 h-1.5 bg-stone-300 rounded-full"></span>
                <span>设计概念</span>
              </div>
              <div class="flex items-center gap-2.5 px-2 py-1.5 rounded-lg text-sm transition-colors"
                   :class="isStageActive('html_generator') ? 'bg-indigo-50 text-indigo-700' : (isStageDone('html_generator') ? 'text-stone-500' : 'text-stone-300')">
                <span v-if="isStageDone('html_generator')" class="text-emerald-500">✓</span>
                <span v-else-if="isStageActive('html_generator')" class="w-1.5 h-1.5 bg-indigo-500 rounded-full animate-pulse"></span>
                <span v-else class="w-1.5 h-1.5 bg-stone-300 rounded-full"></span>
                <span>HTML 生成</span>
              </div>
              <div class="flex items-center gap-2.5 px-2 py-1.5 rounded-lg text-sm transition-colors"
                   :class="isStageActive('code_reviewer') ? 'bg-indigo-50 text-indigo-700' : (isStageDone('code_reviewer') ? 'text-stone-500' : 'text-stone-300')">
                <span v-if="isStageDone('code_reviewer')" class="text-emerald-500">✓</span>
                <span v-else-if="isStageActive('code_reviewer')" class="w-1.5 h-1.5 bg-indigo-500 rounded-full animate-pulse"></span>
                <span v-else class="w-1.5 h-1.5 bg-stone-300 rounded-full"></span>
                <span>代码审查</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Model Selector + Input -->
        <div class="border-t border-stone-200 p-3 bg-white shrink-0 space-y-2">
          <div class="flex items-center gap-2">
            <span class="text-[11px] font-semibold text-stone-400 uppercase tracking-wider">生成模型</span>
            <select
              v-model="selectedModel"
              class="flex-1 text-xs text-stone-700 bg-stone-50 border border-stone-200 rounded-lg px-2 py-1.5 outline-none focus:ring-2 focus:ring-stone-400 focus:border-stone-400"
            >
              <option value="qwen3.6-plus">通义千问 3.6 Plus</option>
              <option value="qwen3.6-max">通义千问 3.6 Max</option>
              <option value="deepseek-v4-pro">DeepSeek V4 Pro</option>
              <option value="deepseek-v4-plus">DeepSeek V4 Plus</option>
            </select>
          </div>
          <div class="bg-stone-50 rounded-xl border border-stone-200 p-2">
            <textarea
              v-model="userInput"
              @keydown="handleKeydown"
              placeholder="描述你想要的网站..."
              class="w-full h-16 p-2 resize-none outline-none text-sm text-stone-700 placeholder-stone-400 bg-transparent"
            />
            <div class="flex justify-end">
              <button
                @click="handleStart"
                :disabled="!userInput.trim() || loading"
                class="bg-stone-800 hover:bg-stone-900 disabled:opacity-40 disabled:cursor-not-allowed text-white px-3 py-1.5 rounded-lg text-xs font-medium transition-colors"
              >
                发送
              </button>
            </div>
          </div>
        </div>
      </aside>

      <!-- Right Canvas -->
      <main class="flex-1 overflow-auto relative"
            style="background-color: #f5f5f0; background-image: linear-gradient(to right, rgba(0,0,0,0.04) 1px, transparent 1px), linear-gradient(to bottom, rgba(0,0,0,0.04) 1px, transparent 1px); background-size: 24px 24px;">
        <!-- Chatting Loading -->
        <div v-if="step === 'chatting' && loading" class="h-full flex items-center justify-center">
          <div class="flex flex-col items-center gap-4">
            <div class="flex gap-1.5">
              <div class="w-2.5 h-2.5 bg-stone-400 rounded-full animate-bounce"></div>
              <div class="w-2.5 h-2.5 bg-stone-400 rounded-full animate-bounce" style="animation-delay: 0.15s"></div>
              <div class="w-2.5 h-2.5 bg-stone-400 rounded-full animate-bounce" style="animation-delay: 0.3s"></div>
            </div>
            <p class="text-stone-500 text-sm">AI 正在分析你的需求...</p>
          </div>
        </div>

        <!-- Chatting Idle -->
        <div v-else-if="step === 'chatting'" class="h-full flex items-center justify-center">
          <div class="text-center space-y-3 text-stone-400">
            <svg class="w-10 h-10 mx-auto opacity-40" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"/>
            </svg>
            <p class="text-sm">在左侧输入框描述你想要的网站</p>
          </div>
        </div>

        <!-- Checklist -->
        <div v-if="step === 'checklist'" class="w-full px-8 py-8 space-y-6">
          <div class="text-center space-y-1">
            <h2 class="text-2xl font-bold text-stone-800">完善你的需求</h2>
            <p class="text-stone-500">请填写以下信息，帮助 AI 更精准地生成</p>
          </div>
          <div class="bg-white rounded-2xl shadow-sm border border-stone-200 p-6 space-y-5">
            <div v-for="item in checklist" :key="item.field" class="space-y-2">
              <label class="block text-sm font-medium text-stone-700">
                {{ item.label }}
                <span v-if="item.description" class="text-stone-400 font-normal text-xs ml-1">({{ item.description }})</span>
              </label>
              <input
                v-if="item.type === 'text'"
                v-model="checklistAnswers[item.field]"
                type="text"
                class="w-full px-4 py-2.5 border border-stone-300 rounded-xl focus:ring-2 focus:ring-stone-400 focus:border-stone-400 outline-none transition-all text-sm"
                :placeholder="item.description || '请输入'"
              />
              <textarea
                v-else-if="item.type === 'textarea'"
                v-model="checklistAnswers[item.field]"
                rows="3"
                class="w-full px-4 py-2.5 border border-stone-300 rounded-xl focus:ring-2 focus:ring-stone-400 focus:border-stone-400 outline-none transition-all resize-none text-sm"
                :placeholder="item.description || '请输入'"
              />
              <select
                v-else-if="item.type === 'select'"
                v-model="checklistAnswers[item.field]"
                class="w-full px-4 py-2.5 border border-stone-300 rounded-xl focus:ring-2 focus:ring-stone-400 focus:border-stone-400 outline-none transition-all bg-white text-sm"
              >
                <option value="">请选择</option>
                <option v-for="opt in item.options" :key="opt" :value="opt">{{ opt }}</option>
              </select>
              <div
                v-else-if="item.type === 'multi-select'"
                class="grid grid-cols-2 gap-2"
              >
                <label
                  v-for="opt in item.options"
                  :key="opt"
                  class="flex items-center gap-2 px-3 py-2 border border-stone-200 rounded-lg hover:bg-stone-50 cursor-pointer transition-colors"
                >
                  <input
                    type="checkbox"
                    :value="opt"
                    v-model="checklistAnswers[item.field]"
                    class="w-4 h-4 rounded border-stone-300 text-stone-700 focus:ring-stone-400"
                  />
                  <span class="text-sm text-stone-700">{{ opt }}</span>
                </label>
              </div>
            </div>
            <div class="pt-2">
              <button
                @click="handleResume"
                :disabled="loading"
                class="w-full bg-stone-800 hover:bg-stone-900 disabled:opacity-40 disabled:cursor-not-allowed text-white py-3 rounded-xl font-medium transition-colors flex items-center justify-center gap-2 text-sm"
              >
                <span v-if="loading" class="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></span>
                <span>{{ loading ? '生成中...' : '确认并生成代码' }}</span>
              </button>
            </div>
          </div>
        </div>

        <!-- Generating -->
        <div v-if="step === 'generating'" class="h-full flex flex-col p-4">
          <div class="flex flex-col items-center justify-center py-6 space-y-4 shrink-0">
            <div class="relative">
              <div class="w-12 h-12 border-4 border-stone-200 rounded-full"></div>
              <div class="absolute top-0 left-0 w-12 h-12 border-4 border-stone-600 border-t-transparent rounded-full animate-spin"></div>
            </div>
            <div class="text-center space-y-1">
              <p class="text-base font-medium text-stone-800">AI 正在为你生成网站设计</p>
              <p class="text-stone-500 text-sm">{{ currentStage || '正在准备...' }}</p>
            </div>
          </div>
          <!-- Code typewriter -->
          <div v-if="streamingHtml" class="flex-1 bg-white rounded-2xl border border-stone-200 overflow-hidden shadow-sm min-h-0">
            <div class="flex items-center gap-2 px-4 py-2.5 bg-stone-50 border-b border-stone-200">
              <div class="w-2.5 h-2.5 rounded-full bg-red-400"></div>
              <div class="w-2.5 h-2.5 rounded-full bg-amber-400"></div>
              <div class="w-2.5 h-2.5 rounded-full bg-emerald-400"></div>
              <span class="ml-2 text-xs text-stone-400 font-mono">index.html</span>
            </div>
            <pre class="p-4 overflow-auto text-sm leading-relaxed text-stone-700 font-mono bg-white h-full whitespace-pre-wrap break-all" style="text-align: left;"><code>{{ simpleBreakHtml(streamingHtml) }}</code></pre>
          </div>
        </div>

        <!-- Result -->
        <div v-if="step === 'result'" class="p-4 space-y-4 w-full">
          <!-- Toolbar -->
          <div class="flex items-center justify-between flex-wrap gap-3">
            <div>
              <h2 class="text-xl font-bold text-stone-800">生成完成</h2>
              <p class="text-stone-500 text-sm mt-0.5">
                审查
                <span :class="reviewPassed ? 'text-emerald-600' : 'text-amber-600'">
                  {{ reviewPassed ? '已通过' : '未通过' }}
                </span>
                <span v-if="retryCount > 0" class="text-stone-400"> · 修复 {{ retryCount }} 次</span>
              </p>
            </div>
            <div class="flex items-center gap-2">
              <button @click="showPreviewModal = true" class="flex items-center gap-1.5 px-3 py-1.5 bg-white border border-stone-300 rounded-lg hover:border-stone-400 hover:text-stone-800 transition-colors text-xs font-medium text-stone-600">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4"/></svg>
                放大预览
              </button>
              <button @click="showTweaks = !showTweaks" class="flex items-center gap-1.5 px-3 py-1.5 bg-white border border-stone-300 rounded-lg hover:border-stone-400 hover:text-stone-800 transition-colors text-xs font-medium text-stone-600">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4"/></svg>
                Tweaks
              </button>
              <button @click="copyCode" class="flex items-center gap-1.5 px-3 py-1.5 bg-white border border-stone-300 rounded-lg hover:border-stone-400 hover:text-stone-800 transition-colors text-xs font-medium text-stone-600">
                <svg v-if="!copySuccess" class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"/></svg>
                <svg v-else class="w-3.5 h-3.5 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/></svg>
                <span>{{ copySuccess ? '已复制' : '复制' }}</span>
              </button>
              <button @click="downloadHtml" class="flex items-center gap-1.5 px-3 py-1.5 bg-stone-800 hover:bg-stone-900 text-white rounded-lg transition-colors text-xs font-medium">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>
                下载
              </button>
            </div>
          </div>

          <!-- Review Card -->
          <div v-if="reviewFeedback" class="rounded-xl border p-3.5 text-sm"
               :class="reviewPassed ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-amber-50 border-amber-200 text-amber-800'">
            <div class="flex items-start gap-2.5">
              <div class="shrink-0 mt-0.5">
                <svg v-if="reviewPassed" class="w-4 h-4 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>
                <svg v-else class="w-4 h-4 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/></svg>
              </div>
              <div class="space-y-1 min-w-0">
                <p class="font-semibold">{{ reviewPassed ? '代码审查通过' : '代码审查未通过' }}</p>
                <p class="leading-relaxed whitespace-pre-wrap">{{ reviewFeedback }}</p>
                <p v-if="!reviewPassed" class="text-xs opacity-70">AI 已尝试自动修复 {{ retryCount }} 次，建议下载后手动调整。</p>
              </div>
            </div>
          </div>

          <!-- Tweaks Panel -->
          <div v-if="showTweaks && tweakList.length > 0" class="bg-white rounded-2xl shadow-sm border border-stone-200 p-5">
            <h3 class="text-sm font-semibold text-stone-800 mb-3">Tweaks - 调节设计系统</h3>
            <div class="grid grid-cols-2 lg:grid-cols-4 gap-3">
              <div v-for="token in tweakList" :key="token.name" class="space-y-1">
                <label class="block text-[10px] font-mono text-stone-400">{{ token.name }}</label>
                <div class="flex items-center gap-2">
                  <input v-if="token.isColor" v-model="tweakValues[token.name]" type="color" class="w-6 h-6 rounded border border-stone-200 p-0 cursor-pointer" />
                  <input v-model="tweakValues[token.name]" type="text" class="flex-1 px-2 py-1 border border-stone-300 rounded-md text-xs focus:ring-2 focus:ring-stone-400 focus:border-stone-400 outline-none" />
                </div>
              </div>
            </div>
          </div>

          <!-- Preview (Large) -->
          <div class="bg-white rounded-2xl shadow-sm border border-stone-200 overflow-hidden">
            <div class="flex items-center gap-2 px-4 py-2.5 bg-stone-50 border-b border-stone-200">
              <div class="w-2.5 h-2.5 rounded-full bg-red-400"></div>
              <div class="w-2.5 h-2.5 rounded-full bg-amber-400"></div>
              <div class="w-2.5 h-2.5 rounded-full bg-emerald-400"></div>
              <span class="ml-2 text-xs text-stone-400 font-mono">index.html</span>
            </div>
            <iframe
              :src="previewUrl || undefined"
              :srcdoc="previewUrl ? undefined : tweakedHtmlCode"
              class="w-full border-0"
              style="height: calc(100vh - 220px); min-height: 500px;"
              sandbox="allow-scripts allow-same-origin"
            ></iframe>
          </div>

          <!-- Preview Modal -->
          <div v-if="showPreviewModal" class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" @click.self="showPreviewModal = false">
            <div class="w-[95vw] h-[95vh] bg-white rounded-2xl shadow-2xl overflow-hidden flex flex-col">
              <div class="flex items-center justify-between px-4 py-3 bg-stone-50 border-b border-stone-200 shrink-0">
                <div class="flex items-center gap-2">
                  <div class="w-2.5 h-2.5 rounded-full bg-red-400"></div>
                  <div class="w-2.5 h-2.5 rounded-full bg-amber-400"></div>
                  <div class="w-2.5 h-2.5 rounded-full bg-emerald-400"></div>
                  <span class="ml-2 text-xs text-stone-400 font-mono">index.html</span>
                </div>
                <button @click="showPreviewModal = false" class="text-stone-500 hover:text-stone-800 p-1 rounded-lg hover:bg-stone-200 transition-colors">
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg>
                </button>
              </div>
              <iframe
                :src="previewUrl || undefined"
                :srcdoc="previewUrl ? undefined : tweakedHtmlCode"
                class="w-full border-0 flex-1"
                sandbox="allow-scripts allow-same-origin"
              ></iframe>
            </div>
          </div>

          <!-- Design Concept -->
          <div v-if="parsedDesignConcept" class="bg-white rounded-2xl shadow-sm border border-stone-200 p-5">
            <h3 class="text-sm font-semibold text-stone-800 mb-3">设计概念</h3>
            <div class="grid grid-cols-2 lg:grid-cols-4 gap-4 text-sm">
              <div v-if="parsedDesignConcept.colorPalette" class="space-y-2">
                <p class="font-medium text-stone-600 text-xs">配色方案</p>
                <div class="flex flex-wrap gap-1.5">
                  <div v-for="(color, key) in parsedDesignConcept.colorPalette" :key="key" class="flex items-center gap-1 px-1.5 py-0.5 bg-stone-50 rounded-md">
                    <div class="w-3 h-3 rounded-sm border border-stone-200" :style="{ background: color }"></div>
                    <span class="text-[10px] text-stone-500">{{ key }}</span>
                  </div>
                </div>
              </div>
              <div v-if="parsedDesignConcept.typography" class="space-y-1">
                <p class="font-medium text-stone-600 text-xs">字体系统</p>
                <p class="text-stone-500 text-xs">标题：{{ parsedDesignConcept.typography.headingFont }}</p>
                <p class="text-stone-500 text-xs">正文：{{ parsedDesignConcept.typography.bodyFont }}</p>
              </div>
              <div v-if="parsedDesignConcept.layoutDirection" class="space-y-1">
                <p class="font-medium text-stone-600 text-xs">布局方向</p>
                <p class="text-stone-500 text-xs">{{ parsedDesignConcept.layoutDirection }}</p>
              </div>
              <div v-if="parsedDesignConcept.mood" class="space-y-1">
                <p class="font-medium text-stone-600 text-xs">整体氛围</p>
                <p class="text-stone-500 text-xs">{{ parsedDesignConcept.mood }}</p>
              </div>
            </div>
          </div>

          <!-- Code Display -->
          <div class="bg-[#1f2028] rounded-2xl overflow-hidden shadow-lg">
            <div class="flex items-center gap-2 px-4 py-2.5 bg-[#16171d] border-b border-[#2e303a]">
              <div class="w-2.5 h-2.5 rounded-full bg-red-500"></div>
              <div class="w-2.5 h-2.5 rounded-full bg-yellow-500"></div>
              <div class="w-2.5 h-2.5 rounded-full bg-green-500"></div>
              <span class="ml-2 text-xs text-stone-400 font-mono">index.html</span>
            </div>
            <pre class="p-4 overflow-x-auto text-xs leading-relaxed whitespace-pre-wrap break-all"><code class="language-html" v-html="highlightedHtmlCode"></code></pre>
          </div>
        </div>
      </main>
    </div>
  </div>
</template>
