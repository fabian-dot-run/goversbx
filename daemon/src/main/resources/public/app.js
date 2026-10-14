const POLL_INTERVAL_MS = 2000;

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

refresh();
setInterval(refresh, POLL_INTERVAL_MS);
