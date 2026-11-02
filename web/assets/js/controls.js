import { clamp, videoContentRect } from './ui.js';

/**
 * Convertit les gestes du récepteur en coordonnées normalisées [0..1]
 * relatives à l'image réellement affichée, puis les transmet au téléphone.
 *
 * Le téléphone effectue la conversion finale vers la résolution de son écran
 * (et inverse son propre cadrage « ajuster » / « remplir »), afin qu'un geste
 * corresponde exactement au pixel touché.
 */
export class TouchController {
  constructor({ layer, video, stage, onGesture, onActivity }) {
    this.layer = layer;
    this.video = video;
    this.stage = stage;
    this.onGesture = onGesture;
    this.onActivity = onActivity;
    this.enabled = false;
    this.pointerId = null;
    this.lastPoint = null;
    this.pendingPoint = null;
    this.lastSentAt = 0;
    this.frame = 0;
    this.moved = false;

    this.onPointerDown = this.onPointerDown.bind(this);
    this.onPointerMove = this.onPointerMove.bind(this);
    this.onPointerUp = this.onPointerUp.bind(this);
    this.onPointerCancel = this.onPointerCancel.bind(this);
    this.onContextMenu = (event) => {
      if (this.enabled) event.preventDefault();
    };
    this.loop = this.loop.bind(this);

    layer.addEventListener('pointerdown', this.onPointerDown);
    layer.addEventListener('pointermove', this.onPointerMove);
    layer.addEventListener('pointerup', this.onPointerUp);
    layer.addEventListener('pointercancel', this.onPointerCancel);
    layer.addEventListener('lostpointercapture', this.onPointerCancel);
    layer.addEventListener('contextmenu', this.onContextMenu);
  }

  setEnabled(enabled) {
    this.enabled = Boolean(enabled);
    this.layer.dataset.active = String(this.enabled);
    this.layer.style.pointerEvents = this.enabled ? 'auto' : 'none';
    if (!this.enabled) this.finish('cancel');
  }

  pointFromEvent(event) {
    const content = videoContentRect(this.video);
    if (!content.width || !content.height) return { x: 0.5, y: 0.5 };
    return {
      x: clamp((event.clientX - content.left) / content.width, 0, 1),
      y: clamp((event.clientY - content.top) / content.height, 0, 1),
    };
  }

  onPointerDown(event) {
    if (!this.enabled) return;
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    if (this.pointerId !== null) return;

    event.preventDefault();
    this.pointerId = event.pointerId;
    this.moved = false;
    this.lastPoint = this.pointFromEvent(event);
    this.pendingPoint = null;
    this.lastSentAt = 0;

    try {
      this.layer.setPointerCapture(event.pointerId);
    } catch {
      /* capture non critique */
    }

    this.emit('down', this.lastPoint);
    this.ripple(event.clientX, event.clientY);
    this.frame = requestAnimationFrame(this.loop);
  }

  onPointerMove(event) {
    if (!this.enabled || event.pointerId !== this.pointerId) return;
    event.preventDefault();
    const point = this.pointFromEvent(event);
    if (!this.moved) {
      const dx = point.x - this.lastPoint.x;
      const dy = point.y - this.lastPoint.y;
      if (Math.hypot(dx, dy) < 0.0015) return;
      this.moved = true;
    }
    this.pendingPoint = point;
  }

  onPointerUp(event) {
    if (event.pointerId !== this.pointerId) return;
    event.preventDefault();
    this.finish('up');
  }

  onPointerCancel(event) {
    if (this.pointerId === null) return;
    if (event && event.pointerId !== undefined && event.pointerId !== this.pointerId) return;
    this.finish('cancel');
  }

  finish(action) {
    cancelAnimationFrame(this.frame);
    this.frame = 0;

    if (this.pointerId !== null) {
      if (this.pendingPoint) {
        this.emit('move', this.pendingPoint);
        this.lastPoint = this.pendingPoint;
        this.pendingPoint = null;
      }
      if (this.lastPoint) this.emit(action, this.lastPoint);
      this.onActivity?.();
    }

    if (this.pointerId !== null) {
      try {
        this.layer.releasePointerCapture(this.pointerId);
      } catch {
        /* pointeur déjà libéré */
      }
    }
    this.pointerId = null;
    this.lastPoint = null;
    this.pendingPoint = null;
  }

  loop() {
    if (this.pointerId === null) return;
    const now = performance.now();
    if (this.pendingPoint && now - this.lastSentAt >= 16) {
      this.emit('move', this.pendingPoint);
      this.lastPoint = this.pendingPoint;
      this.pendingPoint = null;
      this.lastSentAt = now;
    }
    this.frame = requestAnimationFrame(this.loop);
  }

  emit(action, point) {
    this.onGesture?.({ action, x: point.x, y: point.y, ts: Date.now() });
  }

  ripple(clientX, clientY) {
    const rect = this.stage.getBoundingClientRect();
    const node = document.createElement('span');
    node.className = 'ripple';
    node.style.left = `${clientX - rect.left}px`;
    node.style.top = `${clientY - rect.top}px`;
    const host = this.stage.querySelector('.ripples') || this.stage;
    host.appendChild(node);
    node.addEventListener('animationend', () => node.remove(), { once: true });
    setTimeout(() => node.remove(), 900);
  }

  destroy() {
    this.setEnabled(false);
    this.layer.removeEventListener('pointerdown', this.onPointerDown);
    this.layer.removeEventListener('pointermove', this.onPointerMove);
    this.layer.removeEventListener('pointerup', this.onPointerUp);
    this.layer.removeEventListener('pointercancel', this.onPointerCancel);
    this.layer.removeEventListener('lostpointercapture', this.onPointerCancel);
    this.layer.removeEventListener('contextmenu', this.onContextMenu);
  }
}
