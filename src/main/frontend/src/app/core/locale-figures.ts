import { DestroyRef, Directive, ElementRef, effect, inject } from '@angular/core';
import { UserLocale } from './user-locale';

/** A text whose whole content (outer spaces aside) is a figure: a number with two decimals. */
const FIGURE = /^(\s*)(-?)(\d+)\.(\d{2})(\s*)$/;

/** Elements whose text is not shown as a figure cell. */
const SKIP = new Set(['SCRIPT', 'STYLE', 'TEXTAREA']);

/** A figure as the locale shows it: de groups by threes with dots and puts a comma before the decimals;
 *  null when the locale shows it as it is. */
function localized(text: string, locale: string): string | null {
  if (locale !== 'de') return null;
  const m = FIGURE.exec(text);
  if (!m) return null;
  const [, lead, sign, whole, decimals, trail] = m;
  return `${lead}${sign}${whole.replace(/\B(?=(\d{3})+$)/g, '.')},${decimals}${trail}`;
}

// Shows every figure under its host in the signed-in user's locale. Every cell whose whole text is a
// number with two decimals is rewritten as it is rendered, so no template has to format its figures;
// switching back restores the text the template rendered.
@Directive({ selector: '[appLocaleFigures]' })
export class LocaleFigures {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly userLocale = inject(UserLocale);
  /** The text each rewritten node had as rendered, and what it was rewritten to. */
  private readonly rewritten = new WeakMap<Text, { shown: string; rendered: string }>();

  constructor() {
    const observer = new MutationObserver((records) => {
      const locale = this.userLocale.locale();
      for (const r of records) {
        if (r.type === 'characterData') this.show(r.target, locale);
        else r.addedNodes.forEach((n) => this.walk(n, locale));
      }
    });
    observer.observe(this.host, { childList: true, characterData: true, subtree: true });
    inject(DestroyRef).onDestroy(() => observer.disconnect());
    effect(() => this.walk(this.host, this.userLocale.locale()));
  }

  private walk(node: Node, locale: string): void {
    if (node.nodeType === Node.TEXT_NODE) {
      this.show(node, locale);
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE || SKIP.has(node.nodeName)) return;
    const texts = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
    for (let t = texts.nextNode(); t !== null; t = texts.nextNode()) this.show(t, locale);
  }

  private show(node: Node, locale: string): void {
    if (!(node instanceof Text) || (node.parentNode !== null && SKIP.has(node.parentNode.nodeName))) return;
    const text = node.nodeValue ?? '';
    const was = this.rewritten.get(node);
    // Shown as rewritten: keep it, or restore what was rendered when the locale no longer rewrites it.
    if (was !== undefined && was.shown === text) {
      const shown = localized(was.rendered, locale) ?? was.rendered;
      if (shown !== text) this.set(node, shown, was.rendered);
      return;
    }
    const shown = localized(text, locale);
    if (shown !== null) this.set(node, shown, text);
  }

  private set(node: Text, shown: string, rendered: string): void {
    this.rewritten.set(node, { shown, rendered });
    node.nodeValue = shown;
  }
}
