<script setup>
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue'
import { Terminal } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'
import 'xterm/css/xterm.css'

// Connection Form state
const host = ref('127.0.0.1')
const port = ref(2222)
const username = ref('alice')
const authType = ref('password')
const password = ref('benchpass')
const privateKey = ref('')

// WebSocket state
const wsStatus = ref('Connecting...')
const wsStatusClass = ref('badge-connecting')
let ws = null

// Session Tabs & Terminals
const sessions = reactive({})
const activeSessionId = ref(null)
let sessionCounter = 0
const termContainers = ref({})

// Live Process Pool monitor
const poolData = ref({ totalActiveSessions: 0, totalProcesses: 0, processes: [] })
let pollInterval = null

function getWsUrl() {
  const loc = window.location
  const protocol = loc.protocol === 'https:' ? 'wss:' : 'ws:'
  // When running via Vite dev server, proxy handles /ws
  return `${protocol}//${loc.host}/ws/test-engine`
}

function initWebSocket() {
  wsStatus.value = 'Connecting...'
  wsStatusClass.value = 'badge-connecting'

  try {
    ws = new WebSocket(getWsUrl())

    ws.onopen = () => {
      wsStatus.value = 'Connected'
      wsStatusClass.value = 'badge-connected'
      console.log('[WS] Connected to Vert.x Backend Engine')
      fetchEngineStatus()
    }

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        const sessId = msg.sessionId || msg['session.id']
        handleEngineMessage(sessId, msg)
      } catch (err) {
        console.error('[WS] Parse error:', err, event.data)
      }
    }

    ws.onclose = () => {
      wsStatus.value = 'Disconnected'
      wsStatusClass.value = 'badge-disconnected'
      console.warn('[WS] Closed. Reconnecting in 3s...')
      setTimeout(initWebSocket, 3000)
    }

    ws.onerror = (err) => {
      console.error('[WS] Error:', err)
    }
  } catch (e) {
    wsStatus.value = 'Error'
    wsStatusClass.value = 'badge-disconnected'
  }
}

function handleEngineMessage(sessId, msg) {
  const sess = sessions[sessId]
  if (!sess || !sess.term) return

  if (msg.type === 'output') {
    const data = msg.payload?.data || ''
    sess.term.write(data)

    // Backpressure flow-control ACK
    if (data.length > 0 && ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        type: 'ack',
        sessionId: sessId,
        payload: { bytes: data.length },
      }))
    }
  } else if (msg.type === 'state') {
    sess.state = msg.payload?.state || 'unknown'
    sess.term.writeln(`\r\n\x1b[32m[ENGINE] State: ${sess.state} - ${msg.payload?.message || ''}\x1b[0m\r\n`)
    fetchEngineStatus()
  } else if (msg.type === 'error') {
    sess.term.writeln(`\r\n\x1b[31m[ERROR] ${msg.payload?.code || 'ERROR'}: ${msg.payload?.message || ''}\x1b[0m\r\n`)
  } else if (msg.type === 'closed') {
    sess.state = 'closed'
    sess.term.writeln(`\r\n\x1b[33m[SESSION CLOSED] Reason: ${msg.payload?.reason || 'closed'} (Exit: ${msg.payload?.exitStatus ?? -1})\x1b[0m\r\n`)
    fetchEngineStatus()
  }
}

async function fetchEngineStatus() {
  try {
    const res = await fetch('/engine/status')
    if (res.ok) {
      poolData.value = await res.json()
    }
  } catch (err) {
    // ignore polling error
  }
}

function createNewSession() {
  sessionCounter++
  const sessId = `session-${sessionCounter}`

  sessions[sessId] = {
    id: sessId,
    state: 'new',
    term: null,
    fitAddon: null,
  }

  activeSessionId.value = sessId

  nextTick(() => {
    initTerminalForSession(sessId)
  })
}

function initTerminalForSession(sessId) {
  const container = termContainers.value[sessId]
  if (!container) return

  const term = new Terminal({
    cursorBlink: true,
    theme: {
      background: '#0b0d13',
      foreground: '#e2e8f0',
      cursor: '#38bdf8',
      selectionBackground: 'rgba(56, 189, 248, 0.3)',
      black: '#000000',
      red: '#f87171',
      green: '#4ade80',
      yellow: '#facc15',
      blue: '#60a5fa',
      magenta: '#c084fc',
      cyan: '#38bdf8',
      white: '#f8fafc',
    },
    fontSize: 14,
    fontFamily: 'Menlo, Monaco, "Courier New", monospace',
  })

  const fitAddon = new FitAddon()
  term.loadAddon(fitAddon)
  term.open(container)
  fitAddon.fit()

  term.onData((data) => {
    if (ws && ws.readyState === WebSocket.OPEN && (sessions[sessId]?.state === 'connected' || sessions[sessId]?.state === 'connecting')) {
      ws.send(JSON.stringify({
        type: 'input',
        sessionId: sessId,
        payload: { data: data },
      }))
    }
  })

  sessions[sessId].term = term
  sessions[sessId].fitAddon = fitAddon

  term.writeln('\x1b[34m=========================================================\x1b[0m')
  term.writeln(`\x1b[36m Session created: ${sessId}\x1b[0m`)
  term.writeln('\x1b[90m Click "Connect SSH" on the left panel to establish connection.\x1b[0m')
  term.writeln('\x1b[34m=========================================================\x1b[0m\r\n')

  sendResize(sessId)
  term.focus()
}

function switchSession(sessId) {
  activeSessionId.value = sessId
  nextTick(() => {
    const sess = sessions[sessId]
    if (sess && sess.fitAddon) {
      sess.fitAddon.fit()
      sendResize(sessId)
      sess.term.focus()
    }
  })
}

function closeSessionTab(sessId, evt) {
  if (evt) evt.stopPropagation()
  disconnectSession(sessId)

  const sess = sessions[sessId]
  if (sess && sess.term) {
    sess.term.dispose()
  }
  delete sessions[sessId]

  const remaining = Object.keys(sessions)
  if (remaining.length > 0) {
    switchSession(remaining[0])
  } else {
    activeSessionId.value = null
  }
  fetchEngineStatus()
}

function sendResize(sessId) {
  const sess = sessions[sessId]
  if (!sess || !sess.term || !ws || ws.readyState !== WebSocket.OPEN) return

  ws.send(JSON.stringify({
    type: 'resize',
    sessionId: sessId,
    payload: {
      cols: sess.term.cols,
      rows: sess.term.rows,
    },
  }))
}

function connectCurrentSession() {
  if (!activeSessionId.value) return
  const sessId = activeSessionId.value
  const sess = sessions[sessId]

  if (!ws || ws.readyState !== WebSocket.OPEN) {
    sess?.term?.writeln('\x1b[31m[ERROR] WebSocket to Vert.x is disconnected!\x1b[0m')
    return
  }

  sess.state = 'connecting'
  sess.term.writeln(`\r\n\x1b[33mConnecting to ${username.value}@${host.value}:${port.value}...\x1b[0m`)

  const payload = {
    host: host.value.trim(),
    port: parseInt(port.value, 10) || 22,
    username: username.value.trim(),
    authType: authType.value,
    cols: sess.term.cols || 80,
    rows: sess.term.rows || 24,
  }

  if (authType.value === 'password') {
    payload.password = password.value
  } else {
    payload.privateKey = privateKey.value.trim()
  }

  ws.send(JSON.stringify({
    type: 'open',
    sessionId: sessId,
    payload: payload,
  }))

  setTimeout(fetchEngineStatus, 600)
}

function disconnectSession(sessId) {
  const sess = sessions[sessId]
  if (!sess) return

  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({
      type: 'close',
      sessionId: sessId,
      payload: { reason: 'user_closed' },
    }))
  }
  sess.state = 'closed'
  setTimeout(fetchEngineStatus, 600)
}

function handleWindowResize() {
  if (activeSessionId.value && sessions[activeSessionId.value]?.fitAddon) {
    sessions[activeSessionId.value].fitAddon.fit()
    sendResize(activeSessionId.value)
  }
}

onMounted(() => {
  initWebSocket()
  createNewSession()
  pollInterval = setInterval(fetchEngineStatus, 3000)
  window.addEventListener('resize', handleWindowResize)
})

onUnmounted(() => {
  if (pollInterval) clearInterval(pollInterval)
  window.removeEventListener('resize', handleWindowResize)
  if (ws) ws.close()
})
</script>

<template>
  <div class="app-layout">
    <!-- Top Header -->
    <header class="app-header">
      <div class="brand">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="4 17 10 11 4 5"></polyline>
          <line x1="12" y1="19" x2="20" y2="19"></line>
        </svg>
        WebSession Manager &bull; Terminal Test Console
      </div>
      <div class="header-actions">
        <span class="badge" :class="wsStatusClass">WebSocket: {{ wsStatus }}</span>
        <button class="btn btn-secondary btn-sm" @click="fetchEngineStatus">&#x21bb; Refresh Pool</button>
      </div>
    </header>

    <div class="workspace">
      <!-- Left Sidebar: SSH Connection Menu & Live Process Pool -->
      <aside class="sidebar">
        <div class="sidebar-section">
          <div class="section-title">SSH Remote Connection</div>

          <div class="form-group">
            <label>Host & Port</label>
            <div class="form-row">
              <input v-model="host" type="text" placeholder="127.0.0.1" style="flex: 3;" />
              <input v-model="port" type="number" placeholder="2222" style="flex: 1;" />
            </div>
          </div>

          <div class="form-group">
            <label>Username</label>
            <input v-model="username" type="text" placeholder="alice" />
          </div>

          <div class="form-group">
            <label>Authentication Method</label>
            <select v-model="authType">
              <option value="password">Password</option>
              <option value="key">Private Key</option>
            </select>
          </div>

          <div v-if="authType === 'password'" class="form-group">
            <label>Password</label>
            <input v-model="password" type="password" placeholder="Password" />
          </div>

          <div v-else class="form-group">
            <label>Private Key (PEM)</label>
            <textarea v-model="privateKey" rows="3" placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"></textarea>
          </div>

          <div class="form-actions">
            <button
              class="btn btn-primary"
              :disabled="!activeSessionId || sessions[activeSessionId]?.state === 'connected' || sessions[activeSessionId]?.state === 'connecting'"
              @click="connectCurrentSession"
            >
              Connect SSH
            </button>
            <button
              class="btn btn-danger"
              :disabled="!activeSessionId || (sessions[activeSessionId]?.state !== 'connected' && sessions[activeSessionId]?.state !== 'connecting')"
              @click="disconnectSession(activeSessionId)"
            >
              Disconnect
            </button>
          </div>
        </div>

        <!-- Live Go Process Pool & Session Routing Monitor -->
        <div class="sidebar-section pool-section">
          <div class="section-title">
            <span>Live Go Process Pool</span>
            <span class="pool-count">{{ poolData.totalActiveSessions || 0 }} Sess / {{ poolData.totalProcesses || 0 }} Procs</span>
          </div>

          <div v-if="!poolData.processes || poolData.processes.length === 0" class="empty-pool">
            No child processes active. Connect an SSH session to trigger process spawning!
          </div>

          <div v-else class="proc-list">
            <div v-for="proc in poolData.processes" :key="proc.pid" class="proc-card">
              <div class="proc-header">
                <span class="proc-pid">PID: <strong>{{ proc.pid }}</strong></span>
                <span
                  class="proc-slots"
                  :style="{ color: proc.activeSessions >= proc.maxCapacity ? '#f59e0b' : '#10b981' }"
                >
                  {{ proc.activeSessions }} / {{ proc.maxCapacity }} slots
                </span>
              </div>
              <div class="proc-bar">
                <div
                  class="proc-bar-fill"
                  :style="{
                    width: Math.min(100, (proc.activeSessions / proc.maxCapacity) * 100) + '%',
                    backgroundColor: proc.activeSessions >= proc.maxCapacity ? '#f59e0b' : '#3b82f6'
                  }"
                ></div>
              </div>
              <div class="proc-sessions">
                <span v-for="sid in proc.sessionIds" :key="sid" class="sess-pill">{{ sid }}</span>
                <span v-if="!proc.sessionIds || proc.sessionIds.length === 0" class="idle-text">(0 sessions - idle)</span>
              </div>
            </div>
          </div>
        </div>
      </aside>

      <!-- Right Main: Terminal with Multi-Session Tabs -->
      <main class="terminal-main">
        <div class="tab-bar">
          <button
            v-for="(sess, sid) in sessions"
            :key="sid"
            class="tab"
            :class="{ active: sid === activeSessionId }"
            @click="switchSession(sid)"
          >
            <span>{{ sid }}</span>
            <span
              class="tab-dot"
              :class="{
                'dot-connected': sess.state === 'connected',
                'dot-connecting': sess.state === 'connecting',
                'dot-closed': sess.state === 'closed' || sess.state === 'new'
              }"
            ></span>
            <span class="tab-close" @click="closeSessionTab(sid, $event)">&times;</span>
          </button>
          <button class="btn-new-tab" @click="createNewSession">+ New Session</button>
        </div>

        <div class="terminals-viewport">
          <div
            v-for="(sess, sid) in sessions"
            :key="sid"
            :ref="el => { if (el) termContainers[sid] = el }"
            class="term-container"
            :class="{ active: sid === activeSessionId }"
          ></div>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: #12151c;
  color: #e2e8f0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  overflow: hidden;
}

.app-header {
  background-color: #1a1e28;
  border-bottom: 1px solid #31384c;
  padding: 10px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
  font-size: 1.05rem;
  color: #60a5fa;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.badge {
  padding: 3px 10px;
  border-radius: 9999px;
  font-size: 0.75rem;
  font-weight: 500;
}
.badge-disconnected { background: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.4); }
.badge-connecting { background: rgba(245, 158, 11, 0.2); color: #fbbf24; border: 1px solid rgba(245, 158, 11, 0.4); }
.badge-connected { background: rgba(16, 185, 129, 0.2); color: #34d399; border: 1px solid rgba(16, 185, 129, 0.4); }

.workspace {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.sidebar {
  width: 360px;
  min-width: 320px;
  background-color: #1a1e28;
  border-right: 1px solid #31384c;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.sidebar-section {
  padding: 16px;
  border-bottom: 1px solid #31384c;
}

.pool-section {
  flex: 1;
}

.section-title {
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: #94a3b8;
  margin-bottom: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.pool-count {
  color: #60a5fa;
  font-weight: bold;
}

.form-group {
  margin-bottom: 12px;
}

.form-row {
  display: flex;
  gap: 8px;
}

label {
  display: block;
  font-size: 0.8rem;
  color: #94a3b8;
  margin-bottom: 4px;
}

input, select, textarea {
  width: 100%;
  background-color: #222736;
  border: 1px solid #31384c;
  color: #e2e8f0;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 0.85rem;
  outline: none;
  box-sizing: border-box;
}

input:focus, select:focus, textarea:focus {
  border-color: #3b82f6;
}

.form-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 8px 14px;
  font-size: 0.85rem;
  font-weight: 500;
  border-radius: 6px;
  border: none;
  cursor: pointer;
  transition: all 0.15s;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-primary { background-color: #3b82f6; color: white; width: 100%; }
.btn-primary:hover:not(:disabled) { background-color: #2563eb; }
.btn-danger { background-color: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.4); width: 100%; }
.btn-danger:hover:not(:disabled) { background-color: rgba(239, 68, 68, 0.3); }
.btn-secondary { background-color: #222736; color: #e2e8f0; border: 1px solid #31384c; }
.btn-secondary:hover { background-color: #2a3042; }
.btn-sm { font-size: 0.75rem; padding: 4px 8px; }

.empty-pool {
  color: #64748b;
  font-size: 0.8rem;
  text-align: center;
  padding: 24px 8px;
  line-height: 1.4;
}

.proc-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.proc-card {
  background: #222736;
  border: 1px solid #31384c;
  border-radius: 6px;
  padding: 10px;
  font-size: 0.8rem;
}

.proc-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  margin-bottom: 6px;
}

.proc-pid strong {
  color: #60a5fa;
}

.proc-bar {
  height: 6px;
  background: #0f172a;
  border-radius: 3px;
  overflow: hidden;
  margin: 6px 0;
}

.proc-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.3s;
}

.proc-sessions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.sess-pill {
  background: #334155;
  color: #cbd5e1;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.72rem;
  font-family: monospace;
}

.idle-text {
  color: #64748b;
  font-size: 0.72rem;
}

.terminal-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background-color: #0b0d13;
  overflow: hidden;
}

.tab-bar {
  background-color: #1a1e28;
  border-bottom: 1px solid #31384c;
  display: flex;
  align-items: center;
  padding: 0 10px;
  gap: 4px;
  overflow-x: auto;
}

.tab {
  padding: 8px 14px;
  font-size: 0.8rem;
  color: #94a3b8;
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.tab.active {
  color: #e2e8f0;
  border-bottom-color: #3b82f6;
  background: rgba(59, 130, 246, 0.08);
}

.tab-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.dot-connected { background: #34d399; box-shadow: 0 0 6px #34d399; }
.dot-connecting { background: #fbbf24; }
.dot-closed { background: #64748b; }

.tab-close {
  color: #64748b;
  border-radius: 50%;
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.tab-close:hover {
  color: #f87171;
  background: rgba(239, 68, 68, 0.2);
}

.btn-new-tab {
  background: transparent;
  border: 1px dashed #31384c;
  color: #94a3b8;
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 0.75rem;
  cursor: pointer;
  margin-left: 6px;
}
.btn-new-tab:hover {
  color: #e2e8f0;
  border-color: #3b82f6;
}

.terminals-viewport {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.term-container {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 8px 12px;
  display: none;
}

.term-container.active {
  display: block;
}
</style>
