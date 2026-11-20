# Mock payment gateway (WireMock stub)

`POST /payments` on port 8090. Choose the outcome with the `X-Mock-Outcome` header:

| Header value | Response |
| --- | --- |
| (none) | `201` `{"status":"SUCCEEDED"}` |
| `fail` | `402` problem details, code `PAYMENT_DECLINED` |
| `timeout` | waits 30 s, then `504` |

The real client and its timeouts arrive in Phase 5 (checkout).
