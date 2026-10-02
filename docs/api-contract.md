# Petlibro cloud protocol contract

Adapted from the pinned upstream integration source (GPL-3.0):
<https://github.com/jjjonesjr33/petlibro/tree/7ee757fa8f76f628af2e845a301335656e9919a1>
Files read as data: `custom_components/petlibro/api.py`,
`devices/device.py`, `devices/feeders/air_smart_feeder.py`. Upstream was used
for field names and shapes only; its logging, retry and refresh behavior was
deliberately not copied (Morsel never retries a write).

Base URL: `https://api.us.petlibro.com` (US region only; no EU host is used or invented).

## Common request headers

| Header | Value |
| --- | --- |
| `source` | `ANDROID` |
| `language` | `EN` |
| `timezone` | actual IANA zone of the device (e.g. `Europe/Amsterdam`) |
| `version` | `1.3.45` |
| `Content-Type` | `application/json` |
| `token` | session token (all calls except login) |

Permalink: [api.py lines 44-49](https://github.com/jjjonesjr33/petlibro/blob/7ee757fa8f76f628af2e845a301335656e9919a1/custom_components/petlibro/api.py#L44)

## Response envelope

Every response is JSON `{"code": int, "msg": string?, "data": any}`.

- `code == 0` means success. `data` may be `0`, `null`, an object or an array;
  parsers must accept all shapes.
- `code == 1009` means authentication expiry (`NOT_YET_LOGIN`).
- Unknown codes are treated conservatively as UNKNOWN by Morsel; only a
  documented explicit rejection becomes REJECTED.
- Non-200 HTTP status is an error. Upstream retries 5xx; Morsel does not retry
  writes and retries reads at most once only for 1009 re-login.

## Login

`POST /member/auth/login` ([api.py L236-253](https://github.com/jjjonesjr33/petlibro/blob/7ee757fa8f76f628af2e845a301335656e9919a1/custom_components/petlibro/api.py#L236))

```json
{
  "appId": 1,
  "appSn": "c35772530d1041699c87fe62348507a8",
  "country": "US",
  "email": "user@example.com",
  "password": "<lowercase MD5 hex digest of the UTF-8 password>",
  "phoneBrand": "",
  "phoneSystemVersion": "",
  "timezone": "Europe/Amsterdam",
  "thirdId": null,
  "type": null
}
```

Success: `{"code": 0, "data": {"token": "..."}}`. The MD5 digest is
password-equivalent and is itself stored only AES-GCM encrypted by Morsel.

## Device list

`POST /device/device/list` with empty JSON body `{}` ([api.py L534](https://github.com/jjjonesjr33/petlibro/blob/7ee757fa8f76f628af2e845a301335656e9919a1/custom_components/petlibro/api.py#L534)).

Success `data` is an array of device objects. Fields read by Morsel (field
names per [device.py L85-109](https://github.com/jjjonesjr33/petlibro/blob/7ee757fa8f76f628af2e845a301335656e9919a1/custom_components/petlibro/devices/device.py#L85)):

| Field | Meaning |
| --- | --- |
| `deviceSn` | serial |
| `productIdentifier` | model identifier |
| `productName` | marketing model name |
| `name` | user-visible device name |

Synthetic fixture (illustrative; not captured from a live account):

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "id": 123456,
      "deviceSn": "PLAF108000001",
      "productIdentifier": "PLAF108",
      "productName": "Smart Air Pet Feeder",
      "name": "Living room feeder",
      "deviceShareState": 3,
      "mac": "AA:BB:CC:DD:EE:FF",
      "softwareVersion": "1.2.3",
      "hardwareVersion": "A"
    }
  ]
}
```

Morsel binds only exactly one PLAF108 (`productIdentifier == "PLAF108"`).
Zero matches shows a setup message; multiple matches require an explicit serial
choice; a saved serial that is absent from the list fails closed (never
substitutes the first item).

## Real-time device info

`POST /device/device/realInfo` with `{"id": <sn>, "deviceSn": <sn>}`
([api.py L279-282](https://github.com/jjjonesjr33/petlibro/blob/7ee757fa8f76f628af2e845a301335656e9919a1/custom_components/petlibro/api.py#L279)).
Morsel reads `online` from the response object
([air_smart_feeder.py L109-110](https://github.com/jjjonesjr33/petlibro/blob/7ee757fa8f76f628af2e845a301335656e9919a1/custom_components/petlibro/devices/feeders/air_smart_feeder.py#L109)):
`{"code": 0, "data": {"online": true, ...}}`. An offline feeder blocks submission.

## Manual feeding (THE WRITE)

`POST /device/device/manualFeeding` ([api.py L1195-1199](https://github.com/jjjonesjr33/petlibro/blob/7ee757fa8f76f628af2e845a301335656e9919a1/custom_components/petlibro/api.py#L1195))

```json
{
  "deviceSn": "PLAF108000001",
  "grainNum": 1,
  "requestId": "0f8fad5bd9cb469fa259e5371b8a9c21"
}
```

- `grainNum` is an integer portion count (volume portions, not grams). PLAF108
  accepts 1..16; Morsel validates in the domain layer and the client.
- `requestId` is a UUID without hyphens, generated once per operation and
  frozen with the operation ([api.py L1191](https://github.com/jjjonesjr33/petlibro/blob/7ee757fa8f76f628af2e845a301335656e9919a1/custom_components/petlibro/api.py#L1191)).
- Success envelope may carry `data: 0` or `data: null`; both count as cloud
  acceptance (ACCEPTED_UNCONFIRMED), never as physical completion.
- Morsel sends this request exactly once per operation: no retry on timeout,
  I/O error, 5xx, malformed body, cancellation, or 1009. Redirects are never
  followed.

## Work records (feeder history)

`POST /device/workRecord/list` ([api.py L435-441](https://github.com/jjjonesjr33/petlibro/blob/7ee757fa8f76f628af2e845a301335656e9919a1/custom_components/petlibro/api.py#L435))

```json
{
  "deviceSn": "PLAF108000001",
  "startTime": 1767225600000,
  "endTime": 1769817600000,
  "size": 25,
  "type": ["GRAIN_OUTPUT_SUCCESS"]
}
```

`startTime`/`endTime` are epoch milliseconds (upstream uses a 30-day window;
Morsel uses the same window for reconciliation reads). Success `data` is an
array of day entries containing `workRecords` arrays. Fields used upstream:
`type == "GRAIN_OUTPUT_SUCCESS"`, `recordTime` (epoch ms),
`actualGrainNum` (int) —
[air_smart_feeder.py L220-249](https://github.com/jjjonesjr33/petlibro/blob/7ee757fa8f76f628af2e845a301335656e9919a1/custom_components/petlibro/devices/feeders/air_smart_feeder.py#L220).

Synthetic fixture:

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "day": "2026-10-01",
      "workRecords": [
        {
          "type": "GRAIN_OUTPUT_SUCCESS",
          "recordTime": 1769817000000,
          "actualGrainNum": 3
        }
      ]
    }
  ]
}
```

**Correlation caveat (drives Morsel's conservative history policy):** the
pinned upstream source never reads any request/operation correlation field
from work records; only `type`, `recordTime` and `actualGrainNum` are
observed. Unless a proven matching request/operation ID plus serial is
present in a record, Morsel will NOT convert time+amount into success.
Records are therefore surfaced separately as "Feeder history". If a record
does carry a correlation ID matching the operation, equality of
`actualGrainNum` yields REPORTED_SUCCESS and difference yields
REPORTED_MISMATCH (no automatic top-up); duplicates and out-of-order records
cannot confirm twice. Malformed entries are rejected, not guessed.

## What Morsel deliberately does not implement

- No 5xx/timeout retry loop (upstream retries reads and writes; Morsel never
  retries the write and at most re-logins once for reads).
- No logging of request/response bodies (upstream debug-logs them).
- No feeding-plan, camera, fountain, litter box or other device types.
