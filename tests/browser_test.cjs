#!/usr/bin/env node
/**
 * Test navigateur réel de Potato Remote Web.
 *
 * Un faux téléphone (seconde page Chromium) implémente le protocole de
 * signalisation et de DataChannel attendu par l'application Android :
 * il reçoit l'offre, renvoie une answer avec une piste vidéo canvas au format
 * demandé, puis reçoit les commandes tactiles et clavier du récepteur.
 *
 * Ce test valide donc réellement la page viewer, WebRTC (loopback), l'adaptation
 * du viewport, les gestes, le clavier, le rechargement de session et le départ.
 * Il ne remplace pas un test sur un vrai téléphone Android.
 *
 * Prérequis : serveur PHP local sur POTATO_TEST_URL (défaut 127.0.0.1:8088)
 * et Playwright + Chromium installés dans ~/toolchain/pw.
 */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const PLAYWRIGHT_MODULE = process.env.PLAYWRIGHT_MODULE || path.join(os.homedir(), 'toolchain/pw/node_modules/playwright');
const { chromium } = require(PLAYWRIGHT_MODULE);

const CACHE = path.join(os.homedir(), '.cache/ms-playwright');
function chromiumExecutable() {
  try {
    const build = fs.readdirSync(CACHE).find((entry) => entry.startsWith('chromium-'));
    if (!build) return undefined;
    const candidate = path.join(CACHE, build, 'chrome-linux', 'chrome');
    return fs.existsSync(candidate) ? candidate : undefined;
  } catch {
    return undefined;
  }
}

const BASE = (process.env.POTATO_TEST_URL || 'http://127.0.0.1:8088').replace(/\/$/, '');
const SHOTS = path.join(__dirname, 'screenshots');
const results = [];
let session = null;
let phoneToken = null;

function check(name, condition, detail = '') {
  results.push({ name, ok: Boolean(condition), detail });
  console.log(`${condition ? 'PASS' : 'FAIL'} ${name}${!condition && detail ? `  [${detail}]` : ''}`);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(fn, timeout = 20000, label = 'condition', interval = 120) {
  const started = Date.now();
  let last;
  while (Date.now() - started < timeout) {
    try {
      last = await fn();
      if (last) return last;
    } catch (error) {
      last = error;
    }
    await sleep(interval);
  }
  throw new Error(`Timeout ${label} (${timeout} ms) : ${last}`);
}

async function api(action, body = {}, token = null) {
  const response = await fetch(`${BASE}/api/index.php?action=${action}`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}`, 'X-Potato-Token': token } : {}),
    },
    body: JSON.stringify(body),
  });
  const payload = await response.json();
  if (!payload.ok) {
    const error = new Error(payload.error?.message || `Erreur ${response.status}`);
    error.code = payload.error?.code;
    error.status = response.status;
    throw error;
  }
  return payload.data;
}

/**
 * Code exécuté dans la page du faux téléphone. Aucune variable extérieure :
 * Playwright sérialise la fonction telle quelle.
 */
async function fakePhone({ token, iceServers }) {
  const state = {
    messages: [],
    dc: [],
    offers: [],
    connected: false,
    pcState: '',
    dcOpen: false,
    error: null,
    stopped: false,
    hello: null,
    stream: null,
    pc: null,
  };
  window.__phone = state;

  async function call(action, body) {
    const response = await fetch(`api/index.php?action=${action}`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        'X-Potato-Token': token,
      },
      body: JSON.stringify(body),
    });
    const payload = await response.json();
    if (!payload.ok) throw new Error(`${payload.error?.code}: ${payload.error?.message}`);
    return payload.data;
  }

  let canvas = null;
  let stream = null;
  let drawTimer = null;

  function makeStream(viewport) {
    if (!viewport || !viewport.w || !viewport.h) viewport = { w: 960, h: 670 };
    const aspect = Math.max(0.3, Math.min(4, Number(viewport.aspect) || viewport.w / viewport.h));
    if (stream) {
      try { stream.getTracks().forEach((track) => track.stop()); } catch { /* déjà arrêté */ }
    }
    canvas = document.createElement('canvas');
    const width = 960;
    const height = Math.max(2, Math.round(width / aspect));
    canvas.width = width;
    canvas.height = height;
    canvas.style.cssText = 'position:fixed;left:0;top:0;width:320px;height:auto;opacity:.2;z-index:-1';
    document.body.appendChild(canvas);

    const context = canvas.getContext('2d');
    let tick = 0;
    clearInterval(drawTimer);
    drawTimer = setInterval(() => {
      tick += 1;
      const gradient = context.createLinearGradient(0, 0, width, height);
      gradient.addColorStop(0, `hsl(${tick % 360}, 65%, 42%)`);
      gradient.addColorStop(1, '#21150c');
      context.fillStyle = gradient;
      context.fillRect(0, 0, width, height);
      context.fillStyle = '#fff8ec';
      context.textAlign = 'center';
      context.font = `600 ${Math.round(height / 9)}px sans-serif`;
      context.fillText('POTATO PHONE', width / 2, height / 2);
      context.fillStyle = '#ffd2a1';
      context.font = `${Math.round(height / 18)}px monospace`;
      context.fillText(`${width}×${height}`, width / 2, height / 2 + height / 9);
    }, 40);

    stream = canvas.captureStream(20);
    state.stream = { width, height };
  }

  async function handleOffer(message) {
    const viewport = message.payload?.viewport || null;
    state.offers.push({
      viewerId: message.viewerId,
      viewport,
      mode: message.payload?.mode || null,
    });
    if (state.pc) {
      try { state.pc.close(); } catch { /* déjà fermé */ }
    }
    state.dcOpen = false;
    makeStream(viewport);
    const pc = new RTCPeerConnection({ iceServers });
    state.pc = pc;
    state.pcState = 'new';
    pc.onconnectionstatechange = () => {
      state.pcState = pc.connectionState;
      state.connected = pc.connectionState === 'connected';
    };
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        call('signal.send', {
          type: 'ice',
          to: message.viewerId,
          payload: event.candidate.toJSON(),
        }).catch(() => {});
      }
    };
    pc.ondatachannel = (event) => {
      const channel = event.channel;
      state.dcChannel = channel;
      channel.onopen = () => { state.dcOpen = true; };
      channel.onclose = () => { state.dcOpen = false; };
      channel.onmessage = (messageEvent) => {
        try {
          const data = JSON.parse(messageEvent.data);
          state.dc.push(data);
          if (data.t === 'ping') {
            channel.send(JSON.stringify({ t: 'pong', ts: data.ts }));
          }
          if (data.t === 'hello') {
            state.hello = data;
            channel.send(JSON.stringify({
              t: 'state',
              control: true,
              keyboard: true,
              allow_new_viewers: true,
              max_viewers: 2,
              quality: 'auto',
            }));
          }
        } catch {
          /* message de test illisible */
        }
      };
    };
    await pc.setRemoteDescription({ type: 'offer', sdp: message.payload.sdp });
    pc.addTrack(stream.getVideoTracks()[0], stream);
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    await call('signal.send', {
      type: 'answer',
      to: message.viewerId,
      payload: { sdp: pc.localDescription.sdp },
    });
  }

  async function handle(message) {
    if (message.type === 'offer') await handleOffer(message);
    else if (message.type === 'ice' && state.pc) {
      try { await state.pc.addIceCandidate(message.payload); } catch { /* obsolète */ }
    }
  }

  (async () => {
    let cursor = 0;
    while (!state.stopped) {
      try {
        const data = await call('signal.poll', { cursor, wait: 2 });
        cursor = data.cursor;
        state.cursor = cursor;
        for (const message of data.messages || []) await handle(message);
      } catch (error) {
        state.error = String(error);
        await new Promise((resolve) => setTimeout(resolve, 400));
      }
    }
  })();

  state.stop = () => {
    state.stopped = true;
    clearInterval(drawTimer);
    try { state.dcChannel?.close(); } catch { /* ignoré */ }
    try { state.pc?.close(); } catch { /* ignoré */ }
    try { stream?.getTracks().forEach((track) => track.stop()); } catch { /* ignoré */ }
  };
  state.ready = true;
  return true;
}

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });
  const name = `WebTest${String(Date.now()).slice(-6)}`;
  const password = 'patate-web-123';
  session = await api('session.create', {
    session: name,
    password,
    device: 'Téléphone de test Playwright',
    screen: { width: 1080, height: 2400, rotation: 0, density: 2.75 },
    settings: { control: true, keyboard: true, max_viewers: 2, quality: 'auto' },
  });
  phoneToken = session.phoneToken;
  console.log(`Session de test : ${name} (${session.sessionId})`);

  let browser = null;
  let phoneContext = null;
  let viewerContext = null;
  try {
    browser = await chromium.launch({
      headless: true,
      executablePath: chromiumExecutable(),
      args: ['--no-sandbox', '--disable-dev-shm-usage', '--autoplay-policy=no-user-gesture-required'],
    });
    phoneContext = await browser.newContext({ viewport: { width: 900, height: 900 } });
    await phoneContext.route('**/fake-phone.html', (route) => route.fulfill({
      status: 200,
      contentType: 'text/html; charset=utf-8',
      body: '<!doctype html><html lang="fr"><meta charset="utf-8"><title>Faux téléphone</title><body><h1>Faux téléphone Potato</h1></body></html>',
    }));
    const phone = await phoneContext.newPage();
    phone.on('pageerror', (error) => console.log('[faux téléphone] pageerror:', String(error)));
    await phone.goto(`${BASE}/fake-phone.html`, { waitUntil: 'domcontentloaded' });
    await phone.evaluate(fakePhone, { token: phoneToken, iceServers: session.iceServers || [] });
    await waitFor(() => phone.evaluate(() => Boolean(window.__phone?.ready)), 10000, 'faux téléphone prêt');
    check('faux téléphone initialisé', true);

    viewerContext = await browser.newContext({
      viewport: { width: 1194, height: 834 },
      deviceScaleFactor: 2,
      hasTouch: true,
      isMobile: false,
    });
    const viewer = await viewerContext.newPage();
    const consoleErrors = [];
    const pageErrors = [];
    let logoStatus = null;
    viewer.on('console', (message) => {
      if (message.type() === 'error') consoleErrors.push(message.text());
    });
    viewer.on('pageerror', (error) => pageErrors.push(String(error)));
    viewer.on('response', (response) => {
      if (response.url().includes('/assets/img/logo-badge.svg')) logoStatus = response.status();
    });

    await viewer.goto(`${BASE}/index.html`, { waitUntil: 'domcontentloaded' });
    await viewer.waitForFunction(
      () => document.querySelector('#server-state-text')?.textContent === 'Serveur en ligne',
      null,
      { timeout: 15000 },
    );
    await viewer.screenshot({ path: path.join(SHOTS, 'login-ipad.png') });
    check('page de connexion chargée', (await viewer.title()).includes('Potato Remote'));
    check('logo Potato Remote servi', logoStatus === 200, String(logoStatus));
    check('note TURN visible sans TURN', await viewer.evaluate(() => !document.querySelector('#turn-note').hidden));

    await viewer.click('#toggle-password');
    check('bouton afficher le mot de passe', (await viewer.getAttribute('#password', 'type')) === 'text');
    await viewer.click('#toggle-password');
    check('bouton masquer le mot de passe', (await viewer.getAttribute('#password', 'type')) === 'password');

    await viewer.fill('#session', name);
    await viewer.fill('#password', 'mauvais-mot-de-passe');
    await viewer.click('#submit');
    await viewer.waitForFunction(
      () => {
        const box = document.querySelector('#form-error');
        return box && !box.hidden && box.textContent.trim().length > 0;
      },
      null,
      { timeout: 15000 },
    );
    const errorText = (await viewer.textContent('#form-error')) || '';
    check('erreur de mot de passe compréhensible', /incorrect/i.test(errorText), errorText);

    await viewer.fill('#password', password);
    await viewer.click('#submit');
    await viewer.waitForURL('**/viewer.html', { timeout: 20000 });
    await viewer.waitForFunction(
      () => document.querySelector('#connection-text')?.textContent === 'Connecté',
      null,
      { timeout: 35000 },
    );
    await phone.waitForFunction(
      () => window.__phone?.offers?.length >= 1 && window.__phone?.dcOpen === true,
      null,
      { timeout: 25000 },
    );
    await viewer.waitForFunction(
      () => {
        const video = document.querySelector('#video');
        return video && video.videoWidth > 0 && video.videoHeight > 0;
      },
      null,
      { timeout: 25000 },
    );
    await sleep(1200);
    const videoInfo = await viewer.evaluate(() => {
      const video = document.querySelector('#video');
      const stage = document.querySelector('#stage').getBoundingClientRect();
      return {
        width: video.videoWidth,
        height: video.videoHeight,
        currentTime: video.currentTime,
        paused: video.paused,
        stageWidth: stage.width,
        stageHeight: stage.height,
      };
    });
    check('vidéo WebRTC reçue', videoInfo.width > 0 && videoInfo.height > 0 && videoInfo.currentTime > 0, JSON.stringify(videoInfo));
    const aspectDiff = Math.abs((videoInfo.width / videoInfo.height) - (videoInfo.stageWidth / videoInfo.stageHeight));
    check('aucun étirement : format vidéo = cadre', aspectDiff < 0.06, `diff=${aspectDiff.toFixed(3)}`);

    const firstOffer = await phone.evaluate(() => window.__phone.offers[0]);
    check('offre avec viewport iPad', Boolean(firstOffer?.viewport?.w >= 1000), JSON.stringify(firstOffer));
    check('mode de cadrage initial fit', firstOffer?.mode === 'fit', String(firstOffer?.mode));
    const hello = await phone.evaluate(() => window.__phone.hello);
    check('DataChannel ouvert et hello reçu', Boolean(hello), JSON.stringify(hello));

    await viewer.screenshot({ path: path.join(SHOTS, 'viewer-connected-ipad.png') });

    await viewer.click('#btn-control');
    await waitFor(
      () => phone.evaluate(() => window.__phone.dc.some((message) => message.t === 'control' && message.active === true)),
      10000,
      'contrôle activé',
    );
    check('bouton contrôle actif', (await viewer.getAttribute('#btn-control', 'aria-pressed')) === 'true');

    const stage = await viewer.locator('#stage').boundingBox();
    await viewer.mouse.move(stage.x + stage.width * 0.35, stage.y + stage.height * 0.5);
    await viewer.mouse.down();
    await viewer.mouse.move(stage.x + stage.width * 0.46, stage.y + stage.height * 0.62, { steps: 8 });
    await viewer.mouse.up();
    await waitFor(
      () => phone.evaluate(() => window.__phone.dc.filter((message) => message.t === 'touch').length >= 3),
      10000,
      'gestes reçus',
    );
    const touches = await phone.evaluate(() => window.__phone.dc.filter((message) => message.t === 'touch'));
    check('geste down/move/up transmis', ['down', 'move', 'up'].every((action) => touches.some((message) => message.a === action)), JSON.stringify(touches));
    check('coordonnées tactiles normalisées', touches.every((message) => message.x >= 0 && message.x <= 1 && message.y >= 0 && message.y <= 1), JSON.stringify(touches));

    await viewer.click('#btn-keyboard');
    await viewer.waitForSelector('#keyboard-panel:not([hidden])', { timeout: 5000 });
    await viewer.click('[data-char="é"]');
    await viewer.keyboard.type('àç');
    await viewer.waitForTimeout(300);
    await viewer.keyboard.press('Enter');
    await viewer.waitForTimeout(200);
    await viewer.keyboard.press('Backspace');
    await viewer.waitForTimeout(300);
    await viewer.click('#kb-send');
    await sleep(500);
    await viewer.screenshot({ path: path.join(SHOTS, 'keyboard-ipad.png') });

    const keyboardMessages = await phone.evaluate(() => window.__phone.dc.filter((message) => message.t === 'text' || message.t === 'key'));
    const textPayloads = keyboardMessages.filter((message) => message.t === 'text');
    const allText = textPayloads.map((message) => message.text || '').join('|');
    check('accent é transmis', allText.includes('é'), allText);
    check('accents à et ç transmis', allText.includes('àç'), allText);
    check('touche Entrée transmise', keyboardMessages.some((message) => message.t === 'key' && message.key === 'enter'), JSON.stringify(keyboardMessages));
    check('retour arrière transmis', textPayloads.some((message) => message.op === 'backspace'), JSON.stringify(textPayloads));
    check('envoi complet (set) transmis', textPayloads.some((message) => message.op === 'set'), JSON.stringify(textPayloads));
    check('panneau clavier ouvert', await viewer.evaluate(() => !document.querySelector('#keyboard-panel').hidden));

    await viewer.click('#kb-close');
    await viewer.waitForTimeout(400);
    check('panneau clavier refermé', await viewer.evaluate(() => document.querySelector('#keyboard-panel').hidden));

    const dcLength = await phone.evaluate(() => window.__phone.dc.length);
    await viewer.setViewportSize({ width: 834, height: 1194 });
    await sleep(900);
    const viewportMessages = await phone.evaluate(
      (from) => window.__phone.dc.slice(from).filter((message) => message.t === 'viewport'),
      dcLength,
    );
    check('changement de format portrait transmis', viewportMessages.length > 0 && viewportMessages.at(-1).aspect < 1, JSON.stringify(viewportMessages));
    await viewer.screenshot({ path: path.join(SHOTS, 'viewer-portrait-ipad.png') });

    await viewer.click('#btn-fit');
    await waitFor(
      () => phone.evaluate(() => window.__phone.dc.some((message) => message.t === 'viewport' && message.mode === 'fill')),
      10000,
      'mode remplir',
    );
    check('bouton cadrage passe en remplir', (await viewer.textContent('#fit-label'))?.trim() === 'Remplir');

    const offersBeforeReload = await phone.evaluate(() => window.__phone.offers.length);
    await viewer.reload({ waitUntil: 'domcontentloaded' });
    await viewer.waitForFunction(
      () => document.querySelector('#connection-text')?.textContent === 'Connecté',
      null,
      { timeout: 35000 },
    );
    await viewer.waitForFunction(
      () => {
        const video = document.querySelector('#video');
        return video && video.videoWidth > 0;
      },
      null,
      { timeout: 20000 },
    );
    await waitFor(
      () => phone.evaluate((count) => window.__phone.offers.length > count, offersBeforeReload),
      15000,
      'nouvelle offre après rechargement',
    );
    check('rechargement conserve la session', viewer.url().includes('/viewer.html'), viewer.url());
    check('reconnexion vidéo après rechargement', await viewer.evaluate(() => document.querySelector('#video').videoWidth > 0));

    await viewer.click('#btn-leave');
    await viewer.waitForSelector('#leave-dialog:not([hidden])', { timeout: 5000 });
    await viewer.click('#leave-confirm');
    await viewer.waitForURL('**/index.html', { timeout: 10000 });
    check('quitter la session ramène à l’accueil', viewer.url().includes('/index.html'), viewer.url());

    await waitFor(async () => {
      const status = await api('phone.status', { screen: { width: 1080, height: 2400 } }, phoneToken);
      return status.viewers.length === 0;
    }, 10000, 'spectateur retiré');
    check('spectateur retiré du serveur après départ', true);

    check('aucune erreur JavaScript sur la page récepteur', pageErrors.length === 0, pageErrors.join(' | '));
    const meaningfulConsoleErrors = consoleErrors.filter((text) => !/favicon|WebRTC: ICE failed/i.test(text));
    check('aucune erreur console inattendue', meaningfulConsoleErrors.length === 0, meaningfulConsoleErrors.join(' | '));

    await phone.evaluate(() => window.__phone.stop());
  } finally {
    try {
      await api('phone.stop', {}, phoneToken);
    } catch (error) {
      console.log('[cleanup] arrêt de session :', error.message);
    }
    if (viewerContext) await viewerContext.close().catch(() => {});
    if (phoneContext) await phoneContext.close().catch(() => {});
    if (browser) await browser.close().catch(() => {});
  }

  const failed = results.filter((result) => !result.ok);
  console.log(`\n${results.length - failed.length}/${results.length} tests navigateur réussis`);
  if (failed.length) {
    console.log('Échecs :');
    for (const result of failed) console.log(`  - ${result.name} [${result.detail}]`);
  }
  return failed.length ? 1 : 0;
}

main()
  .then((code) => process.exit(code))
  .catch((error) => {
    console.error('ERREUR FATALE:', error);
    process.exit(2);
  });
