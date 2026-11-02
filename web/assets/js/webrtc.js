/**
 * Négociation WebRTC côté récepteur.
 *
 * - Le spectateur est l'offreur : une piste vidéo en réception + un DataChannel
 *   de contrôle. Le téléphone Android crée une PeerConnection indépendante par
 *   spectateur, ce qui permet une adaptation du cadrage propre à chacun.
 * - Le SDP est échangé via l'API HTTP (long-polling), compatible o2switch.
 * - Le média et les commandes ne passent jamais par PHP.
 */
export class PotatoPeer extends EventTarget {
  constructor({ iceServers = [] } = {}) {
    super();
    this.iceServers = Array.isArray(iceServers) ? iceServers : [];
    this.pc = null;
    this.dc = null;
    this.stream = null;
    this.closed = false;
    this.remoteReady = false;
    this.pendingCandidates = [];
    this.queue = [];
    this.pingTimer = null;
    this.rtt = null;
    this._lastBytes = 0;
    this._lastBytesAt = 0;
  }

  emit(type, detail = {}) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }

  /** Crée la PeerConnection et renvoie l'offre SDP à transmettre au téléphone. */
  async start() {
    this.close();
    this.closed = false;
    this.remoteReady = false;
    this.pendingCandidates = [];

    const peer = new RTCPeerConnection({
      iceServers: this.iceServers,
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require',
      iceCandidatePoolSize: 2,
    });
    this.pc = peer;

    peer.addEventListener('icecandidate', (event) => {
      if (event.candidate && !this.closed) {
        this.emit('candidate', { candidate: event.candidate.toJSON() });
      }
    });

    peer.addEventListener('track', (event) => {
      this.stream = event.streams[0] || new MediaStream([event.track]);
      this.emit('stream', { stream: this.stream });
    });

    peer.addEventListener('connectionstatechange', () => {
      if (!this.pc || this.closed) return;
      this.emit('connection', { state: this.pc.connectionState });
    });

    peer.addEventListener('iceconnectionstatechange', () => {
      if (!this.pc || this.closed) return;
      this.emit('ice-state', { state: this.pc.iceConnectionState });
    });

    // La piste vidéo d'abord : le SDP reste simple à apparier côté Android.
    const transceiver = peer.addTransceiver('video', { direction: 'recvonly' });
    preferH264(transceiver);

    try {
      this.dc = peer.createDataChannel('potato-ctrl', { ordered: true });
      this.dc.binaryType = 'arraybuffer';
      this.dc.addEventListener('open', () => {
        this.emit('channel-open');
        this.flushQueue();
      });
      this.dc.addEventListener('close', () => this.emit('channel-close'));
      this.dc.addEventListener('error', () => this.emit('channel-close'));
      this.dc.addEventListener('message', (event) => {
        if (typeof event.data !== 'string' || event.data.length > 65536) return;
        try {
          this.emit('message', { data: JSON.parse(event.data) });
        } catch {
          /* message ignoré */
        }
      });
    } catch (error) {
      this.emit('error', { error });
    }

    const offer = await peer.createOffer();
    await peer.setLocalDescription(offer);
    await this.waitForIce(1200);

    return { sdp: peer.localDescription.sdp };
  }

  waitForIce(maxMs) {
    if (!this.pc || this.pc.iceGatheringState === 'complete') return Promise.resolve();
    return new Promise((resolve) => {
      const finish = () => {
        clearTimeout(timer);
        this.pc?.removeEventListener('icegatheringstatechange', onChange);
        resolve();
      };
      const onChange = () => {
        if (this.pc?.iceGatheringState === 'complete') finish();
      };
      const timer = setTimeout(finish, maxMs);
      this.pc.addEventListener('icegatheringstatechange', onChange);
    });
  }

  async acceptAnswer(sdp) {
    if (!this.pc || this.closed || this.remoteReady) return;
    await this.pc.setRemoteDescription({ type: 'answer', sdp });
    this.remoteReady = true;
    const candidates = this.pendingCandidates.splice(0);
    for (const candidate of candidates) {
      try {
        await this.pc.addIceCandidate(candidate);
      } catch {
        /* candidat obsolète */
      }
    }
  }

  async addIce(payload) {
    if (!payload || typeof payload.candidate !== 'string') return;
    const candidate = {
      candidate: payload.candidate,
      sdpMid: payload.sdpMid ?? '0',
      sdpMLineIndex: Number.isInteger(payload.sdpMLineIndex) ? payload.sdpMLineIndex : 0,
    };
    if (!this.pc || !this.remoteReady) {
      if (this.pendingCandidates.length < 60) this.pendingCandidates.push(candidate);
      return;
    }
    try {
      await this.pc.addIceCandidate(candidate);
    } catch {
      /* candidat obsolète ou déjà appliqué */
    }
  }

  /** Envoie une commande sur le DataChannel (ordre garanti). */
  send(payload, { dropIfBusy = true } = {}) {
    if (!this.dc || this.dc.readyState !== 'open') {
      if (!dropIfBusy) {
        this.queue.push(payload);
        if (this.queue.length > 40) this.queue.shift();
      }
      return false;
    }
    if (dropIfBusy && this.dc.bufferedAmount > 256 * 1024) return false;
    try {
      this.dc.send(JSON.stringify(payload));
      return true;
    } catch {
      return false;
    }
  }

  flushQueue() {
    const pending = this.queue.splice(0);
    for (const payload of pending) this.send(payload);
  }

  startPing() {
    this.stopPing();
    this.pingTimer = setInterval(() => {
      if (this.dc?.readyState === 'open') {
        this.send({ t: 'ping', ts: performance.now() }, { dropIfBusy: false });
      }
    }, 2000);
  }

  stopPing() {
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.pingTimer = null;
  }

  async getStats() {
    if (!this.pc || this.closed) return null;
    let report;
    try {
      report = await this.pc.getStats();
    } catch {
      return null;
    }

    let inbound = null;
    let pair = null;
    report.forEach((entry) => {
      if (entry.type === 'inbound-rtp' && entry.kind === 'video') inbound = entry;
      if (entry.type === 'candidate-pair' && (entry.state === 'succeeded' || entry.nominated) && entry.currentRoundTripTime) {
        pair = entry;
      }
    });
    if (!inbound) return null;

    const now = performance.now();
    let kbps = 0;
    if (this._lastBytes && this._lastBytesAt && now > this._lastBytesAt) {
      kbps = Math.max(0, Math.round(((inbound.bytesReceived - this._lastBytes) * 8) / (now - this._lastBytesAt)));
    }
    this._lastBytes = inbound.bytesReceived || 0;
    this._lastBytesAt = now;

    const received = inbound.packetsReceived || 0;
    const lost = inbound.packetsLost || 0;
    const lossRatio = received + lost > 0 ? Math.round((lost / (received + lost)) * 100) : 0;
    const rtt = pair && pair.currentRoundTripTime ? Math.round(pair.currentRoundTripTime * 1000) : this.rtt;

    return {
      width: inbound.frameWidth || 0,
      height: inbound.frameHeight || 0,
      fps: inbound.framesPerSecond || 0,
      kbps,
      jitter: inbound.jitter ? Math.round(inbound.jitter * 1000) : 0,
      lost: Math.min(100, lossRatio),
      rtt: rtt || null,
      bytes: inbound.bytesReceived || 0,
    };
  }

  close() {
    this.closed = true;
    this.stopPing();
    this.remoteReady = false;
    this.queue = [];
    this.pendingCandidates = [];
    this._lastBytes = 0;
    this._lastBytesAt = 0;

    if (this.dc) {
      try {
        this.dc.close();
      } catch {
        /* déjà fermé */
      }
      this.dc = null;
    }
    if (this.pc) {
      try {
        this.pc.getSenders().forEach((sender) => {
          try {
            sender.track?.stop();
          } catch {
            /* ignoré */
          }
        });
        this.pc.close();
      } catch {
        /* déjà fermé */
      }
      this.pc = null;
    }
    this.stream = null;
  }
}

/** Priorité H.264 (décodage matériel iPad/Android), puis VP8/VP9. */
function preferH264(transceiver) {
  if (typeof transceiver.setCodecPreferences !== 'function') return;
  const capabilities = window.RTCRtpReceiver && RTCRtpReceiver.getCapabilities
    ? RTCRtpReceiver.getCapabilities('video')
    : null;
  if (!capabilities || !Array.isArray(capabilities.codecs)) return;

  const rank = (codec) => {
    const mime = String(codec.mimeType || '').toLowerCase();
    if (mime === 'video/h264') return 0;
    if (mime === 'video/vp8') return 1;
    if (mime === 'video/vp9') return 2;
    if (mime === 'video/av1') return 3;
    return 4;
  };

  try {
    transceiver.setCodecPreferences([...capabilities.codecs].sort((a, b) => rank(a) - rank(b)));
  } catch {
    /* certains navigateurs refusent : la négociation par défaut reste valable */
  }
}
