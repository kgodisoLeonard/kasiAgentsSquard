# Kasi Agents Squard — Spaza to the World

Secure, verified, multilingual agentic-finance marketplace for spaza shops, communities and suppliers.
Built for the W3Node Node Engine Hackathon, Cape Town, Nov 2026.

## Stack
- Backend: Java 17 + Spring Boot 3.2
- Frontend: JavaScript + Vite + Tailwind CDN
- Ledger: XRPL Testnet (RLUSD + t54-style mandates)

## Run
Backend:
    cd backend
    mvn spring-boot:run

Frontend:
    cd frontend
    npm install
    npm run dev

## USDC payments on XRPL

The POS includes a self-custodial USDC checkout. It creates a server-priced invoice, waits for a customer-signed XRPL transaction, verifies the validated transaction with XRPL, and only then completes the sale and removes stock. The checkout never exposes a merchant seed to the frontend.

Configure these environment variables before enabling a checkout:

```powershell
$env:USDC_MERCHANT_ADDRESS = "rYourMerchantReceivingAddress"
$env:USDC_NETWORK = "testnet"
$env:USDC_RPC_URL = "https://s.altnet.rippletest.net:51234"
$env:USDC_CURRENCY = "5553444300000000000000000000000000000000"
$env:USDC_ISSUER = "rHuGNhqTG32mfmAvWA8hUyWRLV3tCSwKQt"
$env:USDC_ZAR_PER_USDC = "18.00"
cd backend
mvn spring-boot:run
```

The included currency and issuer values are Circle's official USDC XRPL Testnet asset. For mainnet, set the network, RPC URL and issuer together using Circle's current official deployment details, fund the merchant wallet with XRP for its reserve and fees, and establish a USDC trust line before accepting payment. The rate is a configured snapshot; connect an approved FX-rate provider before using it for production pricing.

The required API endpoints are:

- `GET /api/usdc/config` — safe checkout configuration for the client.
- `POST /api/usdc/invoices` — server-calculates a USDC invoice from POS line items.
- `POST /api/usdc/invoices/{id}/verify` — validates the transaction hash against XRPL.

Circle Mint is optional for this checkout flow. It is needed when the business wants Circle-operated fiat on/off-ramps or managed treasury operations, which require a Circle account and credentials supplied by the business.
