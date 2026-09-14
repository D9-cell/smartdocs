// localStorage-backed draft recovery (design doc section 5.5).
//
// Key: draft:{documentId}. Value: { content, baseVersion, updatedAt }.
// Every write is wrapped in try/catch: private-mode browsers can throw on
// any localStorage access at all, and a full quota throws
// QuotaExceededError. Either way the app degrades to "no draft recovery",
// never to a crash.

class DraftStore {
  constructor(documentId) {
    this.key = `draft:${documentId}`;
    this.available = DraftStore.isAvailable();
    this.lastWriteAt = 0;
    this.throttleMs = 1000;
  }

  static isAvailable() {
    try {
      const probeKey = '__statecore_probe__';
      window.localStorage.setItem(probeKey, '1');
      window.localStorage.removeItem(probeKey);
      return true;
    } catch (e) {
      return false;
    }
  }

  load() {
    if (!this.available) return null;
    try {
      const raw = window.localStorage.getItem(this.key);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      if (typeof parsed.content !== 'string' || typeof parsed.baseVersion !== 'number') return null;
      return parsed;
    } catch (e) {
      return null;
    }
  }

  // Throttled to once per second (section 5.5). `force` bypasses the
  // throttle — used for beforeunload, where there will be no next tick.
  save(content, baseVersion, force = false) {
    if (!this.available) return;
    const now = Date.now();
    if (!force && now - this.lastWriteAt < this.throttleMs) return;
    this.lastWriteAt = now;
    try {
      window.localStorage.setItem(this.key, JSON.stringify({
        content,
        baseVersion,
        updatedAt: new Date(now).toISOString(),
      }));
    } catch (e) {
      if (e && e.name === 'QuotaExceededError') {
        console.warn('Draft storage quota exceeded; continuing without draft recovery.');
        this.available = false;
      }
    }
  }

  clear() {
    if (!this.available) return;
    try {
      window.localStorage.removeItem(this.key);
    } catch (e) {
      // Nothing to do: if removal fails the browser is already misbehaving
      // for localStorage, and load() will simply keep failing safe too.
    }
  }
}
