# Operating recletters

## Creating a new call

A "call" is the unit of work: an award round, a hiring committee, a grant
call, etc. Create one via the CLI:

```sh
recletters -main tools.NewCall \
    'award-2026' 'My Award 2026' 2026-01-15
# slug, label, deadline (YYYY-MM-DD), then optional opens_at and grace seconds
```

Calls are independent of the calendar year, so multiple parallel calls and
one-offs are first-class. The "active" call is the most-recently-opening
non-archived call whose `deadline + grace_period` is still in the future.

## Sending invitations and reminders

From `/requests`, click *Send request emails* (sends to all `new` referee
requests for the active call) or *Send request email reminders* (re-emails
requests that have been `requested` for more than `reminder.days` days).

The reminder threshold and timezone are configurable:

```hocon
reminder.timezone = "UTC"     # set to your committee's timezone
reminder.days     = 7
```

The same operations are available as bearer-authed HTTP endpoints:

```sh
curl -X POST https://letters.example.org/api/sendRequestEmails \
     -H "Authorization: Bearer $RECLETTERS_TOKEN" \
     --data-urlencode "call=award-2026"
```

## Password reset

Committee members request a reset at `/init_password`. The response is the
same regardless of whether the address is registered: by design, to avoid
enumeration. Reset tokens expire after 48 hours.

To bootstrap a forgotten admin password without email, use the CLI:

```sh
recletters -main tools.AddUser admin@example.org Admin Name "new-password"
# If the user already exists, the INSERT fails; use a direct SQL UPDATE in
# that case, e.g.
#   UPDATE users SET password=crypt(...) WHERE email='admin@example.org';
# but compute the bcrypt hash via tools.AddUser on a throwaway DB first.
```

## Downloading letters

Committee members open `/requests`, click on a received status to download
the PDF. Letters are served with `Content-Disposition: attachment` and
`X-Content-Type-Options: nosniff`.

## Archival

Set `is_archived=true` on the `call_` row to remove it from the default
selector. Archived calls remain accessible via the URL if the id is known.

## Rotating the API token

Edit `api_token` in `/etc/recletters/secrets.conf` and restart the service.
Any cron-driven importers (e.g. the HotCRP companion script) must be updated
at the same time.
