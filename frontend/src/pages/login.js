import { applyTheme, toggleTheme, askCode } from '../ui.js';
import { session } from '../api.js';

export function renderLogin(root, api) {
  applyTheme();
  root.innerHTML = `<main class="auth-page"><section class="auth-layout"><div class="auth-art"><div class="auth-art-copy"><span class="chip">Spaza to the World</span><h1>Stock your shop with confidence.</h1><p>Connect local spaza owners with trusted suppliers, better prices and reliable delivery.</p><div class="auth-art-points"><span>For spaza owners</span><span>For suppliers</span><span>Built for local trade</span></div></div></div><div class="auth-panel"><div class="brand"><div class="logo">S</div><div><h1>Spaza to the World</h1><p>Secure collective buying</p></div></div><div class="row auth-heading"><div><h2 id="auth-title">Sign in</h2><p id="auth-sub" class="sub">Enter your business name and password.</p></div><button id="toggle-auth" class="btn btn-ghost btn-sm" type="button">Create account</button></div><form id="auth-form" class="auth-form"><div id="name-field" class="field" hidden><label for="f-name">Name &amp; surname</label><input id="f-name" class="input" name="name" autocomplete="name" maxlength="100"></div><div class="field" id="business-field"><label for="f-business">Business name</label><input id="f-business" class="input" name="businessName" autocomplete="organization" maxlength="100" required></div><div id="role-field" class="field" hidden><label for="f-role">Account type</label><select id="f-role" class="input" name="role"><option value="OWNER">Spaza owner</option><option value="SUPPLIER">Supplier</option></select></div><div id="email-field" class="field" hidden><label for="f-email">Email</label><input id="f-email" class="input" type="email" name="email" autocomplete="email" maxlength="254"></div><div class="field full"><label for="f-password">Password</label><input id="f-password" class="input" type="password" name="password" autocomplete="current-password" minlength="8" maxlength="72" required></div><p id="auth-error" class="sub auth-error full" hidden></p><button id="submit" class="btn btn-primary btn-block full" type="submit">Sign in</button></form><details id="auth-demo" class="auth-demo sub"><summary>Demo accounts</summary><p>Thandi's Spaza · Sipho's Corner Shop / spaza123<br>Mokoena Meats (Pty) Ltd · Ubuntu Foods Co-op / supplier123<br>Spaza Trust &amp; Safety 1 · 2 / review123<br>Every sign-in also needs a one-time code (SMS simulated: shown on screen).</p></details></div></section></main>`;
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
  const fields = ['name-field', 'role-field', 'email-field'].map(id => root.querySelector(`#${id}`));
  let registering = false;

  const updateMode = () => {
    fields.forEach(field => {
      field.hidden = !registering;
      field.querySelector('input,select').required = registering;
    });
    root.querySelector('#auth-title').textContent = registering ? 'Create your account' : 'Sign in';
    root.querySelector('#auth-sub').textContent = registering ? 'Choose whether you run a spaza or supply one.' : 'Enter your business name and password.';
    form.classList.toggle('is-register', registering);
    root.querySelector('#auth-demo').hidden = registering;
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
        : { businessName: values.businessName.trim(), password: values.password };
      if (registering) { await api.register(body); registering = false; }
      // Step 1: password. Step 2: mandatory one-time code (SMS delivery is simulated).
      const challenge = await api.login({ businessName: body.businessName, password: body.password });
      const code = await askCode({ title: 'Enter your sign-in code', message: `We sent a 6-digit code to the phone registered for ${body.businessName}.`, demoCode: challenge.demoCode });
      if (!code) throw new Error('Sign-in cancelled. You need the one-time code to continue.');
      const result = await api.verifyMfa(challenge.challengeId, code);
      if (!result?.user?.id || !result.accessToken) throw new Error('Unexpected response from the server. Please try again.');
      session.set({ accessToken: result.accessToken, refreshToken: result.refreshToken, user: result.user });
      location.hash = { SUPPLIER: 'supplier', ADMIN: 'disputes' }[result.user.role] || 'dashboard';
      location.reload();
    } catch (err) {
      error.textContent = err.message || 'Unable to continue';
      error.hidden = false;
      submit.disabled = false;
      updateMode();
    }
  };
}
