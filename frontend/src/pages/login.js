import { applyTheme, toggleTheme } from '../ui.js';

export function renderLogin(root, api) {
  applyTheme();
  root.innerHTML = `<main class="auth-page"><section class="auth-layout"><div class="auth-art"><div class="auth-art-copy"><span class="chip">Spaza to the World</span><h1>Stock your shop with confidence.</h1><p>Connect local spaza owners with trusted suppliers, better prices and reliable delivery.</p><div class="auth-art-points"><span>For spaza owners</span><span>For suppliers</span><span>Built for local trade</span></div></div></div><div class="auth-panel"><div class="brand"><div class="logo">S</div><div><h1>Spaza to the World</h1><p>Secure collective buying</p></div></div><div class="row auth-heading"><div><h2 id="auth-title">Sign in</h2><p id="auth-sub" class="sub">Enter your account details and we&rsquo;ll take you to the right workspace.</p></div><button id="toggle-auth" class="btn btn-ghost btn-sm" type="button">Create account</button></div><form id="auth-form" class="stack"><div id="name-field" class="field" hidden><label>Your name</label><input class="input" name="name" autocomplete="name" maxlength="100"></div><div id="business-field" class="field" hidden><label>Business name</label><input class="input" name="businessName" autocomplete="organization" maxlength="100"></div><div id="role-field" class="field" hidden><label>Account type</label><select class="input" name="role"><option value="OWNER">Spaza owner</option><option value="SUPPLIER">Supplier</option></select></div><div class="field"><label>Email</label><input class="input" type="email" name="email" autocomplete="username" maxlength="254" required></div><div class="field"><label>Password</label><input class="input" type="password" name="password" autocomplete="current-password" minlength="6" maxlength="72" required></div><p id="auth-error" class="sub auth-error" hidden></p><button id="submit" class="btn btn-primary btn-block" type="submit">Sign in</button></form><div class="divider"></div><p class="sub auth-demo">Demo: owner@spaza.local / spaza123<br>supplier@mokoena.local / supplier123</p></div></section></main>`;
  const themeButton = document.createElement('button');
  themeButton.id = 'theme-toggle';
  themeButton.type = 'button';
  themeButton.className = 'btn btn-ghost btn-sm auth-theme-toggle';
  themeButton.setAttribute('aria-label', 'Toggle dark and light mode');
  root.querySelector('.auth-panel').prepend(themeButton);
  const updateThemeButton = () => { themeButton.textContent = document.documentElement.dataset.theme === 'dark' ? '☀ Light' : '☾ Dark'; };
  themeButton.onclick = () => { toggleTheme(); updateThemeButton(); };
  updateThemeButton();
  const form = root.querySelector('#auth-form');
  const error = root.querySelector('#auth-error');
  const submit = root.querySelector('#submit');
  const toggle = root.querySelector('#toggle-auth');
  const fields = ['name-field', 'business-field', 'role-field'].map(id => root.querySelector(`#${id}`));
  let registering = false;

  const updateMode = () => {
    fields.forEach(field => {
      field.hidden = !registering;
      field.querySelector('input,select').required = registering;
    });
    root.querySelector('#auth-title').textContent = registering ? 'Create your account' : 'Sign in';
    root.querySelector('#auth-sub').innerHTML = registering ? 'Choose whether you run a spaza or supply one.' : 'Enter your account details and we&rsquo;ll take you to the right workspace.';
    submit.textContent = registering ? 'Create account' : 'Sign in';
    toggle.textContent = registering ? 'I already have an account' : 'Create account';
    password.autocomplete = registering ? 'new-password' : 'current-password';
  };

  const password = form.querySelector('input[name="password"]');
  toggle.onclick = () => { registering = !registering; error.hidden = true; updateMode(); };
  form.onsubmit = async event => {
    event.preventDefault();
    if (submit.disabled) return;
    error.hidden = true;
    submit.disabled = true;
    submit.textContent = registering ? 'Creating account...' : 'Signing in...';
    try {
      const values = Object.fromEntries(new FormData(form));
      const body = registering
        ? { name: values.name.trim(), businessName: values.businessName.trim(), role: values.role, email: values.email.trim(), password: values.password }
        : { email: values.email.trim(), password: values.password };
      const result = await (registering ? api.register(body) : api.login(body));
      if (!result?.user?.id || !result.user.role) throw new Error('Unexpected response from the server. Please try again.');
      localStorage.setItem('spaza_user', JSON.stringify(result.user));
      location.hash = result.user.role === 'SUPPLIER' ? 'supplier' : 'dashboard';
      location.reload();
    } catch (err) {
      error.textContent = err.message || 'Unable to continue';
      error.hidden = false;
      submit.disabled = false;
      updateMode();
    }
  };
}
