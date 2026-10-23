// Live updates come from the daemon's event stream (/api/events); polling stays as a slow fallback
// in case the stream is down.
const FALLBACK_POLL_MS = 15000;

let swRegistration = null;

// The admin API is token-gated (threat-model F3). The token arrives in the URL (?token=...), set by
// `goversbx dashboard`; we keep it in localStorage and strip it from the address bar.
const TOKEN = resolveToken();

function resolveToken() {
  let token = '';
  try {
    const fromUrl = new URLSearchParams(location.search).get('token');
    if (fromUrl) {
      token = fromUrl;
      localStorage.setItem('goversbx-admin-token', token);
      history.replaceState(null, '', location.pathname + location.hash);
    } else {
      token = localStorage.getItem('goversbx-admin-token') || '';
    }
  } catch (e) {
    // private mode / blocked storage: fall back to whatever was in the URL
    try { token = new URLSearchParams(location.search).get('token') || ''; } catch (_) { token = ''; }
  }
  return token;
}

function authHeaders(extra) {
  const headers = Object.assign({}, extra || {});
  if (TOKEN) headers['Authorization'] = 'Bearer ' + TOKEN;
  return headers;
}

function showAuthWarning() {
  const el = document.querySelector('#auth-warning');
  if (el) el.hidden = false;
}

async function fetchRequests() {
  const response = await fetch('/api/requests', { headers: authHeaders() });
  if (response.status === 401) {
    showAuthWarning();
    return [];
  }
  if (!response.ok) {
    return [];
  }
  return response.json();
}

async function decide(id, action) {
  await fetch(`/api/requests/${id}/${action}`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ decidedBy: 'web-ui' }),
  });
  await refresh();
}

function formatTime(iso) {
  if (!iso) return '-';
  return new Date(iso).toLocaleString();
}

// A project host set (several hosts, from the project's .goversbx/network.yaml) is a broader grant
// than one host, so it is marked as such and every host is listed in full before the buttons.
function isHostSet(r) {
  return r.origin === 'PROJECT_FILE';
}

function hostsCell(r) {
  if (!isHostSet(r)) return escapeHtml(r.host || '');
  const items = (r.hosts || []).map(h => `<li>${escapeHtml(h)}</li>`).join('');
  return `<span class="origin">project host set · ${(r.hosts || []).length} hosts</span><ul class="hosts">${items}</ul>`;
}

function reasonCell(r) {
  if (!isHostSet(r)) return escapeHtml(r.reason || '');
  const profiles = (r.profiles || []).length ? `<br>profiles: ${escapeHtml(r.profiles.join(', '))}` : '';
  return `declared in <code>${escapeHtml(r.source || '')}</code>${profiles}`;
}

function sbxResultCell(r) {
  if (r.status !== 'APPROVED') return '-';
  if (r.sbxApplied) return r.decidedBy === 'goversbx' ? 'already granted' : 'applied';
  const results = r.hostResults || [];
  if (results.length > 1) {
    const failed = results.filter(x => !x.applied).map(x => `${escapeHtml(x.host)}: ${escapeHtml(x.output || '')}`);
    return `FAILED for ${failed.length} of ${results.length}:<br>${failed.join('<br>')}`;
  }
  return `FAILED: ${escapeHtml(r.sbxOutput || '')}`;
}

function renderPending(requests, tbody, emptyEl) {
  tbody.innerHTML = '';
  const pending = requests.filter(r => r.status === 'PENDING');
  emptyEl.hidden = pending.length > 0;

  for (const r of pending) {
    const tr = document.createElement('tr');
    tr.id = `request-${r.id}`;
    tr.classList.toggle('highlight', location.hash === `#request-${r.id}`);
    tr.classList.toggle('set', isHostSet(r));
    tr.innerHTML = `
      <td>${escapeHtml(r.sandboxName)}</td>
      <td>${hostsCell(r)}</td>
      <td>${reasonCell(r)}</td>
      <td>${formatTime(r.requestedAt)}</td>
      <td>
        <button class="approve" data-id="${r.id}" data-action="approve">Approve${isHostSet(r) ? ' all' : ''}</button>
        <button class="deny" data-id="${r.id}" data-action="deny">Deny</button>
      </td>
    `;
    tbody.appendChild(tr);
  }

  tbody.querySelectorAll('button').forEach(btn => {
    btn.addEventListener('click', () => decide(btn.dataset.id, btn.dataset.action));
  });
}

function renderDecided(requests, tbody, emptyEl) {
  tbody.innerHTML = '';
  const decided = requests.filter(r => r.status !== 'PENDING');
  emptyEl.hidden = decided.length > 0;

  for (const r of decided) {
    const tr = document.createElement('tr');
    tr.classList.toggle('set', isHostSet(r));
    tr.innerHTML = `
      <td>${escapeHtml(r.sandboxName)}</td>
      <td>${hostsCell(r)}</td>
      <td>${reasonCell(r)}</td>
      <td><span class="status status-${r.status}">${r.status}</span></td>
      <td>${formatTime(r.decidedAt)}</td>
      <td>${sbxResultCell(r)}</td>
    `;
    tbody.appendChild(tr);
  }
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = value;
  return div.innerHTML;
}

async function refresh() {
  const requests = await fetchRequests();
  renderPending(requests, document.querySelector('#pending-table tbody'), document.querySelector('#pending-empty'));
  renderDecided(requests, document.querySelector('#decided-table tbody'), document.querySelector('#decided-empty'));
}

// --- Notifications --------------------------------------------------------------------------

function notificationsGranted() {
  return 'Notification' in window && Notification.permission === 'granted';
}

function updateNotificationControls() {
  const button = document.querySelector('#notify-button');
  const state = document.querySelector('#notify-state');
  if (!('Notification' in window)) {
    button.hidden = true;
    state.textContent = 'This browser does not support notifications.';
  } else if (Notification.permission === 'granted') {
    button.hidden = true;
    state.textContent = 'Notifications are on - you can approve or deny right from a notification.';
  } else if (Notification.permission === 'denied') {
    button.hidden = true;
    state.textContent = 'Notifications are blocked for this page - allow them in the browser\'s site settings.';
  } else {
    button.hidden = false;
    state.textContent = 'Get a notification for every new request.';
  }
}

async function enableNotifications() {
  // Firefox only grants this from a user gesture, hence the button.
  await Notification.requestPermission();
  updateNotificationControls();
}

async function notifyCreated(r) {
  if (!notificationsGranted() || r.status !== 'PENDING') return;
  const title = isHostSet(r)
    ? `${r.sandboxName} wants ${(r.hosts || []).length} hosts (project host set)`
    : `${r.sandboxName} wants ${r.host}`;
  const body = isHostSet(r) ? (r.hosts || []).join(', ') : (r.reason || 'No reason given.');
  const options = {
    body,
    tag: r.id,
    requireInteraction: true,
    data: { id: r.id, token: TOKEN },
  };
  try {
    if (swRegistration) {
      await swRegistration.showNotification(title, {
        ...options,
        actions: [
          { action: 'approve', title: isHostSet(r) ? 'Approve all' : 'Approve' },
          { action: 'deny', title: 'Deny' },
        ],
      });
    } else {
      // No service worker: a plain notification without buttons that opens the request.
      const notification = new Notification(title, options);
      notification.onclick = () => {
        window.focus();
        showRequest(r.id);
      };
    }
  } catch (e) {
    // E.g. the OS doesn't let the browser show notifications (macOS: System Settings > Notifications).
    console.warn('goversbx: could not show a notification', e);
    document.querySelector('#notify-state').textContent =
      'Could not show a notification - check that your OS allows this browser to send notifications.';
  }
}

async function closeNotification(id) {
  if (!swRegistration) return;
  const open = await swRegistration.getNotifications({ tag: id });
  open.forEach(n => n.close());
}

function showRequest(id) {
  location.hash = `request-${id}`;
  refresh().then(() => {
    const row = document.getElementById(`request-${id}`);
    if (row) row.scrollIntoView({ block: 'center' });
  });
}

async function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  try {
    await navigator.serviceWorker.register('/sw.js');
    swRegistration = await navigator.serviceWorker.ready;
    navigator.serviceWorker.addEventListener('message', event => {
      if (event.data && event.data.type === 'show-request') showRequest(event.data.id);
    });
  } catch (e) {
    console.warn('goversbx: service worker unavailable, notifications without buttons', e);
  }
}

// --- Live updates ---------------------------------------------------------------------------

function connectEvents() {
  const live = document.querySelector('#live-state');
  // EventSource can't set headers, so the token (if any) goes in the query string.
  const events = new EventSource('/api/events' + (TOKEN ? '?token=' + encodeURIComponent(TOKEN) : ''));
  events.addEventListener('ready', () => {
    live.textContent = 'live';
    live.className = 'live live-on';
    refresh();
  });
  events.addEventListener('created', event => {
    const request = JSON.parse(event.data);
    notifyCreated(request);
    refresh();
  });
  events.addEventListener('decided', event => {
    const request = JSON.parse(event.data);
    closeNotification(request.id);
    refresh();
  });
  // EventSource reconnects by itself.
  events.onerror = () => {
    live.textContent = 'reconnecting...';
    live.className = 'live live-off';
  };
}

document.querySelector('#notify-button').addEventListener('click', enableNotifications);
window.addEventListener('hashchange', refresh);
updateNotificationControls();
registerServiceWorker();
connectEvents();
refresh();
setInterval(refresh, FALLBACK_POLL_MS);
