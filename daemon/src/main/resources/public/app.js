// Live updates come from the daemon's event stream (/api/events); polling stays as a slow fallback
// in case the stream is down.
const FALLBACK_POLL_MS = 15000;

let swRegistration = null;

async function fetchRequests() {
  const response = await fetch('/api/requests');
  if (!response.ok) {
    return [];
  }
  return response.json();
}

async function decide(id, action) {
  await fetch(`/api/requests/${id}/${action}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ decidedBy: 'web-ui' }),
  });
  await refresh();
}

function formatTime(iso) {
  if (!iso) return '-';
  return new Date(iso).toLocaleString();
}

function renderPending(requests, tbody, emptyEl) {
  tbody.innerHTML = '';
  const pending = requests.filter(r => r.status === 'PENDING');
  emptyEl.hidden = pending.length > 0;

  for (const r of pending) {
    const tr = document.createElement('tr');
    tr.id = `request-${r.id}`;
    tr.classList.toggle('highlight', location.hash === `#request-${r.id}`);
    tr.innerHTML = `
      <td>${escapeHtml(r.sandboxName)}</td>
      <td>${escapeHtml(r.host)}</td>
      <td>${escapeHtml(r.reason || '')}</td>
      <td>${formatTime(r.requestedAt)}</td>
      <td>
        <button class="approve" data-id="${r.id}" data-action="approve">Approve</button>
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
    const sbxResult = r.status === 'APPROVED'
      ? (r.sbxApplied ? 'applied' : `FAILED: ${escapeHtml(r.sbxOutput || '')}`)
      : '-';
    tr.innerHTML = `
      <td>${escapeHtml(r.sandboxName)}</td>
      <td>${escapeHtml(r.host)}</td>
      <td>${escapeHtml(r.reason || '')}</td>
      <td><span class="status status-${r.status}">${r.status}</span></td>
      <td>${formatTime(r.decidedAt)}</td>
      <td>${sbxResult}</td>
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
  if (!notificationsGranted()) return;
  const title = `${r.sandboxName} wants ${r.host}`;
  const options = {
    body: r.reason || 'No reason given.',
    tag: r.id,
    requireInteraction: true,
    data: { id: r.id },
  };
  if (swRegistration) {
    await swRegistration.showNotification(title, {
      ...options,
      actions: [
        { action: 'approve', title: 'Approve' },
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
  const events = new EventSource('/api/events');
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
