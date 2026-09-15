// EditorState finite state machine and SaveScheduler (design doc section 5.4).
//
//        IDLE --edit--> DIRTY --flush--> SAVING --200--> IDLE
//                          ^                 |  \
//                          |--edit in flight-|   \--412--> CONFLICT --resolve--> SAVING
//                                            \--5xx/network--> ERROR --retry--> SAVING
//
// The textarea stays editable in every state, including CONFLICT — blocking
// input loses keystrokes. `baseVersion` updates only on a successful save or
// an explicit conflict resolution.

const DEBOUNCE_MS = 800;
const HARD_FLUSH_MS = 30000;
const BACKOFF_SCHEDULE_MS = [1000, 2000, 4000, 8000, 30000];

async function sha256Hex(text) {
  const bytes = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

class Editor {
  constructor({ documentId, textarea, statusEl, conflictPanel, recoveryPanel }) {
    this.documentId = documentId;
    this.textarea = textarea;
    this.statusEl = statusEl;
    this.conflictPanel = conflictPanel;
    this.recoveryPanel = recoveryPanel;

    this.draftStore = new DraftStore(documentId);
    this.state = 'IDLE';
    this.baseVersion = null;
    this.debounceTimer = null;
    this.hardFlushTimer = null;
    this.backoffAttempt = 0;
    this.backoffTimer = null;
    this.pendingDirty = false;
    this.saveInFlight = false;

    this.textarea.addEventListener('input', () => this.onInput());
    this.textarea.addEventListener('blur', () => this.flush());
    this._visibilityHandler = () => {
      if (document.visibilityState === 'hidden') this.flush();
    };
    document.addEventListener('visibilitychange', this._visibilityHandler);
    this._beforeUnloadHandler = () => this.onBeforeUnload();
    window.addEventListener('beforeunload', this._beforeUnloadHandler);
    this._keydownHandler = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        this.flush();
      }
    };
    document.addEventListener('keydown', this._keydownHandler);
  }

  destroy() {
    clearTimeout(this.debounceTimer);
    clearTimeout(this.hardFlushTimer);
    clearTimeout(this.backoffTimer);
    document.removeEventListener('visibilitychange', this._visibilityHandler);
    window.removeEventListener('beforeunload', this._beforeUnloadHandler);
    document.removeEventListener('keydown', this._keydownHandler);
  }

  async init() {
    this.setStatus('Loading…');
    const { document: doc, version } = await Api.getDocument(this.documentId);
    const draft = this.draftStore.load();

    if (!draft) {
      this.applyServerContent(doc.content, version);
      return;
    }
    if (draft.content === doc.content) {
      this.draftStore.clear();
      this.applyServerContent(doc.content, version);
      return;
    }
    if (draft.baseVersion === version) {
      this.showRecoveryPrompt(draft, doc.content, version);
      return;
    }
    // draft.baseVersion < version: the server moved on while we were away.
    this.enterConflict(version, doc.content, draft.content);
  }

  applyServerContent(content, version) {
    this.textarea.value = content;
    this.baseVersion = version;
    this.setState('IDLE');
    this.startHardFlushTimer();
  }

  showRecoveryPrompt(draft, serverContent, serverVersion) {
    this.recoveryPanel.hidden = false;
    this.recoveryPanel.querySelector('[data-role="draft-text"]').textContent = draft.content;
    this.recoveryPanel.querySelector('[data-role="server-text"]').textContent = serverContent;

    const cleanup = () => {
      this.recoveryPanel.hidden = true;
    };
    this.recoveryPanel.querySelector('[data-action="keep-draft"]').onclick = () => {
      cleanup();
      this.textarea.value = draft.content;
      this.baseVersion = serverVersion;
      this.setState('DIRTY');
      this.startHardFlushTimer();
      this.scheduleDebouncedSave();
    };
    this.recoveryPanel.querySelector('[data-action="discard-draft"]').onclick = () => {
      cleanup();
      this.draftStore.clear();
      this.applyServerContent(serverContent, serverVersion);
    };
  }

  enterConflict(currentVersion, currentContent, localContent) {
    this.setState('CONFLICT');
    this.conflictPanel.hidden = false;
    this.conflictPanel.querySelector('[data-role="local-text"]').textContent = localContent;
    this.conflictPanel.querySelector('[data-role="server-text"]').textContent = currentContent;

    this.conflictPanel.querySelector('[data-action="keep-local"]').onclick = () => {
      this.conflictPanel.hidden = true;
      this.textarea.value = localContent;
      this.baseVersion = currentVersion;
      this.setState('DIRTY');
      this.scheduleDebouncedSave();
    };
    this.conflictPanel.querySelector('[data-action="use-server"]').onclick = () => {
      this.conflictPanel.hidden = true;
      this.draftStore.clear();
      this.applyServerContent(currentContent, currentVersion);
    };
  }

  onInput() {
    if (this.state === 'CONFLICT' || this.state === 'SAVING') {
      // Editing during an unresolved conflict, or while a save is in
      // flight, just marks pendingDirty — the textarea keeps taking
      // keystrokes either way, and a SAVING request in flight is never
      // interrupted (single in-flight rule, section 4.3).
      this.pendingDirty = true;
      this.draftStore.save(this.textarea.value, this.baseVersion);
      return;
    }
    this.setState('DIRTY');
    this.draftStore.save(this.textarea.value, this.baseVersion);
    this.scheduleDebouncedSave();
  }

  scheduleDebouncedSave() {
    clearTimeout(this.debounceTimer);
    this.debounceTimer = setTimeout(() => this.flush(), DEBOUNCE_MS);
  }

  startHardFlushTimer() {
    clearTimeout(this.hardFlushTimer);
    this.hardFlushTimer = setTimeout(() => {
      if (this.state === 'DIRTY') this.flush();
      this.startHardFlushTimer();
    }, HARD_FLUSH_MS);
  }

  onBeforeUnload() {
    if (this.state === 'DIRTY' || this.pendingDirty) {
      // Synchronous, best-effort: no async fetch survives unload reliably.
      this.draftStore.save(this.textarea.value, this.baseVersion, true);
    }
  }

  async flush() {
    if (this.state === 'CONFLICT' || this.state === 'SAVING') return;
    if (this.saveInFlight) {
      this.pendingDirty = true;
      return;
    }
    if (this.state !== 'DIRTY' && this.state !== 'ERROR') return;

    clearTimeout(this.debounceTimer);
    const content = this.textarea.value;
    const expectedVersion = this.baseVersion;
    this.saveInFlight = true;
    this.setState('SAVING');

    try {
      const clientHash = await sha256Hex(content);
      const { result, version } = await Api.updateContent(this.documentId, expectedVersion, content, clientHash);
      this.baseVersion = version ?? result.version;
      this.draftStore.clear();
      this.backoffAttempt = 0;
      this.saveInFlight = false;

      if (this.pendingDirty) {
        this.pendingDirty = false;
        this.setState('DIRTY');
        this.scheduleDebouncedSave();
      } else {
        this.setState('IDLE');
      }
    } catch (err) {
      this.saveInFlight = false;
      if (err instanceof Api.ApiError && err.status === 412) {
        const currentVersion = err.body?.currentVersion;
        const currentContent = err.body?.currentContentTruncated ? null : err.body?.currentContent;
        this.pendingDirty = false;
        if (currentContent !== null && currentContent !== undefined) {
          this.enterConflict(currentVersion, currentContent, content);
        } else {
          // Content was too large to echo; fall back to a plain GET.
          const { document: doc, version } = await Api.getDocument(this.documentId);
          this.enterConflict(version, doc.content, content);
        }
        return;
      }
      this.enterError(err);
    }
  }

  enterError(err) {
    this.setState('ERROR');
    console.error('Save failed', err);
    const delay = BACKOFF_SCHEDULE_MS[Math.min(this.backoffAttempt, BACKOFF_SCHEDULE_MS.length - 1)];
    this.backoffAttempt++;
    clearTimeout(this.backoffTimer);
    this.backoffTimer = setTimeout(() => {
      if (this.state === 'ERROR') {
        this.setState('DIRTY');
        this.flush();
      }
    }, delay);
  }

  setState(state) {
    this.state = state;
    this.render();
  }

  render() {
    const labels = {
      IDLE: 'Saved',
      DIRTY: 'Unsaved changes',
      SAVING: 'Saving…',
      CONFLICT: 'Conflict — resolve below',
      ERROR: 'Save failed — retrying…',
    };
    this.setStatus(labels[this.state] || this.state);
  }

  setStatus(text) {
    if (this.statusEl) this.statusEl.textContent = text;
  }
}
