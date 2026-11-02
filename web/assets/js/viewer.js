import { api } from './api.js';
import {
  $, clamp, detectDevice, formatLatency, isAppleTouchDevice, qualityLabel,
  setPressed, show, sleep, toast,
} from './ui.js';
import { PotatoPeer } from './webrtc.js';
import { TouchController } from './controls.js';
import { RemoteKeyboard } from './keyboard.js';

const STORAGE_KEY = 'potato.session';

function loadSession() {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const data = JSON.parse(raw);
    if (!data || typeof data.token !== 'string' || data.token.length < 20) return null;
    return data;
  } catch {
    return null;
  }
}

const session = loadSession();

if (!session) {
  try {
    sessionStorage.setItem('potato.flash', 'Reconnectez-vous pour rejoindre une session.');
  } catch {
    /* stockage indisponible */
  }
  window.location.replace('index.html');
} else {
  main(session);
}

function main(activeSession) {
  const els = {
    body: document.body,
    stageWrap: $('#stage-wrap'),
    stage: $('#stage'),
    video: $('#video'),
    touchLayer: $('#touch-layer'),
    overlay: $('#overlay'),
    overlayTitle: $('#overlay-title'),
    overlayText: $('#overlay-text'),
    overlayAction: $('#overlay-action'),
    caption: $('#stage-caption'),
    sessionName: $('#session-name'),
    chipConnection: $('#chip-connection'),
    connectionText: $('#connection-text'),
    chipLatency: $('#chip-latency'),
    latencyText: $('#latency-text'),
    chipQuality: $('#chip-quality'),
    qualityText: $('#quality-text'),
    chipViewers: $('#chip-viewers'),
    viewersText: $('#viewers-text'),
    btnControl: $('#btn-control'),
    btnKeyboard: $('#btn-keyboard'),
    btnFit: $('#btn-fit'),
    fitLabel: $('#fit-label'),
    btnFullscreen: $('#btn-fullscreen'),
    btnLeave: $('#btn-leave'),
    keyboardPanel: $('#keyboard-panel'),
    keyboardTextarea: $('#keyboard-textarea'),
    leaveDialog: $('#leave-dialog'),
    leaveCancel: $('#leave-cancel'),
    leaveConfirm: $('#leave-confirm'),
  };

  const state = {
    session: activeSession,
    peer: null,
    cursor: Number(activeSession.cursor) || 0,
    settings: { control: true, keyboard: true, maxViewers: 4, quality: 'auto', allow_new_viewers: true },
    phoneOnline: true,
    connected: false,
    controlEnabled: false,
    controlUsedAt: 0,
    fitMode: 'fit',
    latency: null,
    rtt: null,
    stats: null,
    viewerCount: 1,
    ended: false,
    attempts: 0,
    reconnectTimer: null,
    offerTimer: null,
    offerSends: 0,
    pollAbort: null,
    heartbeatTimer: null,
    statsTimer: null,
    chromeTimer: null,
    captionTimer: null,
    viewportTimer: null,
    wakeLock: null,
    keyboard: null,
    touch: null,
  };

  /* ------------------------------------------------------------------ */
  /*  Interface                                                          */
  /* ------------------------------------------------------------------ */
  els.sessionName.textContent = activeSession.name || 'Session';
  renderViewers(1);
  show(els.chipViewers, true);
  setConnection('connecting', 'Connexion…');

  state.keyboard = new RemoteKeyboard({
    sheet: els.keyboardPanel,
    textarea: els.keyboardTextarea,
    send: keyboardSend,
    onOpenChange: (open) => {
      setPressed(els.btnKeyboard, open);
      if (open) {
        document.body.classList.remove('chrome-hidden');
        clearTimeout(state.chromeTimer);
      } else {
        scheduleChrome();
      }
    },
  });

  state.touch = new TouchController({
    layer: els.touchLayer,
    video: els.video,
    stage: els.stage,
    onGesture: ({ action, x, y, ts }) => {
      if (!state.controlEnabled || !state.connected) return;
      state.controlUsedAt = Date.now();
      const accepted = state.peer?.send(
        { t: 'touch', a: action, x: Number(x.toFixed(5)), y: Number(y.toFixed(5)), ts },
        { dropIfBusy: action !== 'up' && action !== 'cancel' },
      );
      if (!accepted && action === 'down') toast('Téléphone non connecté.', { level: 'error' });
    },
  });

  els.btnControl.addEventListener('click', () => setControlEnabled(!state.controlEnabled));
  els.btnKeyboard.addEventListener('click', toggleKeyboard);
  els.btnFit.addEventListener('click', toggleFitMode);
  els.btnFullscreen.addEventListener('click', toggleFullscreen);
  els.btnLeave.addEventListener('click', () => {
    els.leaveDialog.hidden = false;
    els.leaveCancel.focus();
  });
  els.leaveCancel.addEventListener('click', () => {
    els.leaveDialog.hidden = true;
  });
  els.leaveConfirm.addEventListener('click', leaveSession);

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !els.leaveDialog.hidden) {
      els.leaveDialog.hidden = true;
    }
  });

  els.overlayAction.addEventListener('click', () => {
    if (state.ended) {
      forgetAndGoHome();
    } else {
      connect();
    }
  });

  /* Reprise du rendu après un changement d'onglet. */
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      requestWakeLock();
      scheduleViewportReport();
      if (!state.ended) heartbeat();
    }
  });

  window.addEventListener('resize', scheduleViewportReport, { passive: true });
  window.addEventListener('orientationchange', scheduleViewportReport, { passive: true });
  if ('ResizeObserver' in window) {
    new ResizeObserver(scheduleViewportReport).observe(els.stage);
  }

  document.addEventListener('pointermove', scheduleChrome, { passive: true });
  document.addEventListener('pointerdown', scheduleChrome, { passive: true });
  document.addEventListener('fullscreenchange', () => {
    setPressed(els.btnFullscreen, Boolean(document.fullscreenElement));
    els.body.classList.remove('is-pseudo-fullscreen');
    scheduleChrome();
    scheduleViewportReport();
  });

  window.addEventListener('beforeunload', () => {
    if (state.peer) state.peer.close();
  });

  requestWakeLock();
  bootstrap();
  state.heartbeatTimer = setInterval(heartbeat, 5000);
  state.statsTimer = setInterval(refreshStats, 3000);

  /* ------------------------------------------------------------------ */
  /*  Connexion WebRTC                                                   */
  /* ------------------------------------------------------------------ */
  async function bootstrap() {
    clearTimeout(state.reconnectTimer);
    setConnection('connecting', 'Connexion…');
    try {
      // Valide le jeton et récupère le curseur courant : un rechargement de
      // page ne rejoue pas les anciennes offres/réponses et ne perd pas la
      // session. session.info vérifie aussi l'expiration et l'expulsion.
      const info = await api.sessionInfo(state.session.token);
      if (state.ended) return;
      const cursor = Number(info.cursor);
      if (Number.isFinite(cursor)) state.cursor = cursor;
      saveCursor();
      state.phoneOnline = info.phoneOnline !== false;
      if (info.name) {
        state.session.name = info.name;
        els.sessionName.textContent = info.name;
      }
      if (Array.isArray(info.iceServers) && info.iceServers.length) {
        state.session.iceServers = info.iceServers;
      }
      if (info.settings) applyRemoteSettings(info.settings);
      if (!state.phoneOnline) showWaiting();
    } catch (error) {
      handleSessionError(error);
      if (!state.ended) {
        setOverlay('error', 'Serveur indisponible', error.message || 'Impossible de vérifier la session.', {
          label: 'Réessayer',
          onClick: () => {
            els.overlay.hidden = true;
            bootstrap();
          },
        });
      }
      return;
    }
    startPolling();
    connect();
  }

  function saveCursor() {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const data = JSON.parse(raw);
      data.cursor = state.cursor;
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(data));
    } catch {
      /* stockage indisponible */
    }
  }

  function currentViewport() {
    const rect = els.stage.getBoundingClientRect();
    const dpr = clamp(window.devicePixelRatio || 1, 1, 3);
    const width = Math.max(240, Math.round(rect.width * dpr));
    const height = Math.max(160, Math.round(rect.height * dpr));
    return { w: width, h: height, dpr, aspect: Number((width / height).toFixed(4)) };
  }

  async function connect() {
    if (state.ended) return;
    clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
    clearTimeout(state.offerTimer);
    teardownPeer();

    state.connected = false;
    state.offerSends = 0;
    els.video.classList.add('is-waiting');
    setConnection('connecting', 'Connexion…');
    setOverlay('loading', 'Connexion au téléphone…', 'Négociation sécurisée WebRTC (DTLS-SRTP).');

    const peer = new PotatoPeer({ iceServers: state.session.iceServers || [] });
    state.peer = peer;
    wirePeer(peer);

    try {
      const { sdp } = await peer.start();
      await sendOffer(sdp);
      state.offerTimer = setTimeout(retryOffer, 12000);
    } catch (error) {
      setOverlay('error', 'Connexion impossible', 'Le navigateur n\'a pas pu établir la connexion vidéo.', { label: 'Réessayer' });
      console.error('[potato] WebRTC', error);
    }
  }

  async function sendOffer(sdp) {
    if (state.ended) return;
    state.offerSends += 1;
    try {
      await api.sendSignal(state.session.token, 'offer', {
        sdp,
        viewport: currentViewport(),
        mode: state.fitMode,
      });
    } catch (error) {
      handleSessionError(error);
    }
  }

  function retryOffer() {
    if (state.ended || state.connected) return;
    if (state.offerSends >= 4) {
      setOverlay(
        'waiting',
        'En attente du téléphone',
        state.phoneOnline
          ? 'Le téléphone n\'a pas encore répondu. Vérifiez que le partage est toujours actif.'
          : 'Le téléphone n\'est pas en ligne. Lancez le partage depuis l\'application Android.',
        { label: 'Réessayer' },
      );
      return;
    }
    connect();
  }

  function wirePeer(peer) {
    peer.addEventListener('stream', ({ detail }) => {
      if (state.peer !== peer) return;
      els.video.srcObject = detail.stream;
      els.video.classList.remove('is-waiting');
      const promise = els.video.play();
      if (promise && typeof promise.catch === 'function') promise.catch(() => {});
    });

    peer.addEventListener('connection', ({ detail }) => {
      if (state.peer !== peer) return;
      if (detail.state === 'connected') onConnected();
      else if (detail.state === 'failed') scheduleReconnect();
      else if (detail.state === 'disconnected') {
        setConnection('reconnecting', 'Reconnexion…');
        scheduleReconnect();
      }
    });

    peer.addEventListener('candidate', ({ detail }) => {
      api.sendSignal(state.session.token, 'ice', detail.candidate).catch(() => {});
    });

    peer.addEventListener('channel-open', () => {
      state.latency = null;
      peer.startPing();
      peer.send({
        t: 'hello',
        viewerId: state.session.viewerId,
        label: detectDevice(),
        viewport: currentViewport(),
        mode: state.fitMode,
      }, { dropIfBusy: false });
      peer.send({ t: 'control', active: state.controlEnabled }, { dropIfBusy: false });
      peer.send({ t: 'keyboard', active: state.keyboard.isOpen() }, { dropIfBusy: false });
    });

    peer.addEventListener('message', ({ detail }) => onPeerMessage(detail.data));
    peer.addEventListener('error', () => scheduleReconnect());
    peer.addEventListener('channel-close', () => {
      if (state.connected && !state.ended) setConnection('reconnecting', 'Canal de contrôle fermé');
    });
  }

  function onConnected() {
    state.connected = true;
    state.attempts = 0;
    clearTimeout(state.offerTimer);
    clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
    hideOverlay();
    setConnection('online', 'Connecté');
    show(els.chipLatency, true);
    show(els.chipQuality, true);
    show(els.chipViewers, true);
    applyRemoteSettings(state.settings);
  }

  function scheduleReconnect() {
    if (state.ended || state.reconnectTimer) return;
    state.attempts += 1;
    if (state.attempts > 7) {
      endSession('error', 'Connexion perdue', 'Le téléphone ne répond plus. Vérifiez qu\'il est toujours en ligne et que le partage est actif.');
      return;
    }
    const delay = Math.min(8000, 1200 * state.attempts);
    setOverlay('reconnecting', 'Reconnexion…', `Nouvelle tentative dans ${Math.round(delay / 1000)} s.`);
    setConnection('reconnecting', 'Reconnexion…');
    state.reconnectTimer = setTimeout(() => {
      state.reconnectTimer = null;
      connect();
    }, delay);
  }

  function teardownPeer() {
    if (state.peer) {
      state.peer.close();
      state.peer = null;
    }
  }

  /* ------------------------------------------------------------------ */
  /*  Signalisation                                                      */
  /* ------------------------------------------------------------------ */
  async function startPolling() {
    let backoff = 900;
    while (!state.ended) {
      const controller = new AbortController();
      state.pollAbort = controller;
      try {
        const data = await api.poll(state.session.token, state.cursor, 18, controller.signal);
        state.pollAbort = null;
        if (state.ended) return;
        if (Number.isFinite(data.cursor)) {
          state.cursor = data.cursor;
          saveCursor();
        }

        const wasOnline = state.phoneOnline;
        state.phoneOnline = data.phoneOnline !== false;
        applyPresence(data);

        const messages = Array.isArray(data.messages) ? data.messages : [];
        for (const message of messages) {
          await handleSignal(message);
          if (state.ended) return;
        }

        if (!wasOnline && state.phoneOnline) toast('Téléphone de nouveau en ligne.');
        if (!state.phoneOnline && !state.connected) showWaiting();
        backoff = 900;
      } catch (error) {
        state.pollAbort = null;
        if (state.ended || error.code === 'aborted') return;
        if (error.code === 'kicked') {
          endSession('kicked', 'Accès retiré', 'L\'hôte a retiré votre accès à cette session.');
          return;
        }
        if (['session_ended', 'viewer_removed', 'invalid_token', 'missing_token'].includes(error.code)) {
          endSession('ended', 'Session terminée', 'Le partage a été arrêté, a expiré, ou n\'est plus disponible.');
          return;
        }
        setConnection('reconnecting', 'Serveur injoignable');
        await sleep(backoff);
        backoff = Math.min(12000, Math.round(backoff * 1.6));
      }
    }
  }

  async function handleSignal(message) {
    if (!message || !message.type) return;
    if (message.type === 'answer') {
      clearTimeout(state.offerTimer);
      try {
        await state.peer?.acceptAnswer(message.payload.sdp);
      } catch (error) {
        console.warn('[potato] réponse SDP refusée', error);
      }
      return;
    }
    if (message.type === 'ice') {
      await state.peer?.addIce(message.payload);
      return;
    }
    if (message.type === 'bye') {
      if (message.payload && message.payload.reason === 'kicked') {
        endSession('kicked', 'Accès retiré', 'L\'hôte a retiré votre accès à cette session.');
      } else {
        endSession('ended', 'Session terminée', 'Le téléphone a mis fin au partage.');
      }
      return;
    }
    if (message.type === 'state') {
      applyRemoteSettings(message.payload);
      return;
    }
    if (message.type === 'notice') {
      toast(message.payload.message || 'Message du téléphone', {
        level: message.payload.level === 'error' ? 'error' : 'info',
      });
    }
  }

  function applyPresence(data) {
    if (!data) return;
    if (data.settings) applyRemoteSettings(data.settings);
    if (Number.isFinite(data.viewerCount)) renderViewers(data.viewerCount);
    if (!state.phoneOnline) {
      setControlEnabled(false);
      els.keyboard.setAllowed(false, 'Téléphone hors ligne.');
      if (!state.connected) showWaiting();
      if (state.connected) setConnection('offline', 'Téléphone hors ligne');
    } else {
      applyRemoteSettings(state.settings);
    }
  }

  function showWaiting() {
    if (state.ended || state.connected) return;
    setOverlay(
      'waiting',
      'En attente du téléphone',
      'Le téléphone n\'est pas en ligne. Lancez le partage depuis l\'application Android.',
      { label: 'Réessayer' },
    );
  }

  function applyRemoteSettings(settings) {
    if (!settings) return;
    const previous = state.settings;
    state.settings = { ...previous, ...settings };

    const controlAllowed = state.settings.control !== false && state.phoneOnline;
    if (!controlAllowed && state.controlEnabled) {
      setControlEnabled(false);
      toast('Le contrôle tactile a été désactivé sur le téléphone.', { level: 'error' });
    }
    els.btnControl.disabled = !controlAllowed;
    els.btnControl.title = controlAllowed ? 'Activer le contrôle tactile distant' : 'Contrôle désactivé sur le téléphone';

    const keyboardAllowed = state.settings.keyboard !== false && state.phoneOnline;
    els.btnKeyboard.disabled = !keyboardAllowed;
    els.btnKeyboard.title = keyboardAllowed ? 'Ouvrir le clavier distant' : 'Clavier désactivé sur le téléphone';
    if (state.keyboard.allowed !== keyboardAllowed) {
      state.keyboard.setAllowed(keyboardAllowed, keyboardAllowed ? '' : 'Clavier distant désactivé sur le téléphone.');
    }
  }

  function setControlEnabled(enabled) {
    const allowed = state.settings.control !== false && state.phoneOnline && state.connected;
    const next = Boolean(enabled) && allowed;
    if (enabled && !allowed) {
      toast(!state.connected ? 'La connexion n\'est pas encore prête.' : 'Le contrôle est désactivé sur le téléphone.', { level: 'error' });
    }
    state.controlEnabled = next;
    setPressed(els.btnControl, next);
    state.touch.setEnabled(next);
    state.peer?.send({ t: 'control', active: next }, { dropIfBusy: false });
    if (next) caption('Contrôle tactile actif — touchez l\'écran');
    else if (!enabled) caption('Contrôle tactile désactivé');
  }

  function toggleKeyboard() {
    if (!state.connected) {
      toast('La connexion n\'est pas encore prête.', { level: 'error' });
      return;
    }
    if (state.settings.keyboard === false) {
      toast('Le clavier distant est désactivé sur le téléphone.', { level: 'error' });
      return;
    }
    if (!state.phoneOnline) {
      toast('Le téléphone est hors ligne.', { level: 'error' });
      return;
    }
    state.keyboard.toggle();
  }

  function toggleFitMode() {
    state.fitMode = state.fitMode === 'fit' ? 'fill' : 'fit';
    els.fitLabel.textContent = state.fitMode === 'fit' ? 'Ajuster' : 'Remplir';
    setPressed(els.btnFit, state.fitMode === 'fill');
    state.peer?.send({ t: 'viewport', ...currentViewport(), mode: state.fitMode }, { dropIfBusy: false });
    caption(state.fitMode === 'fit'
      ? 'Cadrage : image entière (bandes floues)'
      : 'Cadrage : remplissage (léger recadrage)');
    scheduleViewportReport();
  }

  function keyboardSend(payload) {
    if (state.ended) return false;
    if (!state.peer) return false;
    return state.peer.send(payload, { dropIfBusy: false });
  }

  /* ------------------------------------------------------------------ */
  /*  Statistiques, présence                                             */
  /* ------------------------------------------------------------------ */
  async function refreshStats() {
    if (state.ended || !state.peer) return;
    const stats = await state.peer.getStats();
    if (!stats) return;
    state.stats = stats;
    els.qualityText.textContent = qualityLabel(stats);
    show(els.chipQuality, true);
    if (state.latency === null && stats.rtt) {
      state.rtt = stats.rtt;
      state.latency = stats.rtt / 2;
      renderLatency();
    }
  }

  async function heartbeat() {
    if (state.ended) return;
    try {
      const data = await api.heartbeat(state.session.token, {
        viewport: currentViewport(),
        stats: state.stats ? {
          rtt: Math.round(state.rtt || 0),
          fps: Math.round(state.stats.fps || 0),
          kbps: Math.round(state.stats.kbps || 0),
          width: state.stats.width || 0,
          height: state.stats.height || 0,
          jitter: state.stats.jitter || 0,
          lost: state.stats.lost || 0,
        } : null,
        using_control: state.controlEnabled && Date.now() - state.controlUsedAt < 10000,
        using_keyboard: state.keyboard.isOpen(),
      });
      if (state.ended) return;
      state.phoneOnline = data.phoneOnline !== false;
      applyPresence(data);
    } catch (error) {
      handleSessionError(error);
    }
  }

  function onPeerMessage(message) {
    if (!message || typeof message.t !== 'string') return;
    switch (message.t) {
      case 'pong': {
        if (typeof message.ts === 'number') {
          const rtt = Math.max(0, performance.now() - message.ts);
          state.rtt = rtt;
          state.latency = rtt / 2;
          renderLatency();
        }
        break;
      }
      case 'state': {
        applyRemoteSettings(message);
        break;
      }
      case 'notice': {
        toast(message.message || 'Message du téléphone', { level: message.level === 'error' ? 'error' : 'info' });
        break;
      }
      case 'control-result': {
        if (message.ok === false) {
          state.controlEnabled = false;
          setPressed(els.btnControl, false);
          state.touch.setEnabled(false);
          toast(message.reason || 'Contrôle refusé par le téléphone.', { level: 'error' });
        }
        break;
      }
      case 'keyboard-result': {
        if (message.ok === false) {
          state.keyboard.setStatus(message.reason || 'Saisie refusée par le téléphone.', 'error');
        }
        break;
      }
      case 'viewer-count': {
        if (Number.isFinite(message.count)) renderViewers(message.count);
        break;
      }
      default:
        break;
    }
  }

  function renderLatency() {
    if (state.latency === null) return;
    els.latencyText.textContent = formatLatency(state.latency);
    els.chipLatency.title = state.rtt ? `Aller-retour ${Math.round(state.rtt)} ms` : '';
    show(els.chipLatency, true);
  }

  function renderViewers(count) {
    state.viewerCount = Math.max(1, Number(count) || 1);
    els.viewersText.textContent = String(state.viewerCount);
    show(els.chipViewers, true);
  }

  function setConnection(mode, label) {
    els.chipConnection.dataset.state = mode;
    els.connectionText.textContent = label;
  }

  function setOverlay(mode, title, text, action = null) {
    els.overlay.hidden = false;
    els.overlay.dataset.state = mode;
    els.overlayTitle.textContent = title;
    els.overlayText.textContent = text;
    if (action) {
      els.overlayAction.hidden = false;
      els.overlayAction.textContent = action.label;
      els.overlayAction.onclick = action.onClick || (() => connect());
    } else {
      els.overlayAction.hidden = true;
      els.overlayAction.onclick = null;
    }
  }

  function hideOverlay() {
    els.overlay.hidden = true;
  }

  function caption(text) {
    els.caption.textContent = text;
    els.caption.hidden = false;
    clearTimeout(state.captionTimer);
    state.captionTimer = setTimeout(() => {
      els.caption.hidden = true;
    }, 3400);
  }

  function handleSessionError(error) {
    if (!error || !error.code) return;
    if (error.code === 'kicked') {
      endSession('kicked', 'Accès retiré', 'L\'hôte a retiré votre accès à cette session.');
    } else if (['session_ended', 'viewer_removed', 'invalid_token', 'missing_token'].includes(error.code)) {
      endSession('ended', 'Session terminée', 'Le partage a été arrêté, a expiré, ou n\'est plus disponible.');
    }
  }

  /* ------------------------------------------------------------------ */
  /*  Cycle de vie                                                       */
  /* ------------------------------------------------------------------ */
  function stopLoops() {
    clearInterval(state.heartbeatTimer);
    clearInterval(state.statsTimer);
    clearTimeout(state.reconnectTimer);
    clearTimeout(state.offerTimer);
    clearTimeout(state.chromeTimer);
    clearTimeout(state.captionTimer);
    clearTimeout(state.viewportTimer);
    state.pollAbort?.abort();
    state.pollAbort = null;
  }

  function endSession(mode, title, text) {
    if (state.ended) return;
    state.ended = true;
    stopLoops();
    teardownPeer();
    els.video.pause();
    els.video.srcObject = null;
    state.keyboard.hide();
    state.touch.setEnabled(false);
    [els.btnControl, els.btnKeyboard, els.btnFit, els.btnFullscreen].forEach((button) => {
      button.disabled = true;
    });
    setConnection('ended', 'Session terminée');
    setOverlay(mode, title, text, { label: 'Retour à l\'accueil', onClick: forgetAndGoHome });
    try {
      sessionStorage.setItem('potato.flash', text);
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      /* stockage indisponible */
    }
  }

  function forgetAndGoHome() {
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      /* ignoré */
    }
    window.location.href = 'index.html';
  }

  async function leaveSession() {
    if (state.ended) return;
    state.ended = true;
    stopLoops();
    state.keyboard.hide();
    teardownPeer();
    try {
      await api.leave(state.session.token);
    } catch {
      /* la session expirera d'elle-même */
    }
    forgetAndGoHome();
  }

  /* ------------------------------------------------------------------ */
  /*  Plein écran, veille, redimensionnement                             */
  /* ------------------------------------------------------------------ */
  async function toggleFullscreen() {
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen();
        return;
      }
      if (els.stageWrap.requestFullscreen) {
        await els.stageWrap.requestFullscreen({ navigationUI: 'hide' });
        return;
      }
      if (els.video.webkitEnterFullscreen && isAppleTouchDevice()) {
        els.video.webkitEnterFullscreen();
        return;
      }
    } catch {
      /* repli ci-dessous */
    }
    const pseudo = !els.body.classList.contains('is-pseudo-fullscreen');
    els.body.classList.toggle('is-pseudo-fullscreen', pseudo);
    setPressed(els.btnFullscreen, pseudo);
    scheduleChrome();
    scheduleViewportReport();
  }

  function scheduleChrome() {
    clearTimeout(state.chromeTimer);
    els.body.classList.remove('chrome-hidden');
    const immersive = Boolean(document.fullscreenElement) || els.body.classList.contains('is-pseudo-fullscreen');
    if (!immersive) return;
    state.chromeTimer = setTimeout(() => {
      if (state.keyboard.isOpen() || !els.leaveDialog.hidden) return;
      els.body.classList.add('chrome-hidden');
    }, 3500);
  }

  async function requestWakeLock() {
    if (!('wakeLock' in navigator) || state.wakeLock) return;
    try {
      state.wakeLock = await navigator.wakeLock.request('screen');
      state.wakeLock.addEventListener('release', () => {
        state.wakeLock = null;
      });
    } catch {
      /* non supporté ou refusé */
    }
  }

  function scheduleViewportReport() {
    clearTimeout(state.viewportTimer);
    state.viewportTimer = setTimeout(() => {
      if (state.ended) return;
      state.peer?.send({ t: 'viewport', ...currentViewport(), mode: state.fitMode }, { dropIfBusy: false });
    }, 220);
  }
}
