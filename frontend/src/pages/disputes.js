import { esc, money, toast } from '../ui.js';

export async function renderDisputes(root, api, alive) {
  root.innerHTML = `
    <div class="card" style="margin-bottom:20px">
      <h3>Look up a case</h3><p class="sub">Generate a verifiable evidence pack for any case reference</p>
      <form id="lookup" style="display:flex;gap:10px;flex-wrap:wrap">
        <input id="ref" class="input mono" style="flex:1;min-width:220px" value="EP-2026-000127" required pattern="[A-Za-z0-9\\-]{3,40}"/>
        <button class="btn btn-primary" type="submit">Generate pack</button>
      </form>
    </div>
    <div id="pack"></div>`;

  const $ = (s) => root.querySelector(s);

  async function load(ref) {
    $('#pack').innerHTML = '<div class="loader"><div class="spinner"></div><div>Hashing evidence…</div></div>';
    try {
      const res = await api.evidencePack(ref);
      if (!alive()) return;
      const c = res.content;
      $('#pack').innerHTML = `
        <div class="grid g-3">
          <div class="card span-2">
            <div class="card-head">
              <div><h3>${esc(res.documentTitle)}</h3><p class="sub">${esc(c.type)}</p></div>
              <span class="chip green">✓ ${esc(res.status)}</span>
            </div>
            <div class="grid g-2" style="gap:14px">
              ${party('Claimant', c.claimant, '🏢')}
              ${party('Respondent', c.respondent, '🏪')}
            </div>
            <h3 style="margin:22px 0 10px;font-size:14px;color:var(--muted)">Evidence on file</h3>
            <table class="table"><tbody>
              ${Object.entries(c.evidence).map(([k, v]) => `<tr><td style="color:var(--muted)">${esc(label(k))}</td><td class="mono" style="text-align:right">${esc(v)}</td></tr>`).join('')}
            </tbody></table>
          </div>
          <div class="card">
            <h3>Integrity</h3><p class="sub">Any change to the evidence changes this fingerprint</p>
            <div class="field"><label>SHA-256 fingerprint</label><div class="hash">${esc(res.sha256Fingerprint)}</div></div>
            <div class="divider"></div>
            <div class="stack-sm" style="font-size:13px">
              <div class="row"><span class="k">Verify code</span><span class="mono">${esc(res.verificationCode)}</span></div>
              <div class="row"><span class="k">Amount</span><span class="mono" style="color:var(--primary)">${money(c.amount)}</span></div>
            </div>
            <button class="btn btn-ghost btn-block" id="copy" style="margin-top:18px">Copy fingerprint</button>
          </div>
        </div>`;
      $('#copy').addEventListener('click', async () => {
        try { await navigator.clipboard.writeText(res.sha256Fingerprint); toast('Fingerprint copied'); }
        catch { toast('Clipboard not available', 'error'); }
      });
    } catch (err) {
      if (alive()) $('#pack').innerHTML = `<div class="card empty"><div class="big">⚠️</div>${esc(err.message)}</div>`;
    }
  }

  $('#lookup').addEventListener('submit', (e) => { e.preventDefault(); load($('#ref').value.trim()); });
  await load('EP-2026-000127');
}

const label = (k) => k.replace(/([A-Z])/g, ' $1').replace(/^./, (s) => s.toUpperCase());
const party = (role, name, icon) => `
  <div style="display:flex;gap:12px;align-items:center;padding:14px;border-radius:14px;background:hsla(222 47% 4% / .5);border:1px solid var(--border)">
    <div style="font-size:26px">${icon}</div>
    <div><div style="font-size:12px;color:var(--muted)">${role}</div><div style="font-weight:600">${esc(name)}</div></div>
  </div>`;
