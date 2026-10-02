# Privacy

Morsel has no analytics, no telemetry, no crash reporting and no advertising. It contains no tracking SDKs.

## What the app stores (all local, all private to the app)

- **Bound feeder serial, cat name, portion cap, haptics, reduce-motion, demo mode** — plain local preferences in the app's no-backup storage.
- **Operation journal** — one record per feeding attempt (local operation id, serial, portions, request id, timestamp, honest outcome state) in DataStore, no-backup storage. It exists so nothing can be sent twice when an outcome is unknown; it never leaves the device.
- **Credentials** — the Petlibro email and a password-equivalent digest, plus the session token, encrypted with AES-GCM under an Android Keystore key; ciphertext lives in no-backup storage. The raw password is never persisted. `android:allowBackup=false` and transfer exclusions keep all of this out of backups and device-to-device copies.

## What leaves the device

Only requests to `https://api.us.petlibro.com`: login, device list, device status, one manual-feeding write when the user deliberately taps Feed, and feeder history reads. Nothing else is contacted.

## What is never logged or shared

- No HTTP bodies, credentials, tokens, serials or account identifiers are logged. The feed HTTP client has no retry/authenticator/redirect behavior that could duplicate writes, and it logs nothing.
- Diagnostics are limited to state codes and locally generated operation ids. Sanitized test artifacts (unit/instrumented reports, screenshots, logcat) come from fake/mock transports only; demo mode uses no account and no network.
- CI runs never hold real account credentials and never issue real feeding commands.

## Permissions

`INTERNET` only. No overlay permission, no accessibility services, no background services, no notifications.

## Demo mode

Demo mode simulates a feeder entirely on-device. It dispenses nothing, uses no account, and shares no state with the real world besides the local portion cap.
