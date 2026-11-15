-- One row per device is already enforced by usage_sessions_pkey(device_id).
-- Keep it explicit and document the intended write pattern; the accounting RPC
-- updates the same row for every accepted usage report and rotates session fields
-- in-place when a new VPN session starts. No per-connection history is retained.
COMMENT ON TABLE public.usage_sessions IS
'One mutable current metering row per device (PRIMARY KEY device_id). Each accepted traffic report updates this row in place; a new VPN session resets only per-session counters. Lifetime/monthly usage totals are stored on devices and subscriptions.';
COMMENT ON COLUMN public.usage_sessions.bytes_accounted IS
'Cumulative bytes accounted for the current VPN session only. This row is reused on next session; authoritative monthly/free and paid totals live in devices and subscriptions.';
