/*
 * Chart of one series (#27): loads its values as JSON, so long series (e.g. daily prices since
 * 1999) do not travel through the page. Above MAX_POINTS points the visible range is thinned out
 * with Largest-Triangle-Three-Buckets, which keeps peaks and the shape; range buttons (1Y, 5Y,
 * 10Y, Max) appear for series spanning more than two years.
 *
 *   SeriesChart.init(canvas, '/explore/dataflows/3/series/7/chart.json?stage=BETA', 'Q.DE.P.A.A')
 */
(function () {
  const MAX_POINTS = 1200;

  /** Largest-Triangle-Three-Buckets on indexes 0..n-1 of y (nulls are kept out of the buckets). */
  function lttb(xs, ys, threshold) {
    const points = [];
    for (let i = 0; i < ys.length; i++) if (ys[i] !== null) points.push(i);
    if (points.length <= threshold) return points;
    const keep = [points[0]];
    const every = (points.length - 2) / (threshold - 2);
    let a = 0;
    for (let i = 0; i < threshold - 2; i++) {
      const start = Math.floor((i + 1) * every) + 1;
      const end = Math.min(Math.floor((i + 2) * every) + 1, points.length);
      let avgX = 0, avgY = 0;
      for (let j = start; j < end; j++) { avgX += points[j]; avgY += ys[points[j]]; }
      avgX /= (end - start); avgY /= (end - start);
      const rangeStart = Math.floor(i * every) + 1;
      const rangeEnd = Math.floor((i + 1) * every) + 1;
      const ax = points[a], ay = ys[points[a]];
      let max = -1, next = rangeStart;
      for (let j = rangeStart; j < rangeEnd; j++) {
        const area = Math.abs((ax - avgX) * (ys[points[j]] - ay) - (ax - points[j]) * (avgY - ay));
        if (area > max) { max = area; next = j; }
      }
      keep.push(points[next]);
      a = next;
    }
    keep.push(points[points.length - 1]);
    return keep;
  }

  /** Same period one, five or ten years earlier: "2026-11-12" -> "2025-11-12", "2026-Q2" -> "2025-Q2". */
  function yearsBefore(period, years) {
    const y = parseInt(period.slice(0, 4), 10);
    return isNaN(y) ? null : String(y - years).padStart(4, '0') + period.slice(4);
  }

  async function init(canvas, url, label) {
    if (!canvas || !window.Chart) return;
    const res = await fetch(url, { headers: { Accept: 'application/json' } });
    if (!res.ok) return;
    const data = await res.json();
    const labels = data.labels, values = data.values, asOf = data.asOf || null;
    let chart = null;

    function draw(from) {
      const start = from ? Math.max(0, labels.findIndex(l => l >= from)) : 0;
      const l = labels.slice(start), v = values.slice(start), w = asOf ? asOf.slice(start) : null;
      const idx = lttb(l, v, MAX_POINTS);
      const pick = arr => idx.map(i => arr[i]);
      const many = idx.length > 300;
      const datasets = [{ label: label, data: pick(v), borderColor: '#053771', borderWidth: many ? 1.5 : 2,
                          pointRadius: many ? 0 : 2, tension: 0.1, spanGaps: true }];
      if (w) datasets.push({ label: data.asOfLabel, data: pick(w), borderColor: '#13ad8f', borderDash: [6, 4],
                             borderWidth: 1.5, pointRadius: many ? 0 : 2, tension: 0.1, spanGaps: false });
      if (chart) chart.destroy();
      chart = new Chart(canvas, {
        type: 'line',
        data: { labels: pick(l), datasets: datasets },
        options: {
          maintainAspectRatio: false,
          animation: many ? false : undefined,
          interaction: { mode: 'index', intersect: false },
          plugins: { legend: { display: !!w } },
          scales: { x: { ticks: { maxTicksLimit: 12, autoSkip: true, maxRotation: 0 } } },
        },
      });
    }

    // range buttons for long series
    const first = labels[0], last = labels[labels.length - 1];
    const span = first && last ? parseInt(last.slice(0, 4), 10) - parseInt(first.slice(0, 4), 10) : 0;
    if (span > 2) {
      const bar = document.createElement('div');
      bar.className = 'join mb-2';
      const ranges = [['1Y', 1], ['5Y', 5], ['10Y', 10], ['Max', null]].filter(([, y]) => y === null || y < span);
      ranges.forEach(([text, years]) => {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'join-item btn btn-xs' + (years === null ? ' btn-active' : '');
        b.textContent = text;
        b.title = years === null ? 'Whole series' : 'Last ' + years + ' year(s)';
        b.addEventListener('click', () => {
          bar.querySelectorAll('button').forEach(x => x.classList.remove('btn-active'));
          b.classList.add('btn-active');
          draw(years === null ? null : yearsBefore(last, years));
        });
        bar.appendChild(b);
      });
      canvas.parentElement.before(bar);
    }
    draw(null);
  }

  window.SeriesChart = { init: init, lttb: lttb, yearsBefore: yearsBefore };

  // revision popovers of the value table: one open at a time, closed by a click elsewhere or Escape
  const popovers = () => document.querySelectorAll('details.revision-popover[open]');
  /** Opens upwards when there is no room below (bottom of the window or of a scrolling panel). */
  function place(details) {
    const slot = details.querySelector('.revision-slot');
    if (!slot) return;
    slot.classList.remove('bottom-full', 'mb-1');
    slot.classList.add('top-full', 'mt-1');
    let limit = window.innerHeight;
    for (let el = details.parentElement; el; el = el.parentElement) {
      const o = getComputedStyle(el).overflowY;
      if (o === 'auto' || o === 'scroll') { limit = Math.min(limit, el.getBoundingClientRect().bottom); break; }
    }
    const box = slot.getBoundingClientRect();
    if (box.bottom > limit && details.getBoundingClientRect().top - box.height > 0) {
      slot.classList.remove('top-full', 'mt-1');
      slot.classList.add('bottom-full', 'mb-1');
    }
  }
  document.addEventListener('toggle', e => {
    if (e.target.matches && e.target.matches('details.revision-popover') && e.target.open) {
      popovers().forEach(d => { if (d !== e.target) d.open = false; });
      place(e.target);
    }
  }, true);
  // the revisions arrive after opening: place again once they are there
  document.addEventListener('htmx:afterSwap', e => {
    const d = e.target.closest && e.target.closest('details.revision-popover[open]');
    if (d) place(d);
  });
  document.addEventListener('click', e => {
    popovers().forEach(d => { if (!d.contains(e.target)) d.open = false; });
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') popovers().forEach(d => { d.open = false; });
  });
})();
