(function () {
  var FU = window.FU;
  var TK = {};
  FU.tokens.tokens.forEach(function (t) { TK[t.name] = t; });
  FU.config = { types: 'PDF, JPG, PNG, MP4', exts: ['pdf', 'jpg', 'jpeg', 'png', 'mp4'], maxSize: 250 * 1048576, maxSizeLabel: '250 MB', maxFiles: 20 };

  function val(t, v, mode, dens) {
    var x = t.values[v];
    if (typeof x === 'string') return x;
    if (x.light !== undefined) return x[mode];
    return x[dens];
  }
  function lum(hex) {
    if (!hex || hex[0] !== '#' || hex.length < 7) return null;
    var c = [1, 3, 5].map(function (i) { var u = parseInt(hex.substr(i, 2), 16) / 255; return u <= 0.03928 ? u / 12.92 : Math.pow((u + 0.055) / 1.055, 2.4); });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }
  function contrast(a, b) { var x = lum(a), y = lum(b); if (x == null || y == null) return 21; return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }
  FU.contrast = contrast;

  FU.T = function (key, p) {
    var s = FU.text.strings[key];
    var txt = s ? s.text : key;
    if (p) Object.keys(p).forEach(function (k) { txt = txt.split('{' + k + '}').join(p[k]); });
    return txt;
  };
  var nf1 = new Intl.NumberFormat('es', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  var nf0 = new Intl.NumberFormat('es', { maximumFractionDigits: 0 });
  FU.fmtSize = function (b) {
    if (b == null) return '—';
    if (b < 1048576) return FU.T('unit.kb', { n: nf0.format(Math.max(1, Math.round(b / 1024))) });
    if (b < 1073741824) { var v = b / 1048576; return FU.T('unit.mb', { n: v < 100 ? nf1.format(v) : nf0.format(v) }); }
    return FU.T('unit.gb', { n: nf1.format(b / 1073741824) });
  };
  FU.fmtMbps = function (m) { return FU.T('unit.mbps', { n: nf1.format(m) }); };
  FU.fmtPct = function (n) { return FU.T('unit.percent', { n: Math.floor(n) }).replace(' %', '\u202F%'); };
  FU.fmtDate = function (iso) { var p = iso.split('-'); return p[2] + '/' + p[1] + '/' + p[0]; };

  FU.theme = function (o) {
    var v = o.variant, mode = o.mode, d = o.density, s = {};
    FU.tokens.tokens.forEach(function (t) { s[t.css] = val(t, v, mode, d); });
    var surface = s['--fu-color-surface'];
    if (o.primary) {
      var P = o.primary;
      s['--fu-color-primary'] = P;
      s['--fu-color-primary-hover'] = 'color-mix(in oklch, ' + P + ' 85%, black)';
      s['--fu-color-primary-pressed'] = 'color-mix(in oklch, ' + P + ' 60%, black)';
      s['--fu-color-on-primary'] = contrast(P, '#FFFFFF') >= 4.5 ? '#FFFFFF' : '#000000';
      s['--fu-color-primary-text'] = mode === 'light'
        ? (contrast(P, surface) >= 4.5 ? P : 'color-mix(in oklch, ' + P + ' 65%, black)')
        : 'color-mix(in oklch, ' + P + ' 65%, white)';
      s['--fu-color-primary-subtle'] = 'color-mix(in oklch, ' + P + (mode === 'light' ? ' 8%, ' : ' 22%, ') + surface + ')';
      if (v === 'moderno') s['--fu-color-focus'] = s['--fu-color-primary-text'];
    }
    if (o.font) { s['--fu-font-family'] = o.font; if (v !== 'institucional') s['--fu-font-family-heading'] = o.font; }
    if (o.radius != null) {
      var r = o.radius;
      s['--fu-radius-md'] = r + 'px';
      s['--fu-radius-sm'] = Math.round(r / 2) + 'px';
      s['--fu-radius-lg'] = (v === 'institucional' ? r : r * 2) + 'px';
      if (v !== 'moderno') s['--fu-radius-chip'] = r + 'px';
    }
    var W = o.width, narrow = W < 480, cards = W < 600, med = W >= 600 && W < 900, overlay = W < 900;
    var M = v === 'moderno', I = v === 'institucional', C = d === 'compact';
    var bw = 'var(--fu-border-width) solid var(--fu-color-border)', none = '0 solid transparent';
    var pick = function (f, i, m) { var a = M ? m : I ? i : f; return (C ? a[0] : a[1]) + 'px'; };
    var dzRow = I && !narrow;
    Object.assign(s, {
      fontFamily: 'var(--fu-font-family)', fontSize: 'var(--fu-font-size-body)', lineHeight: 'var(--fu-font-line-body)',
      color: 'var(--fu-color-text)', background: 'var(--fu-color-bg)', colorScheme: mode,
      '--x-pad': narrow ? '12px' : '16px',
      '--x-wide': narrow ? 'none' : 'inline',
      '--x-act': narrow ? '44px' : 'var(--fu-size-control)',
      '--x-dz-dir': dzRow ? 'row' : 'column',
      '--x-dz-ta': dzRow ? 'left' : 'center',
      '--x-dz-items': dzRow ? 'flex-start' : 'center',
      '--x-dz-or': dzRow ? 'none' : 'block',
      '--x-dz-pad': pick([16, 24], [12, 16], [20, 32]),
      '--x-dz-gap': dzRow ? '16px' : pick([8, 8], [4, 4], [12, 12]),
      '--x-dz-ibox': M ? '48px' : 'auto',
      '--x-dz-ibg': M ? 'var(--fu-color-primary-subtle)' : 'transparent',
      '--x-dz-icol': M ? 'var(--fu-color-primary-text)' : 'var(--fu-color-text-secondary)',
      '--x-dz-isz': M ? '32px' : I ? '20px' : '24px',
      '--x-browse-bg': M ? 'var(--fu-color-primary)' : 'var(--fu-color-surface)',
      '--x-browse-fg': M ? 'var(--fu-color-on-primary)' : 'var(--fu-color-text)',
      '--x-browse-bd': M ? 'transparent' : (I ? 'var(--fu-color-border-strong)' : 'var(--fu-color-border)'),
      '--x-browse-hover': M ? 'var(--fu-color-primary-hover)' : 'var(--fu-color-surface-hover)',
      '--x-browse-w': narrow ? '100%' : 'auto',
      '--x-browse-h': narrow ? '44px' : 'var(--fu-size-control)',
      '--x-row-bd': M ? bw : none,
      '--x-row-div': M ? none : bw,
      '--x-row-radius': M ? 'var(--fu-radius-lg)' : '0',
      '--x-row-shadow': M ? 'var(--fu-shadow-card)' : 'none',
      '--x-list-top': M ? none : bw,
      '--x-colgap': I ? '8px' : '12px',
      '--x-ftile': M ? '40px' : 'auto',
      '--x-ftile-bg': M ? 'var(--fu-color-surface-alt)' : 'transparent',
      '--x-size-l1': M ? 'none' : 'inline',
      '--x-size-l2': M ? 'inline' : 'none',
      '--x-fr-cols': narrow ? 'auto minmax(0,1fr) auto' : 'auto minmax(0,1fr) auto auto',
      '--x-fr-areas': narrow ? '"icon main act" "icon doc doc"' : '"icon main doc act"',
      '--x-doc-w': narrow ? '100%' : (C ? '160px' : '200px'),
      '--x-qsum-basis': narrow ? '100%' : 'auto',
      '--x-qbtn-flex': narrow ? '1 1 auto' : '0 0 auto',
      '--x-g-head': cards ? 'none' : 'grid',
      '--x-g-cols': cards ? 'auto minmax(0,1fr) 44px' : med ? 'minmax(180px,2fr) minmax(130px,1fr) 148px 104px 156px 40px' : 'minmax(200px,2fr) minmax(140px,1fr) 148px 88px 112px minmax(120px,1fr) 156px 40px',
      '--x-g-areas': cards ? '"name name menu" "status doc doc" "meta meta meta" "msg msg msg" "prim prim prim"'
        : med ? '"name doc status date prim menu" "meta meta meta meta prim menu" "msg msg msg msg prim menu"'
        : '"name doc status size date user prim menu" "msg msg msg msg msg msg prim menu"',
      '--x-g-wide': (!cards && !med) ? 'flex' : 'none',
      '--x-g-date': cards ? 'none' : 'flex',
      '--x-g-meta': (cards || med) ? 'flex' : 'none',
      '--x-g-meta-date': cards ? 'inline' : 'none',
      '--x-g-prim-w': cards ? '100%' : 'auto',
      '--x-g-prim-h': cards ? '44px' : 'var(--fu-size-control)',
      '--x-g-prim-j': cards ? 'stretch' : 'end',
      '--x-g-gap': cards ? '8px' : M ? '4px' : '0',
      '--x-g-bd': cards || M ? bw : none,
      '--x-g-div': cards || M ? none : bw,
      '--x-g-radius': cards ? 'var(--fu-radius-lg)' : M ? 'var(--fu-radius-md)' : '0',
      '--x-g-pady': cards ? '12px' : 'var(--fu-space-row-y)',
      '--x-g-rowgap': cards ? '6px' : '2px',
      '--x-g-frame-bd': cards || M ? none : bw,
      '--x-head-tt': I ? 'uppercase' : 'none',
      '--x-filters': W < 600 ? 'none' : 'flex',
      '--x-filter-sel': W < 600 ? 'block' : 'none',
      '--x-md-cols': (o.panelOpen && !overlay) ? 'minmax(0,1fr) var(--fu-size-panel)' : 'minmax(0,1fr)',
      '--x-md-gap': (o.panelOpen && !overlay) ? '16px' : '0',
      '--x-panel-pos': overlay ? 'absolute' : 'relative',
      '--x-panel-w': overlay ? 'min(var(--fu-size-panel), 100%)' : 'auto',
      '--x-panel-shadow': overlay ? 'var(--fu-shadow-panel)' : 'none',
      '--x-panel-radius': overlay ? '0' : 'var(--fu-radius-lg)',
      '--x-scrim': overlay ? 'block' : 'none',
      '--x-ctrl-minh': (o.panelOpen && overlay) ? '720px' : '0',
      '--x-panel-pad': M ? '20px' : '16px'
    });
    return s;
  };

  var reduced = function () { return window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches; };

  class FuIcon extends HTMLElement {
    static get observedAttributes() { return ['name', 'size', 'spin']; }
    connectedCallback() { this.r(); }
    attributeChangedCallback() { if (this.isConnected) this.r(); }
    r() {
      var n = this.getAttribute('name'), sz = this.getAttribute('size') || '20px';
      if (/^\d+$/.test(sz)) sz += 'px';
      this.setAttribute('aria-hidden', 'true');
      var ic = window.FU.icons[n];
      var root = this.shadowRoot || this.attachShadow({ mode: 'open' });
      if (this._n !== n || this._sz !== sz) {
        root.innerHTML = '<style>:host{display:inline-flex;flex:none;width:' + sz + ';height:' + sz + ';line-height:0}svg{display:block;width:100%;height:100%}</style>' + (ic ? '<svg viewBox="' + ic.vb + '" fill="currentColor">' + ic.d.map(function (d) { return '<path d="' + d + '"/>'; }).join('') + '</svg>' : '');
        this._n = n; this._sz = sz;
      }
      var spin = this.getAttribute('spin') === 'true';
      if (spin && !this._a && !reduced()) this._a = this.animate([{ transform: 'rotate(0deg)' }, { transform: 'rotate(360deg)' }], { duration: 1200, iterations: Infinity });
      else if (!spin && this._a) { this._a.cancel(); this._a = null; }
    }
  }
  if (!customElements.get('fu-icon')) customElements.define('fu-icon', FuIcon);

  class FuBar extends HTMLElement {
    static get observedAttributes() { return ['mode', 'value', 'label', 'valuetext', 'blocks', 'tone']; }
    connectedCallback() {
      if (!this._f) {
        this._f = document.createElement('div');
        this._f.style.cssText = 'position:absolute;top:0;bottom:0;left:0;width:0;border-radius:inherit';
        this._m = document.createElement('div');
        this._m.style.cssText = 'position:absolute;inset:0;pointer-events:none';
        var sr = this.shadowRoot || this.attachShadow({ mode: 'open' });
        var st = document.createElement('style');
        st.textContent = ':host{display:block;position:relative;overflow:hidden;height:var(--fu-size-progress);border-radius:var(--fu-radius-sm);background:var(--fu-color-border)}';
        sr.append(st, this._f, this._m);
      }
      this.r();
    }
    attributeChangedCallback() { if (this._f) this.r(); }
    r() {
      var f = this._f, mode = this.getAttribute('mode') || 'det';
      this.setAttribute('role', 'progressbar');
      this.setAttribute('aria-valuemin', '0'); this.setAttribute('aria-valuemax', '100');
      var lb = this.getAttribute('label'); if (lb) this.setAttribute('aria-label', lb);
      var vt = this.getAttribute('valuetext'); if (vt) this.setAttribute('aria-valuetext', vt); else this.removeAttribute('aria-valuetext');
      f.style.background = this.getAttribute('tone') === 'success' ? 'var(--fu-color-status-success-fg)' : 'var(--fu-color-primary)';
      if (mode === 'ind') {
        this.removeAttribute('aria-valuenow');
        if (!this._a) {
          f.style.transition = 'none';
          if (reduced()) { f.style.width = '100%'; this._a = f.animate([{ opacity: 0.35 }, { opacity: 1 }], { duration: 900, iterations: Infinity, direction: 'alternate' }); }
          else { f.style.width = '30%'; this._a = f.animate([{ left: '-30%' }, { left: '100%' }], { duration: 1500, iterations: Infinity, easing: 'cubic-bezier(0.33,0,0.67,1)' }); }
        }
      } else {
        if (this._a) { this._a.cancel(); this._a = null; f.style.left = '0'; f.style.opacity = '1'; }
        f.style.transition = 'width var(--fu-motion-normal) linear';
        var v = Math.max(0, Math.min(100, +this.getAttribute('value') || 0));
        f.style.width = v + '%'; this.setAttribute('aria-valuenow', String(Math.floor(v)));
      }
      var b = +this.getAttribute('blocks') || 0;
      this._m.style.background = b > 1 && b <= 32 ? 'repeating-linear-gradient(90deg, transparent 0, transparent calc(' + (100 / b) + '% - 1px), var(--fu-color-surface) calc(' + (100 / b) + '% - 1px), var(--fu-color-surface) ' + (100 / b) + '%)' : 'none';
    }
  }
  if (!customElements.get('fu-bar')) customElements.define('fu-bar', FuBar);
})();
