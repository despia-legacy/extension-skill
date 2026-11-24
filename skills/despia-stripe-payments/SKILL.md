---
name: despia-stripe-payments
description: Add or debug native Stripe payments in a Despia V3 web-view app with stripe://payment (PaymentSheet) and stripe://manage (CustomerSheet): Payment Intents, saved cards, styling, Apple Pay and Google Pay setup and per-call wallet params, stripeEvent results and server confirmation. V3 only.
---

# Despia Stripe Payments

Needs Stripe on in the Despia V3 app and a new build. Calls are `despia('stripe://payment?...')` (PaymentSheet) and `despia('stripe://manage?...')` (CustomerSheet) from `despia-native`; every result arrives once on `window.stripeEvent({ method, status, error?, paymentOption? })`.

## The flow

1. On your server, create the Payment Intent (and for saved cards the customer and ephemeral key); send only the client secret and publishable key to the page.
2. Define `window.stripeEvent` before the call.
3. Call `stripe://payment` with `publishable_key` and `payment_intent_client_secret`, plus styling and wallet params if needed.
4. On `completed`, confirm the payment on your backend before fulfilling.
5. For saved methods, call `stripe://manage` with `publishable_key`, `customer_id`, `ephemeral_key_secret`, optional `setup_intent_client_secret`; read `paymentOption` on `selected`.
6. Apple Pay and Google Pay need their setup (merchant ID, Google Pay setting) and a new build; `apple_pay=never` or `google_pay=never` leaves one out of a call.

## Rules

- Never put a secret key in the page; only `pk_` keys and client secrets.
- Match test and live modes: a `pk_test_` key needs test secrets, and runs Google Pay in its test environment.
- Never treat the event alone as payment proof; confirm on the server.
- A missing wallet never fails a call; the sheet shows other methods.
- A build without Stripe fires `despia-feature-not-included` with `detail.scheme` `stripe`.
- Prove it in the installed app on a device; a browser preview has no native sheet.

The full guide is `references/guide.md`, generated from https://setup.despia.com/payments/stripe/payment. Where this file and the guide differ, the guide is right; report it.
