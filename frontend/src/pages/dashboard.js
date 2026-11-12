import { esc, money, icons, emojiFor } from '../ui.js';

export async function renderDashboard(root, api, alive) {
  const [inv, sales, wallet, agent] = await Promise.all([
    api.inventory(), api.sales(), api.xrplStatus(), api.shortages(),
  ]);
  if (!alive()) return;

  const items = inv.inventory;
  const low = items.filter((p) => p.qty <= p.lowStock);
  const stockValue = items.reduce((s, p) => s + p.qty * p.cost, 0);
  const maxQty = Math.max(1, ...items.map((p) => p.qty));
  const go = agent.groupOrders[0];
  const pct = Math.round((go.currentQty / go.targetQty) * 100);

  root.innerHTML = `
    <div class="grid g-4">
      ${stat('Revenue today', money(sales.revenue), `${sales.count} sale${sales.count === 1 ? '' : 's'}`, 'cash', 'ic-green')}
      ${stat('Profit today', money(sales.profit), sales.revenue ? `${Math.round((sales.profit / sales.revenue) * 100)}% margin` : 'Ring up a sale', 'trend', 'ic-violet')}
      ${stat('Stock value', money(stockValue), `${items.length} products`, 'box', 'ic-amber')}
      ${stat('Low stock', String(low.length), low.length ? 'Needs restock' : 'All good', 'alert', 'ic-rose')}
    </div>

    <div class="grid g-3" style="margin-top:20px">
      <div class="card span-2">
        <div class="card-head">
          <div><h3>Stock levels</h3><p class="sub">Amber bars are at or below reorder level</p></div>
          <button class="btn btn-ghost btn-sm" data-tab="pos">Open POS →</button>
        </div>
        <div class="bars">
          ${items.map((p) => `
            <div class="bar ${p.qty <= p.lowStock ? 'low' : ''}" title="${esc(p.name)}: ${p.qty}">
              <div style="height:${Math.max(4, (p.qty / maxQty) * 100)}%"></div>
              <span>${emojiFor(p.category)} ${p.qty}</span>
            </div>`).join('')}
        </div>
      </div>

      <div class="card">
        <h3>Wallet</h3><p class="sub">XRPL Testnet</p>
        <div style="font-size:30px;font-weight:700" class="mono">${money(wallet.rlusdBalance).slice(1)} <span style="font-size:14px;color:var(--primary)">RLUSD</span></div>
        <div style="color:var(--muted);font-size:14px;margin-top:4px">${wallet.xrpBalance} XRP</div>
        <div class="divider"></div>
        <div class="row"><span class="k">Per-tx agent cap</span><span class="mono">${money(wallet.perTxLimit)}</span></div>
        <button class="btn btn-ghost btn-block" style="margin-top:16px" data-tab="wallet">Send payment</button>
      </div>
    </div>

    <div class="grid g-2" style="margin-top:20px">
      <div class="hero">
        <span class="chip amber">🤖 Agent insight</span>
        <h3>${esc(go.product)} group buy — ${pct}% filled</h3>
        <p>${esc(agent.recommendation)}</p>
        <div class="progress" style="margin:18px 0 8px"><div style="width:${pct}%"></div></div>
        <div class="row" style="font-size:13px"><span class="k">${go.currentQty}/${go.targetQty} kg · closes in ${esc(go.deadline)}</span>
          <button class="btn btn-primary btn-sm" data-tab="agent">Review</button></div>
      </div>

      <div class="card">
        <h3>Recent sales</h3><p class="sub">Latest transactions from the till</p>
        ${sales.sales.length ? `
          <table class="table">
            <thead><tr><th>Sale</th><th>Items</th><th>Method</th><th style="text-align:right">Total</th></tr></thead>
            <tbody>${sales.sales.slice(0, 5).map((s) => `
              <tr>
                <td class="mono" style="font-size:12px">${esc(s.saleId)}</td>
                <td>${s.items.reduce((n, i) => n + i.qty, 0)}</td>
                <td><span class="chip ${s.paymentMethod === 'rlusd' ? 'violet' : 'green'}">${esc((s.paymentMethod || 'cash').toUpperCase())}</span></td>
                <td class="mono" style="text-align:right">${money(s.subtotal)}</td>
              </tr>`).join('')}
            </tbody>
          </table>` : `<div class="empty"><div class="big">🧾</div>No sales yet — head to the POS to make your first one.</div>`}
      </div>
    </div>
  `;
}

function stat(label, value, trend, icon, cls) {
  return `
    <div class="card stat">
      <div class="label">${label}</div>
      <div class="value">${value}</div>
      <div class="trend">${trend}</div>
      <div class="icon ${cls}">${icons[icon]}</div>
    </div>`;
}
