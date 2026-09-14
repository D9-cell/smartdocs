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

async function handle(response) {
  if (response.status === 204 || response.status === 304) {
    return { response, body: null };
  }
  const contentType = response.headers.get('Content-Type') || '';
  const body = contentType.includes('json') ? await response.json().catch(() => null) : null;

  if (!response.ok) {
    const code = body && typeof body === 'object' ? body.code : undefined;
    throw new ApiError(response.status, code, body);
  }
  return { response, body };
}

const Api = {
  ApiError,

  async createDocument(title, content) {
    const { body } = await handle(await fetch('/api/v1/documents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, content }),
    }));
    return body;
  },

  async listDocuments(limit = 100, offset = 0) {
    const { body } = await handle(await fetch(`/api/v1/documents?limit=${limit}&offset=${offset}`));
    return body;
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
