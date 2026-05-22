# api-private-router Java Backend

This module provides the backend runtime for api-private-router. The Docker, release, CI, and systemd paths build and run this Spring Boot service against PostgreSQL.

## Database

Use PostgreSQL for the shared runtime path. Do not switch this backend to MySQL 8: the schema and queries rely on PostgreSQL features such as `jsonb`, `timestamptz`, `ilike`, `returning`, array predicates, and partial indexes.

## Java-Owned Areas

- User center APIs are handled in Java, including profile, password, affiliate transfer, user API keys, user usage, subscriptions, groups, announcements, notification email, and TOTP-related user flows.
- Admin APIs are handled in Java for users, auth identities, groups, accounts, API keys, subscriptions, usage cleanup, announcements, redeem/promo codes, settings, dashboard, proxies, backups, system operations, channels, channel monitors, risk control, affiliate, user attributes, TLS fingerprints, and error-passthrough rules.
- Payment APIs are handled in Java for user checkout/order flows, admin payment config/order/channel/plan/provider management, provider webhooks, and fulfillment paths for EasyPay, Alipay, WxPay, and Stripe.
- Gateway APIs are handled in Java for OpenAI-compatible chat, responses, messages, images, models, Gemini, Anthropic, and the admin/gateway WebSocket paths currently used by this repo.
- Deprecated `admin/data-management` routes remain disabled. The health endpoint returns a disabled/deprecated payload, and non-health actions return `503 DATA_MANAGEMENT_DEPRECATED`.

## Remaining Caveats

- Payment provider configuration still needs hardening: encrypted secret lifecycle, stronger validation parity, secret rotation, and runtime refresh behavior should be completed in Java.
- EasyPay has Java checkout/webhook coverage, but direct query/cancel/refund parity is still weaker than Alipay/WxPay/Stripe.
- Some admin dashboard/capacity metrics are DB-backed approximations until every runtime cache counter has an equivalent Java source.
- Unsupported Responses API HTTP methods/subpaths intentionally return unsupported errors because this Java backend exposes only the supported route surface.

## Build And Test

```powershell
cd java-backend
$env:JAVA_HOME='D:\soft\java\jdk17'
$env:Path='D:\soft\java\jdk17\bin;D:\soft\apache-maven-3.9.12\bin;' + $env:Path
mvn test
```

Verified locally with the Java backend test suite.
