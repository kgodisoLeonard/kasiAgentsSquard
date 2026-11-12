import { esc, money, num, toast } from '../ui.js';

export async function renderWallet(root, api, alive) {
  let status = await api.xrplStatus();
  if (!alive()) return;

  root.innerHTML = `
    <div class="grid g-3">
      <div class="stack">
        <div class="balance-card">
          <div class="lbl">RLUSD balance</div>
          <div class="amt mono" id="bal">${num(status.rlusdBalance)}</div>
          <div class="lbl">${num(status.xrpBalance)} XRP · reserve</div>
          <div style="display:flex;justify-content:space-between;align-items:center;margin-top:26px">
            <span class="mono" style="font-size:12px;opacity:.8">${esc(status.rlusdIssuer.slice(0, 6))}••••${esc(status.rlusdIssuer.slice(-4))}</span>
            <span class="chip" style="background:hsla(0 0% 100% / .1);border-color:hsla(0 0% 100% / .2)">● ${esc(status.network.toUpperCase())}</span>
          </div>
        </div>
        <div class="card">
          <h3>Agent guard-rail</h3><p class="sub">Payments above this are blocked by your mandate</p>
          <div class="mono" style="font-size:24px;font-weight:700">${money(status.perTxLimit)} <span style="font-size:13px;color:var(--muted)">per tx</span></div>
        </div>
      </div>

      <div class="card span-2">
        <h3>Send RLUSD</h3><p class="sub">Submits a Payment transaction to the XRPL Testnet</p>
        <form id="pay-form" class="stack">
          <div class="field"><label for="dest">Destination address</label>
            <input id="dest" class="input mono" style="font-size:13px" value="${esc(status.rlusdIssuer)}" required/></div>
          <div class="field"><label for="amount">Amount (RLUSD)</label>
            <input id="amount" class="input" type="number" min="0.01" max="${status.perTxLimit}" step="0.01" value="250" required/></div>
          <div style="display:flex;gap:8px;flex-wrap:wrap">
            ${[50, 100, 250, 500, status.perTxLimit].map((v) => `<button type="button" class="btn btn-ghost btn-sm" data-amt="${v}">${v}</button>`).join('')}
          </div>
          <button id="pay" type="submit" class="btn btn-primary btn-block" style="padding:14px">Send payment</button>
        </form>
        <div class="terminal" id="log" style="margin-top:18px">
          <div class="info">[SYSTEM] Connected to ${esc(status.wsUrl)}</div>
          <div>[SYSTEM] Ready for submission…</div>
        </div>
      </div>
    </div>

    <div class="card" style="margin-top:20px">
      <h3>Transaction history</h3><p class="sub">Payments sent this session</p>
      <div id="txs"></div>
    </div>`;

  const $ = (s) => root.querySelector(s);
  const log = (html, cls = '') => { const l = $('#log'); l.insertAdjacentHTML('beforeend', `<div class="${cls}">${html}</div>`); l.scrollTop = l.scrollHeight; };

  function drawTxs() {
    $('#bal').textContent = num(status.rlusdBalance);
    $('#txs').innerHTML = status.transactions.length ? `
      <table class="table"><thead><tr><th>Hash</th><th>Destination</th><th>Time</th><th style="text-align:right">Amount</th></tr></thead>
      <tbody>${status.transactions.map((t) => `
        <tr><td class="mono" style="font-size:12px"><a href="https://testnet.xrpl.org/transactions/${esc(t.hash)}" target="_blank" rel="noopener" style="color:var(--primary)">${esc(t.hash.slice(0, 14))}…</a></td>
        <td class="mono" style="font-size:12px">${esc(t.destination.slice(0, 10))}…</td>
        <td style="color:var(--muted);font-size:13px">${new Date(t.timestamp).toLocaleTimeString('en-ZA')}</td>
        <td class="mono" style="text-align:right;color:var(--rose)">−${num(t.amount)}</td></tr>`).join('')}
      </tbody></table>` : `<div class="empty"><div class="big">🪙</div>No payments yet</div>`;
  }
  drawTxs();

  root.querySelectorAll('[data-amt]').forEach((b) => b.addEventListener('click', () => { $('#amount').value = b.dataset.amt; }));

  $('#pay-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const amount = parseFloat($('#amount').value);
    const destination = $('#dest').value.trim();
    if (!Number.isFinite(amount) || amount <= 0) return toast('Enter a valid payment amount', 'error');
    if (amount > status.perTxLimit) return toast(`Payment limit is ${money(status.perTxLimit)} per transaction. Split the payment or update a human-approved mandate.`, 'error');
    const btn = $('#pay'); btn.disabled = true; btn.textContent = 'Submitting…';
    log(`[SUBMIT] Payment ${num(amount)} RLUSD → ${esc(destination.slice(0, 12))}…`);
    try {
      const res = await api.pay({ amount, destination });
      log(`[RESULT] ${esc(res.engineResult)}`, 'ok');
      log(`[HASH] ${esc(res.hash.slice(0, 40))}…`);
      toast(`Sent ${num(amount)} RLUSD`);
      status = await api.xrplStatus();
      drawTxs();
    } catch (err) {
      log(`[BLOCKED] ${esc(err.message)}`, 'err');
      toast(err.message, 'error');
    } finally { btn.disabled = false; btn.textContent = 'Send payment'; }
  });
}
