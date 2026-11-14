import { esc, money } from '../ui.js';

/**
 * Supplier dashboard: KPI tiles, orders by delivery status (tall segmented bar + table), revenue received per day
 * (column chart) and top products (ranked bars). Revenue = escrow released after the spaza owner accepted delivery.
 * Colours: status palette (good / warning / critical) + categorical blue for "to deliver"; every status also carries an
 * icon and a label, never colour alone. Charts are plain SVG/HTML; hover shows a tooltip; tables carry every value.
 */
const STATUS = {
  PAID: { icon: '✓', short: 'Delivered & paid', cls: 'good' },
  AWAITING_CONFIRMATION: { icon: '⏳', short: 'Waiting for owner', cls: 'warning' },
  TO_DELIVER: { icon: '🚚', short: 'To deliver', cls: 'blue' },
  NOT_DELIVERED: { icon: '✕', short: 'Not delivered', cls: 'critical' },
};
const rands = (n) => money(n).replace(/,00$/, '');
const compact = (n) => (n >= 1000 ? `R${(n / 1000).toFixed(n >= 10000 ? 0 : 1).replace('.0', '')}K` : `R${Math.round(n)}`);

export function supplierDashboardHtml(d) {
  const r = d.revenue;
  const total = d.statuses.reduce((s, x) => s + x.orders, 0);
  const tiles = `<div class="sd-kpis">
      <div class="card stat sd-hero"><div class="label">Paid to you</div><div class="value">${money(r.paid)}</div><div class="trend">Released when spaza owners accepted delivery</div></div>
      <div class="card stat"><div class="label">Delivered, waiting for the owner</div><div class="value">${money(r.awaitingConfirmation)}</div><div class="trend">Paid to you once the owner accepts</div></div>
      <div class="card stat"><div class="label">Paid into escrow, to deliver</div><div class="value">${money(r.toDeliver)}</div><div class="trend">Deliver, then the owner accepts</div></div>
      <div class="card stat"><div class="label">Not delivered / refunded</div><div class="value">${money(r.refunded + r.disputed)}</div><div class="trend">${r.disputed > 0 ? `${money(r.disputed)} held in dispute` : 'Returned to the buyers'}</div></div>
    </div>`;

  // Tall segmented bar: one whole (all orders) split by status; block widths proportional to the order counts.
  const blocks = d.statuses.filter((s) => s.orders > 0).map((s) => {
    const pct = (s.orders / total) * 100, st = STATUS[s.key];
    return `<div class="sd-seg sd-${st.cls}" style="flex:${s.orders}" tabindex="0" data-tip="${esc(`${st.icon} ${s.label}: ${s.orders} order${s.orders === 1 ? '' : 's'} · ${money(s.value)}`)}" aria-label="${esc(`${s.label}: ${s.orders} orders, ${money(s.value)}`)}"><span>${pct >= 9 ? `${s.orders}` : ''}</span></div>`;
  }).join('');
  const statusCard = `<div class="card sd-card"><div class="card-head"><div><h3>Orders by delivery status</h3><p class="sub">${total} order${total === 1 ? '' : 's'} · you are paid only after the spaza owner accepts the delivery</p></div></div>
      ${total ? `<div class="sd-segbar" role="img" aria-label="Orders by delivery status">${blocks}</div>` : '<div class="empty">No paid orders yet.</div>'}
      <div class="sd-legend">${d.statuses.map((s) => `<span><i class="sd-swatch sd-${STATUS[s.key].cls}"></i>${STATUS[s.key].icon} ${esc(STATUS[s.key].short)}</span>`).join('')}</div>
      <table class="table sd-table"><thead><tr><th>Status</th><th class="num">Orders</th><th class="num">Value</th></tr></thead><tbody>
        ${d.statuses.map((s) => `<tr><td>${STATUS[s.key].icon} ${esc(s.label)}</td><td class="num">${s.orders}</td><td class="num">${money(s.value)}</td></tr>`).join('')}
      </tbody></table></div>`;

  return `${tiles}<div class="sd-grid">${statusCard}${revenueChart(d.revenueByDay)}${topProducts(d.topProducts)}</div>`;
}

/** Column chart: revenue received per day, last 14 days. One series (blue): no legend; the title names it. */
function revenueChart(days) {
  const W = 460, H = 210, L = 44, R = 8, T = 22, B = 28, iw = W - L - R, ih = H - T - B;
  const max = Math.max(...days.map((x) => Number(x.revenue)), 0);
  const step = niceStep(max), top = Math.max(step * Math.ceil(max / step), step);
  const band = iw / days.length, bw = Math.min(24, band * 0.6);
  const y = (v) => T + ih - (v / top) * ih;
  const ticks = []; for (let v = 0; v <= top + 1e-9; v += step) ticks.push(v);
  const maxIdx = days.reduce((m, x, i) => (Number(x.revenue) > Number(days[m].revenue) ? i : m), 0);
  const cols = days.map((x, i) => {
    const v = Number(x.revenue), cx = L + band * i + band / 2, h = (v / top) * ih, x0 = cx - bw / 2, y0 = T + ih - h;
    const r = Math.min(4, h, bw / 2);
    const path = v > 0 ? `M${x0},${T + ih} V${y0 + r} Q${x0},${y0} ${x0 + r},${y0} H${x0 + bw - r} Q${x0 + bw},${y0} ${x0 + bw},${y0 + r} V${T + ih} Z` : '';
    const day = new Date(x.date + 'T12:00:00');
    const label = day.toLocaleDateString('en-ZA', { day: 'numeric', month: 'short' });
    return `<g class="sd-col" data-tip="${esc(`${day.toLocaleDateString('en-ZA', { weekday: 'short', day: 'numeric', month: 'short' })}: ${money(v)} received`)}">
        <rect class="sd-hit" x="${L + band * i}" y="${T}" width="${band}" height="${ih}"></rect>
        ${path ? `<path class="sd-bar" d="${path}"></path>` : ''}
        ${i % 2 === (days.length - 1) % 2 ? `<text class="sd-axis-label" x="${cx}" y="${H - 10}" text-anchor="middle">${esc(label)}</text>` : ''}
        ${i === maxIdx && v > 0 ? `<text class="sd-value" x="${cx}" y="${y0 - 6}" text-anchor="middle">${compact(v)}</text>` : ''}
      </g>`;
  }).join('');
  const grid = ticks.map((v) => `<line class="sd-gridline" x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}"></line><text class="sd-axis-label" x="${L - 8}" y="${y(v) + 4}" text-anchor="end">${compact(v)}</text>`).join('');
  return `<div class="card sd-card"><div class="card-head"><div><h3>Revenue received per day</h3><p class="sub">Last 14 days · escrow released after the owner accepted delivery</p></div></div>
      <div class="sd-chart"><svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Revenue received per day, last 14 days">${grid}<line class="sd-baseline" x1="${L}" x2="${W - R}" y1="${T + ih}" y2="${T + ih}"></line>${cols}</svg><div class="sd-tip" hidden></div></div>
      <details class="sd-tableview"><summary>Show as table</summary><table class="table sd-table"><thead><tr><th>Day</th><th class="num">Received</th></tr></thead><tbody>
        ${days.map((x) => `<tr><td>${esc(x.date)}</td><td class="num">${money(x.revenue)}</td></tr>`).join('')}</tbody></table></details></div>`;
}

/** Ranked horizontal bars: revenue received per product, largest first, one hue. */
function topProducts(list) {
  const max = Math.max(...list.map((p) => Number(p.revenue)), 0);
  return `<div class="card sd-card"><div class="card-head"><div><h3>Top products by revenue</h3><p class="sub">Revenue received (owner accepted delivery)</p></div></div>
      ${list.length ? `<div class="sd-ranked">${list.map((p) => `<div class="sd-rank-row" data-tip="${esc(`${p.product}: ${money(p.revenue)} for ${p.units} units`)}" tabindex="0">
          <span class="sd-rank-name">${esc(p.product)}</span>
          <span class="sd-rank-track"><span class="sd-rank-bar" style="width:${max ? (Number(p.revenue) / max) * 100 : 0}%"></span></span>
          <span class="sd-rank-val">${rands(p.revenue)}</span></div>`).join('')}</div>`
        : '<div class="empty">No revenue yet. It appears here once a spaza owner accepts one of your deliveries.</div>'}</div>`;
}

function niceStep(max) {
  if (max <= 0) return 100;
  const raw = max / 4, p = 10 ** Math.floor(Math.log10(raw)), f = raw / p;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * p;
}

/** Hover / focus tooltips for every element carrying data-tip inside the dashboard. */
export function wireTooltips(el) {
  let tip = el.querySelector('.sd-floating-tip');
  if (!tip) { tip = document.createElement('div'); tip.className = 'sd-tip sd-floating-tip'; tip.hidden = true; el.appendChild(tip); }
  const show = (target, x, y) => {
    tip.textContent = target.dataset.tip; tip.hidden = false;
    const box = el.getBoundingClientRect();
    tip.style.left = `${Math.min(x - box.left + 12, box.width - tip.offsetWidth - 8)}px`;
    tip.style.top = `${y - box.top - tip.offsetHeight - 10}px`;
  };
  el.onmousemove = (e) => { const t = e.target.closest('[data-tip]'); if (t) show(t, e.clientX, e.clientY); else tip.hidden = true; };
  el.onmouseleave = () => { tip.hidden = true; };
  el.onfocusin = (e) => { const t = e.target.closest('[data-tip]'); if (t) { const b = t.getBoundingClientRect(); show(t, b.left + b.width / 2, b.top); } };
  el.onfocusout = () => { tip.hidden = true; };
}
