/**
 * Client HTTP de Potato Remote.
 * Toutes les réponses suivent {ok:true,data:{...}} ou {ok:false,error:{...}}.
 * Aucun cookie : l'authentification repose sur un jeton porteur stocké en
 * sessionStorage (effacé à la fermeture de l'onglet).
 */
const API_ROOT = new URL('api/index.php', document.baseURI);

export class ApiError extends Error {
  constructor(message, { code = 'error', status = 0, retryAfter = 0 } = {}) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.retryAfter = retryAfter;
  }
}

async function request(action, { method = 'POST', body = null, token = null, signal = null, timeout = 0 } = {}) {
  const url = new URL(API_ROOT);
  url.searchParams.set('action', action);

  const headers = { Accept: 'application/json' };
  if (body !== null) headers['Content-Type'] = 'application/json';
  if (token) {
    headers.Authorization = `Bearer ${token}`;
    headers['X-Potato-Token'] = token;
  }

  let timedOut = false;
  let timer = null;
  let abortController = null;
  if (signal) {
    abortController = new AbortController();
    if (signal.aborted) abortController.abort();
    else signal.addEventListener('abort', () => abortController.abort(), { once: true });
  }
  if (timeout > 0) {
    abortController = abortController || new AbortController();
    timer = setTimeout(() => {
      timedOut = true;
      abortController.abort();
    }, timeout);
  }

  let response;
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === null ? undefined : JSON.stringify(body),
      cache: 'no-store',
      credentials: 'omit',
      signal: abortController ? abortController.signal : undefined,
    });
  } catch (error) {
    if (timedOut) throw new ApiError('Le serveur met trop de temps à répondre.', { code: 'timeout' });
    if (signal && signal.aborted) throw new ApiError('Requête annulée.', { code: 'aborted' });
    throw new ApiError('Serveur injoignable. Vérifiez votre connexion Internet.', { code: 'network' });
  } finally {
    if (timer) clearTimeout(timer);
  }

  let payload = null;
  try {
    payload = await response.json();
  } catch {
    throw new ApiError('Réponse illisible du serveur. Vérifiez l\'installation PHP.', {
      code: 'bad_response',
      status: response.status,
    });
  }

  if (!response.ok || !payload || payload.ok !== true) {
    const detail = (payload && payload.error) || {};
    throw new ApiError(detail.message || `Erreur serveur (${response.status}).`, {
      code: detail.code || 'http_error',
      status: response.status,
      retryAfter: Number(detail.retryAfter || response.headers.get('Retry-After') || 0),
    });
  }

  return payload.data;
}

export const api = {
  config: () => request('config', { method: 'GET', timeout: 12000 }),
  health: () => request('health', { method: 'GET', timeout: 12000 }),
  createSession: (payload) => request('session.create', { body: payload, timeout: 15000 }),
  auth: (payload) => request('session.auth', { body: payload, timeout: 15000 }),
  sessionInfo: (token) => request('session.info', { token, body: {}, timeout: 12000 }),
  heartbeat: (token, payload) => request('viewer.heartbeat', { token, body: payload, timeout: 12000 }),
  leave: (token) => request('viewer.leave', { token, body: {}, timeout: 8000 }),
  sendSignal: (token, type, payload, to) =>
    request('signal.send', { token, body: { type, payload, to }, timeout: 12000 }),
  poll: (token, cursor, wait, signal) =>
    request('signal.poll', { token, body: { cursor, wait }, signal, timeout: (wait + 8) * 1000 }),
};
