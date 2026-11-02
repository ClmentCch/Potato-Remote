import { toast } from './ui.js';

/**
 * Clavier distant.
 *
 * Le champ local est un brouillon : en mode « Live », seules les différences
 * (frappe, retour arrière) sont envoyées, ce qui garde la saisie fluide.
 * Les éditions complexes (collage, remplacement) utilisent l'opération « set »,
 * appliquée par le téléphone via ACTION_SET_TEXT sur le champ actif.
 */
export class RemoteKeyboard {
  constructor({ sheet, textarea, send, onOpenChange }) {
    this.sheet = sheet;
    this.textarea = textarea;
    this.send = send;
    this.onOpenChange = onOpenChange;
    this.live = true;
    this.allowed = true;
    this.open = false;
    this.lastValue = '';
    this.composing = false;
    this.status = sheet.querySelector('#kb-status');
    this.hint = sheet.querySelector('#kb-hint');
    this.liveToggle = sheet.querySelector('#kb-live');
    this.controls = Array.from(sheet.querySelectorAll('button, textarea, input'));

    this.bind();
  }

  bind() {
    this.textarea.addEventListener('input', () => {
      if (this.composing) return;
      this.handleInput();
    });
    this.textarea.addEventListener('compositionstart', () => {
      this.composing = true;
    });
    this.textarea.addEventListener('compositionend', () => {
      this.composing = false;
      this.handleInput();
    });
    this.textarea.addEventListener('keydown', (event) => this.onKeyDown(event));

    this.liveToggle.addEventListener('change', () => {
      this.live = this.liveToggle.checked;
      this.lastValue = this.live ? this.textarea.value : '';
      this.setStatus(this.live ? 'Saisie en direct activée.' : 'Mode manuel : utilisez « Envoyer le texte ».');
    });

    this.sheet.querySelector('#kb-close').addEventListener('click', () => this.close());

    this.sheet.querySelectorAll('[data-key]').forEach((button) => {
      button.addEventListener('click', () => {
        this.dispatchKey(button.dataset.key);
      });
    });

    this.sheet.querySelectorAll('[data-char]').forEach((button) => {
      button.addEventListener('click', () => {
        this.insertAtCursor(button.dataset.char);
      });
    });

    this.sheet.querySelectorAll('.kbd-key[data-key="backspace"]').forEach((button) => {
      button.addEventListener('dblclick', (event) => event.preventDefault());
    });

    this.sheet.querySelector('#kb-paste').addEventListener('click', () => this.paste());
    this.sheet.querySelector('#kb-clear').addEventListener('click', () => this.clearRemote());
    this.sheet.querySelector('#kb-send').addEventListener('click', () => this.sendAll());
  }

  setAllowed(allowed, reason = '') {
    this.allowed = Boolean(allowed);
    this.sheet.dataset.allowed = String(this.allowed);
    this.controls.forEach((element) => {
      element.disabled = !this.allowed;
    });
    if (this.hint) {
      this.hint.textContent = this.allowed
        ? 'Le texte est envoyé au champ actif du téléphone.'
        : (reason || 'Clavier distant désactivé sur le téléphone.');
    }
    if (!this.allowed && this.open) this.close();
  }

  show() {
    if (!this.allowed) {
      toast(this.hint?.textContent || 'Clavier distant désactivé sur le téléphone.', { level: 'error' });
      return false;
    }
    if (this.open) return true;
    this.sheet.hidden = false;
    requestAnimationFrame(() => this.sheet.classList.add('is-open'));
    this.open = true;
    this.lastValue = this.live ? this.textarea.value : '';
    this.send?.({ t: 'keyboard', active: true });
    this.onOpenChange?.(true);
    setTimeout(() => {
      try {
        this.textarea.focus({ preventScroll: true });
      } catch {
        this.textarea.focus();
      }
    }, 120);
    return true;
  }

  hide() {
    if (!this.open) return;
    this.open = false;
    this.sheet.classList.remove('is-open');
    this.send?.({ t: 'keyboard', active: false });
    this.onOpenChange?.(false);
    this.textarea.blur();
    setTimeout(() => {
      if (!this.open) this.sheet.hidden = true;
    }, 240);
  }

  toggle() {
    return this.open ? (this.hide(), false) : this.show();
  }

  close() {
    this.hide();
  }

  isOpen() {
    return this.open;
  }

  onKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.dispatchKey('enter');
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      this.hide();
      return;
    }
    if (event.key === 'Backspace') {
      const value = this.textarea.value;
      const start = this.textarea.selectionStart ?? 0;
      const end = this.textarea.selectionEnd ?? 0;
      if (value.length === 0 || (start === 0 && end === 0)) {
        event.preventDefault();
        this.backspace();
      }
    }
  }

  dispatchKey(key) {
    if (!this.allowed) return;
    if (key === 'backspace') {
      this.backspace();
      return;
    }
    const accepted = this.send?.({ t: 'key', key });
    if (!accepted) {
      this.setStatus('Clavier indisponible : le téléphone n\'est pas connecté.', 'error');
      return;
    }
    const labels = { enter: 'Entrée envoyée', escape: 'Touche Échap envoyée', back: 'Retour envoyé', home: 'Accueil envoyé' };
    this.setStatus(labels[key] || 'Touche envoyée', 'ok');
  }

  backspace() {
    if (!this.allowed) return;
    const accepted = this.send?.({ t: 'text', op: 'backspace', count: 1 });
    this.setStatus(accepted ? 'Retour arrière envoyé' : 'Téléphone non connecté.', accepted ? 'ok' : 'error');
  }

  handleInput() {
    const next = this.textarea.value;
    if (!this.live) {
      this.lastValue = next;
      return;
    }
    const previous = this.lastValue || '';
    if (next === previous) return;

    let payload = null;
    if (next.length > previous.length && next.startsWith(previous)) {
      payload = { t: 'text', op: 'insert', text: next.slice(previous.length) };
    } else if (next.length < previous.length && previous.startsWith(next)) {
      payload = { t: 'text', op: 'backspace', count: previous.length - next.length };
    } else {
      payload = { t: 'text', op: 'set', text: next };
    }

    const accepted = this.send?.(payload);
    this.lastValue = next;
    this.setStatus(accepted ? 'Texte transmis.' : 'Téléphone non connecté : texte conservé localement.', accepted ? 'ok' : 'error');
  }

  insertAtCursor(text) {
    if (!this.allowed) return;
    const start = this.textarea.selectionStart ?? this.textarea.value.length;
    const end = this.textarea.selectionEnd ?? start;
    this.textarea.setRangeText(text, start, end, 'end');
    this.handleInput();
    this.textarea.focus();
  }

  async paste() {
    if (!this.allowed) return;
    try {
      const text = await navigator.clipboard.readText();
      if (!text) {
        this.setStatus('Presse-papiers vide.', 'error');
        return;
      }
      this.insertAtCursor(text);
      this.setStatus('Texte collé et transmis.', 'ok');
    } catch {
      this.setStatus('Accès au presse-papiers refusé par le navigateur.', 'error');
      toast('Le navigateur bloque la lecture du presse-papiers. Collez dans le champ ci-dessus.', { level: 'error' });
    }
  }

  clearRemote() {
    if (!this.allowed) return;
    this.textarea.value = '';
    this.lastValue = '';
    const accepted = this.send?.({ t: 'text', op: 'set', text: '' });
    this.setStatus(accepted ? 'Champ distant vidé.' : 'Téléphone non connecté.', accepted ? 'ok' : 'error');
    this.textarea.focus();
  }

  sendAll() {
    if (!this.allowed) return;
    const text = this.textarea.value;
    if (!text) {
      this.setStatus('Rien à envoyer.', 'error');
      return;
    }
    const accepted = this.send?.({ t: 'text', op: 'set', text });
    this.lastValue = text;
    this.setStatus(accepted ? 'Texte complet transmis.' : 'Téléphone non connecté.', accepted ? 'ok' : 'error');
  }

  setStatus(message, level = 'info') {
    if (!this.status) return;
    this.status.textContent = message;
    this.status.dataset.level = level;
  }
}
