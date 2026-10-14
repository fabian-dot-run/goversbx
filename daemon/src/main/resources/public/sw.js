// Service worker for the admin UI: owns the request notifications, so their Approve/Deny buttons
// work even when the tab is in the background. Notifications are tagged with the request id; the
// page closes them again when a request is decided elsewhere.

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

self.addEventListener('notificationclick', event => {
  const id = event.notification.data && event.notification.data.id;
  event.notification.close();
  if (!id) return;

  if (event.action === 'approve' || event.action === 'deny') {
    event.waitUntil(decide(id, event.action));
  } else {
    event.waitUntil(showRequest(id));
  }
});

async function decide(id, action) {
  try {
    const response = await fetch(`/api/requests/${id}/${action}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decidedBy: 'notification' }),
    });
    // 409: someone decided it first - nothing to report.
    if (response.ok || response.status === 409) return;
    await failed(id, action, `HTTP ${response.status}`);
  } catch (e) {
    await failed(id, action, 'daemon not reachable');
  }
}

function failed(id, action, why) {
  return self.registration.showNotification(`goversbx: could not ${action} the request`, {
    body: `${why} - open the admin UI to decide.`,
    tag: `${id}-error`,
    data: { id },
  });
}

async function showRequest(id) {
  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  for (const client of windows) {
    if (new URL(client.url).origin === self.location.origin) {
      client.postMessage({ type: 'show-request', id });
      return client.focus();
    }
  }
  return self.clients.openWindow(`/#request-${id}`);
}
