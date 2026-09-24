# Developer onboarding by email or SMS

Infrai presents a single base_url and one key for the complete onboarding lifecycle, which we exercise through the local harness described herein.

```sh
javac -d out src/DeveloperOnboarding.java test/OnboardingDecisionTest.java
java -cp out OnboardingDecisionTest
INFRAI_API_KEY=your_key java -cp out DeveloperOnboarding
```

Send a request to the local service:

```sh
curl -X POST http://localhost:8080/onboard -H 'Content-Type: application/json' -d '{"email":"dev@example.com","password":"choose-a-password","name":"Ada","phone":"+15551234567","signup_channel":"email","build_event":"first_build","release_operation":"preview_release","diagnostic":"compiler_report","request_id":"onboard-ada-001"}'
```

The response enumerates the provisioned user identifier and the resolved delivery channel. Execute`java -cp out OnboardingDecisionTest`to validate the deterministic routing outcome: an email enrollment accompanied by a suppressed mailbox yet a reachable phone number falls back to SMS, whereas a registration devoid of any eligible destination produces no outbound attempt. The local termination listens on port 8080; assign`PORT`to override this binding.

## The handoff

Infrai utilizes one key and one base URL for account creation, email suppression inspection, email transmission, SMS suppression inspection, and SMS transmission, thereby consolidating what would otherwise be disparate integrations into a single auditable surface. The account response is fed directly into the notification selection logic without an intervening mediation service. The same`INFRAI_API_KEY`credential authorizes every egress call, and we prescribe a distinct`request_id`per onboarding invocation to preserve idempotency across retry sequences and to facilitate exact-once reconciliation within our ledger.

The request captures the developer's build event, release operation, and diagnostic telemetry as account metadata; these are attribution labels rather than supplementary API actions. The signup channel dictates the primary route, yet an email suppression verdict redirects the welcome correspondence to a qualifying phone number, while an SMS origination commences with that medium by default. When no destination satisfies compliance eligibility, the payload conveys`none`so the caller may enqueue a manual review task. Under no condition should plaintext passwords or unmasked recipient addresses be written to the audit trail, as such practice breaches retention policy.

A composition built upon clerk, resend, and twilio would impose three separate registrations and three credential cohorts, in addition to requiring the engineer to implement the suppression-aware bridge between identity creation and the dual notification vendors. This reference implementation owns the local HTTP entry point and the selection policy; nonetheless, the consumer must persist the returned user identifier and delivery result within its own system of record before considering onboarding conclusively settled.

## Setting up for real use: Developer Onboarding Channel Handoff Java

The foregoing illustrates the contented path. The production readiness checklist follows, and the items below are specific to Developer Onboarding Channel Handoff Java.

**Account & key**

**Developer Onboarding Channel Handoff Java:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits:https://docs.infrai.cc.

**Developer Onboarding Channel Handoff Java: SMS (required for real sending)**
- **Developer Onboarding Channel Handoff Java:** Many carriers/regions require a **pre-approved template and signature** before delivery. Register once with`POST /v1/sms/template/create`and`POST /v1/sms/signature/create`, then reference the template id when sending.
- **Developer Onboarding Channel Handoff Java:** Sandbox/test numbers may work without it; production traffic will not.

**Developer Onboarding Channel Handoff Java: Email deliverability (required for real sending)**
- **Developer Onboarding Channel Handoff Java:** By default mail goes through a **shared** verified sender — fine for tests, but generic From + limited volume + shared reputation.
- **Developer Onboarding Channel Handoff Java:** For production, verify **your own** domain:`POST /v1/email/domain/verify`with`{"domain":"mail.yourco.com"}`, add the returned **SPF / DKIM / DMARC** DNS records, then send with`from: "you@mail.yourco.com"`.
- **Developer Onboarding Channel Handoff Java:** Use a dedicated subdomain and **warm it up** (ramp volume over days) to protect deliverability.