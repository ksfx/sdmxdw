/*
 * Searchable dimension pickers for time series dataflows.
 *
 * <div class="dim-filter" data-facets-url="/explore/dataflows/5/facets" data-mode="submit|event">
 *
 * Loads the facets (codes that occur in the dataflow, with series counts under the other
 * selections), renders one search field per key dimension and keeps the selection as hidden
 * inputs (k_<DIM>=code), so a surrounding form submits it like the old multi-selects.
 * mode "submit": submit the surrounding form on every change (dataflow page).
 * mode "event":  reload the facets and dispatch "dimfilter:change" on the element (explorer tree).
 */
(function () {
  const MAX_ITEMS = 100;

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  class DimensionFilter {
    constructor(root) {
      this.root = root;
      this.url = root.dataset.facetsUrl;
      this.mode = root.dataset.mode || 'submit';
      this.selected = new Map(); // param -> Set(code)
      this.dimensions = [];
      root.dimensionFilter = this;
      this.load(root.dataset.query || '');
    }

    /** Current selection (and the free-text field next to the picker, if any) as query string. */
    query() {
      const p = new URLSearchParams();
      this.selected.forEach((codes, param) => codes.forEach(c => p.append(param, c)));
      const scope = this.root.closest('.series-filter, form');
      const q = scope && scope.querySelector('input[name=q]');
      if (q && q.value.trim()) p.set('q', q.value.trim());
      return p.toString();
    }

    async load(query) {
      this.root.classList.add('opacity-60');
      try {
        const res = await fetch(this.url + (query ? (this.url.includes('?') ? '&' : '?') + query : ''), { headers: { Accept: 'application/json' } });
        if (!res.ok) throw new Error(res.status);
        const data = await res.json();
        this.dimensions = data.dimensions;
        this.total = data.total;
        this.selected = new Map(data.dimensions.map(d => [d.param, new Set(d.selected)]));
        this.render();
      } catch (e) {
        this.root.textContent = 'Could not load dimension filters (' + e.message + ').';
      } finally {
        this.root.classList.remove('opacity-60');
      }
    }

    changed() {
      if (this.mode === 'submit') {
        this.renderHidden();
        const form = this.root.closest('form');
        const page = form.querySelector('input[name=page]');
        if (page) page.remove();
        form.submit();
        return;
      }
      const query = this.query();
      this.load(query).then(() =>
        this.root.dispatchEvent(new CustomEvent('dimfilter:change', { bubbles: true, detail: { query } })));
    }

    add(param, code) { this.selected.get(param).add(code); this.changed(); }
    remove(param, code) { this.selected.get(param).delete(code); this.changed(); }

    renderHidden() {
      this.root.querySelectorAll('input[type=hidden]').forEach(i => i.remove());
      this.selected.forEach((codes, param) => codes.forEach(code => {
        const h = el('input'); h.type = 'hidden'; h.name = param; h.value = code;
        this.root.appendChild(h);
      }));
    }

    render() {
      this.root.replaceChildren();
      const grid = el('div', 'flex flex-wrap gap-3 items-start');
      this.dimensions.forEach(d => grid.appendChild(this.renderDimension(d)));
      this.root.appendChild(grid);
      this.renderHidden();
    }

    renderDimension(d) {
      const box = el('div', 'w-60 relative');
      const label = el('div', 'text-xs font-semibold py-1 truncate', d.code);
      label.title = d.label;
      box.appendChild(label);

      const chips = el('div', 'flex flex-wrap gap-1 mb-1');
      const byCode = new Map(d.values.map(v => [v.code, v]));
      this.selected.get(d.param).forEach(code => {
        const v = byCode.get(code);
        const chip = el('span', 'badge badge-primary gap-1 cursor-default', code);
        chip.title = v && v.name ? v.name : code;
        const x = el('button', 'opacity-70 hover:opacity-100', '×');
        x.type = 'button';
        x.title = 'Remove';
        x.addEventListener('click', () => this.remove(d.param, code));
        chip.appendChild(x);
        chips.appendChild(chip);
      });
      box.appendChild(chips);

      const input = el('input', 'input input-bordered input-xs w-full');
      input.type = 'search';
      input.placeholder = 'Search ' + d.values.length + ' codes…';
      input.setAttribute('autocomplete', 'off');
      // dataflow page: floating overlay; explorer tree: inline (the tree panel clips overlays)
      const overlay = this.mode === 'submit' ? ' absolute z-30 w-[28rem] shadow-lg' : ' w-full';
      const list = el('ul', 'menu menu-xs bg-base-100 border border-base-300 rounded-box mt-1 max-h-64 overflow-y-auto flex-nowrap hidden' + overlay);
      const fill = () => {
        const needle = input.value.trim().toLowerCase();
        const chosen = this.selected.get(d.param);
        const matches = d.values.filter(v => !chosen.has(v.code)
          && (!needle || v.code.toLowerCase().includes(needle) || (v.name || '').toLowerCase().includes(needle)));
        list.replaceChildren();
        matches.slice(0, MAX_ITEMS).forEach(v => {
          const li = el('li');
          const a = el('a', 'flex gap-2 flex-nowrap' + (v.count === 0 ? ' opacity-40' : ''));
          // count first, so long code names cannot push it out of view
          a.appendChild(el('span', 'opacity-60 shrink-0 w-10 text-right tabular-nums', v.count.toLocaleString('de-CH')));
          const text = el('span', 'truncate', v.code + (v.name ? ' – ' + v.name : ''));
          text.title = text.textContent;
          a.appendChild(text);
          // mousedown fires before the input loses focus
          a.addEventListener('mousedown', ev => { ev.preventDefault(); this.add(d.param, v.code); });
          li.appendChild(a);
          list.appendChild(li);
        });
        if (matches.length > MAX_ITEMS) list.appendChild(el('li', 'menu-title', (matches.length - MAX_ITEMS) + ' more – keep typing'));
        if (!matches.length) list.appendChild(el('li', 'menu-title', 'No matching codes'));
      };
      input.addEventListener('focus', () => { fill(); list.classList.remove('hidden'); });
      input.addEventListener('input', fill);
      input.addEventListener('blur', () => list.classList.add('hidden'));
      input.addEventListener('keydown', ev => {
        if (ev.key === 'Escape') { input.blur(); }
        if (ev.key === 'Enter') {
          ev.preventDefault();
          const first = list.querySelector('a');
          if (first) first.dispatchEvent(new MouseEvent('mousedown', { cancelable: true }));
        }
      });
      box.appendChild(input);
      box.appendChild(list);
      return box;
    }
  }

  window.DimensionFilter = {
    init(root) { return root.dimensionFilter || new DimensionFilter(root); },
    /** Explorer tree: show/hide the pickers next to a series list, initialised on first use. */
    toggle(button) {
      const panel = button.closest('.series-filter').querySelector('.dim-filter');
      panel.classList.toggle('hidden');
      if (!panel.classList.contains('hidden')) this.init(panel);
    },
  };

  document.addEventListener('DOMContentLoaded', () =>
    document.querySelectorAll('.dim-filter[data-autoinit]').forEach(r => window.DimensionFilter.init(r)));

  // explorer tree: reload the series list when the dimension selection changes
  document.addEventListener('dimfilter:change', ev => {
    const wrapper = ev.target.closest('.series-filter');
    if (!wrapper || !window.htmx) return;
    const list = wrapper.parentElement.querySelector('.series-list');
    htmx.ajax('GET', wrapper.dataset.listUrl + '&' + ev.detail.query, { target: list, swap: 'outerHTML' });
  });
})();
