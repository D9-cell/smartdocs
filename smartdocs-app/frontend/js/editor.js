// Document state machine, typing guard, and caret preservation (design doc
// section 5.3, 4.3, 4.4). Content now moves entirely over the shared
// SyncClient (design doc D3 — the WebSocket write path calls the same
// service method as REST, so this is a transport swap, not a new save
// pipeline). REST stays for the very first load (so the Stage 1 draft
// recovery flow below is untouched) and for rename/delete, which still
// mutate rows over REST and arrive back here as doc.renamed/doc.deleted.
//
//        IDLE --edit--> DIRTY --debounce/force--> SENDING --doc.applied--> IDLE
//                          ^                          |  \
//                          |--edit while in flight-----   \--rejected--> DIRTY (errorNotice)
//                                                     \--connection drops--> DIRTY
//
// The textarea stays editable in every state but READ_ONLY (the document
// was deleted out from under us) — blocking input loses keystrokes.
// `baseVersion` updates only on an applied write, an applied remote frame,
// or an explicit conflict resolution.

const DEBOUNCE_IDLE_MS = 250;
const DEBOUNCE_FORCE_MS = 1000;

// design doc D10: refuse to blindly push local edits over a document that
// moved on while we were disconnected. One flag, default false — flip only
// for a deployment that has decided the risk of silently clobbering
// someone's work is acceptable.
const OFFLINE_CLOBBER = false;

function commonPrefixLength(a, b) {
  const max = Math.min(a.length, b.length);
  let i = 0;
  while (i < max && a[i] === b[i]) i++;
  return i;
}

function commonSuffixLength(a, b, prefixLength) {
  const max = Math.min(a.length, b.length) - prefixLength;
  let i = 0;
  while (i < max && a[a.length - 1 - i] === b[b.length - 1 - i]) i++;
  return i;
}

// design doc section 4.4: remote edit above the caret shifts it, below
// leaves it, under it parks it at the edit boundary. An O(n) double scan is
// enough at this document size.
function preserveCaret(oldText, newText, caret) {
  const prefix = commonPrefixLength(oldText, newText);
  const suffix = commonSuffixLength(oldText, newText, prefix);
  if (caret <= prefix) return caret;
  if (caret >= oldText.length - suffix) return caret + (newText.length - oldText.length);
  return prefix;
}

class Editor {
  constructor({ documentId, textarea, statusEl, titleInput, conflictPanel, recoveryPanel, syncClient, onRenamed, onDeleted }) {
    this.documentId = documentId;
    this.textarea = textarea;
    this.statusEl = statusEl;
    this.titleInput = titleInput;
    this.conflictPanel = conflictPanel;
    this.recoveryPanel = recoveryPanel;
    this.syncClient = syncClient;
    this.onRenamed = onRenamed;
    this.onDeleted = onDeleted;

    this.draftStore = new DraftStore(documentId);
    this.state = 'IDLE';
    this.baseVersion = null;
    this.idleTimer = null;
    this.forceTimer = null;
    this.inFlightMsgId = null;
    this.pendingRemote = null; // { version, content } stashed while DIRTY/SENDING (typing guard rule 2)
    this.pendingDirty = false; // more edits arrived while a send was in flight
    this.errorNotice = null;
    this.connectionState = syncClient.state;

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

    this._onSnapshot = (p) => this.onSnapshot(p);
    this._onInSync = (p) => this.onInSync(p);
    this._onApplied = (p) => this.onApplied(p);
    this._onChanged = (p) => this.onChanged(p);
    this._onRenamed = (p) => this.onRenamedRemote(p);
    this._onDeleted = (p) => this.onDeletedRemote(p);
    this._onSyncError = (p) => this.onSyncError(p);
    this._onSyncState = (s) => this.onSyncState(s);
    syncClient.on('snapshot', this._onSnapshot);
    syncClient.on('inSync', this._onInSync);
    syncClient.on('applied', this._onApplied);
    syncClient.on('changed', this._onChanged);
    syncClient.on('renamed', this._onRenamed);
    syncClient.on('deleted', this._onDeleted);
    syncClient.on('error', this._onSyncError);
    syncClient.on('state', this._onSyncState);
  }

  destroy() {
    clearTimeout(this.idleTimer);
    clearTimeout(this.forceTimer);
    document.removeEventListener('visibilitychange', this._visibilityHandler);
    window.removeEventListener('beforeunload', this._beforeUnloadHandler);
    document.removeEventListener('keydown', this._keydownHandler);
    this.syncClient.off('snapshot', this._onSnapshot);
    this.syncClient.off('inSync', this._onInSync);
    this.syncClient.off('applied', this._onApplied);
    this.syncClient.off('changed', this._onChanged);
    this.syncClient.off('renamed', this._onRenamed);
    this.syncClient.off('deleted', this._onDeleted);
    this.syncClient.off('error', this._onSyncError);
    this.syncClient.off('state', this._onSyncState);
    this.syncClient.unsubscribe(this.documentId);
  }

  async init() {
    this.setStatus('Loading…');
    const { document: doc, version } = await Api.getDocument(this.documentId);
    const draft = this.draftStore.load();

    if (!draft) {
      this.applyServerContent(doc.content, version);
      this._subscribeToSync();
      return;
    }
    if (draft.content === doc.content) {
      this.draftStore.clear();
      this.applyServerContent(doc.content, version);
      this._subscribeToSync();
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
  }

  _subscribeToSync() {
    this.syncClient.subscribe(this.documentId, this.baseVersion);
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
      this._subscribeToSync();
      this.setState('DIRTY');
      this._armSendTimers();
    };
    this.recoveryPanel.querySelector('[data-action="discard-draft"]').onclick = () => {
      cleanup();
      this.draftStore.clear();
      this.applyServerContent(serverContent, serverVersion);
      this._subscribeToSync();
    };
  }

  enterConflict(currentVersion, currentContent, localContent) {
    this.pendingRemote = null;
    this.setState('CONFLICT');
    this.conflictPanel.hidden = false;
    this.conflictPanel.querySelector('[data-role="local-text"]').textContent = localContent;
    this.conflictPanel.querySelector('[data-role="server-text"]').textContent = currentContent;

    this.conflictPanel.querySelector('[data-action="keep-local"]').onclick = () => {
      this.conflictPanel.hidden = true;
      this.textarea.value = localContent;
      this.baseVersion = currentVersion;
      this._subscribeToSync();
      this.setState('DIRTY');
      this._armSendTimers();
    };
    this.conflictPanel.querySelector('[data-action="use-server"]').onclick = () => {
      this.conflictPanel.hidden = true;
      this.draftStore.clear();
      this._applyRemote(currentVersion, currentContent);
      this._subscribeToSync();
    };
  }

  onInput() {
    this.errorNotice = null;
    if (this.state === 'CONFLICT' || this.state === 'READ_ONLY') {
      // The panel (or read-only lock) owns the decision; further keystrokes
      // just keep the draft safe, same as Stage 1.
      this.draftStore.save(this.textarea.value, this.baseVersion);
      return;
    }
    this.draftStore.save(this.textarea.value, this.baseVersion);
    if (this.state === 'SENDING') {
      this.pendingDirty = true;
      return;
    }
    this.setState('DIRTY');
    this._armSendTimers();
  }

  _armSendTimers() {
    clearTimeout(this.idleTimer);
    this.idleTimer = setTimeout(() => this.trySend(), DEBOUNCE_IDLE_MS);
    if (!this.forceTimer) {
      // Not reset per keystroke: a continuous typist would otherwise never
      // go quiet long enough for the idle timer alone to fire.
      this.forceTimer = setTimeout(() => this.trySend(), DEBOUNCE_FORCE_MS);
    }
  }

  trySend() {
    clearTimeout(this.idleTimer);
    clearTimeout(this.forceTimer);
    this.forceTimer = null;

    if (this.state === 'SENDING') {
      this.pendingDirty = true;
      return;
    }
    if (this.state !== 'DIRTY') return;
    if (this.connectionState !== 'OPEN') return; // retried once resubscribed — see onInSync / onSyncState

    const content = this.textarea.value;
    const msgId = this.syncClient.sendUpdate(this.documentId, content, this.baseVersion);
    if (!msgId) return; // connection dropped between the check above and now

    this.inFlightMsgId = msgId;
    this.setState('SENDING');
  }

  async flush() {
    this.errorNotice = null;
    if (this.state === 'CONFLICT' || this.state === 'READ_ONLY') return;
    if (this.state === 'DIRTY') this.trySend();
    if (this.inFlightMsgId) {
      await this._waitForAck(3000);
    }
  }

  _waitForAck(timeoutMs) {
    return new Promise((resolve) => {
      const msgId = this.inFlightMsgId;
      if (!msgId) {
        resolve();
        return;
      }
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        this.syncClient.off('applied', onApplied);
        resolve();
      };
      const timer = setTimeout(finish, timeoutMs);
      const onApplied = (payload) => {
        if (payload.msgId === msgId) finish();
      };
      this.syncClient.on('applied', onApplied);
    });
  }

  onBeforeUnload() {
    if (this.state === 'DIRTY' || this.state === 'SENDING' || this.pendingDirty) {
      // Synchronous, best-effort: nothing async survives unload reliably.
      this.draftStore.save(this.textarea.value, this.baseVersion, true);
    }
  }

  onApplied(payload) {
    if (payload.documentId !== this.documentId || payload.msgId !== this.inFlightMsgId) return;
    this.inFlightMsgId = null;
    if (payload.changed) {
      this.baseVersion = payload.version;
      this.draftStore.clear();
    }
    if (this.pendingDirty) {
      this.pendingDirty = false;
      this.setState('DIRTY');
      this.trySend();
    } else {
      this.setState('IDLE');
      this._applyPendingRemoteIfAny();
    }
  }

  onSyncError(payload) {
    if (payload.msgId == null || payload.msgId !== this.inFlightMsgId) return;
    this.inFlightMsgId = null;
    console.error('WS update rejected', payload.code, payload.message);
    if (payload.code === 'NOT_FOUND') {
      this.enterReadOnly('This document is no longer available.');
      return;
    }
    this.errorNotice = payload.message;
    this.setState('DIRTY');
    if (payload.retryable) {
      setTimeout(() => {
        if (this.state === 'DIRTY') this.trySend();
      }, 1000);
    }
  }

  onChanged(payload) {
    if (payload.documentId !== this.documentId) return;
    this._handleInboundVersion(payload.version, payload.content, false);
  }

  onSnapshot(payload) {
    if (payload.documentId !== this.documentId) return;
    this._handleInboundVersion(payload.version, payload.content, true);
  }

  onInSync(payload) {
    if (payload.documentId !== this.documentId) return;
    // The resubscribe confirmation: the server has us in the room again, so
    // a pending send from before a reconnect is now safe to retry.
    if (this.state === 'DIRTY') this.trySend();
  }

  onRenamedRemote(payload) {
    if (payload.documentId !== this.documentId) return;
    if (this.titleInput) this.titleInput.value = payload.title;
    if (this.onRenamed) this.onRenamed(payload.title);
  }

  onDeletedRemote(payload) {
    if (payload.documentId !== this.documentId) return;
    this.enterReadOnly('This document was deleted.');
  }

  onSyncState(state) {
    this.connectionState = state;
    if (state !== 'OPEN' && this.state === 'SENDING') {
      // The in-flight write's session is gone; it will never ack. Fall back
      // to DIRTY so reconnection (onInSync / onSnapshot) retries — or, per
      // D10, offers the conflict view instead of resending blind.
      this.inFlightMsgId = null;
      this.setState('DIRTY');
      return;
    }
    this.render();
  }

  /**
   * design doc section 4.4, the typing guard, plus D10 for the snapshot
   * case: a `doc.snapshot` only ever arrives on (re)subscribe, so a dirty
   * local state there means we just reconnected with unsent edits while the
   * server moved on — ask, never blind-push over someone else's work.
   */
  _handleInboundVersion(version, content, isSnapshot) {
    if (version <= this.baseVersion) return; // rule 1: discard

    const locallyDirty = this.state === 'DIRTY' || this.state === 'SENDING';
    if (isSnapshot && locallyDirty && !OFFLINE_CLOBBER) {
      this.pendingRemote = null;
      this.enterConflict(version, content, this.textarea.value);
      return;
    }
    if (locallyDirty) {
      this.pendingRemote = { version, content }; // rule 2: stash, don't touch the textarea
      return;
    }
    this._applyRemote(version, content); // rules 3/4: apply immediately
  }

  _applyRemote(version, content) {
    this.applyRemoteContent(content);
    this.baseVersion = version;
    this.draftStore.clear();
    this.setState('IDLE');
  }

  _applyPendingRemoteIfAny() {
    if (this.pendingRemote) {
      const pending = this.pendingRemote;
      this.pendingRemote = null;
      this._applyRemote(pending.version, pending.content);
    }
  }

  applyRemoteContent(content) {
    const oldText = this.textarea.value;
    if (oldText === content) return;
    const selectionStart = preserveCaret(oldText, content, this.textarea.selectionStart);
    const selectionEnd = preserveCaret(oldText, content, this.textarea.selectionEnd);
    this.textarea.value = content;
    this.textarea.setSelectionRange(selectionStart, selectionEnd);
  }

  enterReadOnly(message) {
    this.pendingRemote = null;
    this.errorNotice = null;
    this.setState('READ_ONLY');
    this.textarea.disabled = true;
    this.setStatus(message);
    if (this.onDeleted) this.onDeleted();
  }

  setState(state) {
    this.state = state;
    this.render();
  }

  render() {
    if (this.errorNotice) {
      this.setStatus(this.errorNotice);
      return;
    }
    const labels = {
      IDLE: 'Saved',
      DIRTY: 'Unsaved changes',
      SENDING: 'Saving…',
      CONFLICT: 'Conflict — resolve below',
      READ_ONLY: 'Read-only',
    };
    let text = labels[this.state] || this.state;
    if (this.state !== 'READ_ONLY' && this.connectionState && this.connectionState !== 'OPEN') {
      text += ' (reconnecting…)';
    }
    this.setStatus(text);
  }

  setStatus(text) {
    if (this.statusEl) this.statusEl.textContent = text;
  }
}
