import { esc, toast } from '../ui.js';

export async function renderRules(root, api, alive) {
  const [data, agents] = await Promise.all([api.humanRules(), api.agents()]);
  if (!alive()) return;
  const rules = data.rules, agent = agents.agents[0];
  root.innerHTML = `
    <div class="hero"><span class="chip amber">Human rules come first</span><h3>When the agent may act</h3><p>${esc(rules.autoOrderMeans)} Spending limits are set under <a href="#mandates">Mandates</a>.</p></div>
    <div class="grid g-2" style="margin-top:20px">
      <div class="card"><h3>${esc(agent.name)}</h3><p class="sub">${esc(agent.role)}</p>
        <div class="row" style="margin-top:12px"><span class="k">Agent status</span><span class="chip ${agent.enabled ? 'green' : 'rose'}">${agent.enabled ? 'ACTIVE' : 'PAUSED'}</span></div>
        <p class="sub" style="margin-top:10px">A paused agent cannot run any action, including reads.</p>
        <button id="toggle" class="btn ${agent.enabled ? 'btn-ghost' : 'btn-primary'} btn-block" style="margin-top:12px">${agent.enabled ? 'Pause agent' : 'Resume agent'}</button></div>
      <div class="card"><form id="rules" class="stack">
        <div class="field"><label for="r-warn">Low-stock warning threshold (items)</label><input id="r-warn" class="input" name="warningThreshold" type="number" min="1" value="${rules.warningThreshold}" required></div>
        <div class="field"><label for="r-auto">Agent may order automatically only below (items)</label><input id="r-auto" class="input" name="autoOrderThreshold" type="number" min="0" value="${rules.autoOrderThreshold}" required></div>
        <label style="display:flex;gap:8px;align-items:center"><input type="checkbox" name="autoOrderEnabled" ${rules.autoOrderEnabled ? 'checked' : ''}> Let the agent place restock orders automatically (paid into escrow under your restock rules and mandate). Off = the agent only warns you.</label>
        <button class="btn btn-primary" type="submit">Save rules</button></form></div>
    </div>`;
  root.querySelector('#toggle').onclick = async () => { try { await api.setAgentEnabled(agent.id, !agent.enabled); toast('Agent status updated'); renderRules(root, api, alive); } catch (e) { toast(e.message, 'error'); } };
  root.querySelector('#rules').onsubmit = async (e) => {
    e.preventDefault(); const f = new FormData(e.target);
    try { await api.saveAgentRules({ warningThreshold: +f.get('warningThreshold'), autoOrderThreshold: +f.get('autoOrderThreshold'), autoOrderEnabled: f.get('autoOrderEnabled') === 'on' }); toast('Human rules saved. They now override the agent\'s restock rules.'); }
    catch (err) { toast(err.message, 'error'); }
  };
}
