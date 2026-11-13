# Kasi Agents Squard — Spaza to the World

Secure, verified, multilingual agentic-finance marketplace for spaza shops, communities and suppliers.
An AI agent spots shortages, groups nearby shops into bulk orders and drafts those orders. Each owner approves only their own share,
every payment passes the owner's spending mandate, and money waits in escrow until the buyers confirm delivery.
Built for the W3Node Node Engine Hackathon, Cape Town, Nov 2026.

> **Demo honesty note:** the XRPL ledger, SMS delivery, identity verification, credit and sanctions screening are **simulated**.
> They are labelled as simulated in the code, the UI and the [Security](#security) section below. Nothing here moves real money.

## Stack
- Backend: Java 17 + Spring Boot 3.2, H2 (file) database, JPA
- Frontend: JavaScript + Vite
- Ledger: **simulated** XRPL Testnet escrow (EscrowCreate / EscrowFinish / EscrowCancel model), RLUSD amounts

## Run
Backend:

    cd backend
    mvn spring-boot:run

Frontend (proxies `/api` to port 8080):

    cd frontend
    npm install
    npm run dev

Tests (no database or server needed):

    cd backend
    mvn test

## Demo accounts (published passwords, demo only)

| Role | Email | Password |
|---|---|---|
| Spaza owner | owner@spaza.local | spaza123 |
| Spaza owner | sipho@spaza.local | spaza123 |
| Supplier (Mokoena Meats) | supplier@mokoena.local | supplier123 |
| Supplier (Ubuntu Foods Co-op) | ubuntu@coop.local | supplier123 |
| Dispute reviewer | reviewer1@spaza.local / reviewer2@spaza.local | review123 |

Every sign-in asks for a one-time code. No SMS provider is connected, so the code is shown on screen and labelled "Simulated SMS".

## Till and autonomous restock
1. Staff open **Till** and add items to the customer's order (`addToOrder`). Each item leaves stock **immediately**; removing it or cancelling the order puts it back. **Take payment & close order** closes it.
2. If a sale takes an item **below the owner's restock level** (default 5), the Finance Agent orders **exactly the quantity, supplier and price limit set in the owner's mandate** (Mandates → *Automatic restock*), within the agent's **monthly budget**.
3. It will not order again while an order for that item is still open. The payment passes every mandate check, is held in escrow, and is released to the supplier only when the owner confirms delivery. Then the stock is added back to the shelf.
4. Price above the limit, budget exceeded, or no approved supplier: the agent does not buy, and tells the owner instead.

Demo rules for Thandi: White Bread, Maize Meal (10kg) and Milk restock below 5, from Ubuntu Foods, R3,000/month budget. Sell 8 loaves of bread at the till to see it trigger.

## Connecting the LLM (Google AI Studio / Gemini)
The agent works without a model (rule-based). With a key, **Gemini decides** what to do and acts only through the agent's guarded tools, so the mandate, budget, escrow and audit rules still apply whatever the model says.

1. Create a key at https://aistudio.google.com (Get API key).
2. Start the backend with the key in the environment. Never put it in code, the frontend or git.

       # PowerShell
       $env:GEMINI_API_KEY = "<your key>"
       $env:GEMINI_MODEL = "gemini-flash-latest"   # optional; any Gemini model your key can use
       cd backend; mvn spring-boot:run

3. **The real case:** sell bread at the Till until fewer than 5 are left. Gemini receives the stock, the mandate limits and the open orders, decides to call `PLACE_RESTOCK_ORDER`, and the till shows its explanation. You can also use **Finance Agent → Ask the agent**.

If the model errors, hits quota or does not act, the owner's restock rule is applied anyway. Limits: 5 tool steps per run and 40 runs per owner per day. Data sent to Google: stock levels, mandate limits, open order ids and supplier names; no people's names, emails, IDs or credentials.

## Demo flow (about 5 minutes)
1. **Owner (Thandi):** open **Finance Agent**, then click **Let the agent draft a group order** for a low-stock item. The agent drafts an order for Thandi and Sipho; no money moves.
2. In the **Agent console**, run `UPDATE_MANDATE`, `TRANSFER_FUNDS` or `READ_IDENTITY_DOCUMENTS`. Each one is refused and logged. Run `RECOMMEND_SUPPLIER` to see the prompt-injection text in Ubuntu Foods' description flagged as untrusted data.
3. **Group Orders:** approve your share. Shares over R1,000 ask for a step-up code. The mandate is checked, then your share moves into escrow.
4. **Sipho:** sign in in another browser profile and approve his share. The supplier minimum is met, so the order is now **funded**.
5. **Supplier (Ubuntu):** accept, dispatch and mark delivered.
6. **Both owners** confirm receipt. The escrow is released directly to the supplier's verified payout address.
7. **Mandates:** lower your per-order cap (step-up needed), then try to approve a bigger share. It is blocked with a reason.
8. **Audit Log:** click **Verify chain again**. Every step above is there, hash-chained.
9. Optional: **Report non-delivery** on a funded order, then sign in as a reviewer, open **Dispute Reviews**, read the evidence pack and decide with written reasons.

## Security

Status key: **Real** means implemented and enforced in code, and covered by tests where noted. **Simulated** means it works in the demo but uses a stand-in, labelled as such. **Missing** means not built.

### Priority 1: core financial controls

| Control | Status | Where |
|---|---|---|
| Spending mandate enforced outside the agent: per-order cap, daily cap, approved-supplier allow-list, valid-until date, order-deadline rule | **Real** (persisted) | `finance/MandateService`, `SpendingMandate` |
| Every payment (human or agent) checked against the mandate **before** any ledger transaction; blocked payments return a reason and are logged | **Real** · tested | `MandateService.authorize`, `GroupOrderService.fund`, `XrplController.pay` |
| Agent has read-only mandate access: it receives only the `MandateReader` interface, which has no write method | **Real** · tested | `FinanceConfig.agentGateway`, `AgentGatewayTest` |
| Only the authenticated owner can change their mandate, through a separate endpoint, with a step-up code | **Real** · tested | `MandateController` (`PUT /api/mandates`) |
| Escrow: member funds are held until every paying buyer confirms delivery, then released **directly** to the verified supplier | **Real logic** on a **simulated ledger** · tested | `GroupOrderService`, `SimulatedXrplLedger` |
| Refund when the supplier minimum is not met by the deadline, or delivery is not confirmed in time | **Real** · tested, checked every 30 s | `GroupOrderService.processDeadlines` |
| No agent or member intermediary wallet: escrow destination is fixed at creation; the ledger refuses any destination that is not a verified supplier payout address | **Real** · tested | `SimulatedXrplLedger.requireVerified` |
| Agent allow-list (detect shortages, recommend, notify, draft order, retry an approved payment, read mandate); everything else refused and logged | **Real** · tested | `agent/AgentGateway` |
| Untrusted text (supplier descriptions, chat) is cleaned, checked for injection patterns, flagged and never acted on; `asModelData` wraps it in delimiters for any future LLM | **Real** (pattern-based, not exhaustive) · tested | `agent/UntrustedText` |
| LLM agent (Gemini, optional): the model can only call the guarded tools; key server-side only; minimal data sent; supplier text wrapped as untrusted; jailbroken tool calls are refused | **Real** · tested with a scripted fake model and a local Gemini-format endpoint (not yet against live Gemini) | `agent/LlmAgent`, `GeminiClient`, `LlmAgentTest` |
| Agent has no access to identity documents, secrets or wallets | **Real** (no such references exist) · tested | `AgentGatewayTest` |
| Append-only, hash-chained audit log (who, what, when, inputs, outputs) for agent actions, mandate checks and changes, payments, approvals, sign-ins and disputes; tamper detection | **Real** · tested | `audit/AuditLog`, `GET /api/audit/verify` |
| Per-member approval; everyone sees the same price and split; quantity-based split that adds up exactly | **Real** · tested | `GroupOrderService.split`, `approveShare` |
| Autonomous restock: only for products with an owner-set rule, only the rule's quantity, supplier and price limit, within a monthly agent budget, no duplicate while an order is open, paid into escrow | **Real** · tested | `AgentGateway` (`PLACE_RESTOCK_ORDER`), `MandateService.updateAutonomy`, `AutoRestockTest` |

The agent itself is rule-based in this build. The required system prompt is stored in `AgentGateway.SYSTEM_PROMPT` and shown in the UI. No LLM is called yet. If one is connected, it must call `AgentGateway.execute` as its only tool.

### Priority 2

| Control | Status |
|---|---|
| Email/password sign-in, bcrypt hashing | **Real** |
| Password rules (8+ chars, letter + number, not containing the email name) | **Real** |
| Breached-password check | **Simulated**: small local list of common breached passwords (no HaveIBeenPwned call) |
| Mandatory one-time code on every sign-in (random, hashed, 5-min expiry, 5 attempts) | **Real**; **SMS delivery simulated** (code shown on screen) |
| Passkeys / biometrics, Google OpenID Connect | **Missing** |
| Account lockout (5 failures → 15 min) and per-IP rate limits | **Real** (in memory, per server) |
| CAPTCHA after repeated failures | **Missing** |
| 15-minute opaque access tokens stored as hashes; refresh-token rotation with reuse detection (reuse signs out every session) | **Real** (in memory: sessions end on restart) |
| New-device alerts, sign out all devices | **Real** in-app; email/SMS **simulated** |
| Step-up code for large order approvals (> R1,000), payments > R1,000, mandate changes | **Real** |
| Step-up for adding credit, changing bank details, withdrawals | **Missing** (these features don't exist yet) |
| Role-based access control, deny by default (OWNER / SUPPLIER / ADMIN) on every `/api` endpoint | **Real** |
| Identity levels: Level 1 at sign-up; Level 2 required to order or pay | **Real** enforcement; **verification simulated** (demo accounts pre-verified, no ID/liveness/registry provider, no four-eyes review UI, no encrypted document vault) |
| Supplier trust: only verified suppliers receive orders; trial limit R3,000 until 3 completed deliveries; ratings only from paying buyers of completed orders | **Real** |
| Sanctions / watch-list screening | **Simulated** (local list) |
| Fraud flags: unusually large share, rapid repeated orders (flag only, shown to members and logged) | **Real**, basic; new-device, duplicate-identity and behaviour-change signals **missing** |
| Credit, affordability checks, licensed credit partner, debit mandates | **Simulated** display only; no lending logic |
| Disputes: buyer opens a non-delivery dispute and escrow stays held; evidence pack from platform records with SHA-256 fingerprint; agent summary with sources and confidence; decision by an ADMIN who is not a party, with written reasons | **Real** |
| Two-reviewer panel, 14-day appeal, account freezing, graduated sanctions, notices in each party's language | **Missing / simulated in text only** |

### Priority 3: platform

| Control | Status |
|---|---|
| Security headers (`nosniff`, `X-Frame-Options: DENY`, CSP and `no-store` on API, HSTS over HTTPS) | **Real** (`SecurityHeadersFilter`) |
| CORS limited to configured frontend origins (`spaza.cors.allowed-origins`) | **Real** |
| Input validation, output encoding (`esc()` on every rendered value), parameterised queries (JPA) | **Real** |
| API rate limiting (300/min per IP; 30/min on sign-in endpoints) | **Real** (in memory) |
| H2 web console disabled by default (`SPAZA_H2_CONSOLE=true` to enable locally) | **Real** |
| TLS | **Deployment**: terminate TLS at the host (Render provides HTTPS); not configured in the app |
| AES-256 at rest / field-level encryption | **Missing**: the H2 file database is not encrypted |
| Secrets in environment variables or a vault | No secrets or wallet seeds exist in this build (ledger is simulated); `.gitignore` excludes `.env`, seeds and `data/` |
| Card data | None stored; use a PCI DSS-compliant tokenising provider if cards are added |
| Dependency scanning / secure-coding checks in CI | **Missing** (recommend Dependabot + OWASP dependency-check) |
| Persistence | Mandates, users, ratings and the audit log are persisted. **Group orders, sessions and the simulated ledger are in memory and reset on restart.** |

### Compliance notes (not legal advice)
- **POPIA:** collect the minimum personal data, state the purpose, give access/correction/deletion rights, and report breaches. Identity documents belong in a separate encrypted vault with strict access logging. Community insights should be aggregated and anonymised.
- **FICA:** customer due diligence (the Level 2 verification) and record-keeping for suppliers and owners. Real verification needs an accredited KYC provider.
- **National Credit Act:** credit may only be offered through a registered credit provider. This demo does not lend money.
- **PCI DSS:** never store card numbers; use a tokenising payment provider.
- **Payments / escrow:** holding client funds in escrow needs a licensed partner or bank trust account. Here escrow is only simulated.
