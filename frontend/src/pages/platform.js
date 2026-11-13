import { esc, money, toast, when } from '../ui.js';
import { session } from '../api.js';

const CONTROLS = [
  ['Password hashing (bcrypt), strength rules', 'Real'],
  ['Breached-password check', 'Simulated: small local list, no HaveIBeenPwned call'],
  ['One-time code on every sign-in', 'Real codes · SMS delivery simulated'],
  ['Account lockout after 5 failed sign-ins (15 min)', 'Real'],
  ['CAPTCHA after repeated failures', 'Not built'],
  ['Google sign-in (OpenID Connect), passkeys', 'Not built'],
  ['15-min access tokens, refresh-token rotation, reuse detection', 'Real (in memory)'],
  ['New-device alerts, sign out all devices', 'Real in-app · email/SMS simulated'],
  ['Step-up code for large orders, payments, mandate changes', 'Real'],
  ['Identity verification Level 2 (ID, liveness, registry)', 'Simulated: demo accounts pre-verified'],
  ['Credit, bank debit, licensed lender', 'Simulated'],
  ['XRPL Testnet signing', 'Simulated ledger'],
];

export async function renderPlatform(root, api, alive) {
  const [data, me, notificationData] = await Promise.all([api.platformOverview(), api.me(), api.notifications()]);
  if (!alive()) return;
  const p = data.profile, r = data.repayment, g = data.stokvel;

  root.innerHTML = `<div class="hero"><span class="chip ${p.level >= 2 ? 'green' : 'amber'}">Level ${p.level} · ${esc(p.levelLabel)}</span><h3>Your account security</h3>
      <p>${p.level >= 2 ? 'You can order and pay within your mandate.' : 'Orders and payouts need Level 2 verification (ID, liveness selfie, address). Verification is simulated in this demo.'}</p></div>
    <div class="grid g-3" style="margin-top:20px">
      <div class="card"><h3>Signed-in devices</h3><p class="sub">${me.sessions.length} active session${me.sessions.length === 1 ? '' : 's'}</p>
        <div class="stack-sm">${me.sessions.map((s) => `<div class="row"><span>Device ${esc(s.device)}…<br><small>${when(s.signedInAt)}</small></span>${s.current ? '<span class="chip green">This device</span>' : ''}</div>`).join('')}</div>
        <button class="btn btn-ghost btn-block" id="logout-all" style="margin-top:14px">Sign out all devices</button></div>
      <div class="card"><h3>Security alerts</h3><p class="sub">New devices and session changes</p>
        <div class="stack-sm">${me.alerts.map((a) => `<div><b>${esc(a.type.replaceAll('_', ' ').toLowerCase())}</b><br><small>${esc(a.message)} · ${when(a.at)}</small></div>`).join('') || '<div class="empty">No alerts</div>'}</div></div>
      <div class="card"><h3>Credit & repayment</h3><p class="sub">Simulated: no lender or bank is connected</p>
        <div class="row"><span class="k">Due in 30 days</span><b class="mono">${money(r.amount)}</b></div>
        <div class="row" style="margin-top:10px"><span class="k">Status</span><span class="chip amber">${esc(r.status)}</span></div>
        <p class="sub" style="margin-top:12px">${esc(g.name)} · ${g.members} members · split ${esc(g.splitMethod)} · each member liable only for their own share</p></div>
    </div>
    <div class="card" style="margin-top:20px"><h3>What is real and what is simulated</h3><div class="table-wrap"><table class="table"><tbody>
      ${CONTROLS.map(([c, s]) => `<tr><td>${esc(c)}</td><td><span class="chip ${s.startsWith('Real') ? 'green' : s.startsWith('Not') ? 'rose' : 'amber'}">${esc(s)}</span></td></tr>`).join('')}</tbody></table></div></div>
    <div class="card" style="margin-top:20px"><div class="card-head"><div><h3>Low-stock notifications</h3><p class="sub">${esc(notificationData.delivery)}</p></div><span class="chip amber">${notificationData.notifications.length} active</span></div>
      <div class="stack-sm">${notificationData.notifications.map((n) => `<div class="row"><span><b>${esc(n.title)}</b><br><small>${esc(n.message)}</small></span><button class="btn btn-ghost btn-sm" data-tab="agent">${esc(n.action)}</button></div>`).join('') || '<div class="empty">No notifications</div>'}</div></div>`;

  root.querySelector('#logout-all').onclick = async () => {
    try { const r = await api.logoutAll(); toast(`Signed out ${r.sessionsEnded} session(s)`); session.clear(); setTimeout(() => window.location.assign(window.location.pathname), 600); }
    catch (err) { toast(err.message, 'error'); }
  };
}
