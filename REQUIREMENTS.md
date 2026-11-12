# Requirements — Bloomary android

Format and evidence rules: [CONTRIBUTING.md](CONTRIBUTING.md). Check: `scripts/trace-check.sh REQUIREMENTS.md app/src/test`.

| ID | Requirement (When X, Y.) | Source | Status |
|---|---|---|---|
| AND-001 | When a signed-in member saves a draft, the app queues it and uploads it with `POST /api/drafts/sync`, and drafts saved on another device appear in the list after a refresh; with "Reduce data usage" on, nothing is uploaded. | iOS parity (DraftStore sync), Kat 2026-11-11 | active |
| AND-002 | When a member publishes a saved draft, the post request carries its `draftId` and the LinkedIn token in the `Authorization` header only. | IOS-002 | active |
| AND-003 | When a member taps Unschedule on a scheduled draft, the app sends `DELETE /api/linkedin/schedule` with that draft's ID. | IOS-003 | active |
| AND-004 | When the editor differs from the open saved draft only by leading or trailing whitespace, the app reports no unsaved changes; with real unsaved changes, opening another draft asks first. | IOS-004 | active |
| AND-005 | When the API rejects the LinkedIn token on draft sync (HTTP 401), the app stops syncing and retrying, keeps the change queued, and signs LinkedIn out asking the member to reconnect. | IOS-005 | active |
| AND-006 | When a member taps Naturalize English before allowing AI, the app asks to send the draft to Google Gemini and sends nothing until they tap Allow; Allow is remembered until account deletion. | IOS-006 | active |
| AND-007 | When the server reports the `lapsed` tier, existing drafts are read-only (no edit, delete, publish or schedule) and the banner names whether the trial or the subscription ended. | `docs/product/tiers.md`, iOS parity | active |
| AND-008 | When a draft upload fails, the app retries with backoff and parks it after 5 attempts, or at once on a 400/413/422 rejection, until the member taps Retry sync. | iOS DraftSyncPolicy parity | active |
<!-- Add rows like:
| AND-001 | When <condition>, <system> <observable result>. | <issue / spec link> | active |
-->
