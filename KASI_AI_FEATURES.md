# Kasi AI Studio — implementation notes

## Included
- AI Studio navigation for shop owners, and 11-language selector with initial translations and English fallback.
- Rule-based, data-grounded assistant for sales, profit, inventory, wallet, and stock recommendations.
- Inventory-based business pulse; stock reorder proposals; verified supplier name matches; gross-margin what-if calculator.
- SpeechRecognition and SpeechSynthesis progressive enhancement where supported by the browser and installed voices.
- No autonomous purchases, payments, or pricing changes. Existing marketplace owner controls remain in charge.

## Important limitations
- This is not yet an LLM-backed chatbot. Connect a backend-only model provider and authenticated, scoped tool access before marketing it as generative AI.
- UI localization is partial: the new studio and some common labels have translations; existing pages are not fully localized. Native-speaker review is required, especially for financial terminology.
- Browser speech recognition and voices for South African languages vary and may be unavailable.
- Health score is an illustrative inventory-risk heuristic, not a credit or financial health rating.
- Stock recommendations use threshold arithmetic, not historical demand forecasting.
- POS sales history is in-memory and cumulative; it cannot reliably represent today's sales or expenses.
- Supplier comparison uses exact product-name matching; supplier quantity units must be verified before purchasing.
- Wallet balances shown are XRPL Testnet balances, not actual spendable cash.

## Running
From `frontend`, run `npm install` then `npm run dev`; start the existing Java backend separately as described in README. Sign in as shop owner and open **Kasi AI Studio**. The Studio requires backend API access for inventory data.

## Suggested next development steps
1. Add authenticated Spring Boot AI gateway with server-side provider key and strict role-based authorization.
2. Add durable transaction and expense history, shop scoping, and time-filtered financial reporting.
3. Add localization dictionaries for every existing page and native-language quality review.
4. Add audited agent proposals with explicit approval and mandate checks.
5. Build time-series stock forecasts from actual sales and supplier lead times.
