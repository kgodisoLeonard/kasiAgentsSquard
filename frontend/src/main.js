import { renderStudio } from './kasi/studio.js';
import { t,langOptions,setLang } from './kasi/languages.js';
import './styles.css';
import { api, session } from './api.js';
import { icons, loader, errorBox, applyTheme, toggleTheme } from './ui.js';
import { renderDashboard } from './pages/dashboard.js';
import { renderAgent } from './pages/agent.js';
import { renderWallet } from './pages/wallet.js';
import { renderMandates } from './pages/mandates.js';
import { renderDisputes } from './pages/disputes.js';
import { renderPlatform } from './pages/platform.js';
import { renderRules } from './pages/rules.js';
import { renderLogin } from './pages/login.js';
import { renderSupplier } from './pages/supplier.js';
import { renderMarketplace } from './pages/marketplace.js';
import { renderOrders } from './pages/orders.js';
import { renderAudit } from './pages/audit.js';
import { renderPOS } from './pages/pos.js';

applyTheme();
localStorage.removeItem('spaza_user'); // legacy, pre-session storage
let user = session.user();
// Discard corrupted or outdated saved sessions instead of rendering a broken app.
if (user && (typeof user !== 'object' || !user.id || !['OWNER', 'SUPPLIER', 'ADMIN'].includes(user.role))) { session.clear(); user = null; }
const firstName = String(user?.name || '').trim().split(/\s+/)[0];
const ownerRoutes = {
  studio: { label: '✨ Kasi AI Studio', title: 'Kasi AI Studio', sub: 'Multilingual business intelligence and owner-approved actions.', render: renderStudio },
  dashboard: { label: 'Dashboard', title: firstName ? `Good day, ${firstName} 👋` : 'Good day 👋', sub: "Here's how your spaza is doing today.", render: renderDashboard },
  pos: { label: 'Till', title: 'Till · Customer orders', sub: 'Add items to the customer order; stock drops straight away. The agent restocks items that fall below your mandate rules.', render: renderPOS },
  marketplace: { label: 'Marketplace', title: 'Supplier Marketplace', sub: 'Choose verified products and place stock orders.', render: renderMarketplace },
  orders: { label: 'Group Orders', title: 'Group Orders & Escrow', sub: 'Approve only your own share. Money waits in escrow until every buyer confirms delivery.', render: renderOrders },
  agent: { label: 'Finance Agent', title: 'Spaza Finance Agent', sub: 'Shortage detection and order drafts. Allow-listed actions only; every action is logged.', render: renderAgent },
  wallet: { label: 'Wallet', title: 'RLUSD Wallet (simulated)', sub: 'Pay verified suppliers within your mandate. Simulated ledger, not the live XRPL.', render: renderWallet },
  mandates: { label: 'Mandates', title: 'Spending Mandate', sub: 'Hard limits checked before every payment. The agent can read them but never change them.', render: renderMandates },
  disputes: { label: 'Disputes', title: 'Disputes & Evidence', sub: 'Evidence packs built from platform records, decided by human reviewers.', render: renderDisputes },
  audit: { label: 'Audit Log', title: 'Audit Log', sub: 'Append-only, hash-chained record of every agent action, payment, approval and mandate change.', render: renderAudit },
  platform: { label: 'Trust Centre', title: 'Trust Centre', sub: 'Identity level, sessions, security alerts and what is simulated.', render: renderPlatform },
  rules: { label: 'Human Rules', title: 'Human Rules', sub: 'When the agent may warn you or draft an order.', render: renderRules },
};
const roleRoutes = {
  SUPPLIER: {
    supplier: { label: 'Supplier Orders', title: 'Supplier Orders', sub: 'Escrow-funded orders from verified spaza owners.', render: renderSupplier },
    disputes: { label: 'Disputes', title: 'Disputes & Evidence', sub: 'Evidence packs for your orders.', render: renderDisputes },
  },
  ADMIN: {
    disputes: { label: 'Dispute Reviews', title: 'Dispute Reviews', sub: 'Independent human decisions with written reasons.', render: renderDisputes },
    audit: { label: 'Audit Log', title: 'Audit Log', sub: 'Verify the hash chain and inspect every recorded action.', render: renderAudit },
  },
};
const routes = roleRoutes[user?.role] || ownerRoutes;

if (!user) {
  renderLogin(document.getElementById('app'), api);
} else {

const navButtons = (cls = '', only) => Object.entries(routes).filter(([id]) => !only || only.includes(id)).map(([id, r]) => `
  <button class="nav-btn ${cls}" data-tab="${id}" id="nav-${id}${cls ? '-m' : ''}">${icons[id] || ''}<span>${r.label}</span></button>`).join('');

document.getElementById('app').innerHTML = `
  <header class="mobile-header">
    <button class="burger" id="menu-open" aria-label="Open menu" aria-expanded="false"><span></span><span></span><span></span></button>
    <div class="brand"><div class="logo" style="width:36px;height:36px;font-size:17px">K</div><h1>Kasi Agents Squad</h1></div>
    <span class="status-dot" id="api-dot-m"></span>
  </header>
  <div class="overlay" id="overlay"></div>
  <div class="shell">
    <aside class="sidebar" id="sidebar">
      <div class="brand">
        <div class="logo">K</div>
        <div><h1>Kasi Agents Squad</h1><p>Spaza to the World</p></div>
        <button class="close-btn" id="menu-close" aria-label="Close menu">✕</button>
      </div>
      <nav class="nav"><div class="nav-label">Menu</div>${navButtons()}</nav>
      <div class="sidebar-foot"><label for="global-kasi-lang">🌍 Language</label><select id="global-kasi-lang" class="input" aria-label="Language">${langOptions()}</select>
        <div><span class="status-dot" id="api-dot"></span><span id="api-status">Checking API…</span></div>
        <div style="margin-top:6px;color:var(--dim)">XRPL Testnet · RLUSD</div>
        <button class="btn btn-ghost btn-block" id="theme-toggle" style="margin-top:14px" type="button"></button>
        <button class="btn btn-ghost btn-block" id="logout" style="margin-top:14px">Log out</button>
      </div>
    </aside>
    <main class="main">
      <header class="topbar">
        <div><h2 id="page-title"></h2><p id="page-sub"></p></div>
        <span class="chip green" id="clock"></span>
      </header>
      <section id="content"></section>
    </main>
  </div>
  <nav class="mobile-nav">${navButtons('m', routes.dashboard ? ['dashboard', 'pos', 'orders', 'wallet'] : Object.keys(routes).slice(0, 4))}
    <button class="nav-btn m" id="more-btn"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="5" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="19" cy="12" r="1.5"/></svg><span>More</span></button>
  </nav>
`;

document.getElementById('global-kasi-lang').addEventListener('change', ev => setLang(ev.target.value));
window.addEventListener('kasi-language-change', () => { document.getElementById('global-kasi-lang').value = localStorage.getItem('kasi_lang'); showTab(location.hash.slice(1) || (routes.dashboard ? 'dashboard' : Object.keys(routes)[0])); });
const sidebar = document.getElementById('sidebar');
const overlay = document.getElementById('overlay');
function setMenu(open) {
  sidebar.classList.toggle('open', open);
  overlay.classList.toggle('show', open);
  document.body.classList.toggle('no-scroll', open);
  document.getElementById('menu-open').setAttribute('aria-expanded', String(open));
}
document.getElementById('menu-open').addEventListener('click', () => setMenu(true));
document.getElementById('more-btn').addEventListener('click', () => setMenu(true));
document.getElementById('menu-close').addEventListener('click', () => setMenu(false));
overlay.addEventListener('click', () => setMenu(false));
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') setMenu(false); });
document.getElementById('logout').addEventListener('click', async () => { try { await api.logout(); } catch { /* already signed out */ } session.clear(); window.location.assign(window.location.pathname); });
const themeButton = document.getElementById('theme-toggle');
function updateThemeButton() { themeButton.textContent = document.documentElement.dataset.theme === 'dark' ? '☀ Light mode' : '☾ Dark mode'; }
themeButton.addEventListener('click', () => { toggleTheme(); updateThemeButton(); });
updateThemeButton();

const content = document.getElementById('content');
let renderToken = 0;

async function showTab(id) {
  if (!routes[id]) {
    id = routes.dashboard ? 'dashboard' : Object.keys(routes)[0];
  }
  const r = routes[id];
  document.querySelectorAll('.nav-btn').forEach((b) => b.classList.toggle('active', b.dataset.tab === id));
  document.getElementById('page-title').textContent = id === 'studio' ? t('assistant') : r.title;
  document.getElementById('page-sub').textContent = r.sub;
  document.title = `${r.label} · Kasi Agents Squad`;
  const token = ++renderToken;
  content.innerHTML = loader();
  try {
    await r.render(content, api, () => token === renderToken);
  } catch (err) {
    if (token === renderToken) content.innerHTML = errorBox(err.message);
  }
}

document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-tab]');
  if (b) { location.hash = b.dataset.tab; setMenu(false); }
});
window.addEventListener('hashchange', () => showTab(location.hash.slice(1)));

async function checkHealth() {
  const dot = document.getElementById('api-dot');
  const label = document.getElementById('api-status');
  try {
    await api.health();
    dot.className = 'status-dot on'; label.textContent = 'API online';
    document.getElementById('api-dot-m').className = 'status-dot on';
  } catch {
    dot.className = 'status-dot off'; label.textContent = 'API offline';
    document.getElementById('api-dot-m').className = 'status-dot off';
  }
}

function tickClock() {
  document.getElementById('clock').textContent = '● ' + new Date().toLocaleString('en-ZA', { weekday: 'short', hour: '2-digit', minute: '2-digit' });
}

checkHealth(); setInterval(checkHealth, 15000);
tickClock(); setInterval(tickClock, 30000);
showTab(location.hash.slice(1) || (routes.dashboard ? 'dashboard' : Object.keys(routes)[0]));
}
