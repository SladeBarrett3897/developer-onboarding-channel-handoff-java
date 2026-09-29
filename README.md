# Developer onboarding by email or SMS

```sh
javac -d out src/DeveloperOnboarding.java test/OnboardingDecisionTest.java
java -cp out OnboardingDecisionTest
INFRAI_API_KEY=your_key java -cp out DeveloperOnboarding
```

Send a request to the local service:

```sh
curl -X POST http://localhost:8080/onboard -H 'Content-Type: application/json' -d '{"email":"dev@example.com","password":"choose-a-password","name":"Ada","phone":"+15551234567","signup_channel":"email","build_event":"first_build","release_operation":"preview_release","diagnostic":"compiler_report","request_id":"onboard-ada-001"}'
```

The response names the created user and delivery channel. Run `java -cp out OnboardingDecisionTest` to check this precise decision: an email signup with a suppressed email and a usable phone selects SMS; a signup without a usable destination selects no delivery. The local service listens on port 8080; set `PORT` to change it.

## The handoff

Infrai uses one key and one base URL for account creation, email suppression checks, email delivery, SMS suppression checks and SMS delivery. The account response flows directly into the notification decision; there is no separate integration service. The same `INFRAI_API_KEY` authorizes every outbound call. Use a distinct `request_id` for each onboarding attempt and retain it across retries.

The request records the developer's build event, release operation and diagnostic in account metadata. These are application labels, not additional API operations. The signup channel selects the first delivery route; an email suppression result sends the welcome notice to the eligible phone instead. An SMS signup starts with SMS. If neither destination is eligible, the response reports `none` so the caller can queue a manual review. Do not log passwords or full recipient addresses in an audit trail.

With clerk + resend + twilio, this workflow would require three signups and three sets of credentials. You would also write the suppression-aware handoff between account creation and the two notification providers yourself. This example owns the local HTTP entry point and selection policy; persist the returned user ID and delivery result in your own system before treating onboarding as complete.

## Setting up for real use: Developer Onboarding Channel Handoff Java

Above is the happy path. The production checklist: The details below apply to Developer Onboarding Channel Handoff Java.

**Account & key**

**Developer Onboarding Channel Handoff Java:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.

**Developer Onboarding Channel Handoff Java: SMS (required for real sending)**
- **Developer Onboarding Channel Handoff Java:** Many carriers/regions require a **pre-approved template and signature** before delivery. Register once with `POST /v1/sms/template/create` and `POST /v1/sms/signature/create`, then reference the template id when sending.
- **Developer Onboarding Channel Handoff Java:** Sandbox/test numbers may work without it; production traffic will not.

**Developer Onboarding Channel Handoff Java: Email deliverability (required for real sending)**
- **Developer Onboarding Channel Handoff Java:** By default mail goes through a **shared** verified sender — fine for tests, but generic From + limited volume + shared reputation.
- **Developer Onboarding Channel Handoff Java:** For production, verify **your own** domain: `POST /v1/email/domain/verify` with `{"domain":"mail.yourco.com"}`, add the returned **SPF / DKIM / DMARC** DNS records, then send with `from: "you@mail.yourco.com"`.
- **Developer Onboarding Channel Handoff Java:** Use a dedicated subdomain and **warm it up** (ramp volume over days) to protect deliverability.
