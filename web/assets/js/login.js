import { api, ApiError } from './api.js';
import { $, detectDevice, toast } from './ui.js';

const form = $('#login-form');
const sessionInput = $('#session');
const passwordInput = $('#password');
const submitButton = $('#submit');
const errorBox = $('#form-error');
const serverState = $('#server-state');
const serverStateText = $('#server-state-text');
const togglePassword = $('#toggle-password');
const turnNote = $('#turn-note');
const label = submitButton.querySelector('.btn-label');
const spinner = submitButton.querySelector('.btn-spinner');

const ERROR_MESSAGES = {
  auth_failed: 'Nom de session ou mot de passe incorrect.',
  session_full: 'Cette session est complète. Demandez à l\'hôte de libérer une place.',
  join_closed: 'L\'hôte n\'accepte plus de nouveaux spectateurs.',
  session_ended: 'Cette session est terminée ou abandonnée par le téléphone.',
  rate_limited: 'Trop de tentatives de connexion. Patientez un instant avant de réessayer.',
  network: 'Serveur injoignable. Vérifiez votre connexion puis réessayez.',
  timeout: 'Le serveur met trop de temps à répondre. Réessayez.',
  https_required: 'Ce serveur doit être utilisé en HTTPS.',
  invalid_server_key: 'Ce serveur exige une clé d\'accès pour créer une session.',
  server_error: 'Erreur interne du serveur. Consultez les journaux PHP de l\'hébergement.',
};

function setServerState(state, text) {
  serverState.dataset.state = state;
  serverStateText.textContent = text;
}

function setBusy(busy) {
  submitButton.disabled = busy;
  submitButton.classList.toggle('is-loading', busy);
  spinner.hidden = !busy;
  label.textContent = busy ? 'Connexion…' : 'Se connecter';
}

function showError(message) {
  errorBox.textContent = message;
  errorBox.hidden = false;
  errorBox.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

function clearError() {
  errorBox.hidden = true;
  errorBox.textContent = '';
}

function failureMessage(error) {
  if (!(error instanceof ApiError)) return ERROR_MESSAGES.server_error;
  if (error.code === 'rate_limited' && error.retryAfter > 0) {
    const minutes = Math.max(1, Math.ceil(error.retryAfter / 60));
    return `${ERROR_MESSAGES.rate_limited} (${minutes} min)`;
  }
  return ERROR_MESSAGES[error.code] || error.message || ERROR_MESSAGES.server_error;
}

/* Pré-remplissage : ?s=NomDeSession, ou dernière session utilisée. */
const params = new URLSearchParams(window.location.search);
const prefill = (params.get('s') || localStorage.getItem('potato.lastSession') || '').trim();
if (prefill) {
  sessionInput.value = prefill;
  passwordInput.focus();
} else {
  sessionInput.focus();
}

if (sessionInput.value) {
  sessionInput.setAttribute('value', sessionInput.value);
}

/* Afficher / masquer le mot de passe. */
togglePassword.addEventListener('click', () => {
  const visible = passwordInput.type === 'text';
  passwordInput.type = visible ? 'password' : 'text';
  togglePassword.setAttribute('aria-pressed', String(!visible));
  togglePassword.setAttribute('aria-label', visible ? 'Afficher le mot de passe' : 'Masquer le mot de passe');
  togglePassword.querySelector('.icon-eye').hidden = !visible;
  togglePassword.querySelector('.icon-eye-off').hidden = visible;
  passwordInput.focus();
});

/* État du serveur + diagnostic TURN. */
(async () => {
  try {
    const config = await api.config();
    setServerState('online', 'Serveur en ligne');
    turnNote.hidden = Boolean(config.turnConfigured);
  } catch (error) {
    setServerState('offline', 'Serveur injoignable');
    turnNote.hidden = true;
  }
})();

/* Message flash éventuel (session expirée, accès retiré…). */
try {
  const flash = sessionStorage.getItem('potato.flash');
  if (flash) {
    sessionStorage.removeItem('potato.flash');
    toast(flash, { level: 'error', duration: 6500 });
  }
} catch {
  /* stockage indisponible (navigation privée) */
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearError();

  const session = sessionInput.value.trim();
  const password = passwordInput.value;

  if (session.length < 3) {
    showError('Le nom de session doit contenir au moins 3 caractères.');
    sessionInput.focus();
    return;
  }
  if (password.length < 4) {
    showError('Saisissez le mot de passe affiché sur le téléphone.');
    passwordInput.focus();
    return;
  }

  setBusy(true);

  try {
    const viewport = {
      w: Math.max(320, Math.round(window.innerWidth * window.devicePixelRatio)),
      h: Math.max(240, Math.round(window.innerHeight * window.devicePixelRatio)),
      dpr: window.devicePixelRatio || 1,
    };

    const data = await api.auth({
      session,
      password,
      device: detectDevice(),
      viewport,
    });

    sessionStorage.setItem('potato.session', JSON.stringify({
      token: data.viewerToken,
      viewerId: data.viewerId,
      name: data.name,
      cursor: data.cursor,
      iceServers: data.iceServers || [],
      turnConfigured: Boolean(data.turnConfigured),
      joinedAt: Date.now(),
    }));

    try {
      localStorage.setItem('potato.lastSession', data.name);
    } catch {
      /* stockage plein ou privé */
    }

    window.location.replace('viewer.html');
  } catch (error) {
    showError(failureMessage(error));
    setBusy(false);
    if (error && (error.code === 'network' || error.code === 'timeout')) {
      setServerState('offline', 'Serveur injoignable');
    }
    passwordInput.select();
  }
});
