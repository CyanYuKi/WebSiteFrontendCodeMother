<script setup>
import { ref, nextTick, computed } from 'vue'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

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
const cancelled = ref(false)
const sseReader = ref(null)

// 打字机动画
const twDisplayed = ref({})       // 逐字显示文本 { pageName: string }
const twTimer = ref(null)         // 共享 rAF 句柄
const twLastTick = ref(0)         // 上次高亮计算时间戳
const twHighlightCache = ref({})  // 高亮结果缓存 { pageName: { input, output } }
const TW_SPEED = 5                // 每帧输出字符数

// Tweaks
const showTweaks = ref(false)
const tweakValues = ref({})
const showPreviewModal = ref(false)

// Model selection
const selectedModel = ref('qwen3.6-plus')

// 多页面预览 URL
const previewUrl = ref('')
const screenshotUrl = ref('')

// 多页面列表与当前选中页面
const pages = ref([])
const currentPage = ref('')

// iframe 引用，用于监听内部导航
const previewFrame = ref(null)
const modalFrame = ref(null)

// 子页面生成状态
const pageList = ref([])          // 从 page_list SSE 事件获取 [{name, title, overview}]
const pageTokens = ref({})        // { [pageName]: accumulatedCode }
const pageStatuses = ref({})      // { [pageName]: 'generating' | 'completed' | '' }

const activePreviewUrl = computed(() => {
  if (!previewUrl.value) return undefined
  return previewUrl.value + (currentPage.value || 'index.html')
})

const allGeneratingPages = computed(() => {
  const list = ['index.html']
  for (const p of pageList.value) {
    if (p.name && !list.includes(p.name)) list.push(p.name)
  }
  return list
})

// Agent 聊天消息流
const messages = ref([])

// ==================== Auth State ====================
function getCookie(name) {
  const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'))
  return match ? decodeURIComponent(match[2]) : ''
}

function setCookie(name, value, days) {
  const d = new Date()
  d.setTime(d.getTime() + days * 24 * 60 * 60 * 1000)
  document.cookie = name + '=' + encodeURIComponent(value) + ';expires=' + d.toUTCString() + ';path=/;SameSite=Lax'
}

function removeCookie(name) {
  document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:00 UTC;path=/;SameSite=Lax'
}

const authToken = ref(getCookie('authToken'))
const currentUser = ref(null)
const authMode = ref(null) // 'login' | 'register' | null
const authError = ref('')
const authLoading = ref(false)

// 用户首页 & 历史项目
const showUserHome = ref(false)
const myProjects = ref([])
const projectsLoading = ref(false)

// 用户管理
const showUserManagement = ref(false)
const userList = ref([])
const userMgmtLoading = ref(false)
const editingUserId = ref(null)
const editRole = ref('')

// 登录表单
const loginForm = ref({ username: '', password: '' })
const registerForm = ref({ username: '', password: '', email: '' })

// 已有 token 时自动获取用户信息
if (authToken.value) {
  fetchMe()
}

async function fetchMe() {
  try {
    const res = await fetch('/api/auth/me', {
      headers: { 'Authorization': 'Bearer ' + authToken.value }
    })
    if (res.ok) {
      currentUser.value = await res.json()
    } else {
      removeCookie('authToken')
      authToken.value = ''
    }
  } catch {
    // 忽略
  }
}

function authHeaders() {
  return authToken.value ? { 'Authorization': 'Bearer ' + authToken.value, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' }
}

function isAdmin() {
  return currentUser.value && currentUser.value.role === 'ADMIN'
}

async function handleLogin() {
  authError.value = ''
  authLoading.value = true
  try {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(loginForm.value)
    })
    if (!res.ok) {
      const err = await res.json().catch(() => ({}))
      throw new Error(err.message || err.error || '登录失败')
    }
    const data = await res.json()
    authToken.value = data.token
    currentUser.value = data.user
    setCookie('authToken', data.token, 30)
    authMode.value = null
    loginForm.value = { username: '', password: '' }
  } catch (e) {
    authError.value = e.message
  } finally {
    authLoading.value = false
  }
}

async function handleRegister() {
  authError.value = ''
  authLoading.value = true
  try {
    const res = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(registerForm.value)
    })
    if (!res.ok) {
      const err = await res.json().catch(() => ({}))
      throw new Error(err.message || err.error || '注册失败')
    }
    const data = await res.json()
    authToken.value = data.token
    currentUser.value = data.user
    setCookie('authToken', data.token, 30)
    authMode.value = null
    registerForm.value = { username: '', password: '', email: '' }
  } catch (e) {
    authError.value = e.message
  } finally {
    authLoading.value = false
  }
}

async function handleLogout() {
  try {
    await fetch('/api/auth/logout', {
      method: 'POST',
      headers: authHeaders()
    })
  } catch { /* ignore */ }
  authToken.value = ''
  currentUser.value = null
  removeCookie('authToken')
  reset()
}

async function openUserHome() {
  showUserHome.value = true
  step.value = ''
  await loadMyProjects()
}

async function deleteProject(projectId) {
	  if (!confirm('确定要删除该项目吗？此操作不可撤销。')) return
	  try {
	    const res = await fetch('/api/projects/my/' + projectId, {
	      method: 'DELETE',
	      headers: authHeaders()
	    })
	    if (!res.ok) {
	      const err = await res.json().catch(() => ({}))
	      throw new Error(err.message || '删除失败')
	    }
	    await loadMyProjects()
	  } catch(e) {
	    alert('删除失败: ' + e.message)
	  }
	}

	async function downloadProject(projectId) {
	  try {
	    const res = await fetch('/api/projects/my/' + projectId + '/download-zip', {
	      headers: authHeaders()
	    })
	    if (!res.ok) throw new Error('下载失败')
	    const blob = await res.blob()
	    const url = URL.createObjectURL(blob)
	    const a = document.createElement('a')
	    a.href = url
	    a.download = projectId + '.zip'
	    document.body.appendChild(a)
	    a.click()
	    document.body.removeChild(a)
	    URL.revokeObjectURL(url)
	  } catch(e) {
	    alert('下载失败: ' + e.message)
	  }
	}

	function closeUserHome() {
  showUserHome.value = false
  reset()
}

async function loadMyProjects() {
  projectsLoading.value = true
  try {
    const res = await fetch('/api/projects/my', { headers: authHeaders() })
    if (res.ok) {
      myProjects.value = await res.json()
    }
  } catch { /* ignore */ }
  projectsLoading.value = false
}

function saveChatHistory(projectId) {
  if (!projectId) return
  const history = JSON.stringify(messages.value)
  fetch('/api/projects/my/' + projectId + '/chat-history', {
    method: 'PUT',
    headers: authHeaders(),
    body: JSON.stringify({ chatHistory: history })
  }).catch(() => {})
}

async function reloadProject(projectId) {
  try {
    const res = await fetch('/api/projects/my/' + projectId, { headers: authHeaders() })
    if (!res.ok) throw new Error('加载失败')
    const data = await res.json()
    htmlCode.value = data.htmlCode || ''
    designConcept.value = data.designConcept || ''
    reviewPassed.value = data.reviewPassed
    reviewFeedback.value = ''
    retryCount.value = data.retryCount || 0
    previewUrl.value = '/api/preview/' + projectId + '/'
    pages.value = ['index.html']
    currentPage.value = 'index.html'
    // 恢复对话历史
    try {
      const history = JSON.parse(data.chatHistory || '[]')
      messages.value = history
    } catch { messages.value = [] }
    showUserHome.value = false
    step.value = 'result'
    initTweaks()
    await nextTick()
    document.querySelectorAll('pre code').forEach(block => {
      hljs.highlightElement(block)
    })
  } catch(e) {
    alert('加载项目失败: ' + e.message)
  }
}

async function loadUsers() {
  userMgmtLoading.value = true
  try {
    const res = await fetch('/api/users', { headers: authHeaders() })
    if (res.ok) {
      userList.value = await res.json()
    }
  } catch { /* ignore */ }
  userMgmtLoading.value = false
}

function openUserManagement() {
  showUserManagement.value = true
  loadUsers()
}

async function saveUserEdit(id) {
  try {
    await fetch('/api/users/' + id, {
      method: 'PUT',
      headers: authHeaders(),
      body: JSON.stringify({ role: editRole.value })
    })
    editingUserId.value = null
    editRole.value = ''
    loadUsers()
  } catch { /* ignore */ }
}

async function deleteUser(id) {
  if (!confirm('确定要删除该用户吗？')) return
  try {
    const res = await fetch('/api/users/' + id, {
      method: 'DELETE',
      headers: authHeaders()
    })
    if (!res.ok && res.status !== 200) {
      const err = await res.json().catch(() => ({}))
      alert(err.error || '删除失败')
      return
    }
    loadUsers()
  } catch(e) {
    alert('删除失败: ' + e.message)
  }
}

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
  const code = formatHtmlCode(tweakedHtmlCode.value)
  if (!code) return ''
  try {
    return hljs.highlight(code, { language: 'html' }).value
  } catch {
    return code.replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }
})

/**
 * 格式化 HTML 代码：智能缩进 + 标签换行，模拟 IDE 效果
 */
function formatHtmlCode(html) {
  if (!html) return ''

  // 步骤1: 在标签边界处插入换行（保留文本内容）
  let withNewlines = html
    .replace(/>(\s*)</g, '>\n<')           // 标签之间换行
    .replace(/(<!DOCTYPE[^>]*>)/gi, '$1\n') // DOCTYPE 后换行

  // 步骤2: 按行处理缩进
  const lines = withNewlines.split('\n')
  let indent = 0
  const result = []
  const indentStr = '  '

  for (let rawLine of lines) {
    let line = rawLine.trim()
    if (!line) { result.push(''); continue }

    // 检测标签：必须是以 < 开头且以 > 结尾的完整标签
    const isFullTag = /^<[^>]+>$/.test(line)
    const isCloseTag = /^<\/[a-zA-Z]/.test(line)
    const isSelfClosing = /\/>\s*$/.test(line) || /^<(area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)[^>]*>$/i.test(line)
    const isDoctype = /^<!DOCTYPE/i.test(line)
    const isOpeningTag = isFullTag && !isCloseTag && !isSelfClosing && !isDoctype

    // 闭标签：先缩进，再减层级
    if (isCloseTag && indent > 0) {
      indent--
    }

    // 添加当前行（带缩进）
    result.push(indentStr.repeat(indent) + line)

    // 开标签：增加层级（但排除一行内就闭合的情况如 <div>...</div>）
    if (isOpeningTag && !line.includes('</')) {
      indent++
    }
  }

  // 步骤3: 对 <style> 块内的 CSS 做额外格式化
  let output = result.join('\n')
  output = output.replace(/(<style[^>]*>)([\s\S]*?)(<\/style>)/g, (match, open, css, close) => {
    const cssLines = css.split('\n').map(l => l.trim())
    let cssIndent = 1
    const formattedCss = cssLines.map(line => {
      if (!line) return ''
      const isCssClose = /^\}/.test(line)
      const isCssOpen = /\{\s*$/.test(line)
      if (isCssClose && cssIndent > 1) cssIndent--
      const indented = indentStr.repeat(cssIndent) + line
      if (isCssOpen) cssIndent++
      return indented
    }).join('\n')
    return open + '\n' + formattedCss + '\n' + indentStr + close
  })

  // 步骤4: 对 <script> 块内的 JS 做简单格式化
  output = output.replace(/(<script[^>]*>)([\s\S]*?)(<\/script>)/g, (match, open, js, close) => {
    const jsLines = js.split('\n').map(l => l.trim())
    let jsIndent = 1
    const formattedJs = jsLines.map(line => {
      if (!line) return ''
      const isJsClose = /^\}/.test(line) || /^\)/.test(line) || /^\];/.test(line)
      const isJsOpen = /\{\s*$/.test(line) || /\(\s*$/.test(line) || /\[\s*$/.test(line)
      if (isJsClose && jsIndent > 1) jsIndent--
      const indented = indentStr.repeat(jsIndent) + line
      if (isJsOpen) jsIndent++
      return indented
    }).join('\n')
    return open + '\n' + formattedJs + '\n' + indentStr + close
  })

  return output
}

// ==================== Typewriter Engine ====================

function twStartIfNeeded() {
  if (twTimer.value) return
  function tick() {
    let hasMore = false
    const updated = { ...twDisplayed.value }
    for (const [name, full] of Object.entries(pageTokens.value)) {
      if (!full) continue
      const current = updated[name] || ''
      if (current.length < full.length) {
        hasMore = true
        updated[name] = full.substring(0, current.length + TW_SPEED)
      }
    }
    twDisplayed.value = updated
    if (hasMore) {
      twTimer.value = requestAnimationFrame(tick)
    } else {
      twTimer.value = null
    }
  }
  twTimer.value = requestAnimationFrame(tick)
}

function twStop() {
  if (twTimer.value) {
    cancelAnimationFrame(twTimer.value)
    twTimer.value = null
  }
  // flush: 将每页显示文本推到与源文本相同长度
  const updated = { ...twDisplayed.value }
  for (const [name, full] of Object.entries(pageTokens.value)) {
    if (full) updated[name] = full
  }
  twDisplayed.value = updated
}

function twIsAnimating(name) {
  const full = pageTokens.value[name] || ''
  const displayed = twDisplayed.value[name] || ''
  return displayed.length < full.length
}

function twLineCount(name) {
  const text = twDisplayed.value[name] || ''
  if (!text) return 1
  return formatStreamingHtml(text).split('\n').length
}

function escapeHtml(text) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function colorizeAttrs(attrsStr) {
  let out = ''
  const re = /(\s+)([a-zA-Z][\w-]*)(?:(\s*=\s*)(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g
  let m
  while ((m = re.exec(attrsStr)) !== null) {
    out += escapeHtml(m[1])
    out += '<span class="hljs-attr">' + escapeHtml(m[2]) + '</span>'
    if (m[3]) {
      out += '<span class="hljs-tag">=</span>'
      const val = m[4] !== undefined ? m[4] : (m[5] !== undefined ? m[5] : (m[6] || ''))
      out += '<span class="hljs-string">&quot;' + escapeHtml(val) + '&quot;</span>'
    }
  }
  return out
}

function colorizeTag(tag) {
  if (/^<!DOCTYPE/i.test(tag)) {
    return '<span class="hljs-meta">' + escapeHtml(tag) + '</span>'
  }
  if (/^<!--/.test(tag)) {
    return '<span class="hljs-comment">' + escapeHtml(tag) + '</span>'
  }
  if (/^<\//.test(tag)) {
    const m = tag.match(/^<\/([a-zA-Z][\w-]*)/)
    if (m) {
      return '<span class="hljs-tag">&lt;/</span>' +
        '<span class="hljs-name">' + escapeHtml(m[1]) + '</span>' +
        '<span class="hljs-tag">&gt;</span>'
    }
    return escapeHtml(tag)
  }
  const m = tag.match(/^<([a-zA-Z][\w-]*)((\s[^>]*?)?)\s*(\/?)>$/)
  if (m) {
    let out = '<span class="hljs-tag">&lt;</span>'
    out += '<span class="hljs-name">' + escapeHtml(m[1]) + '</span>'
    if (m[2]) out += colorizeAttrs(m[2])
    out += m[4] === '/' ? '<span class="hljs-tag">/&gt;</span>' : '<span class="hljs-tag">&gt;</span>'
    return out
  }
  return escapeHtml(tag)
}

function highlightStreaming(formattedHtml) {
  if (!formattedHtml) return ''
  let result = ''
  let i = 0
  while (i < formattedHtml.length) {
    if (formattedHtml[i] === '<') {
      const gt = formattedHtml.indexOf('>', i)
      if (gt !== -1) {
        result += colorizeTag(formattedHtml.substring(i, gt + 1))
        i = gt + 1
      } else {
        result += escapeHtml(formattedHtml.substring(i))
        break
      }
    } else {
      const nextLt = formattedHtml.indexOf('<', i)
      if (nextLt === -1) {
        result += escapeHtml(formattedHtml.substring(i))
        break
      }
      result += escapeHtml(formattedHtml.substring(i, nextLt))
      i = nextLt
    }
  }
  return result
}

/**
 * 流式代码专用格式化：标签换行 + 基础缩进
 * 用 > 作为天然断点拆行，逐行计算深度，避免跨调用状态漂移
 */
function formatStreamingHtml(html) {
  if (!html) return ''

  // 仅在 > 后不是换行时才插入换行，避免双倍空行
  let text = html.replace(/>([^\n])/g, '>\n$1')
  text = text.replace(/\n{3,}/g, '\n\n')

  const rawLines = text.split('\n')
  const result = []

  // 建立标签位置索引
  const tagRe = /<(\/?)([a-zA-Z][\w-]*)[^>]*>/g
  const tags = []
  let m
  while ((m = tagRe.exec(text)) !== null) {
    const isClose = m[1] === '/'
    const isVoid = /^<(area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)\b/i.test(m[0])
    tags.push({ pos: m.index, len: m[0].length, isClose, isVoid })
  }

  let lineStartPos = 0
  for (let i = 0; i < rawLines.length; i++) {
    const trimmed = rawLines[i].trim()
    if (!trimmed) {
      result.push('')
      lineStartPos = text.indexOf('\n', lineStartPos)
      if (lineStartPos < 0) lineStartPos = text.length
      else lineStartPos++
      continue
    }

    const leadingWS = rawLines[i].length - rawLines[i].trimStart().length
    const linePos = lineStartPos + leadingWS

    let opens = 0, closes = 0
    for (const t of tags) {
      if (t.pos >= linePos) break
      if (t.isClose) { closes++; continue }
      if (t.isVoid) continue
      opens++
    }

    const lineStartsWithClose = /^<\//.test(trimmed)
    const depth = Math.max(0, opens - closes - (lineStartsWithClose ? 1 : 0))

    result.push('  '.repeat(depth) + trimmed)

    lineStartPos = text.indexOf('\n', lineStartPos)
    if (lineStartPos < 0) lineStartPos = text.length
    else lineStartPos++
  }

  return result.join('\n')
}

function twHighlighted(name) {
  const text = twDisplayed.value[name] || ''
  if (!text) return ''
  const cache = twHighlightCache.value[name]
  if (cache && cache.input === text) return cache.output

  // 截掉末尾不完整标签（< 之后无 >），避免着色器处理半截标签
  let safeText = text
  const lastOpen = text.lastIndexOf('<')
  const lastClose = text.lastIndexOf('>')
  if (lastOpen > lastClose) {
    safeText = text.substring(0, lastOpen)
  }

  try {
    const formatted = formatStreamingHtml(safeText)
    let result = highlightStreaming(formatted)

    if (safeText !== text) {
      result += escapeHtml(text.substring(lastOpen))
    }

    twHighlightCache.value = { ...twHighlightCache.value, [name]: { input: text, output: result } }
    return result
  } catch {
    const escaped = escapeHtml(text)
    twHighlightCache.value = { ...twHighlightCache.value, [name]: { input: text, output: escaped } }
    return escaped
  }
}

// ==================== Methods ====================

async function handleStart() {
  if (!userInput.value.trim()) return
  if (!authToken.value) {
    authMode.value = 'login'
    return
  }
  loading.value = true
  step.value = 'chatting'
  messages.value.push({ role: 'user', content: userInput.value })
  userInput.value = ''

  try {
    const res = await fetch('/api/generate/start', {
      method: 'POST',
      headers: authHeaders(),
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
  cancelled.value = false
  step.value = 'generating'
  streamingHtml.value = ''
  currentStage.value = '正在准备...'
  pageList.value = []
  pageTokens.value = {}
  pageStatuses.value = {}
  twDisplayed.value = {}
  twHighlightCache.value = {}
  twStop()

  try {
    const res = await fetch('/api/generate/resume-stream', {
      method: 'POST',
      headers: authHeaders(),
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
    sseReader.value = reader
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      if (cancelled.value) break
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
          // 同时映射到 pageTokens，用于多页面统一展示
          if (!pageTokens.value['index.html']) pageTokens.value['index.html'] = ''
          pageTokens.value['index.html'] += event.data
          twStartIfNeeded()
        } else if (event.name === 'page_list') {
          const data = JSON.parse(event.data)
          pageList.value = data.pages || []
          for (const p of pageList.value) {
            if (p.name && !pageStatuses.value[p.name]) pageStatuses.value[p.name] = ''
          }
        } else if (event.name === 'page_status') {
          const data = JSON.parse(event.data)
          pageStatuses.value[data.page] = data.status
        } else if (event.name === 'page_token') {
          const data = JSON.parse(event.data)
          if (!pageTokens.value[data.page]) pageTokens.value[data.page] = ''
          pageTokens.value[data.page] += data.token
          twStartIfNeeded()
        } else if (event.name === 'complete') {
          twStop()
          const data = JSON.parse(event.data)
          htmlCode.value = data.htmlCode || ''
          designConcept.value = data.designConcept || ''
          designTokensStr.value = data.designTokens || ''
          reviewPassed.value = data.reviewPassed
          reviewFeedback.value = data.reviewFeedback || ''
          retryCount.value = data.retryCount || 0
          previewUrl.value = data.previewUrl || ''
          screenshotUrl.value = data.previewUrl ? (data.previewUrl + 'screenshot.png') : ''
          pages.value = data.pages || ['index.html']
          // 优先默认打开 index.html，如果不存在则取第一个
          currentPage.value = pages.value.includes('index.html') ? 'index.html' : (pages.value[0] || 'index.html')
          console.log('[SSE] complete event, previewUrl=', previewUrl.value, 'pages=', pages.value)
          // 标记所有页面完成
          pageStatuses.value['index.html'] = 'completed'
          for (const p of pageList.value) {
            if (p.name) pageStatuses.value[p.name] = 'completed'
          }
          initTweaks()
          step.value = 'result'
          // 保存对话历史到后端
          saveChatHistory(data.projectId)
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
    if (!cancelled.value) {
      alert('请求失败: ' + err.message)
    }
    step.value = 'checklist'
  } finally {
    loading.value = false
    sseReader.value = null
  }
}

function stageToLabel(stage) {
  const map = {
    'asset_collector': '正在收集素材...',
    'design_concept': '正在生成设计概念...',
    'html_generator': '正在生成 HTML 代码...',
    'sub_page_generator': '正在生成子页面...',
    'code_reviewer': '正在审查代码...'
  }
  return map[stage] || '处理中...'
}

function isStageActive(stageId) {
  const map = {
    'asset_collector': '正在收集素材...',
    'design_concept': '正在生成设计概念...',
    'html_generator': '正在生成 HTML 代码...',
    'sub_page_generator': '正在生成子页面...',
    'code_reviewer': '正在审查代码...'
  }
  return currentStage.value === map[stageId]
}

function isStageDone(stageId) {
  if (step.value === 'result') return true
  const order = ['asset_collector', 'design_concept', 'html_generator', 'sub_page_generator', 'code_reviewer']
  const map = {
    'asset_collector': '正在收集素材...',
    'design_concept': '正在生成设计概念...',
    'html_generator': '正在生成 HTML 代码...',
    'sub_page_generator': '正在生成子页面...',
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
  showUserHome.value = false
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
  pageList.value = []
  pageTokens.value = {}
  pageStatuses.value = {}
  pages.value = []
  currentPage.value = ''
  previewUrl.value = ''
  twStop()
  twDisplayed.value = {}
  twHighlightCache.value = {}
}

function pageStatusClass(page) {
  const s = pageStatuses.value[page]
  if (s === 'generating') return 'border-indigo-200 bg-indigo-50 text-indigo-700'
  if (s === 'completed') return 'border-emerald-200 bg-emerald-50 text-emerald-700'
  return 'border-stone-200 text-stone-500 bg-white'
}

function pageStatusBadgeClass(page) {
  const s = pageStatuses.value[page]
  if (s === 'generating') return 'bg-indigo-100 text-indigo-700'
  if (s === 'completed') return 'bg-emerald-100 text-emerald-700'
  return 'bg-stone-100 text-stone-500'
}

function pageStatusLabel(page) {
  const s = pageStatuses.value[page]
  if (s === 'generating') return '生成中'
  if (s === 'completed') return '已完成'
  return '等待中'
}

function syncCurrentPageFromFrame(frameRef) {
  try {
    const frame = frameRef.value
    if (!frame || !frame.contentWindow) return
    const href = frame.contentWindow.location.href
    // 从 URL 提取页面文件名，如 /api/preview/xxx/about.html
    const match = href.match(/\/([^\/]+\.html)(?:[?#].*)?$/)
    if (match) {
      const pageName = match[1]
      if (pages.value.includes(pageName) && currentPage.value !== pageName) {
        currentPage.value = pageName
      }
    }
  } catch (e) {
    // 跨域或安全限制时忽略
  }
}

function onPreviewFrameLoad() {
  syncCurrentPageFromFrame(previewFrame)
}

function onModalFrameLoad() {
  syncCurrentPageFromFrame(modalFrame)
}

async function cancelGeneration() {
  if (!cancelled.value) {
    cancelled.value = true
    twStop()
    // 关闭 SSE 流
    if (sseReader.value) {
      try {
        await sseReader.value.cancel()
      } catch (e) {
        // 忽略
      }
    }
    // 通知后端取消
    if (sessionId.value) {
      try {
        await fetch('/api/generate/cancel', {
          method: 'POST',
          headers: authHeaders(),
          body: JSON.stringify({ sessionId: sessionId.value })
        })
      } catch (e) {
        // 忽略
      }
    }
    loading.value = false
    step.value = 'checklist'
  }
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
      <template v-if="authToken">
    <!-- Header -->
    <header class="bg-[#fafaf8] border-b border-stone-200 shrink-0">
      <div class="flex items-center justify-between px-5 py-2.5">
        <div class="flex items-center gap-3">
          <div class="w-7 h-7 bg-stone-800 rounded-lg flex items-center justify-center text-white font-bold text-[10px]">AI</div>
          <span class="text-sm font-semibold text-stone-700 tracking-tight">WebsiteMother</span>
        </div>
        <div class="flex items-center gap-2">
          <template v-if="currentUser">
            <button @click="openUserHome()" :disabled="step === 'generating'" :class="step === 'generating' ? 'text-xs text-stone-400 px-2 py-1 rounded-lg cursor-not-allowed' : 'text-xs text-stone-600 hover:text-stone-800 px-2 py-1 rounded-lg hover:bg-stone-100 transition-colors font-medium'">{{ currentUser.username }}</button>
            <span class="text-[10px] px-1.5 py-0.5 rounded-full font-medium"
                  :class="isAdmin() ? 'bg-amber-100 text-amber-700' : 'bg-stone-100 text-stone-500'">
              {{ currentUser.role }}
            </span>
            <button v-if="isAdmin()" @click="openUserManagement"
                    class="text-xs text-stone-500 hover:text-stone-800 px-2 py-1 rounded-lg hover:bg-stone-100 transition-colors">
              用户管理
            </button>
            <button @click="handleLogout"
                    class="text-xs text-stone-500 hover:text-red-600 px-2 py-1 rounded-lg hover:bg-red-50 transition-colors">
              登出
            </button>
          </template>
          <template v-else>
            <button @click="authMode = 'login'"
                    class="text-xs text-stone-600 hover:text-stone-800 px-3 py-1.5 rounded-lg hover:bg-stone-100 transition-colors">
              登录
            </button>
            <button @click="authMode = 'register'"
                    class="text-xs bg-stone-800 hover:bg-stone-900 text-white px-3 py-1.5 rounded-lg transition-colors">
              注册
            </button>
          </template>
          <button
            @click="reset"
            class="text-xs text-stone-500 hover:text-stone-800 px-3 py-1.5 rounded-lg hover:bg-stone-100 transition-colors"
          >
            新对话
          </button>
        </div>
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
                   :class="isStageActive('sub_page_generator') ? 'bg-indigo-50 text-indigo-700' : (isStageDone('sub_page_generator') ? 'text-stone-500' : 'text-stone-300')">
                <span v-if="isStageDone('sub_page_generator')" class="text-emerald-500">✓</span>
                <span v-else-if="isStageActive('sub_page_generator')" class="w-1.5 h-1.5 bg-indigo-500 rounded-full animate-pulse"></span>
                <span v-else class="w-1.5 h-1.5 bg-stone-300 rounded-full"></span>
                <span>子页面生成</span>
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
              <option value="deepseek-v4-flush">DeepSeek V4 Flush</option>
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
        <!-- User Home -->
        <div v-if="showUserHome" class="h-full p-8 overflow-auto">
          <div class="max-w-3xl mx-auto space-y-6">
            <div class="flex items-center justify-between">
              <div>
                <h2 class="text-2xl font-bold text-stone-800">我的项目</h2>
                <p class="text-stone-400 text-sm mt-1">历史生成记录</p>
              </div>
              <button @click="closeUserHome" class="text-xs text-stone-500 hover:text-stone-800 px-3 py-1.5 rounded-lg hover:bg-stone-100 transition-colors">返回工作台</button>
            </div>
            <div v-if="projectsLoading" class="text-center py-12 text-stone-400 text-sm">加载中...</div>
            <div v-else-if="myProjects.length === 0" class="text-center py-12">
              <p class="text-stone-400 text-sm">暂无项目记录</p>
              <p class="text-stone-300 text-xs mt-1">生成网站后会自动保存到这里</p>
            </div>
            <div v-else class="grid gap-3">
              <div v-for="proj in myProjects" :key="proj.projectId"
                   class="bg-white rounded-xl border border-stone-200 hover:border-stone-400 hover:shadow-sm transition-all overflow-hidden">
                <div class="flex gap-4 p-4 cursor-pointer" @click="reloadProject(proj.projectId)">
                  <!-- 缩略图 -->
                  <div class="w-36 h-24 shrink-0 rounded-lg overflow-hidden bg-stone-100 border border-stone-200 relative">
                    <div class="absolute inset-0 flex items-center justify-center text-stone-300">
                      <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"/></svg>
                    </div>
                    <img v-if="proj.screenshotUrl" :src="proj.screenshotUrl" class="relative w-full h-full object-cover object-top" @error="$event.target.remove()" />
                  </div>
                  <!-- 项目信息 -->
                  <div class="flex-1 min-w-0 flex flex-col justify-center">
                    <p class="text-sm font-medium text-stone-800 truncate">{{ proj.originalInput || '未命名项目' }}</p>
                    <div class="flex items-center gap-3 mt-1.5">
                      <span class="text-xs text-stone-400">{{ proj.createdAt ? proj.createdAt.substring(0, 16).replace('T', ' ') : '' }}</span>
                      <span class="text-[10px] px-1.5 py-0.5 rounded-full"
                            :class="proj.reviewPassed ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-700'">
                        {{ proj.reviewPassed ? '审查通过' : '审查未通过' }}
                      </span>
                      <span v-if="proj.retryCount > 0" class="text-[10px] text-stone-400">修复 {{ proj.retryCount }} 次</span>
                    </div>
                  </div>
                </div>
                <!-- 操作按钮 -->
                <div class="flex items-center gap-1 px-4 pb-3" @click.stop>
                  <button @click.stop="downloadProject(proj.projectId)"
                     class="flex items-center gap-1 px-2.5 py-1 text-xs text-stone-500 hover:text-stone-700 hover:bg-stone-100 rounded-lg transition-colors">
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>
                    下载
                  </button>
                  <button @click.stop="deleteProject(proj.projectId)"
                          class="flex items-center gap-1 px-2.5 py-1 text-xs text-stone-400 hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors">
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>
                    删除
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Chatting Loading -->
        <div v-if="!showUserHome && step === 'chatting' && loading" class="h-full flex items-center justify-center">
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
        <div v-else-if="!showUserHome && step === 'chatting'" class="h-full flex items-center justify-center">
          <div class="text-center space-y-3 text-stone-400">
            <svg class="w-10 h-10 mx-auto opacity-40" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"/>
            </svg>
            <p class="text-sm">在左侧输入框描述你想要的网站</p>
          </div>
        </div>

        <!-- Checklist -->
        <div v-if="!showUserHome && step === 'checklist'" class="w-full px-8 py-8 space-y-6">
          <div class="text-center space-y-1">
            <h2 class="text-2xl font-bold text-stone-800">完善你的需求</h2>
            <p class="text-stone-500">请填写以下信息，帮助 AI 更精准地生成</p>
          </div>
          <div class="bg-white rounded-2xl shadow-sm border border-stone-200 p-6 space-y-5">
            <!-- 空清单提示 -->
            <div v-if="checklist.length === 0" class="text-center py-8 text-stone-400">
              <p>未获取到需求清单，您可以直接点击生成，AI 将根据描述自动生成。</p>
            </div>
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
        <div v-if="!showUserHome && step === 'generating'" class="h-full flex flex-col p-4 gap-3">
          <div class="flex flex-col items-center justify-center py-4 space-y-3 shrink-0">
            <div class="relative">
              <div class="w-12 h-12 border-4 border-stone-200 rounded-full"></div>
              <div class="absolute top-0 left-0 w-12 h-12 border-4 border-stone-600 border-t-transparent rounded-full animate-spin"></div>
            </div>
            <div class="text-center space-y-2">
              <p class="text-base font-medium text-stone-800">AI 正在为你生成网站设计</p>
              <p class="text-stone-500 text-sm">{{ currentStage || '正在准备...' }}</p>
              <button
                @click="cancelGeneration"
                class="mt-1 px-4 py-1.5 text-xs font-medium text-stone-500 border border-stone-300 rounded-lg hover:bg-stone-100 hover:text-stone-700 transition-colors"
              >
                取消生成
              </button>
            </div>
          </div>

          <!-- 页面生成状态清单 -->
          <div v-if="allGeneratingPages.length > 0" class="shrink-0 flex justify-center">
            <div class="flex flex-wrap gap-2 justify-center">
              <div v-for="page in allGeneratingPages" :key="page"
                   class="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium border transition-colors"
                   :class="pageStatusClass(page)">
                <span class="font-mono">{{ page }}</span>
                <span v-if="pageStatuses[page] === 'generating'" class="w-1.5 h-1.5 bg-current rounded-full animate-pulse opacity-70"></span>
                <span v-else-if="pageStatuses[page] === 'completed'" class="text-[10px]">✓</span>
                <span v-else class="w-1.5 h-1.5 bg-stone-300 rounded-full"></span>
              </div>
            </div>
          </div>

          <!-- 多页面代码预览网格（IDE 风格打字机效果） -->
          <div class="flex-1 grid grid-cols-1 lg:grid-cols-2 gap-3 overflow-auto min-h-0">
            <div v-for="page in allGeneratingPages" :key="page"
                 v-show="twDisplayed[page] && twDisplayed[page].length > 0"
                 class="bg-white rounded-xl border border-stone-200 overflow-hidden flex flex-col min-h-[160px] shadow-sm">
              <div class="flex items-center justify-between px-3 py-2 bg-stone-50 border-b border-stone-200">
                <div class="flex items-center gap-2">
                  <div class="w-2 h-2 rounded-full bg-red-400"></div>
                  <div class="w-2 h-2 rounded-full bg-amber-400"></div>
                  <div class="w-2 h-2 rounded-full bg-emerald-400"></div>
                  <span class="ml-1 text-xs text-stone-500 font-mono">{{ page }}</span>
                </div>
                <span class="text-[10px] px-1.5 py-0.5 rounded-full font-medium"
                      :class="pageStatusBadgeClass(page)">
                  {{ pageStatusLabel(page) }}
                </span>
              </div>
              <div class="flex flex-1 overflow-auto min-h-0">
                <!-- 行号 Gutter -->
                <div class="select-none text-right py-3 pl-3 pr-2 border-r border-stone-200 text-stone-300 font-mono text-sm leading-relaxed bg-stone-50/50 shrink-0"
                     style="min-width: 3rem">
                  <div v-for="n in twLineCount(page)" :key="n">{{ n }}</div>
                </div>
                <!-- 代码区 + 光标 -->
                <pre class="flex-1 p-3 text-sm leading-relaxed font-mono whitespace-pre-wrap break-words m-0 bg-white text-stone-700"><code class="language-html" style="display:block; white-space:pre-wrap" v-html="twHighlighted(page)"></code><span v-if="twIsAnimating(page)" class="inline-block w-[2px] h-[1.2em] bg-stone-500 animate-pulse" style="animation-duration: 0.8s"></span></pre>
              </div>
            </div>
          </div>
        </div>

        <!-- Result -->
        <div v-if="!showUserHome && step === 'result'" class="p-4 space-y-4 w-full">
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

          <!-- 页面切换器 -->
          <div v-if="pages.length > 1" class="flex items-center gap-2 flex-wrap">
            <span class="text-xs text-stone-400 font-medium">页面预览:</span>
            <button v-for="page in pages" :key="page"
                    @click="currentPage = page"
                    class="px-2.5 py-1 rounded-lg text-xs font-medium border transition-colors"
                    :class="currentPage === page ? 'bg-stone-800 text-white border-stone-800' : 'bg-white text-stone-600 border-stone-200 hover:border-stone-400'">
              {{ page }}
            </button>
          </div>

          <!-- Preview (Large) -->
          <div class="bg-white rounded-2xl shadow-sm border border-stone-200 overflow-hidden">
            <div class="flex items-center gap-2 px-4 py-2.5 bg-stone-50 border-b border-stone-200">
              <div class="w-2.5 h-2.5 rounded-full bg-red-400"></div>
              <div class="w-2.5 h-2.5 rounded-full bg-amber-400"></div>
              <div class="w-2.5 h-2.5 rounded-full bg-emerald-400"></div>
              <span class="ml-2 text-xs text-stone-400 font-mono">{{ currentPage }}</span>
            </div>
            <iframe
              ref="previewFrame"
              :key="currentPage"
              :src="activePreviewUrl"
              :srcdoc="previewUrl ? undefined : tweakedHtmlCode"
              class="w-full border-0"
              style="height: calc(100vh - 260px); min-height: 500px;"
              sandbox="allow-scripts allow-same-origin"
              @load="onPreviewFrameLoad"
            ></iframe>
          </div>

          <!-- Preview Modal -->
          <div v-if="showPreviewModal" class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" @click.self="showPreviewModal = false">
            <div class="w-[95vw] h-[95vh] bg-white rounded-2xl shadow-2xl overflow-hidden flex flex-col">
              <div class="flex items-center justify-between px-4 py-3 bg-stone-50 border-b border-stone-200 shrink-0">
                <div class="flex items-center gap-3">
                  <div class="flex items-center gap-2">
                    <div class="w-2.5 h-2.5 rounded-full bg-red-400"></div>
                    <div class="w-2.5 h-2.5 rounded-full bg-amber-400"></div>
                    <div class="w-2.5 h-2.5 rounded-full bg-emerald-400"></div>
                    <span class="ml-2 text-xs text-stone-400 font-mono">{{ currentPage }}</span>
                  </div>
                  <div v-if="pages.length > 1" class="flex items-center gap-1.5 border-l border-stone-200 pl-3">
                    <button v-for="page in pages" :key="page"
                            @click="currentPage = page"
                            class="px-2 py-0.5 rounded text-xs font-medium border transition-colors"
                            :class="currentPage === page ? 'bg-stone-800 text-white border-stone-800' : 'bg-white text-stone-600 border-stone-200 hover:border-stone-400'">
                      {{ page }}
                    </button>
                  </div>
                </div>
                <button @click="showPreviewModal = false" class="text-stone-500 hover:text-stone-800 p-1 rounded-lg hover:bg-stone-200 transition-colors">
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg>
                </button>
              </div>
              <iframe
                ref="modalFrame"
                :key="currentPage"
                :src="activePreviewUrl"
                :srcdoc="previewUrl ? undefined : tweakedHtmlCode"
                class="w-full border-0 flex-1"
                sandbox="allow-scripts allow-same-origin"
                @load="onModalFrameLoad"
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
          <div class="bg-white rounded-2xl overflow-hidden shadow-sm border border-stone-200">
            <div class="flex items-center gap-2 px-4 py-2.5 bg-stone-50 border-b border-stone-200">
              <div class="w-2.5 h-2.5 rounded-full bg-red-400"></div>
              <div class="w-2.5 h-2.5 rounded-full bg-amber-400"></div>
              <div class="w-2.5 h-2.5 rounded-full bg-emerald-400"></div>
              <span class="ml-2 text-xs text-stone-400 font-mono">index.html</span>
            </div>
            <pre class="p-4 overflow-x-auto text-sm leading-relaxed whitespace-pre-wrap text-stone-700"><code class="language-html block" v-html="highlightedHtmlCode"></code></pre>
          </div>
        </div>
      </main>
    </div>
      </template>

      <!-- ==================== Not Logged In: Landing Page ==================== -->
      <div v-else class="flex-1 flex items-center justify-center p-8">
        <div class="text-center max-w-xl">
          <div class="w-20 h-20 bg-stone-800 rounded-3xl flex items-center justify-center text-white font-bold text-2xl mx-auto mb-8 shadow-lg shadow-stone-800/10">AI</div>
          <h1 class="text-4xl font-bold text-stone-800 mb-4 tracking-tight">WebsiteMother</h1>
          <p class="text-stone-400 text-base leading-relaxed mb-12 max-w-md mx-auto">
            AI 驱动的网站生成器。用自然语言描述你想要的网站，自动生成完整的设计概念、配色方案、HTML 代码和多页面。
          </p>
          <div class="flex items-center justify-center gap-4">
            <button @click="authMode = 'login'" class="px-10 py-3.5 bg-stone-800 hover:bg-stone-900 text-white rounded-xl font-medium transition-colors shadow-sm">登录</button>
            <button @click="authMode = 'register'" class="px-10 py-3.5 bg-white border border-stone-200 hover:border-stone-400 text-stone-700 rounded-xl font-medium transition-colors shadow-sm">注册</button>
          </div>
        </div>

      </div>
    <!-- Auth Modal -->
    <div v-if="authMode" class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" @click.self="authMode = null">
      <div class="bg-white rounded-2xl shadow-2xl p-6 w-full max-w-sm mx-4">
        <h2 class="text-lg font-bold text-stone-800 mb-4">{{ authMode === 'login' ? '登录' : '注册' }}</h2>
        <div v-if="authError" class="mb-3 p-2.5 bg-red-50 border border-red-200 rounded-lg text-xs text-red-600">{{ authError }}</div>
        <div class="space-y-3">
          <input v-model="(authMode === 'login' ? loginForm : registerForm).username" type="text" placeholder="用户名"
                 class="w-full px-4 py-2.5 border border-stone-300 rounded-xl focus:ring-2 focus:ring-stone-400 focus:border-stone-400 outline-none text-sm" />
          <input v-model="(authMode === 'login' ? loginForm : registerForm).password" type="password" placeholder="密码"
                 class="w-full px-4 py-2.5 border border-stone-300 rounded-xl focus:ring-2 focus:ring-stone-400 focus:border-stone-400 outline-none text-sm"
                 @keydown.enter="authMode === 'login' ? handleLogin() : handleRegister()" />
          <input v-if="authMode === 'register'" v-model="registerForm.email" type="email" placeholder="邮箱（选填）"
                 class="w-full px-4 py-2.5 border border-stone-300 rounded-xl focus:ring-2 focus:ring-stone-400 focus:border-stone-400 outline-none text-sm" />
          <button
            @click="authMode === 'login' ? handleLogin() : handleRegister()"
            :disabled="authLoading"
            class="w-full bg-stone-800 hover:bg-stone-900 disabled:opacity-40 text-white py-2.5 rounded-xl font-medium text-sm transition-colors">
            {{ authLoading ? '请稍候...' : (authMode === 'login' ? '登录' : '注册') }}
          </button>
          <button @click="authMode = authMode === 'login' ? 'register' : 'login'"
                  class="w-full text-xs text-stone-500 hover:text-stone-700 py-1 transition-colors">
            {{ authMode === 'login' ? '没有账号？去注册' : '已有账号？去登录' }}
          </button>
        </div>
      </div>
    </div>

    <!-- User Management Panel -->
    <div v-if="showUserManagement" class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" @click.self="showUserManagement = false">
      <div class="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[80vh] flex flex-col mx-4">
        <div class="flex items-center justify-between px-5 py-4 border-b border-stone-200">
          <h2 class="text-lg font-bold text-stone-800">用户管理</h2>
          <button @click="showUserManagement = false" class="text-stone-500 hover:text-stone-800 p-1 rounded-lg hover:bg-stone-100">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg>
          </button>
        </div>
        <div class="flex-1 overflow-auto p-4">
          <div v-if="userMgmtLoading" class="text-center py-8 text-stone-400 text-sm">加载中...</div>
          <table v-else class="w-full text-sm">
            <thead>
              <tr class="text-left text-stone-400 text-xs uppercase tracking-wider border-b border-stone-100">
                <th class="pb-2 font-medium">ID</th>
                <th class="pb-2 font-medium">用户名</th>
                <th class="pb-2 font-medium">邮箱</th>
                <th class="pb-2 font-medium">角色</th>
                <th class="pb-2 font-medium">创建时间</th>
                <th class="pb-2 font-medium text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="user in userList" :key="user.id" class="border-b border-stone-50">
                <td class="py-2.5 text-stone-500">{{ user.id }}</td>
                <td class="py-2.5 font-medium">{{ user.username }}</td>
                <td class="py-2.5 text-stone-500">{{ user.email || '-' }}</td>
                <td class="py-2.5">
                  <template v-if="editingUserId === user.id">
                    <select v-model="editRole" class="text-xs border border-stone-300 rounded px-1.5 py-0.5">
                      <option value="USER">USER</option>
                      <option value="ADMIN">ADMIN</option>
                    </select>
                    <button @click="saveUserEdit(user.id)" class="ml-1 text-emerald-600 hover:text-emerald-700 text-xs font-medium">保存</button>
                    <button @click="editingUserId = null" class="ml-1 text-stone-400 hover:text-stone-600 text-xs">取消</button>
                  </template>
                  <span v-else class="px-1.5 py-0.5 rounded-full text-[10px] font-medium"
                        :class="user.role === 'ADMIN' ? 'bg-amber-100 text-amber-700' : 'bg-stone-100 text-stone-500'">
                    {{ user.role }}
                  </span>
                </td>
                <td class="py-2.5 text-stone-400 text-xs">{{ user.createdAt ? user.createdAt.substring(0, 10) : '-' }}</td>
                <td class="py-2.5 text-right">
                  <button v-if="editingUserId !== user.id" @click="editingUserId = user.id; editRole = user.role"
                          class="text-xs text-stone-500 hover:text-stone-800 mr-2">编辑</button>
                  <button v-if="user.id !== currentUser?.id" @click="deleteUser(user.id)"
                          class="text-xs text-red-400 hover:text-red-600">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>
