# CollectFlow

Portfolio reference implementation of a 2023 project, built to show an accounts-receivable collections (dunning) workflow. It runs reminders, calls, credit holds and agency referrals for overdue B2B accounts, and gives collectors a prioritized worklist.

**Stack:** Java 17, Spring Boot 3.1, Camunda 7.20 (embedded engine, REST, Cockpit/Tasklist), PostgreSQL, React 18 + TypeScript (Vite).

## Process (`backend/src/main/resources/bpmn/dunning.bpmn`)
```
start → Decide next step ─┬─ WAIT ────────→ timer (${waitDuration}) ─┐
                          ├─ reminder/notice → Send notice (async, 3 retries) ─┤
                          ├─ CALL_TASK → [Call customer] (collectors, 2-day SLA) → Record outcome ─┤
                          ├─ CREDIT_HOLD → Place credit hold ────────────┤
                          ├─ AGENCY_REVIEW → [Approve agency referral] → end
                          └─ STOP → end                                    └→ back to Decide
event subprocess: message "PaymentReceived" interrupts any step → re-evaluate → done, or new cycle if partial
boundary error NO_CONTACT on Send notice → [Find billing contact] (AR clerks)
```
- **Rules live in Java, not in gateways.** `DunningPolicy` is the single, unit-tested place where finance reviews the ladder:
  - Grace period, then a friendly reminder, first notice, collector call, final notice, credit hold (60 days) and agency review (90 days).
  - **Segments:** strategic accounts get an account-manager call at 15 days and are never sent to an agency automatically. Small balances skip the call.
  - **Pauses:** dunning stops while a dispute is open or a promise-to-pay is active.
  - **Escalation:** each broken promise pulls the schedule forward a week.
  - **No duplicates:** each communication is sent at most once per stage.
- The loop re-reads AR on every pass, so payments, disputes and promises recorded elsewhere take effect immediately.
- Sending a notice is an async job with a retry cycle, so an email outage doesn't fail the process.

## Worklist (`web/`)
- Reads Camunda's REST task API.
- **Priority** combines a log-scaled balance, age, segment and SLA breach, so one huge account doesn't bury everything else.
- Hides tasks claimed by other collectors.
- Claim and complete with call outcomes. A promise-to-pay must be a date within 45 days; longer promises need a payment plan.

## Run and test
```bash
cd backend && mvn test             # DunningPolicy: ladder, segments, no-repeat, pauses, broken promises
cd backend && mvn spring-boot:run  # Camunda Cockpit at :8080/camunda
cd web && npm install && npm test && npm run build   # 3 tests (priority, worklist, promise validation)
```
