import { esc, toast, when } from '../ui.js';

export async function renderAudit(root, api, alive) {
  const [data, check] = await Promise.all([api.audit(), api.verifyAudit()]);
  if (!alive()) return;
  const short = (h) => `${h.slice(0, 10)}…`;

  root.innerHTML = `
    <div class="hero"><span class="chip ${check.valid ? 'green' : 'rose'}">${check.valid ? '✓ Hash chain intact' : '✗ Chain broken'}</span>
      <h3>${check.valid ? `${check.checked} entries verified` : `Tampering detected at entry #${esc(check.brokenAtSeq)}`}</h3>
      <p>Each entry stores the SHA-256 hash of the entry before it, so editing or deleting any record breaks every hash after it.
      The log is append-only: the API has no edit or delete operation.${check.valid ? ` Head hash: <span class="mono">${esc(short(check.headHash || ''))}</span>` : ` ${esc(check.reason || '')}`}</p>
      <button class="btn btn-ghost btn-sm" id="reverify" style="margin-top:12px">Verify chain again</button></div>
    <div class="card" style="margin-top:20px"><div class="card-head"><div><h3>Latest 200 entries</h3><p class="sub">Who, what, when, inputs and outputs</p></div>
      <select id="filter" class="input" style="max-width:200px" aria-label="Filter by actor"><option value="">All actors</option><option>AGENT</option><option>HUMAN</option><option>SYSTEM</option></select></div>
      <div class="table-wrap"><table class="table"><thead><tr><th>#</th><th>When</th><th>Actor</th><th>Action</th><th>Inputs → outputs</th><th>Hash</th></tr></thead><tbody id="rows"></tbody></table></div></div>`;

  const draw = (filter) => {
    root.querySelector('#rows').innerHTML = data.entries.filter((e) => !filter || e.actorType === filter).map((e) => `<tr>
      <td class="mono">${e.seq}</td><td style="white-space:nowrap">${when(e.at)}</td>
      <td><span class="chip ${e.actorType === 'AGENT' ? 'violet' : e.actorType === 'HUMAN' ? 'green' : ''}">${esc(e.actorType)}</span><br><small>${esc(e.actor)}</small></td>
      <td><b style="${/BLOCKED|FAILED|LOCKED|FRAUD/.test(e.action) ? 'color:var(--rose)' : ''}">${esc(e.action)}</b></td>
      <td><div class="audit-json mono">${esc(e.inputs)}<br>→ ${esc(e.outputs)}</div></td>
      <td class="mono" title="${esc(e.hash)}">${esc(short(e.hash))}<br><small title="previous">↳ ${esc(short(e.prevHash))}</small></td></tr>`).join('') || '<tr><td colspan="6" class="empty">No entries</td></tr>';
  };
  draw('');
  root.querySelector('#filter').onchange = (e) => draw(e.target.value);
  root.querySelector('#reverify').onclick = async () => {
    try { const r = await api.verifyAudit(); toast(r.valid ? `Chain intact · ${r.checked} entries` : `Chain broken at #${r.brokenAtSeq}`, r.valid ? 'success' : 'error'); }
    catch (err) { toast(err.message, 'error'); }
  };
}
