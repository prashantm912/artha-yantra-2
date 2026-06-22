# deploy/secrets/ — Docker secret files (A.9 / ADR D13)

Everything in this directory **except this README and `.gitkeep` is
gitignored**. Secrets live outside the repo and outside images; compose mounts
them at `/run/secrets/` per service.

| File (create locally) | Contents | Consumed by |
|---|---|---|
| `postgres_password` | single-line strong generated password | timescaledb (`POSTGRES_PASSWORD_FILE`) + services |
| `kite_api_key` | brand-new 2.0 Kite API key (A6) | market-data-service only (live mode) |
| `kite_api_secret` | its secret | market-data-service only (live mode) |
| `artha_master_key` | 256-bit base64 AES-GCM key (Stage B token store) | market-data-service |
| `openalgo_api_key` | OpenAlgo's OWN generated API key (from its UI; NOT a broker secret) | market-data-service (only when `artha.marketdata.source.*=openalgo`, plan §3/§4) |
| `upstox_analytics_token` | dedicated long-lived Upstox **analytics** access token (Developer Apps; SEPARATE from any live broker session) | market-data-service (only when `artha.upstox.analytics.enabled=true`, ADR-0002) |

Mock mode (`SPRING_PROFILES_ACTIVE=mock`) needs **only `postgres_password`**
(the database always requires one); no Kite material is ever present.

Generate examples:

```powershell
# strong password
-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 32 | % {[char]$_}) |
  Set-Content -NoNewline -Encoding ascii deploy/secrets/postgres_password

# 256-bit base64 master key
$b = New-Object byte[] 32; (New-Object Security.Cryptography.RNGCryptoServiceProvider).GetBytes($b)
[Convert]::ToBase64String($b) | Set-Content -NoNewline -Encoding ascii deploy/secrets/artha_master_key
```

Never echo these files into logs or shell history; the Kite `access_token`
itself is **never** a file or env var — it lives AES-GCM-encrypted in Postgres
(Stage B, Flow 1).
