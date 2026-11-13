import { esc, money, num, toast, when, simulatedBadge } from '../ui.js';
import { withStepUp } from '../api.js';

export async function renderWallet(root, api, alive) {
  let status = await api.xrplStatus();
  if (!alive()) return;
  const cap = Number(status.perTxLimit || 0);

  root.innerHTML = `
    <div class="sim-note" style="margin-bottom:16px"><b>Simulated ledger.</b> ${esc(status.notice)} Transaction hashes are local SHA-256 digests and have no XRPL explorer page.</div>
    <div class="grid g-3">
      <div class="stack">
        <div class="balance-card">
          <div class="lbl">RLUSD balance (simulated)</div>
          <div class="amt mono" id="bal">${num(status.rlusdBalance)}</div>
          <div style="display:flex;justify-content:space-between;align-items:center;margin-top:26px">
            <span class="mono" style="font-size:12px;opacity:.8">Issuer ${esc(status.rlusdIssuer.slice(0, 6))}••••${esc(status.rlusdIssuer.slice(-4))}</span>
            ${simulatedBadge('Not connected')}
          </div>
        </div>
        <div class="card"><h3>Your mandate</h3><p class="sub">Checked before every payment</p>
          ${status.mandate ? `<div class="mono" style="font-size:24px;font-weight:700">${money(cap)} <span style="font-size:13px;color:var(--muted)">per order</span></div>
          <p class="sub" style="margin-top:6px">Daily cap ${money(status.mandate.dailyCap)} · spent today ${money(status.mandate.spentToday)}</p>` : '<p class="sub">No mandate: payments are blocked.</p>'}
          <button class="btn btn-ghost btn-sm" data-tab="mandates" style="margin-top:10px">Manage mandate</button></div>
      </div>
      <div class="card span-2">
        <h3>Pay a verified supplier</h3><p class="sub">Money can only go to a verified supplier's payout address. Payments above R1,000 need a verification code.</p>
        <form id="pay-form" class="stack">
          <div class="field"><label for="payee">Supplier</label><select id="payee" class="input">${status.payees.map((p) => `<option value="${esc(p.id)}">${esc(p.name)} · ${esc(p.address.slice(0, 10))}…</option>`).join('')}</select></div>
          <div class="field"><label for="amount">Amount (RLUSD)</label><input id="amount" class="input" type="number" min="0.01" step="0.01" value="250" required/></div>
          <div style="display:flex;gap:8px;flex-wrap:wrap">${[50, 100, 250, 500].map((v) => `<button type="button" class="btn btn-ghost btn-sm" data-amt="${v}">${v}</button>`).join('')}</div>
          <button id="pay" type="submit" class="btn btn-primary btn-block" style="padding:14px">Send payment (simulated)</button>
        </form>
        <div class="terminal" id="log" style="margin-top:18px"><div class="info">[SYSTEM] Simulated ledger · no network connection</div><div>[SYSTEM] Every payment passes the mandate check first.</div></div>
      </div>
    </div>
    <div class="card" style="margin-top:20px"><h3>Ledger activity</h3><p class="sub">Escrow deposits, releases, refunds and direct payments for your wallet</p><div id="txs"></div></div>`;

  const $ = (s) => root.querySelector(s);
  const log = (html, cls = '') => { const l = $('#log'); l.insertAdjacentHTML('beforeend', `<div class="${cls}">${html}</div>`); l.scrollTop = l.scrollHeight; };
  const drawTxs = () => {
    $('#bal').textContent = num(status.rlusdBalance);
    $('#txs').innerHTML = status.transactions.length ? `<div class="table-wrap"><table class="table"><thead><tr><th>Type</th><th>Simulated hash</th><th>Memo</th><th>Time</th><th style="text-align:right">Amount</th></tr></thead><tbody>
      ${status.transactions.map((t) => `<tr><td>${esc(t.type)}</td><td class="mono" style="font-size:12px" title="${esc(t.hash)}">${esc(t.hash.slice(0, 14))}…</td><td style="font-size:12px">${esc(t.memo || '')}</td>
      <td style="color:var(--muted);font-size:13px">${when(t.timestamp)}</td><td class="mono" style="text-align:right">${t.type === 'EscrowCancel' ? '+' : '−'}${num(t.amount)}</td></tr>`).join('')}</tbody></table></div>`
      : '<div class="empty"><div class="big">🪙</div>No ledger activity yet</div>';
  };
  drawTxs();

  root.querySelectorAll('[data-amt]').forEach((b) => b.addEventListener('click', () => { $('#amount').value = b.dataset.amt; }));
  $('#pay-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const amount = parseFloat($('#amount').value);
    if (!Number.isFinite(amount) || amount <= 0) return toast('Enter a valid payment amount', 'error');
    const btn = $('#pay'); btn.disabled = true; btn.textContent = 'Checking mandate…';
    log(`[SUBMIT] ${num(amount)} RLUSD → ${esc($('#payee').selectedOptions[0].textContent)}`);
    try {
      const res = await withStepUp((stepUp) => api.pay({ amount, supplierId: $('#payee').value }, stepUp));
      if (!res) { log('[CANCELLED] Verification cancelled', 'err'); return; }
      log(`[RESULT] ${esc(res.engineResult)} · ${esc(res.hash.slice(0, 24))}…`, 'ok');
      toast(`Sent ${num(amount)} RLUSD (simulated)`);
      status = await api.xrplStatus(); drawTxs();
    } catch (err) {
      log(`[BLOCKED] ${esc(err.message)}`, 'err');
      toast(err.message, 'error');
    } finally { btn.disabled = false; btn.textContent = 'Send payment (simulated)'; }
  });
}
