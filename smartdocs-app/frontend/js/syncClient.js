// WebSocket connection, backoff, heartbeat, and version gate (design doc
// section 5.3). Owns the socket only — never touches the textarea or the
// document state machine; editor.js consumes this via events and calls
// subscribe()/sendUpdate() in response to user and document events.
//
//         connect()            open
// CLOSED ──────────▶ CONNECTING ─────────▶ OPEN
//    ▲                   │                  │
//    │  backoff expires  │ fail             │ close / error
//    │                   ▼                  ▼
//    └────────────── RECONNECTING ◀─────────┘

const PROTOCOL_VERSION = 1;
const HEARTBEAT_INTERVAL_MS = 25000;
const HEARTBEAT_MISS_LIMIT = 2;
const BACKOFF_SCHEDULE_MS = [500, 1000, 2000, 4000, 8000, 16000, 30000];

class SyncClient {
  constructor() {
    this.ws = null;
    this.state = 'CLOSED';
    this.listeners = {};
    this.backoffAttempt = 0;
    this.backoffTimer = null;
    this.heartbeatTimer = null;
    this.missedPongs = 0;
    this.closedByUser = true;
    // documentId -> knownVersion. Doubles as "what am I subscribed to" so a
    // reconnect can resubscribe everything with each document's latest
    // known version, transferring no body when nothing actually changed.
    this.subscriptions = new Map();
  }

  on(event, callback) {
    (this.listeners[event] = this.listeners[event] || []).push(callback);
  }

  // One SyncClient is shared for the tab's whole lifetime (one socket per
  // tab, design doc section 1) while Editor instances come and go as the
  // user switches documents — without this, each switch would leave the
  // previous Editor's listeners firing on a document it no longer owns.
  off(event, callback) {
    const list = this.listeners[event];
    if (!list) return;
    const index = list.indexOf(callback);
    if (index !== -1) list.splice(index, 1);
  }

  emit(event, data) {
    (this.listeners[event] || []).forEach((cb) => {
      try {
        cb(data);
      } catch (err) {
        console.error(`SyncClient listener for "${event}" threw`, err);
      }
    });
  }

  connect() {
    this.closedByUser = false;
    clearTimeout(this.backoffTimer);
    this._open();
  }

  disconnect() {
    this.closedByUser = true;
    clearTimeout(this.backoffTimer);
    this._stopHeartbeat();
    if (this.ws) this.ws.close(1000);
    this.setState('CLOSED');
  }

  subscribe(documentId, knownVersion) {
    this.subscriptions.set(documentId, knownVersion ?? null);
    if (this.state === 'OPEN') {
      this._sendSubscribe(documentId, this.subscriptions.get(documentId));
    }
  }

  unsubscribe(documentId) {
    this.subscriptions.delete(documentId);
    if (this.state === 'OPEN') {
      this._send('doc.unsubscribe', { documentId });
    }
  }

  /** @return the msgId used, so the caller can correlate it with the eventual 'applied' event, or null if not connected. */
  sendUpdate(documentId, content, baseVersion) {
    if (this.state !== 'OPEN') return null;
    return this._send('doc.update', { documentId, baseVersion, content });
  }

  async _open() {
    this.setState(this.backoffAttempt === 0 ? 'CONNECTING' : 'RECONNECTING');

    let ticket;
    try {
      const result = await Api.requestWsTicket();
      ticket = result.ticket;
    } catch (err) {
      this._scheduleReconnect();
      return;
    }
    if (this.closedByUser) return; // disconnect() ran while the ticket request was in flight

    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${scheme}://${window.location.host}/ws?ticket=${encodeURIComponent(ticket)}`;
    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onopen = () => {
      this.setState('OPEN');
      this.backoffAttempt = 0;
      this.missedPongs = 0;
      this._startHeartbeat();
      for (const [documentId, knownVersion] of this.subscriptions) {
        this._sendSubscribe(documentId, knownVersion);
      }
    };

    ws.onmessage = (event) => this._handleMessage(event.data);

    ws.onclose = () => {
      this._stopHeartbeat();
      if (this.closedByUser) {
        this.setState('CLOSED');
        return;
      }
      this._scheduleReconnect();
    };

    // onerror carries no useful detail in browsers; onclose always follows it.
    ws.onerror = () => {};
  }

  _scheduleReconnect() {
    this.setState('RECONNECTING');
    const base = BACKOFF_SCHEDULE_MS[Math.min(this.backoffAttempt, BACKOFF_SCHEDULE_MS.length - 1)];
    this.backoffAttempt++;
    // Full jitter, not a fixed delay: without it a server restart brings
    // every client back inside the same window and knocks it over again.
    const delay = Math.random() * base;
    clearTimeout(this.backoffTimer);
    this.backoffTimer = setTimeout(() => this._open(), delay);
  }

  _startHeartbeat() {
    this._stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      this.missedPongs++;
      if (this.missedPongs > HEARTBEAT_MISS_LIMIT) {
        if (this.ws) this.ws.close();
        return;
      }
      this._send('ping', {});
    }, HEARTBEAT_INTERVAL_MS);
  }

  _stopHeartbeat() {
    clearInterval(this.heartbeatTimer);
    this.heartbeatTimer = null;
  }

  _sendSubscribe(documentId, knownVersion) {
    this._send('doc.subscribe', { documentId, knownVersion: knownVersion ?? null });
  }

  _send(type, payload) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return null;
    const msgId = crypto.randomUUID();
    this.ws.send(JSON.stringify({ v: PROTOCOL_VERSION, type, msgId, ts: Date.now(), payload }));
    return msgId;
  }

  _handleMessage(raw) {
    let envelope;
    try {
      envelope = JSON.parse(raw);
    } catch (err) {
      return; // a malformed frame from the server would be a server bug, not ours to recover
    }

    if (envelope.type === 'pong') {
      this.missedPongs = 0;
      return;
    }

    // Version gate (design doc D6): drop anything at or below the version
    // we already know, except 'doc.applied' — that's the answer to our own
    // write, not unsolicited inbound state, and always carries our new version.
    const payload = envelope.payload || {};
    if (envelope.type !== 'doc.applied' && payload.documentId && typeof payload.version === 'number') {
      const known = this.subscriptions.get(payload.documentId);
      if (typeof known === 'number' && payload.version <= known) {
        return;
      }
      this.subscriptions.set(payload.documentId, payload.version);
    }

    switch (envelope.type) {
      case 'doc.snapshot':
        this.emit('snapshot', payload);
        break;
      case 'doc.in_sync':
        this.emit('inSync', payload);
        break;
      case 'doc.applied':
        this.emit('applied', { ...payload, msgId: envelope.msgId });
        break;
      case 'doc.changed':
        this.emit('changed', payload);
        break;
      case 'doc.renamed':
        this.emit('renamed', payload);
        break;
      case 'doc.deleted':
        this.emit('deleted', payload);
        break;
      case 'error':
        this.emit('error', { ...payload, msgId: envelope.msgId });
        break;
    }
  }

  setState(state) {
    this.state = state;
    this.emit('state', state);
  }
}
