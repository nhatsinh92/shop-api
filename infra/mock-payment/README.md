# Mock payment gateway (WireMock stub)

`POST /payments` on port 8090. Choose the outcome with the `X-Mock-Outcome` header:

| Header value | Response |
| --- | --- |
| (none) | `201` `{"status":"SUCCEEDED"}` |
| `fail` | `402` problem details, code `PAYMENT_DECLINED` |
| `timeout` | waits 30 s, then `504` |

`core` calls it through `PAYMENT_URL`. `make demo` also sets `PAYMENT_MOCK_OUTCOME_HEADER=true`, so
`POST /api/orders` forwards its `X-Mock-Payment-Outcome` header (`fail`, `timeout`) as `X-Mock-Outcome`.
`make demo-smoke-checkout` uses both outcomes. `core` stops waiting after `PAYMENT_TIMEOUT` (3 s), so
a `timeout` order answers `TIMED_OUT` long before the mock's 30 s delay ends.

## Demo caveat: the `timeout` outcome trips the payment breaker

`core` sends every charge through the circuit breaker `payment` (window of 20 calls, at least 10
calls, open at 50 % failures or slow calls, open for 10 s). A `timeout` outcome counts as a
failure, so about ten `timeout` orders within a 20-call window open the breaker. While it is open,
online orders fail at once without calling the mock, and `GET /api/checkout/payment-methods`
reports `EWALLET` and `CARD` as unavailable **for every demo user** for 10 s; COD is not affected.
This is expected. A `fail` outcome (`402`) is a decline and never opens the breaker. The k6
checkout scenario pays COD, so it never trips it.
