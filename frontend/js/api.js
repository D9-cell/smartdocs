// Thin fetch wrapper over the /api/v1/documents surface. Every function
// returns a parsed JSON body on success and throws an ApiError on failure.
// ApiError always carries `status` and, for a problem+json body, `code` and
// the raw `body` object so callers can branch on the stable `code` field
// (never on `detail` text — see the design doc, section 6.7).

class ApiError extends Error {
  constructor(status, code, body) {
    super(`API error ${status}${code ? ` (${code})` : ''}`);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

function quoted(version) {
  return `"${version}"`;
}

function parseEtag(response) {
  const raw = response.headers.get('ETag');
  if (!raw) return null;
  const match = /^"?(\d+)"?$/.exec(raw.trim());
  return match ? Number(match[1]) : null;
}

// A 401 SESSION_INVALID means "you have no valid session" — send the
// browser to the login page. INVALID_CREDENTIALS (a normal failed login
// attempt) is a 401 too but must NOT redirect, so this only fires on the
// specific code, not on every 401 (the login page itself calls /auth/login
// and needs to show that failure inline, not bounce in a loop).
const LOGIN_PAGE = '/login.html';

async function handle(response) {
  if (response.status === 204 || response.status === 304) {
    return { response, body: null };
  }
  const contentType = response.headers.get('Content-Type') || '';
  const body = contentType.includes('json') ? await response.json().catch(() => null) : null;

  if (!response.ok) {
    const code = body && typeof body === 'object' ? body.code : undefined;
    if (code === 'SESSION_INVALID' && !window.location.pathname.endsWith('login.html')) {
      window.location.href = LOGIN_PAGE;
    }
    throw new ApiError(response.status, code, body);
  }
  return { response, body };
}

const Api = {
  ApiError,

  async register(email, password, displayName) {
    const { body } = await handle(await fetch('/api/v1/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password, displayName }),
    }));
    return body;
  },

  async login(email, password) {
    const { body } = await handle(await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    }));
    return body;
  },

  async logout() {
    await handle(await fetch('/api/v1/auth/logout', { method: 'POST' }));
  },

  async me() {
    const { body } = await handle(await fetch('/api/v1/auth/me', { cache: 'no-store' }));
    return body;
  },

  async createDocument(title, content) {
    const { body } = await handle(await fetch('/api/v1/documents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, content }),
    }));
    return body;
  },

  async listDocuments(limit = 100, cursor = null) {
    const query = cursor ? `?limit=${limit}&cursor=${encodeURIComponent(cursor)}` : `?limit=${limit}`;
    const { body } = await handle(await fetch(`/api/v1/documents${query}`));
    return body; // { items, nextCursor }
  },

  async getDocument(id) {
    const { response, body } = await handle(await fetch(`/api/v1/documents/${id}`, {
      cache: 'no-store',
    }));
    return { document: body, version: parseEtag(response) };
  },

  async updateContent(id, expectedVersion, content, clientHash) {
    const { response, body } = await handle(await fetch(`/api/v1/documents/${id}/content`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'If-Match': quoted(expectedVersion),
      },
      body: JSON.stringify({ content, clientHash }),
    }));
    return { result: body, version: parseEtag(response) };
  },

  async renameDocument(id, expectedVersion, title) {
    const { response, body } = await handle(await fetch(`/api/v1/documents/${id}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        'If-Match': quoted(expectedVersion),
      },
      body: JSON.stringify({ title }),
    }));
    return { result: body, version: parseEtag(response) };
  },

  async deleteDocument(id, expectedVersion) {
    await handle(await fetch(`/api/v1/documents/${id}`, {
      method: 'DELETE',
      headers: { 'If-Match': quoted(expectedVersion) },
    }));
  },

  async listRevisions(id) {
    const { body } = await handle(await fetch(`/api/v1/documents/${id}/revisions`));
    return body;
  },

  async getRevision(id, version) {
    const { body } = await handle(await fetch(`/api/v1/documents/${id}/revisions/${version}`));
    return body;
  },
};
