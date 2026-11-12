export async function renderDisputes(root, api) {
  root.innerHTML = `
    <div class="card">
      <h2 class="text-xl font-bold mb-2">Disputes &amp; Tamper-Evident Evidence Packs</h2>
      <p class="text-sm text-slate-400 mb-4">SHA-256 fingerprint and verification code for each case.</p>
      <div id="pack" class="space-y-3"></div>
    </div>
  `;
  const pack = root.querySelector('#pack');
  try {
    const res = await api.evidencePack('EP-2026-000127');
    pack.innerHTML = `
      <div class="bg-slate-950 border border-slate-800 rounded-xl p-5 space-y-3">
        <h3 class="font-bold text-white">${res.documentTitle}</h3>
        <div class="text-xs font-mono space-y-1">
          <div class="flex justify-between"><span class="text-slate-500">Status:</span><span class="text-emerald-400">${res.status}</span></div>
          <div class="flex justify-between"><span class="text-slate-500">SHA-256:</span><span class="text-emerald-400 break-all text-right">${res.sha256Fingerprint.substring(0, 48)}...</span></div>
          <div class="flex justify-between"><span class="text-slate-500">Verify Code:</span><span class="text-white">${res.verificationCode}</span></div>
          <div class="flex justify-between"><span class="text-slate-500">Verify URL:</span><span class="text-emerald-400">${res.verificationUrl}</span></div>
        </div>
        <div class="pt-3 border-t border-slate-800 text-xs text-slate-400">
          Amount owed: <span class="text-emerald-400 font-mono">R${res.content.amount.toFixed(2)}</span>
        </div>
      </div>
    `;
  } catch (err) {
    pack.innerHTML = `<div class="text-rose-400 text-sm">${err.message}</div>`;
  }
}
