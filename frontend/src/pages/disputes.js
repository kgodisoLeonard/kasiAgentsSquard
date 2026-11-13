import { esc, money, toast, statusChip } from '../ui.js';
import { session as auth } from '../api.js';

export async function renderDisputes(root, api, alive) {
  const me = auth.user();
  const [disputeData, orderData] = await Promise.all([api.disputes(), api.groupOrders()]);
  if (!alive()) return;
  const isReviewer = me.role === 'ADMIN';
  const disputes = disputeData.disputes;
  const all = orderData.orders;
  const preselected = sessionStorage.getItem('spaza_evidence') || disputes[0]?.id || all[0]?.id || '';
  sessionStorage.removeItem('spaza_evidence');

  root.innerHTML = `
    <div class="hero"><span class="chip violet">Human decisions only</span><h3>${isReviewer ? 'Cases waiting for an independent reviewer' : 'Your disputes and evidence'}</h3>
      <p>Evidence packs are built from platform records (the order, its escrow transactions and the audit log) and fingerprinted with SHA-256.
      The agent's summary cites those records and recommends; a human reviewer who is not a party decides, with written reasons. Appeals and graduated sanctions are simulated in this demo.</p></div>
    <div class="grid g-3" style="margin-top:20px">
      <div class="card"><h3>Cases</h3><p class="sub">Disputed or refunded orders</p><div class="stack-sm" id="cases"></div>
        <div class="divider"></div>
        <form id="lookup" class="stack"><div class="field"><label for="ref">Evidence pack for order</label>
          <select id="ref" class="input">${all.map((o) => `<option value="${esc(o.id)}" ${o.id === preselected ? 'selected' : ''}>${esc(o.id)} · ${esc(o.product)} · ${esc(o.status)}</option>`).join('')}</select></div>
          <button class="btn btn-primary" type="submit" ${all.length ? '' : 'disabled'}>Generate pack</button></form></div>
      <div class="span-2" id="pack"><div class="card empty">Choose an order to build its evidence pack.</div></div>
    </div>`;

  const $ = (s) => root.querySelector(s);
  $('#cases').innerHTML = disputes.map((d) => `<div class="row"><span><b>${esc(d.id)}</b><br><small>${esc(d.product)} · ${esc(d.supplierName)}</small></span>${statusChip(d.status)}</div>`).join('') || '<div class="empty">No open cases.</div>';

  async function load(ref) {
    if (!ref) return;
    $('#pack').innerHTML = '<div class="loader"><div class="spinner"></div><div>Hashing evidence…</div></div>';
    try {
      const res = await api.evidencePack(ref);
      if (!alive()) return;
      const c = res.content, s = c.agentSummary;
      $('#pack').innerHTML = `
        <div class="card"><div class="card-head"><div><h3>${esc(res.documentTitle)}</h3><p class="sub">${esc(c.supplier)} · ${money(c.total)}${c.disputeReason ? ` · ${esc(c.disputeReason)}` : ''}</p></div>${statusChip(res.status)}</div>
          <div class="field"><label>SHA-256 fingerprint</label><div class="hash">${esc(res.sha256Fingerprint)}</div></div>
          <div class="row" style="margin-top:8px;font-size:13px"><span class="k">Verify code</span><span class="mono">${esc(res.verificationCode)}</span></div>
          <div class="sim-note"><b>Agent summary (${esc(s.confidence)} confidence):</b> ${esc(s.finding)}<br><b>Recommendation:</b> ${esc(s.recommendation)}<br>
            <small>Sources: audit entries ${s.sourceAuditSeq.map((x) => '#' + x).join(', ') || 'none'} · ${esc(s.note)}</small></div>
          ${isReviewer && res.status === 'DISPUTED' ? `<form id="decide" class="stack" style="margin-top:14px"><div class="field"><label for="reasons">Written reasons (required)</label><textarea id="reasons" class="input" rows="3" required></textarea></div>
            <div class="row" style="gap:8px;justify-content:flex-start"><button class="btn btn-primary btn-sm" data-outcome="RELEASE" type="submit">Release escrow to supplier</button><button class="btn btn-ghost btn-sm" data-outcome="REFUND" type="submit">Refund buyers</button></div></form>` : ''}
          <h3 style="margin:18px 0 8px;font-size:14px">Records (${c.records.length})</h3>
          <div class="table-wrap"><table class="table"><thead><tr><th>#</th><th>Actor</th><th>Action</th><th>Hash</th></tr></thead><tbody>
            ${c.records.map((r) => `<tr><td class="mono">${r.seq}</td><td>${esc(r.actorType)} · ${esc(r.actor)}</td><td>${esc(r.action)}</td><td class="mono" style="font-size:11px">${esc(r.hash.slice(0, 12))}…</td></tr>`).join('')}</tbody></table></div>
          <button class="btn btn-ghost btn-block" id="copy" style="margin-top:14px">Copy fingerprint</button></div>`;
      $('#copy').onclick = async () => { try { await navigator.clipboard.writeText(res.sha256Fingerprint); toast('Fingerprint copied'); } catch { toast('Clipboard not available', 'error'); } };
      const form = $('#decide');
      if (form) form.addEventListener('submit', async (e) => {
        e.preventDefault();
        try { await api.resolveDispute(ref, e.submitter.dataset.outcome, $('#reasons').value.trim()); toast('Decision recorded'); renderDisputes(root, api, alive); }
        catch (err) { toast(err.message, 'error'); }
      });
    } catch (err) {
      if (alive()) $('#pack').innerHTML = `<div class="card empty"><div class="big">⚠️</div>${esc(err.message)}</div>`;
    }
  }

  $('#lookup').addEventListener('submit', (e) => { e.preventDefault(); load($('#ref').value); });
  await load(preselected);
}
