import { esc } from '../ui.js';

const DEMO_ACCOUNTS = {
  owner: { email: 'owner@spaza.local', password: 'spaza123', label: 'Spaza owner' },
  supplier: { email: 'supplier@mokoena.local', password: 'supplier123', label: 'Verified supplier' },
};

export function renderLogin(root, api) {
  root.innerHTML = `<main style="min-height:100vh;display:grid;place-items:center;padding:24px"><section class="card" style="width:min(460px,100%)"><div class="brand" style="padding:0"><div class="logo">S</div><div><h1>Spaza to the World</h1><p>Secure collective buying</p></div></div><h2 style="margin-top:28px">Sign in</h2><p class="sub">Select your role, then enter the matching account details.</p><div class="segmented" id="roles"><button type="button" data-role="owner" class="active">Spaza owner</button><button type="button" data-role="supplier">Supplier</button></div><form id="login" class="stack" style="margin-top:18px"><div class="field"><label>Email</label><input class="input" type="email" name="email" autocomplete="username" required></div><div class="field"><label>Password</label><input class="input" type="password" name="password" autocomplete="current-password" required></div><p id="login-error" class="sub" style="display:none;color:var(--rose);margin:0"></p><button id="submit" class="btn btn-primary btn-block" type="submit">Sign in as Spaza owner</button></form><div class="divider"></div><p class="sub" style="margin:0">Demo accounts are prefilled when you choose a role.</p></section></main>`;
  const form = root.querySelector('#login'), error = root.querySelector('#login-error'), submit = root.querySelector('#submit');
  let selected = 'owner';
  function choose(role) { selected = role; const a = DEMO_ACCOUNTS[role]; form.email.value = a.email; form.password.value = a.password; submit.textContent = `Sign in as ${a.label}`; root.querySelectorAll('[data-role]').forEach(b => b.classList.toggle('active', b.dataset.role === role)); error.style.display = 'none'; }
  root.querySelector('#roles').onclick = e => { const b = e.target.closest('[data-role]'); if (b) choose(b.dataset.role); };
  choose('owner');
  form.onsubmit = async e => { e.preventDefault(); error.style.display = 'none'; submit.disabled = true; submit.textContent = 'Signing in…'; try { const result = await api.login(Object.fromEntries(new FormData(form))); localStorage.setItem('spaza_user', JSON.stringify(result.user)); location.assign(result.user.role === 'SUPPLIER' ? '#supplier' : '#dashboard'); location.reload(); } catch (err) { error.textContent = esc(err.message || 'Unable to sign in'); error.style.display = 'block'; submit.disabled = false; submit.textContent = `Sign in as ${DEMO_ACCOUNTS[selected].label}`; } };
}
