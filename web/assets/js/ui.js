/** Utilitaires d'interface partagés. */

export const $ = (selector, root = document) => root.querySelector(selector);
export const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

export function show(element, visible) {
  if (!element) return;
  element.hidden = !visible;
}

export function setPressed(button, pressed) {
  if (button) button.setAttribute('aria-pressed', String(Boolean(pressed)));
}

let toastContainer = null;

export function toast(message, { level = 'info', duration = 4200 } = {}) {
  if (!toastContainer) toastContainer = $('#toasts');
  if (!toastContainer) return;

  const node = document.createElement('div');
  node.className = 'toast';
  node.dataset.level = level;
  node.setAttribute('role', level === 'error' ? 'alert' : 'status');
  node.textContent = message;
  toastContainer.appendChild(node);

  while (toastContainer.children.length > 3) {
    toastContainer.firstElementChild.remove();
  }

  setTimeout(() => {
    node.classList.add('is-leaving');
    node.addEventListener('animationend', () => node.remove(), { once: true });
    setTimeout(() => node.remove(), 600);
  }, duration);
}

/** Libellé d'appareil envoyé au téléphone (liste des spectateurs). */
export function detectDevice() {
  const ua = navigator.userAgent;

  let os = '';
  if (/iPad/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)) os = 'iPad';
  else if (/iPhone/.test(ua)) os = 'iPhone';
  else if (/Android/.test(ua)) os = 'Android';
  else if (/Macintosh/.test(ua)) os = 'Mac';
  else if (/Windows/.test(ua)) os = 'Windows';
  else if (/CrOS/.test(ua)) os = 'ChromeOS';
  else if (/Linux/.test(ua)) os = 'Linux';

  let browser = '';
  if (/EdgiOS|Edg\//.test(ua)) browser = 'Edge';
  else if (/CriOS/.test(ua)) browser = 'Chrome';
  else if (/FxiOS|Firefox/.test(ua)) browser = 'Firefox';
  else if (/OPiOS|OPR\//.test(ua)) browser = 'Opera';
  else if (/Chrome\//.test(ua)) browser = 'Chrome';
  else if (/Safari\//.test(ua)) browser = 'Safari';

  return [os, browser].filter(Boolean).join(' · ') || 'Navigateur';
}

export function isAppleTouchDevice() {
  return /iPad|iPhone|iPod/.test(navigator.userAgent)
    || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

/** Libellé de qualité : « 1080p · 30 i/s ». */
export function qualityLabel(info) {
  if (!info || !info.width || !info.height) return '—';
  const height = Number(info.height);
  const preset = height >= 1000 ? '1080p' : height >= 700 ? '720p' : height >= 500 ? '540p' : `${Math.round(height)}p`;
  const fps = Number(info.fps) > 0 ? ` · ${Math.round(info.fps)} i/s` : '';
  return `${preset}${fps}`;
}

export function formatLatency(ms) {
  if (ms === null || ms === undefined || !Number.isFinite(ms) || ms < 0) return '—';
  return `${Math.round(ms)} ms`;
}

export function formatDuration(ms) {
  const seconds = Math.max(0, Math.round(ms / 1000));
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  if (hours > 0) return `${hours} h ${String(minutes % 60).padStart(2, '0')}`;
  if (minutes > 0) return `${minutes} min`;
  return `${seconds} s`;
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function clamp(value, min = 0, max = 1) {
  return Math.min(max, Math.max(min, value));
}

/** Taille en pixels de la vidéo réellement affichée (object-fit: contain). */
export function videoContentRect(video) {
  const rect = video.getBoundingClientRect();
  const vw = video.videoWidth || rect.width || 16;
  const vh = video.videoHeight || rect.height || 9;
  const scale = Math.min(rect.width / vw, rect.height / vh);
  const width = vw * scale;
  const height = vh * scale;
  return {
    left: rect.left + (rect.width - width) / 2,
    top: rect.top + (rect.height - height) / 2,
    width,
    height,
  };
}
