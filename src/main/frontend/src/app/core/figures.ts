import { DestroyRef, Directive, ElementRef, effect, inject } from '@angular/core';
import { Locale, Session } from './session';

/** A text whose whole content is a figure: a number with two decimals. */
const FIGURE = /^(\s*)(-?)(\d+)\.(\d{2})(\s*)$/;

/** A figure (as the store and the API write it, `1234.56`) as the locale shows it. */
export function showFigure(text: string, locale: Locale): string {
  const m = FIGURE.exec(text);
  if (!m || locale === 'en') return text;
  const [, lead, sign, whole, cents, trail] = m;
  return `${lead}${sign}${whole.replace(/\B(?=(\d{3})+(?!\d))/g, '.')},${cents}${trail}`;
}

// Shows every figure under the host in the signed-in user's locale. The templates bind figures as
// the API gives them; this rewrites each text node whose whole text is a figure, remembering the
// figure it was given so that it can show it again when the locale changes.
@Directive({ selector: '[appFigures]' })
export class Figures {
  private readonly session = inject(Session);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  /** Each rewritten text node: the figure it was bound to, and the text shown for it. */
  private readonly shown = new WeakMap<Text, { raw: string; text: string }>();

  constructor() {
    const observer = new MutationObserver((records) => {
      for (const r of records) {
        if (r.type === 'characterData') this.show(r.target as Text);
        else r.addedNodes.forEach((n) => this.walk(n));
      }
    });
    observer.observe(this.host, { subtree: true, childList: true, characterData: true });
    inject(DestroyRef).onDestroy(() => observer.disconnect());
    effect(() => {
      this.session.locale();
      this.walk(this.host);
    });
  }

  private walk(root: Node): void {
    if (root.nodeType === Node.TEXT_NODE) {
      this.show(root as Text);
      return;
    }
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let n = walker.nextNode(); n; n = walker.nextNode()) this.show(n as Text);
  }

  private show(node: Text): void {
    const before = this.shown.get(node);
    const raw = before && node.data === before.text ? before.raw : node.data;
    if (!FIGURE.test(raw)) {
      this.shown.delete(node);
      return;
    }
    const text = showFigure(raw, this.session.locale());
    this.shown.set(node, { raw, text });
    if (node.data !== text) node.data = text;
  }
}
