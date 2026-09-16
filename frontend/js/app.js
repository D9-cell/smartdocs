// Sidebar bootstrap: list/create/select documents, then hand the selected
// document off to the Editor state machine. Deliberately kept out of
// editor.js — this is app wiring, not editor state. Kept out of an inline
// <script> tag in index.html — the server's CSP (SecurityHeadersFilter) is
// `default-src 'self'` with no `unsafe-inline`, so a real browser silently
// blocks inline script.
(function () {
  const docListEl = document.getElementById('doc-list');
  const newDocBtn = document.getElementById('new-doc-btn');
  const toolbarEl = document.getElementById('toolbar');
  const titleInput = document.getElementById('title-input');
  const editorEl = document.getElementById('editor');
  const emptyStateEl = document.getElementById('empty-state');
  const statusEl = document.getElementById('status');
  const conflictPanel = document.getElementById('conflict-panel');
  const recoveryPanel = document.getElementById('recovery-panel');
  const accountEmailEl = document.getElementById('account-email');
  const logoutBtn = document.getElementById('logout-btn');

  let activeEditor = null;
  let activeId = null;
  let renameInFlight = false;

  // One socket for the tab's whole lifetime (design doc section 1);
  // Editor instances come and go as the user switches documents.
  const syncClient = new SyncClient();

  logoutBtn.addEventListener('click', async () => {
    syncClient.disconnect();
    try {
      await Api.logout();
    } finally {
      window.location.href = '/login.html';
    }
  });

  async function refreshList(selectId) {
    const { items: docs } = await Api.listDocuments();
    docListEl.innerHTML = '';
    for (const doc of docs) {
      const li = document.createElement('li');
      li.textContent = doc.title || 'Untitled';
      li.dataset.id = doc.id;
      if (doc.id === (selectId ?? activeId)) li.classList.add('active');
      li.addEventListener('click', () => selectDocument(doc.id));
      docListEl.appendChild(li);
    }
  }

  async function selectDocument(id) {
    if (activeEditor) {
      await activeEditor.flush();
      activeEditor.destroy();
      activeEditor = null;
    }
    activeId = id;
    toolbarEl.hidden = false;
    editorEl.hidden = false;
    emptyStateEl.hidden = true;

    const { document: doc } = await Api.getDocument(id);
    titleInput.value = doc.title;

    activeEditor = new Editor({
      documentId: id,
      textarea: editorEl,
      statusEl,
      titleInput,
      conflictPanel,
      recoveryPanel,
      syncClient,
      onRenamed: () => refreshList(activeId),
      onDeleted: () => {
        const editor = activeEditor;
        activeEditor = null;
        activeId = null;
        toolbarEl.hidden = true;
        editorEl.hidden = true;
        emptyStateEl.hidden = false;
        if (editor) editor.destroy();
        refreshList();
      },
    });
    await activeEditor.init();
    await refreshList(id);
    [...docListEl.children].forEach((li) => li.classList.toggle('active', li.dataset.id === id));
  }

  async function createDocument() {
    const doc = await Api.createDocument('Untitled', '');
    await refreshList(doc.id);
    await selectDocument(doc.id);
    titleInput.focus();
    titleInput.select();
  }

  async function renameActive() {
    if (!activeEditor || renameInFlight) return;
    const newTitle = titleInput.value;
    renameInFlight = true;
    try {
      const { result, version } = await Api.renameDocument(activeId, activeEditor.baseVersion, newTitle);
      activeEditor.baseVersion = version ?? result.version;
      titleInput.value = result.title;
      await refreshList(activeId);
    } catch (err) {
      if (err instanceof Api.ApiError && err.status === 412) {
        // Someone else changed the document; reload to pick up the
        // current version rather than silently losing the rename.
        await selectDocument(activeId);
      } else {
        console.error('Rename failed', err);
      }
    } finally {
      renameInFlight = false;
    }
  }

  newDocBtn.addEventListener('click', () => createDocument());
  titleInput.addEventListener('blur', renameActive);
  titleInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      titleInput.blur();
    }
  });

  (async function bootstrap() {
    // A 401 here redirects to /login.html automatically (see api.js);
    // nothing below this line runs for an unauthenticated visitor.
    const user = await Api.me();
    accountEmailEl.textContent = user.email;
    syncClient.connect();
    await refreshList();
  })();
})();
