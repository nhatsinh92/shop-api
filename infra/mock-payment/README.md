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
