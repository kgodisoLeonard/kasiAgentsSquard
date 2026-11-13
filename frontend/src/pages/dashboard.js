import { esc, money, icons, productThumb, toast } from '../ui.js';

/**
 * Dashboard. The agent's shortage scan runs once per visit (it may draft a group order); after that the page refreshes
 * itself every 10 seconds from stock, wallet, orders and sales, and only redraws when something changed.
 */
export async function renderDashboard(root, api, alive) {
  const [inv, wallet, agent, orderData, salesData] = await Promise.all([api.inventory(), api.xrplStatus(), api.shortages(), api.groupOrders(), api.sales()]);
  if (!alive()) return;
  let data = { inv, wallet, orderData, salesData }, last = JSON.stringify(data);
  const refresh = async () => {
    const [i, w, o, s] = await Promise.all([api.inventory(), api.xrplStatus(), api.groupOrders(), api.sales()]);
    if (!alive()) return;
    const next = { inv: i, wallet: w, orderData: o, salesData: s }, json = JSON.stringify(next);
    if (json !== last) { last = json; data = next; paint(root, api, alive, agent, data, refresh); }
    const chip = root.querySelector('#live-chip'); if (chip) { chip.textContent = `● Live · ${new Date().toLocaleTimeString()}`; chip.className = 'chip green'; }
  };
  paint(root, api, alive, agent, data, refresh);
  const timer = setInterval(async () => {
    if (!alive()) return clearInterval(timer);
    if (document.hidden || root.querySelector('button:disabled')) return;   // tab not visible, or an action is in progress
    try { await refresh(); }
    catch { const chip = root.querySelector('#live-chip'); if (chip) { chip.textContent = '○ Reconnecting…'; chip.className = 'chip amber'; } }
  }, 10000);
}

function paint(root, api, alive, agent, { inv, wallet, orderData, salesData }, refresh) {
  const items = inv.inventory, low = items.filter(p => p.qty <= p.lowStock);
  const stockValue = items.reduce((s, p) => s + p.qty * p.cost, 0);
  // The insight card shows your open group buy, or the agent's top shortage if no order is open yet.
  const open = orderData.orders.find((o) => o.status === 'COLLECTING') || orderData.orders.find((o) => !['COMPLETED', 'CANCELLED', 'REFUNDED'].includes(o.status));
  const top = agent.recommendations.find((r) => r.supplierId) || agent.recommendations[0];
  const left = (iso) => { const m = Math.max(0, Math.round((new Date(iso) - Date.now()) / 60000)); return m >= 60 ? `${Math.round(m / 60)}h` : `${m}m`; };
  const go = open
    ? { product: open.product, currentQty: open.fundedQuantity, targetQty: open.minQuantity, deadline: open.status === 'COLLECTING' ? left(open.approvalDeadline) : open.status.replaceAll('_', ' ').toLowerCase() }
    : { product: top ? top.product : 'No shortages', currentQty: 0, targetQty: top?.minOrder || 1, deadline: top ? 'not started' : '—' };
  agent.recommendation = agent.note;
  const pct = Math.min(100, Math.round((go.currentQty / go.targetQty) * 100));
  root.innerHTML = `<div class="grid g-4">${stat('Income today', money(salesData.today.revenue), `${salesData.today.count} paid order${salesData.today.count === 1 ? '' : 's'} · profit ${money(salesData.today.profit)}`, 'cash', 'ic-green')}${stat('Total income', money(salesData.revenue), `${salesData.count} sales · profit ${money(salesData.profit)}`, 'cash', 'ic-green')}${stat('Stock value', money(stockValue), `${items.length} products`, 'box', 'ic-amber')}${stat('Low stock', String(low.length), low.length ? 'Needs restock' : 'All good', 'alert', 'ic-rose')}</div><div class="grid g-3" style="margin-top:20px"><div class="card span-2"><div class="card-head"><div><h3>Stock levels</h3><p class="sub">Product photos and live quantities · <a class="photo-credits" href="/assets/products/CREDITS.md" target="_blank" rel="noopener">photo credits</a></p></div><button class="btn btn-ghost btn-sm" data-tab="marketplace">Open Marketplace →</button></div><div class="stock-grid">${items.map(p => `<div class="stock-item ${p.qty <= p.lowStock ? 'low' : ''}">${productThumb(p.name, p.category)}<div><b>${esc(p.name)}</b><span>${p.qty} in stock · reorder at ${p.lowStock}</span>${p.onOrder ? `<span class="on-order" title="Restock order ${esc(p.onOrder.orderId)} is ${esc(p.onOrder.status.replaceAll('_', ' ').toLowerCase())}">On order: ${p.onOrder.quantity} (${esc(p.onOrder.orderId)})</span>` : ''}</div></div>`).join('')}</div></div><div class="card"><h3>Wallet</h3><p class="sub">XRPL Testnet · simulated</p><div style="font-size:30px;font-weight:700" class="mono">${money(wallet.rlusdBalance).slice(1)} <span style="font-size:14px;color:var(--primary)">RLUSD</span></div><div style="color:var(--muted);font-size:14px;margin-top:4px">${wallet.xrpBalance} XRP</div><div class="divider"></div><div class="row"><span class="k">Per-tx agent cap</span><span class="mono">${money(wallet.perTxLimit)}</span></div><button class="btn btn-ghost btn-block" style="margin-top:16px" data-tab="wallet">Send payment</button></div></div><div class="hero" style="margin-top:20px"><span class="chip amber">🤖 Agent insight</span><h3>${esc(go.product)} group buy — ${pct}% filled</h3><p>${esc(agent.recommendation)}</p><div class="progress" style="margin:18px 0 8px"><div style="width:${pct}%"></div></div><div class="row" style="font-size:13px"><span class="k">${go.currentQty}/${go.targetQty} kg · closes in ${esc(go.deadline)}</span><button class="btn btn-primary btn-sm" data-tab="agent">Review</button></div></div>`;
  // Deliveries: the owner confirms receipt here; that releases escrow to the supplier and adds the stock to the shop.
  const mine = orderData.orders.filter((o) => o.yourShare && o.yourShare.status === 'FUNDED' && !o.yourShare.deliveryConfirmed
    && ['FUNDED', 'ACCEPTED', 'IN_TRANSIT', 'DELIVERED_PENDING_CONFIRMATION'].includes(o.status));
  const canConfirm = (o) => ['IN_TRANSIT', 'DELIVERED_PENDING_CONFIRMATION'].includes(o.status);
  const label = { FUNDED: 'Paid, waiting for the supplier to accept', ACCEPTED: 'Accepted, waiting for dispatch', IN_TRANSIT: 'On the way', DELIVERED_PENDING_CONFIRMATION: 'Supplier says delivered' };
  mine.sort((a, b) => canConfirm(b) - canConfirm(a));
  root.firstElementChild.insertAdjacentHTML('afterend', `<div class="card deliveries" style="margin-top:20px"><div class="card-head"><div><h3>Deliveries <span id="live-chip" class="chip green" style="margin-left:6px;font-size:11px">● Live · ${new Date().toLocaleTimeString()}</span></h3><p class="sub">Confirm only once the stock is in your shop. Confirming pays the supplier from escrow and adds the stock to your shelves.</p></div><span class="chip ${mine.some(canConfirm) ? 'amber' : 'green'}">${mine.filter(canConfirm).length} to confirm</span></div>
    ${mine.length ? mine.map((o) => `<div class="delivery-row">${productThumb(o.product, '')}<div class="info"><b>${esc(o.product)} · ${o.yourShare.quantity}</b><span>${esc(o.id)} · ${esc(o.supplierName)} · ${money(o.yourShare.total)} in escrow</span><span class="chip ${canConfirm(o) ? 'amber' : 'violet'}">${label[o.status]}</span></div>
      ${canConfirm(o) ? `<button class="btn btn-primary btn-sm" data-confirm-delivery="${esc(o.id)}">Confirm delivery</button>` : '<span class="sub">Confirm after dispatch</span>'}</div>`).join('') : '<div class="empty">No deliveries waiting. Restock orders show here once paid.</div>'}</div>`);
  root.querySelector('.deliveries').onclick = async (e) => {
    const b = e.target.closest('[data-confirm-delivery]');
    if (!b) return;
    const o = mine.find((x) => x.id === b.dataset.confirmDelivery);
    if (!confirm(`Confirm you received ${o.yourShare.quantity} × ${o.product} (${o.id})? This pays ${o.supplierName} ${money(o.yourShare.total)} from escrow.`)) return;
    b.disabled = true; b.textContent = 'Confirming…';
    try { const r = await api.confirmDelivery(o.id); toast(r.message); if (alive()) await refresh(); }
    catch (err) { toast(err.message, 'error'); b.disabled = false; b.textContent = 'Confirm delivery'; }
  };
}

function stat(label, value, trend, icon, cls) { return `<div class="card stat"><div class="label">${label}</div><div class="value">${value}</div><div class="trend">${trend}</div><div class="icon ${cls}">${icons[icon]}</div></div>`; }
