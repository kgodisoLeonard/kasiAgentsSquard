import { esc, money, toast } from '../ui.js';

export async function renderAgent(root, api, alive) {
  const data = await api.shortages();
  if (!alive()) return;
  const go = data.groupOrders[0];
  const shops = 5;
  let share = 10;
  let filled = go.currentQty;

  root.innerHTML = `
    <div class="hero">
      <span class="chip amber">⚡ ${data.shortagesDetected} shortages detected</span>
      <h3>${esc(go.product)} bulk restock · ${esc(go.community)}</h3>
      <p>${esc(data.recommendation)}</p>
    </div>

    <div class="grid g-3" style="margin-top:20px">
      <div class="card span-2">
        <div class="card-head">
          <div><h3>Order ${esc(go.id)}</h3><p class="sub">Closes in ${esc(go.deadline)}</p></div>
          <span class="chip green" id="status">${esc(go.status)}</span>
        </div>
        <div class="row" style="margin-bottom:8px"><span class="k">Pool progress</span><span class="mono" id="fill-label"></span></div>
        <div class="progress"><div id="fill-bar" style="width:0"></div></div>

        <div class="grid g-3" style="margin-top:22px;gap:14px">
          ${mini('Retail price', money(go.unitPrice) + '/kg')}
          ${mini('Wholesale price', money(go.wholesalePrice) + '/kg', 'var(--primary)')}
          ${mini('You save', money(go.unitPrice - go.wholesalePrice) + '/kg', 'var(--amber)')}
        </div>

        <div class="field" style="margin-top:22px">
          <label for="share">Your share: <b id="share-label" style="color:var(--text)">${share} kg</b></label>
          <input id="share" type="range" min="1" max="${go.targetQty - go.currentQty + share}" value="${share}" style="accent-color:hsl(158 72% 46%)"/>
        </div>
      </div>

      <div class="card">
        <h3>Fair cost split</h3><p class="sub">Delivery split evenly across ${shops} shops</p>
        <div class="stack-sm" id="split"></div>
        <div class="divider"></div>
        <div class="row total-row"><span style="font-size:16px">Due in 30 days</span><span class="v" id="due"></span></div>
        <button id="approve" class="btn btn-primary btn-block" style="margin-top:18px;padding:14px">Approve my share</button>
        <p style="font-size:12px;color:var(--dim);margin-top:10px;text-align:center">Funds held in escrow · protected by your mandate</p>
      </div>
    </div>`;

  const $ = (s) => root.querySelector(s);
  function draw() {
    const goods = share * go.wholesalePrice;
    const delivery = go.deliveryFee / shops;
    const pct = Math.min(100, Math.round((filled / go.targetQty) * 100));
    $('#fill-bar').style.width = pct + '%';
    $('#fill-label').textContent = `${filled}/${go.targetQty} kg · ${pct}%`;
    $('#share-label').textContent = `${share} kg`;
    $('#split').innerHTML = `
      <div class="row"><span class="k">Goods (${share} kg × ${money(go.wholesalePrice)})</span><span class="mono">${money(goods)}</span></div>
      <div class="row"><span class="k">Delivery (1/${shops})</span><span class="mono">${money(delivery)}</span></div>
      <div class="row"><span class="k">Saved vs retail</span><span class="mono" style="color:var(--amber)">−${money(share * (go.unitPrice - go.wholesalePrice))}</span></div>`;
    $('#due').textContent = money(goods + delivery);
  }
  requestAnimationFrame(draw);

  $('#share').addEventListener('input', (e) => { share = +e.target.value; draw(); });
  $('#approve').addEventListener('click', async (e) => {
    const btn = e.currentTarget; btn.disabled = true; btn.textContent = 'Approving…';
    try {
      const res = await api.approveGroupBuy({ quantity: share });
      filled = Math.min(go.targetQty, go.currentQty + share);
      $('#share').disabled = true;
      $('#status').textContent = filled >= go.targetQty ? 'FILLED' : 'JOINED';
      draw();
      btn.textContent = '✓ Approved';
      toast(res.message);
    } catch (err) { toast(err.message, 'error'); btn.disabled = false; btn.textContent = 'Approve my share'; }
  });
}

const mini = (k, v, color = 'var(--text)') => `
  <div style="padding:14px;border-radius:14px;background:hsla(222 47% 4% / .5);border:1px solid var(--border)">
    <div style="font-size:12px;color:var(--muted)">${k}</div>
    <div class="mono" style="font-size:17px;font-weight:600;margin-top:4px;color:${color}">${v}</div>
  </div>`;
