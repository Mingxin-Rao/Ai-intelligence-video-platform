<template>
  <div class="app-stage">
    <div class="bg-grain" aria-hidden="true"></div>
    <div class="bg-vignette" aria-hidden="true"></div>

    <header class="navbar">
      <div class="nav-content">
        <a class="brand" href="#top" @click.prevent>
          <span class="brand-name">DoVideo</span>
          <span class="brand-dot">.</span>
        </a>

        <div class="nav-controls">
          <div class="status-pill" :class="{ 'is-active': uploading }" aria-hidden="true">
            <span class="status-dot"></span>
            <span class="status-text">{{ uploading ? 'Uploading' : 'Ready' }}</span>
          </div>

          <button v-if="!currentUser" type="button" class="btn btn-primary nav-cta" @click="openAuthModal">
            Sign in
          </button>

          <div v-else class="user-profile">
            <span class="user-name">{{ currentUser.nickname }}</span>
            <button type="button" class="btn btn-ghost btn-sm" @click="logout" title="Sign out">Sign out</button>
          </div>
        </div>
      </div>
    </header>

    <main class="main-container">
      <section class="hero-section">
        <h1 class="hero-title">
          <span class="hero-line hero-sans">Stop scrubbing through footage.</span>
          <span class="hero-line hero-serif">Start understanding it.</span>
        </h1>
        <p class="hero-sub">
          DoVideo <strong>extracts the audio</strong>, <strong>transcribes every word</strong>, and writes an
          <strong>AI summary</strong> — from any upload or link. Every insight traced back to the source.
        </p>

        <div class="upload-wrapper">
          <input
              type="file"
              id="file-input"
              @change="handleFileChange"
              accept="video/*"
              hidden
          />

          <div
              class="upload-card"
              :class="{ 'is-dragover': isDragOver }"
              @dragover.prevent="isDragOver = true"
              @dragleave.prevent="isDragOver = false"
              @drop.prevent="handleDrop"
          >
            <div class="upload-split" v-if="!uploading">

              <label for="file-input" class="upload-zone zone-local">
                <span class="zone-icon" aria-hidden="true">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
                </span>
                <span class="zone-title">Local file</span>
                <span class="zone-desc">{{ isDragOver ? 'Release to upload' : 'Click or drag a video here' }}</span>
              </label>

              <div class="upload-zone zone-url">
                <span class="zone-icon" aria-hidden="true">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path></svg>
                </span>
                <span class="zone-title">Web link</span>
                <span class="zone-desc">YouTube · Bilibili · TikTok</span>

                <div class="url-row" @click.stop>
                  <input
                      v-model="videoUrl"
                      type="text"
                      aria-label="Video link"
                      placeholder="Paste a video link"
                      @keyup.enter="handleUrlUpload"
                  />
                  <button type="button" class="url-go" @click="handleUrlUpload" aria-label="Fetch link">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="5" y1="12" x2="19" y2="12"></line><polyline points="12 5 19 12 12 19"></polyline></svg>
                  </button>
                </div>
              </div>

            </div>

            <div class="upload-busy" v-else>
              <div class="spinner"></div>
              <span class="busy-text">Establishing a channel and parsing the source…</span>
            </div>
          </div>
        </div>
      </section>

      <!-- Workspace: shown when signed in and tasks exist -->
      <section v-if="currentUser && list.length > 0" class="workspace-section">
        <div class="section-header">
          <h3>Workspace</h3>
          <span class="count-chip">{{ list.length }} {{ list.length === 1 ? 'task' : 'tasks' }}</span>
        </div>
        <div class="card-grid">
          <div v-for="item in list" :key="item.id" class="project-card">

            <button type="button" class="delete-btn" @click.stop="deleteItem(item)" title="Delete this item" aria-label="Delete this item">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
              </svg>
            </button>
            <div class="card-meta">
              <div class="meta-icon" aria-hidden="true">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>
              </div>
              <div class="meta-info">
                <div class="filename-mask" :title="item.filename">{{ item.filename }}</div>
                <div class="meta-tags">
                  <span class="time-tag">{{ formatTime(item.uploadTime) }}</span>
                  <span class="status-indicator" :class="item.status.toLowerCase()">
                    {{ item.status === 'COMPLETED' ? 'Ready' : 'Processing' }}
                  </span>
                </div>
              </div>
            </div>

            <div class="action-dock">
              <button type="button" class="dock-item" @click="downloadAudio(item)">
                <span class="item-icon" aria-hidden="true">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>
                </span>
                <span class="item-label">Audio</span>
              </button>

              <button
                  type="button"
                  class="dock-item"
                  :disabled="item.status !== 'COMPLETED'"
                  @click="transcribe(item.id)"
              >
                <span class="item-icon" aria-hidden="true">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
                </span>
                <span class="item-label">Transcript</span>
              </button>

              <button
                  type="button"
                  class="dock-item ai-core"
                  :disabled="item.status !== 'COMPLETED'"
                  @click="aiAnalyze(item.id)"
              >
                <span class="item-icon" aria-hidden="true">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"></path><path d="M19 3v4"></path><path d="M21 5h-4"></path></svg>
                </span>
                <span class="item-label">AI summary</span>
              </button>
            </div>
          </div>
        </div>
      </section>

      <!-- Empty state: signed in but no tasks yet -->
      <section v-else-if="currentUser" class="workspace-section">
        <div class="empty-state">
          <div class="empty-icon" aria-hidden="true">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"></rect><line x1="7" y1="2" x2="7" y2="22"></line><line x1="17" y1="2" x2="17" y2="22"></line><line x1="2" y1="12" x2="22" y2="12"></line><line x1="2" y1="7" x2="7" y2="7"></line><line x1="2" y1="17" x2="7" y2="17"></line><line x1="17" y1="17" x2="22" y2="17"></line><line x1="17" y1="7" x2="22" y2="7"></line></svg>
          </div>
          <h4>Nothing here yet</h4>
          <p>Upload a video or paste a link above to begin.</p>
        </div>
      </section>

      <!-- Sidebar backdrop -->
      <div class="sidebar-backdrop" v-if="sidebar.visible" @click="closeSidebar"></div>
      <!-- inert removes it from focus order & the a11y tree while closed -->
      <div class="sidebar-panel" :class="{ 'is-open': sidebar.visible }" role="dialog" aria-modal="true" aria-labelledby="sidebar-title-label" :inert="!sidebar.visible">
        <div class="sidebar-header">
          <div class="sidebar-title" id="sidebar-title-label">
            <span class="icon" v-if="sidebar.type === 'ai'" aria-hidden="true">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"></path><path d="M19 3v4"></path><path d="M21 5h-4"></path></svg>
            </span>
            <span class="icon" v-else aria-hidden="true">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
            </span>
            {{ sidebar.title }}
          </div>
          <div class="sidebar-actions">
            <button
                type="button"
                v-if="!sidebar.loading && sidebar.content"
                class="icon-btn"
                @click="copyContent"
                title="Copy to clipboard"
                aria-label="Copy to clipboard"
            >
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
            </button>
            <button type="button" class="icon-btn" @click="closeSidebar" aria-label="Close panel">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
          </div>
        </div>
        <div class="sidebar-body">
          <div v-if="sidebar.loading" class="loading-state"><div class="spinner small"></div><p>Processing…</p></div>
          <div v-else>
            <div v-if="sidebar.type === 'ai'" class="markdown-content" v-html="renderedMarkdown"></div>
            <div v-else class="text-content"><pre>{{ sidebar.content }}</pre></div>
          </div>
        </div>
      </div>

      <!-- Sign in / sign up modal -->
      <div v-if="showAuthModal" class="modal-backdrop" @click.self="closeAuthModal">
        <div class="auth-panel" role="dialog" aria-modal="true" aria-labelledby="auth-title-label">
          <div class="auth-header">
            <h2 class="auth-title" id="auth-title-label">{{ authMode === 'login' ? 'Sign in' : 'Create account' }}</h2>
            <button type="button" class="icon-btn" @click="closeAuthModal" aria-label="Close">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
          </div>
          <div class="auth-body">
            <div class="input-group">
              <label for="auth-email">Email</label>
              <input id="auth-email" v-model="authForm.email" type="email" autocomplete="email" placeholder="you@example.com" @keyup.enter="handleAuth" />
            </div>
            <div class="input-group">
              <label for="auth-password">Password</label>
              <input id="auth-password" v-model="authForm.password" type="password" :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'" placeholder="Enter your password" @keyup.enter="handleAuth" />
              <p class="input-hint" v-if="authMode === 'register'">At least 8 characters, with an uppercase letter, a lowercase letter, and a digit.</p>
            </div>
            <div class="input-group" v-if="authMode === 'register'">
              <label for="auth-confirm">Confirm password</label>
              <input id="auth-confirm" v-model="authForm.confirmPassword" type="password" autocomplete="new-password" placeholder="Re-enter your password" @keyup.enter="handleAuth" />
            </div>
            <div class="input-group" v-if="authMode === 'register'">
              <label for="auth-nickname">Nickname</label>
              <input id="auth-nickname" v-model="authForm.nickname" type="text" placeholder="Choose a display name" @keyup.enter="handleAuth" />
            </div>
            <div class="auth-action">
              <button type="button" class="btn btn-primary btn-block" @click="handleAuth" :disabled="authLoading">
                <span v-if="!authLoading">{{ authMode === 'login' ? 'Sign in' : 'Create account' }}</span>
                <span v-else>Working…</span>
              </button>
            </div>
            <div class="auth-toggle">
              <span class="toggle-text">{{ authMode === 'login' ? "Don't have an account?" : 'Already have an account?' }}</span>
              <button type="button" class="toggle-link" @click="switchAuthMode">{{ authMode === 'login' ? 'Sign up' : 'Sign in' }}</button>
            </div>
            <p v-if="authMessage" class="auth-msg" :class="{'error': authError}">{{ authMessage }}</p>
          </div>
        </div>
      </div>

      <!-- Global toast notification -->
      <transition name="toast-pop">
        <div
            v-if="message"
            class="toast"
            :class="{ 'error': messageIsError }"
            role="status"
            aria-live="polite"
        >
          <span class="toast-icon" aria-hidden="true">
            <svg v-if="messageIsError" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
            <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>
          </span>
          <span>{{ messageText }}</span>
        </div>
      </transition>

      <!-- Themed confirm dialog (replaces native confirm) -->
      <transition name="toast-pop">
        <div v-if="confirmModal.visible" class="modal-backdrop" @click.self="resolveConfirm(false)">
          <div class="confirm-panel" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title-label" aria-describedby="confirm-message-label">
            <div class="confirm-icon" aria-hidden="true">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>
            </div>
            <h3 class="confirm-title" id="confirm-title-label">{{ confirmModal.title }}</h3>
            <p class="confirm-message" id="confirm-message-label">{{ confirmModal.message }}</p>
            <div class="confirm-actions">
              <button type="button" class="btn btn-ghost" @click="resolveConfirm(false)">Cancel</button>
              <button type="button" class="btn btn-primary" @click="resolveConfirm(true)">{{ confirmModal.confirmText }}</button>
            </div>
          </div>
        </div>
      </transition>
    </main>

    <footer class="app-footer">
      <p class="footer-line">Turning video into <em>understanding</em>.</p>
      <p class="footer-credit">DoVideo · Spring Boot · RocketMQ · Vue 3</p>
    </footer>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { marked } from 'marked'
import SparkMD5 from 'spark-md5'

// --- State definitions ---
const file = ref(null)
const videoUrl = ref('')
const message = ref('')
const uploading = ref(false)
// 0-100; drives the chunked-upload progress readout
const uploadProgress = ref(0)
const list = ref([])
const isDragOver = ref(false)
const sidebar = ref({ visible: false, type: 'ai', title: '', content: '', loading: false })
const currentUser = ref(null)
// Auth token (JWT), sent on every protected request
const authToken = ref(localStorage.getItem('token') || '')
const showAuthModal = ref(false)
const authMode = ref('login')
const authLoading = ref(false)
const authMessage = ref('')
const authError = ref(false)
const authForm = ref({ email: '', password: '', confirmPassword: '', nickname: '' })

// Email & password rules (kept in sync with the backend)
const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/
const PASSWORD_RE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/
const pollingTimers = ref({})
// Confirm-dialog state (promise-based, replaces the native confirm())
const confirmModal = ref({ visible: false, title: '', message: '', confirmText: 'Confirm', resolve: null })

// Backend API base URL
// The API origin. Overridable because the backend's host port is itself
// overridable (APP_PORT in the root .env), so hard-coding 9090 breaks the moment
// something else already owns that port.
//   echo 'VITE_API_BASE=http://localhost:9091' > client/.env.local
const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:9090'

// Markdown parsing (strips any <think> reasoning blocks)
const renderedMarkdown = computed(() => {
  if (!sidebar.value.content) return ''
  let cleanText = sidebar.value.content.replace(/<think>[\s\S]*?<\/think>/gi, "")
  if (cleanText.includes("</think>")) cleanText = cleanText.split("</think>").pop()
  if (!cleanText.trim()) cleanText = sidebar.value.content
  return marked.parse(cleanText)
})

// Toast: error detection still keys off the original emoji markers, but the displayed
// text strips the leading emoji so the monochrome typography stays clean.
const messageIsError = computed(() => message.value.startsWith('❌') || message.value.startsWith('⚠️'))
const messageText = computed(() => {
  const stripped = message.value.replace(/^[^\p{L}\p{N}]+/u, '').trim()
  return stripped || message.value
})

// --- Core business logic ---

const handleFileChange = async (e) => {
  if (!currentUser.value) {
    e.target.value = ''
    showMsg('⚠️ Access restricted: please sign in first', true)
    openAuthModal()
    return
  }
  const selectedFile = e.target.files[0]
  if (!selectedFile) return
  file.value = selectedFile
  videoUrl.value = ''
  await uploadFile()
}

const handleDrop = async (e) => {
  isDragOver.value = false
  if (!currentUser.value) {
    showMsg('⚠️ Access restricted: please sign in first', true)
    openAuthModal()
    return
  }
  const droppedFiles = e.dataTransfer.files
  if (!droppedFiles || droppedFiles.length === 0) return
  const selectedFile = droppedFiles[0]
  if (!selectedFile.type.startsWith('video/')) {
    showMsg('⚠️ Only video files are supported', true)
    return
  }
  file.value = selectedFile
  videoUrl.value = ''
  await uploadFile()
}

// ---------------------------------------------------------------------------
// Resumable chunked upload.
//
// A single PUT of a multi-GB file must survive one uninterrupted connection; on a
// flaky link that fails and the whole transfer restarts. Slicing means a failure
// costs one chunk, and the server can be asked what is still missing before
// resuming — including across a page reload, since the state is keyed by content
// hash rather than by session.
// ---------------------------------------------------------------------------

// Hash the file incrementally so a multi-GB file is never held in memory at once.
const computeFileMd5 = async (f, chunkSize, onProgress) => {
  const spark = new SparkMD5.ArrayBuffer()
  const total = Math.ceil(f.size / chunkSize)
  for (let i = 0; i < total; i++) {
    const slice = f.slice(i * chunkSize, Math.min((i + 1) * chunkSize, f.size))
    spark.append(await slice.arrayBuffer())
    if (onProgress) onProgress(Math.round(((i + 1) / total) * 100))
  }
  return spark.end()
}

// Bounded concurrency: enough sockets to fill the link, few enough that a stall
// does not tie up the whole browser connection pool.
const CHUNK_CONCURRENCY = 3
const MAX_PASSES = 4

const uploadFile = async () => {
  if (!file.value) return
  const f = file.value
  uploading.value = true
  uploadProgress.value = 0

  try {
    message.value = 'Fingerprinting file…'
    // Provisional size; init returns the size the server actually wants
    let chunkSize = 5 * 1024 * 1024
    const fileMd5 = await computeFileMd5(f, chunkSize, (p) => {
      message.value = `Fingerprinting file… ${p}%`
    })

    // 1. Ask what is needed. Already-known content needs no transfer at all.
    const initBody = new FormData()
    initBody.append('fileMd5', fileMd5)
    initBody.append('totalSize', String(f.size))
    const initRes = await authFetch(`${API_BASE}/media/upload/init`, { method: 'POST', body: initBody })
    const init = await initRes.json()
    if (init.status === 'ERROR') throw new Error(init.msg || 'Init failed')

    if (init.status === 'INSTANT') {
      uploadProgress.value = 100
      showMsg('✅ Already uploaded — skipped the transfer entirely')
      fetchList()
      return
    }

    chunkSize = Number(init.chunkSize) || chunkSize
    const totalChunks = Number(init.totalChunks)
    let missing = init.missingChunks || []
    if (missing.length < totalChunks) {
      message.value = `Resuming: ${totalChunks - missing.length}/${totalChunks} chunks already stored`
    }

    // 2. Send what is missing, re-asking between passes so only true gaps are retried.
    for (let pass = 0; pass < MAX_PASSES && missing.length > 0; pass++) {
      const queue = [...missing]
      const failed = []

      const worker = async () => {
        while (queue.length > 0) {
          const index = queue.shift()
          const slice = f.slice(index * chunkSize, Math.min((index + 1) * chunkSize, f.size))
          const body = new FormData()
          body.append('file', slice, `${fileMd5}-${index}`)
          body.append('fileMd5', fileMd5)
          body.append('chunkIndex', String(index))
          try {
            const res = await authFetch(`${API_BASE}/media/upload/chunk`, { method: 'POST', body })
            const json = await res.json()
            if (json.status !== 'OK') throw new Error(json.msg || 'chunk rejected')
          } catch (e) {
            // Re-queued for the next pass rather than failing the whole upload
            failed.push(index)
          }
          uploadProgress.value = Math.min(99, Math.round(((totalChunks - queue.length - failed.length) / totalChunks) * 100))
          message.value = `Uploading… ${uploadProgress.value}%`
        }
      }

      await Promise.all(Array.from({ length: Math.min(CHUNK_CONCURRENCY, queue.length) }, worker))
      missing = failed
      if (missing.length > 0) {
        message.value = `${missing.length} chunk(s) failed — retrying just those…`
      }
    }

    if (missing.length > 0) {
      throw new Error(`${missing.length} chunk(s) could not be delivered after ${MAX_PASSES} passes`)
    }

    // 3. Merge server-side. Refused if anything is still missing.
    message.value = 'Merging…'
    const mergeBody = new FormData()
    mergeBody.append('fileMd5', fileMd5)
    mergeBody.append('fileName', f.name)
    mergeBody.append('totalChunks', String(totalChunks))
    const mergeRes = await authFetch(`${API_BASE}/media/upload/merge`, { method: 'POST', body: mergeBody })
    const merged = await mergeRes.json()
    if (merged.status === 'INCOMPLETE') {
      throw new Error(`Server still missing chunks: ${(merged.missingChunks || []).join(', ')}`)
    }
    if (merged.status === 'ERROR') throw new Error(merged.msg || 'Merge failed')

    uploadProgress.value = 100
    showMsg('✅ Upload complete')
    fetchList()
  } catch (error) {
    console.error(error)
    // Delivered chunks stay on the server, so a retry resumes instead of restarting
    showMsg('❌ Upload failed: ' + error.message + ' (progress kept — retry to resume)', true)
  } finally {
    uploading.value = false
  }
}

// Link upload (backend returns HTTP 500 on failure so errors are caught here)
const handleUrlUpload = async () => {
  if (!videoUrl.value) return

  if (!currentUser.value) {
    showMsg('⚠️ Access restricted: please sign in first', true)
    openAuthModal()
    return
  }

  // Basic link validation
  if (!videoUrl.value.startsWith('http')) {
    showMsg('⚠️ Please enter a valid http/https link', true)
    return
  }

  uploading.value = true
  message.value = 'Parsing the link and fast-downloading (low-bitrate mode)…'

  const formData = new FormData()
  formData.append('url', videoUrl.value)

  try {
    const res = await authFetch(`${API_BASE}/media/upload-url`, {
      method: 'POST',
      body: formData
    })
    // The backend returns 500 on failure, so this correctly surfaces the error.
    const text = await res.text()
    if (!res.ok) throw new Error(text)

    showMsg('✅ Link resource saved')
    videoUrl.value = ''
    fetchList()
  } catch (error) {
    console.error(error)
    // Surface the specific backend error message
    let errMsg = error.message
    // "Unsupported URL" is yt-dlp's native error
    if (errMsg.includes("Unsupported URL")) errMsg = "This platform link is not supported"
    showMsg('❌ Parsing failed: ' + errMsg, true)
  } finally {
    uploading.value = false
  }
}

// Toast: messages starting with ❌ / ⚠️ render with the error style
const showMsg = (msg, isError = false) => {
  message.value = msg
  setTimeout(() => { if (message.value === msg) message.value = '' }, 4000)
}

// Fetch wrapper that attaches the auth header; on 401 it signs out and prompts a re-login.
const authFetch = async (url, options = {}) => {
  const headers = { ...(options.headers || {}) }
  if (authToken.value) headers['Authorization'] = 'Bearer ' + authToken.value
  const res = await fetch(url, { ...options, headers })
  if (res.status === 401) {
    handleUnauthorized()
    throw new Error('Unauthorized')
  }
  return res
}
const handleUnauthorized = () => {
  authToken.value = ''
  currentUser.value = null
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  list.value = []
  showMsg('⚠️ Session expired, please sign in again', true)
  openAuthModal()
}

const fetchList = async () => {
  try {
    let url = `${API_BASE}/media/list`
    if (currentUser.value) {
      // Append a _t timestamp so the browser always issues a fresh request (no cache).
      const timestamp = new Date().getTime()
      url += `?_t=${timestamp}`

      const res = await authFetch(url)
      const data = await res.json()
      // Reverse so newest items come first
      list.value = data.reverse()
    } else {
      list.value = []
    }
  } catch (error) {
    console.error(error)
  }
}

const deleteItem = async (item) => {
  // Use the themed confirm dialog instead of native confirm()
  const ok = await askConfirm({
    title: 'Delete file',
    message: `Permanently delete "${item.filename}"? This action cannot be undone.`,
    confirmText: 'Delete'
  })
  if (!ok) return
  try {
    const url = `${API_BASE}/media/delete?id=${item.id}`
    const res = await authFetch(url, { method: 'DELETE' })
    const text = await res.text()
    // The backend returns the exact string "Deleted successfully" on success.
    if (text.trim() === 'Deleted successfully') {
      showMsg('✅ File deleted')
      list.value = list.value.filter(i => i.id !== item.id)
    } else {
      showMsg('❌ ' + text, true)
    }
  } catch (e) {
    showMsg('❌ Delete request failed', true)
  }
}

const formatTime = (timeStr) => {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

const downloadAudio = async (item) => {
  const url = `${API_BASE}/debug/download?id=${item.id}`
  let fileName = item.filename || 'audio.mp3';
  fileName = fileName.replace(/\.[^/.]+$/, "") + ".mp3";
  try {
    showMsg('Transcoding and downloading…')
    const res = await authFetch(url)
    if (!res.ok) throw new Error("Fail")
    const blob = await res.blob()
    const downloadUrl = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(downloadUrl)
    showMsg('✅ Download complete')
  } catch (e) {
    // Use the unified toast instead of a native alert()
    showMsg('❌ Download failed', true)
  }
}

const transcribe = async (id) => {
  const item = list.value.find(i => i.id === id)
  // Cache only successful results; a failed (❌) one is re-runnable
  if (item && item.transcriptText && !item.transcriptText.includes("❌")) {
    openSidebar('text', 'Full transcript')
    sidebar.value.content = item.transcriptText
    sidebar.value.loading = false
    return
  }
  if (pollingTimers.value[id] && pollingTimers.value[id].type === 'text') {
    openSidebar('text', 'Full transcript')
    sidebar.value.loading = true
    sidebar.value.content = "📝 Text extraction is already running in the background…"
    return
  }
  openSidebar('text', 'Full transcript')
  sidebar.value.loading = true
  sidebar.value.content = "📝 Extraction task submitted, recognizing the speech stream…"
  try {
    await authFetch(`${API_BASE}/debug/transcribe?id=${id}`)
    startPolling(id, 'text')
  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

// === AI analysis: handles rate-limit / distributed-lock rejections ===
const aiAnalyze = async (id) => {
  const item = list.value.find(i => i.id === id)

  // 1. Already has a result — show it directly (excluding the "queued/processing" placeholder markers).
  if (item && item.aiSummary
      && !item.aiSummary.includes("[MQ]")
      && !item.aiSummary.includes("⏳")
      && !item.aiSummary.includes("🚀")
      && !item.aiSummary.includes("❌")) {
    openSidebar('ai', 'AI summary')
    sidebar.value.content = item.aiSummary
    sidebar.value.loading = false
    return
  }

  // 2. Already polling — just open the sidebar
  if (pollingTimers.value[id] && pollingTimers.value[id].type === 'ai') {
    openSidebar('ai', 'AI summary')
    sidebar.value.loading = true
    sidebar.value.content = "🚀 The system is crunching in the background…\n\n(Task in progress — no need to resubmit)"
    return
  }

  // 3. Prepare to submit and open the sidebar in a loading state
  openSidebar('ai', 'AI summary')
  sidebar.value.loading = true
  sidebar.value.content = "🚀 Requesting compute resources from the distributed cluster…"

  try {
    // Call the backend
    const res = await authFetch(`${API_BASE}/debug/ai?id=${id}`)
    const text = await res.text()

    // 4. Inspect the response text: ⚠️ (rate-limit/lock) or ❌ (error) means the task was rejected.
    if (text.includes("⚠️") || text.includes("❌")) {
      showMsg(text, true)
      // Close the sidebar since the task never started
      sidebar.value.visible = false
      sidebar.value.loading = false
      return
    }

    // 5. Success (contains ✅ / 🚀) — start polling
    startPolling(id, 'ai')
    sidebar.value.content = text + "\n\n⏳ Waiting for a consumer to pick up the task…"

  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

const startPolling = (id, type) => {
  // Clear any previous timer
  if (pollingTimers.value[id]) clearInterval(pollingTimers.value[id].timer)
  console.log(`[polling] Watching task ID: ${id}, type: ${type}`)

  const timer = setInterval(async () => {
    // 1. Force-refresh the list (timestamp defeats caching)
    await fetchList()
    const item = list.value.find(i => i.id === id)
    if (!item) return

    let isFinished = false
    let result = ''

    if (type === 'ai') {
      const text = item.aiSummary || ''

      // Completion check: success contains the Markdown heading "##"; failure contains the "❌" marker.
      const isSuccess = text.includes("##")
      const isError = text.includes("❌")

      if (isSuccess || isError) {
        isFinished = true
        result = text
      }

    } else if (type === 'text') {
      const text = item.transcriptText || ''
      // Transcript: finished once there is enough content, or an error marker appears.
      if (text && (text.length > 10 || text.includes("❌"))) {
        isFinished = true
        result = text
      }
    }

    // 2. Settle
    if (isFinished) {
      // If the sidebar is showing the same type, update it
      if (sidebar.value.visible && sidebar.value.type === type) {
        sidebar.value.content = result
        sidebar.value.loading = false
      }

      // ❌ marker => error, otherwise success
      if (result.includes("❌")) {
        showMsg("⚠️ Task finished with errors", true)
      } else {
        showMsg("✅ Task completed")
      }

      clearInterval(timer)
      delete pollingTimers.value[id]
    }
  }, 3000) // Poll every 3 seconds

  pollingTimers.value[id] = { timer, type }

  // Hard stop after 5 minutes as a safety net
  setTimeout(() => {
    if (pollingTimers.value[id]) {
      clearInterval(pollingTimers.value[id].timer)
      delete pollingTimers.value[id]
    }
  }, 300000)
}

const openSidebar = (type, title) => {
  sidebar.value.visible = true
  sidebar.value.type = type
  sidebar.value.title = title
  sidebar.value.loading = true
  sidebar.value.content = ''
}
const closeSidebar = () => { sidebar.value.visible = false }

// Copy the sidebar content to the clipboard
const copyContent = async () => {
  const text = sidebar.value.content || ''
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    showMsg('✅ Copied to clipboard')
  } catch (e) {
    showMsg('❌ Copy failed', true)
  }
}

// Promise-based confirm dialog
const askConfirm = ({ title, message, confirmText = 'Confirm' }) => {
  return new Promise((resolve) => {
    confirmModal.value = { visible: true, title, message, confirmText, resolve }
  })
}
const resolveConfirm = (value) => {
  const done = confirmModal.value.resolve
  confirmModal.value.visible = false
  if (done) done(value)
}

const openAuthModal = () => {
  showAuthModal.value = true
  authMessage.value = ''
  authForm.value = { email: '', password: '', confirmPassword: '', nickname: '' }
}
const closeAuthModal = () => { showAuthModal.value = false }
const switchAuthMode = () => {
  authMode.value = authMode.value === 'login' ? 'register' : 'login'
  authMessage.value = ''
}
const handleAuth = async () => {
  const f = authForm.value
  // Common validation: email/password present and email well-formed.
  if (!f.email || !f.password) {
    authMessage.value = 'Please enter both an email and a password'
    authError.value = true
    return
  }
  if (!EMAIL_RE.test(f.email)) {
    authMessage.value = 'Please enter a valid email address'
    authError.value = true
    return
  }
  // Extra checks for sign-up: password strength + matching confirmation.
  if (authMode.value === 'register') {
    if (!PASSWORD_RE.test(f.password)) {
      authMessage.value = 'Password must be at least 8 characters and include an uppercase letter, a lowercase letter, and a digit'
      authError.value = true
      return
    }
    if (f.password !== f.confirmPassword) {
      authMessage.value = 'The two passwords do not match'
      authError.value = true
      return
    }
  }
  authLoading.value = true
  authMessage.value = ''
  const endpoint = authMode.value === 'login' ? '/user/login' : '/user/register'
  // Only send the fields the backend needs (never send confirmPassword).
  const payload = authMode.value === 'register'
    ? { email: f.email, password: f.password, nickname: f.nickname }
    : { email: f.email, password: f.password }
  try {
    const res = await fetch(`${API_BASE}${endpoint}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    const data = await res.json()
    if (data.code === 200) {
      if (authMode.value === 'login') {
        currentUser.value = data.userInfo
        authToken.value = data.token || ''
        localStorage.setItem('user', JSON.stringify(data.userInfo))
        localStorage.setItem('token', authToken.value)
        closeAuthModal()
        showMsg(`Welcome back, ${data.userInfo.nickname}`)
        fetchList()
      } else {
        authMessage.value = 'Registered successfully — please sign in'
        authError.value = false
        setTimeout(() => switchAuthMode(), 1000)
      }
    } else {
      authMessage.value = data.msg || 'Operation failed'
      authError.value = true
    }
  } catch (e) {
    console.error(e)
    authMessage.value = 'Network connection error'
    authError.value = true
  } finally {
    authLoading.value = false
  }
}
const logout = () => {
  currentUser.value = null
  authToken.value = ''
  localStorage.removeItem('user')
  localStorage.removeItem('token')
  list.value = []
  showMsg('Signed out')
}

// Esc closes the topmost overlay (confirm > auth modal > sidebar).
const handleKeydown = (e) => {
  if (e.key !== 'Escape') return
  if (confirmModal.value.visible) resolveConfirm(false)
  else if (showAuthModal.value) closeAuthModal()
  else if (sidebar.value.visible) closeSidebar()
}

onMounted(() => {
  const savedUser = localStorage.getItem('user')
  const savedToken = localStorage.getItem('token')
  // Both user info and a token are required to be "signed in"
  if (savedUser && savedToken) {
    try {
      currentUser.value = JSON.parse(savedUser)
      authToken.value = savedToken
    } catch (e) {}
  } else {
    // Clear stale state if either is missing (pre-auth sessions)
    localStorage.removeItem('user')
    localStorage.removeItem('token')
  }
  fetchList()
  window.addEventListener('keydown', handleKeydown)
})

// Clean up listeners and polling timers on unmount
onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
  Object.values(pollingTimers.value).forEach(t => t && clearInterval(t.timer))
})
</script>

<style>
/* Fonts: Inter (sans body) + Fraunces (serif "voice") */
@import url('https://fonts.googleapis.com/css2?family=Fraunces:ital,opsz,wght@0,9..144,400;0,9..144,500;1,9..144,400&family=Inter:wght@300;400;500;600&display=swap');

:root {
  --bg: #0a0a0b;            /* near-black canvas */
  --bg-elev: #141416;       /* cards */
  --bg-elev-2: #1b1b1e;     /* inputs / hover */
  --text: #f4f4f5;          /* primary near-white */
  --text-2: #a1a1a6;        /* secondary gray */
  --text-3: #8a8a8f;        /* muted labels (meets WCAG AA) */
  --white: #ffffff;
  --line: rgba(255, 255, 255, 0.10);   /* hairline */
  --line-2: rgba(255, 255, 255, 0.20); /* stronger hairline */
  --radius: 12px;
  --radius-lg: 18px;
  --pill: 999px;
  --font-sans: 'Inter', system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  --font-serif: 'Fraunces', Georgia, 'Times New Roman', serif;
  --font-mono: ui-monospace, 'SF Mono', 'Cascadia Mono', Menlo, monospace;
}

* { box-sizing: border-box; margin: 0; padding: 0; }

html, body, #app {
  margin: 0 !important; padding: 0 !important; width: 100vw !important;
  max-width: 100vw !important; min-height: 100vh !important;
  overflow-x: hidden; background-color: var(--bg);
}

.app-stage {
  position: relative; z-index: 1; width: 100%; min-height: 100vh;
  display: flex; flex-direction: column;
  color: var(--text); font-family: var(--font-sans);
  -webkit-font-smoothing: antialiased; letter-spacing: -0.011em;
}

/* Focus-visible (accessibility) */
:focus-visible { outline: 2px solid rgba(255, 255, 255, 0.7); outline-offset: 2px; border-radius: 4px; }

/* Background: very subtle grain + a faint top glow (grayscale only) */
.bg-grain { position: fixed; inset: 0; z-index: -1; pointer-events: none; opacity: 0.025;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.8' numOctaves='3'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E"); }
.bg-vignette { position: fixed; top: -30%; left: 50%; transform: translateX(-50%); width: 90vw; height: 70vh; z-index: -2; pointer-events: none;
  background: radial-gradient(ellipse at center, rgba(255, 255, 255, 0.05) 0%, rgba(10, 10, 11, 0) 62%); }

/* ===== Buttons (pill) ===== */
.btn {
  font-family: var(--font-sans); font-size: 0.9rem; font-weight: 500;
  border-radius: var(--pill); cursor: pointer; border: 1px solid transparent;
  padding: 9px 20px; display: inline-flex; align-items: center; justify-content: center; gap: 8px;
  transition: background 0.2s ease, border-color 0.2s ease, color 0.2s ease, transform 0.1s ease; white-space: nowrap;
}
.btn:active { transform: scale(0.98); }
.btn-primary { background: var(--white); color: #000; border-color: var(--white); }
.btn-primary:hover:not(:disabled) { background: #e4e4e6; border-color: #e4e4e6; }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-ghost { background: transparent; color: var(--text); border-color: var(--line-2); }
.btn-ghost:hover:not(:disabled) { background: rgba(255, 255, 255, 0.06); border-color: rgba(255, 255, 255, 0.4); }
.btn-sm { padding: 6px 14px; font-size: 0.82rem; }
.btn-block { width: 100%; padding: 12px; }

/* ===== Navbar ===== */
.navbar { position: sticky; top: 0; z-index: 100; width: 100%; padding: 1.1rem 0;
  background: rgba(10, 10, 11, 0.72); backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--line); }
.nav-content { max-width: 1200px; margin: 0 auto; padding: 0 2rem; display: flex; justify-content: space-between; align-items: center; gap: 14px; }
.brand { display: inline-flex; align-items: baseline; text-decoration: none; }
.brand-name { font-size: 1.35rem; font-weight: 600; color: var(--white); letter-spacing: -0.03em; }
.brand-dot { font-size: 1.35rem; font-weight: 600; color: var(--text-3); }

.nav-controls { display: flex; align-items: center; gap: 12px; }
.user-profile { display: flex; align-items: center; gap: 12px; }
.user-name { font-size: 0.9rem; color: var(--text-2); max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.status-pill { display: flex; align-items: center; gap: 8px; padding: 6px 12px; border-radius: var(--pill);
  border: 1px solid var(--line); font-size: 0.75rem; color: var(--text-3); }
.status-dot { width: 6px; height: 6px; background: var(--text-2); border-radius: 50%; }
.status-pill.is-active .status-dot { background: var(--white); animation: pulse 1.4s infinite ease-in-out; }

/* ===== Hero ===== */
.main-container { flex: 1; width: 100%; max-width: 1120px; margin: 0 auto; padding: 5.5rem 2rem 4rem; }
.hero-section { text-align: center; margin-bottom: 6rem; animation: fadeUp 0.7s ease forwards; }
.hero-title { font-size: clamp(2.3rem, 5.4vw, 4.5rem); line-height: 1.05; letter-spacing: -0.03em; margin-bottom: 1.5rem; }
.hero-line { display: block; }
.hero-sans { font-family: var(--font-sans); font-weight: 400; color: var(--text-2); }
.hero-serif { font-family: var(--font-serif); font-weight: 400; color: var(--white); }
.hero-sub { max-width: 600px; margin: 0 auto 3.25rem; font-size: clamp(1rem, 1.5vw, 1.12rem);
  line-height: 1.65; color: var(--text-2); }
.hero-sub strong { color: var(--text); font-weight: 500; }

/* ===== Upload area ===== */
.upload-wrapper { max-width: 780px; margin: 0 auto; opacity: 0; animation: fadeUp 0.7s 0.15s ease forwards; }
.upload-card { background: var(--bg-elev); border: 1px solid var(--line); border-radius: var(--radius-lg);
  overflow: hidden; transition: border-color 0.25s ease, background 0.25s ease; }
.upload-card:hover, .upload-card.is-dragover { border-color: var(--line-2); background: #161618; }

.upload-split { display: grid; grid-template-columns: 1fr 1fr; min-height: 240px; }
.upload-zone { display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 10px; padding: 42px 28px; text-align: center; cursor: pointer; transition: background 0.2s ease; }
.upload-zone:hover { background: rgba(255, 255, 255, 0.025); }
.zone-local { border-right: 1px solid var(--line); }
.zone-icon { color: var(--text); display: flex; margin-bottom: 4px; }
.zone-title { font-size: 1.08rem; font-weight: 500; color: var(--white); }
.zone-desc { font-size: 0.85rem; color: var(--text-3); }

.url-row { display: flex; align-items: center; gap: 4px; margin-top: 14px; width: 100%; max-width: 280px;
  border: 1px solid var(--line-2); border-radius: var(--pill); padding: 4px 4px 4px 16px; transition: border-color 0.2s ease; }
.url-row:focus-within { border-color: rgba(255, 255, 255, 0.45); }
.url-row input { flex: 1; min-width: 0; background: transparent; border: none; outline: none;
  color: var(--text); font-family: var(--font-sans); font-size: 0.9rem; padding: 6px 0; }
.url-row input::placeholder { color: var(--text-3); }
.url-go { flex-shrink: 0; width: 30px; height: 30px; border-radius: 50%; border: none; cursor: pointer;
  background: var(--white); color: #000; display: flex; align-items: center; justify-content: center; transition: background 0.2s ease; }
.url-go:hover { background: #e4e4e6; }

.upload-busy { min-height: 240px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; }
.busy-text { color: var(--text-2); font-size: 0.9rem; }

.spinner { width: 34px; height: 34px; border: 2px solid var(--line-2); border-top-color: var(--white);
  border-radius: 50%; animation: spin 0.8s linear infinite; }
.spinner.small { width: 24px; height: 24px; }

/* ===== Workspace ===== */
.workspace-section { opacity: 0; animation: fadeUp 0.7s 0.3s ease forwards; }
.section-header { display: flex; align-items: center; gap: 12px; margin-bottom: 1.75rem; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.section-header h3 { font-size: 1.35rem; font-weight: 500; color: var(--white); letter-spacing: -0.02em; }
.count-chip { font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.1em; color: var(--text-3);
  border: 1px solid var(--line); border-radius: var(--pill); padding: 3px 10px; }

.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(300px, 100%), 1fr)); gap: 16px; }
.project-card { position: relative; background: var(--bg-elev); border: 1px solid var(--line);
  border-radius: 14px; overflow: hidden; transition: border-color 0.2s ease, transform 0.2s ease; }
.project-card:hover { border-color: var(--line-2); transform: translateY(-2px); }
.card-meta { display: flex; gap: 14px; padding: 1.25rem; align-items: center; border-bottom: 1px solid var(--line); }
.meta-icon { flex-shrink: 0; width: 46px; height: 46px; border-radius: 10px; border: 1px solid var(--line);
  display: flex; align-items: center; justify-content: center; color: var(--text); }
.meta-info { min-width: 0; }
.filename-mask { font-size: 1rem; font-weight: 500; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 190px; }
.meta-tags { display: flex; align-items: center; gap: 10px; margin-top: 6px; }
.time-tag { font-size: 0.8rem; color: var(--text-3); font-variant-numeric: tabular-nums; }
.status-indicator { font-size: 0.68rem; text-transform: uppercase; letter-spacing: 0.1em; font-weight: 500;
  padding: 2px 9px; border-radius: var(--pill); border: 1px solid var(--line-2); }
.status-indicator.completed { color: var(--white); }
.status-indicator.processing { color: var(--text-2); animation: pulse 1.6s infinite ease-in-out; }

.action-dock { display: grid; grid-template-columns: 1fr 1fr 1.3fr; gap: 8px; padding: 12px; }
.dock-item { display: flex; align-items: center; justify-content: center; gap: 7px; padding: 10px 8px;
  border-radius: 10px; border: 1px solid var(--line); background: transparent; color: var(--text-2);
  font-family: var(--font-sans); font-size: 0.82rem; font-weight: 500; cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, border-color 0.2s ease; }
.dock-item .item-icon { display: flex; }
.dock-item:hover:not(:disabled) { color: var(--white); border-color: var(--line-2); background: rgba(255, 255, 255, 0.05); }
.dock-item:disabled { opacity: 0.35; cursor: not-allowed; }
.dock-item.ai-core { background: var(--white); color: #000; border-color: var(--white); }
.dock-item.ai-core:hover:not(:disabled) { background: #e4e4e6; }
.dock-item.ai-core:disabled { opacity: 0.35; }

.delete-btn { position: absolute; top: 10px; right: 10px; z-index: 5; padding: 5px; background: transparent;
  border: none; color: var(--text-3); cursor: pointer; opacity: 0; transition: opacity 0.2s ease, color 0.2s ease; }
.project-card:hover .delete-btn { opacity: 1; }
.delete-btn:hover { color: var(--white); }

/* Empty state */
.empty-state { text-align: center; padding: 4rem 1.5rem; border: 1px dashed var(--line); border-radius: var(--radius-lg); }
.empty-icon { display: flex; justify-content: center; color: var(--text-3); margin-bottom: 1rem; }
.empty-state h4 { font-size: 1.15rem; font-weight: 500; color: var(--text); margin-bottom: 6px; }
.empty-state p { font-size: 0.9rem; color: var(--text-3); }

/* ===== Sidebar ===== */
.sidebar-backdrop { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); backdrop-filter: blur(3px); z-index: 998; }
.sidebar-panel { position: fixed; top: 0; right: -620px; width: 560px; max-width: 92vw; height: 100%;
  background: var(--bg-elev); border-left: 1px solid var(--line); z-index: 999;
  transition: right 0.4s cubic-bezier(0.19, 1, 0.22, 1); display: flex; flex-direction: column; }
.sidebar-panel.is-open { right: 0; }
.sidebar-header { padding: 20px 26px; border-bottom: 1px solid var(--line); display: flex;
  justify-content: space-between; align-items: center; gap: 12px; }
.sidebar-title { display: flex; align-items: center; gap: 10px; font-size: 1.15rem; font-weight: 500; color: var(--white); min-width: 0; }
.sidebar-title .icon { color: var(--text-2); display: flex; flex-shrink: 0; }
.sidebar-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.icon-btn { display: flex; align-items: center; justify-content: center; padding: 7px; border-radius: 8px;
  background: transparent; border: 1px solid transparent; color: var(--text-3); cursor: pointer; transition: all 0.2s ease; }
.icon-btn:hover { color: var(--text); background: rgba(255, 255, 255, 0.06); }
.sidebar-body { flex: 1; overflow-y: auto; padding: 26px; }
.loading-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; gap: 16px; color: var(--text-3); }

.markdown-content, .text-content { line-height: 1.75; color: var(--text-2); font-size: 0.95rem; }
.text-content pre { white-space: pre-wrap; word-break: break-word; font-family: var(--font-mono); font-size: 0.85rem;
  background: #0d0d0f; padding: 16px; border-radius: var(--radius); border: 1px solid var(--line); color: var(--text-2); }
.markdown-content h1, .markdown-content h2 { font-family: var(--font-serif); font-weight: 500; color: var(--white);
  margin: 1.5em 0 0.5em; letter-spacing: -0.01em; }
.markdown-content h1 { font-size: 1.6rem; border-bottom: 1px solid var(--line); padding-bottom: 0.4em; }
.markdown-content h2 { font-size: 1.3rem; }
.markdown-content h3 { font-family: var(--font-sans); font-weight: 500; font-size: 1.05rem; color: var(--text); margin: 1.3em 0 0.4em; }
.markdown-content p { margin-bottom: 1em; }
.markdown-content ul, .markdown-content ol { padding-left: 20px; margin-bottom: 1em; }
.markdown-content li { margin-bottom: 6px; }
.markdown-content strong { color: var(--text); font-weight: 500; }
.markdown-content a { color: var(--white); text-decoration: underline; text-underline-offset: 2px; }
.markdown-content code { font-family: var(--font-mono); font-size: 0.85em; background: rgba(255, 255, 255, 0.08);
  padding: 2px 6px; border-radius: 5px; color: var(--text); }

/* ===== Modals (auth / confirm) ===== */
.modal-backdrop { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.7); backdrop-filter: blur(5px);
  z-index: 2000; display: flex; justify-content: center; align-items: center; padding: 16px; }
.auth-panel { width: 400px; max-width: 92vw; background: var(--bg-elev); border: 1px solid var(--line);
  border-radius: 16px; overflow: hidden; animation: fadeUp 0.25s ease forwards; }
.auth-header { padding: 20px 24px; border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; align-items: center; }
.auth-title { font-size: 1.2rem; font-weight: 500; color: var(--white); letter-spacing: -0.02em; }
.auth-body { padding: 24px; }
.input-group { margin-bottom: 16px; }
.input-group label { display: block; font-size: 0.78rem; color: var(--text-2); margin-bottom: 7px; }
.input-group input { width: 100%; background: var(--bg-elev-2); border: 1px solid var(--line);
  border-radius: 10px; padding: 11px 13px; color: var(--text); font-family: var(--font-sans); font-size: 0.95rem;
  outline: none; transition: border-color 0.2s ease; }
.input-group input::placeholder { color: var(--text-3); }
.input-group input:focus { border-color: rgba(255, 255, 255, 0.4); }
.input-hint { margin-top: 8px; font-size: 0.75rem; line-height: 1.45; color: var(--text-3); }
.auth-action { margin: 22px 0 16px; }
.auth-toggle { text-align: center; font-size: 0.85rem; color: var(--text-3); }
.toggle-link { background: none; border: none; color: var(--white); cursor: pointer; font-weight: 500;
  margin-left: 6px; text-decoration: underline; text-underline-offset: 2px; font-size: 0.85rem; }
.auth-msg { margin-top: 14px; text-align: center; font-size: 0.82rem; color: var(--text-2); }
.auth-msg.error { color: var(--white); }

.confirm-panel { width: 400px; max-width: 92vw; background: var(--bg-elev); border: 1px solid var(--line);
  border-radius: 16px; padding: 28px; text-align: center; animation: fadeUp 0.25s ease forwards; }
.confirm-icon { display: flex; justify-content: center; color: var(--text); margin-bottom: 14px; }
.confirm-title { font-size: 1.15rem; font-weight: 500; color: var(--white); margin-bottom: 8px; }
.confirm-message { font-size: 0.9rem; color: var(--text-2); line-height: 1.6; margin-bottom: 22px; word-break: break-word; }
.confirm-actions { display: flex; gap: 10px; }
.confirm-actions .btn { flex: 1; }

/* ===== Toast ===== */
.toast { position: fixed; top: 84px; left: 50%; transform: translateX(-50%); z-index: 1500;
  display: flex; align-items: center; gap: 9px; max-width: 90vw;
  background: var(--white); color: #000; padding: 10px 18px; border-radius: var(--pill);
  font-size: 0.88rem; font-weight: 500; box-shadow: 0 10px 30px -8px rgba(0, 0, 0, 0.6); }
.toast-icon { display: flex; }
.toast.error { background: var(--bg-elev-2); color: var(--text); border: 1px solid var(--line-2); }

.toast-pop-enter-active, .toast-pop-leave-active { transition: opacity 0.3s ease, transform 0.3s cubic-bezier(0.19, 1, 0.22, 1); }
.toast-pop-enter-from, .toast-pop-leave-to { opacity: 0; transform: translateX(-50%) translateY(-10px); }
.modal-backdrop.toast-pop-enter-from, .modal-backdrop.toast-pop-leave-to { transform: none; }

/* ===== Footer ===== */
.app-footer { border-top: 1px solid var(--line); padding: 2.5rem 2rem; text-align: center; }
.footer-line { font-family: var(--font-serif); font-size: 1.05rem; color: var(--text-2); }
.footer-line em { font-style: italic; color: var(--white); }
.footer-credit { margin-top: 8px; font-size: 0.75rem; color: var(--text-3); letter-spacing: 0.02em; }

/* Custom scrollbar */
.sidebar-body::-webkit-scrollbar { width: 8px; }
.sidebar-body::-webkit-scrollbar-thumb { background: var(--line-2); border-radius: 4px; }
.sidebar-body::-webkit-scrollbar-thumb:hover { background: rgba(255, 255, 255, 0.35); }

@keyframes spin { to { transform: rotate(360deg); } }
@keyframes fadeUp { from { opacity: 0; transform: translateY(24px); } to { opacity: 1; transform: translateY(0); } }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }

/* ===== Responsive ===== */
@media (max-width: 768px) {
  .main-container { padding: 3.5rem 1.2rem 3rem; }
  .hero-section { margin-bottom: 3.5rem; }
  .nav-content { padding: 0 1.2rem; }
  .status-pill { display: none; }
  .upload-split { grid-template-columns: 1fr; }
  .zone-local { border-right: none; border-bottom: 1px solid var(--line); }
  .action-dock { grid-template-columns: 1fr; }
  .sidebar-panel { width: 100vw; max-width: 100vw; border-left: none; }
  .toast { top: 74px; }
}

@media (max-width: 420px) {
  .brand-name, .brand-dot { font-size: 1.2rem; }
  .nav-cta { padding: 8px 16px; }
}

/* Respect reduced-motion preference */
@media (prefers-reduced-motion: reduce) {
  * { animation-duration: 0.01ms !important; animation-iteration-count: 1 !important; transition-duration: 0.01ms !important; }
}
</style>
