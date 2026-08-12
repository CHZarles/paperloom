# Developer Specification: Redis Worker Safe Lease Recovery

> Version: 1.0  
> Date: 2026-08-12  
> Status: Proposed  
> Scope: PaperLoom Research Harness Redis Streams worker ownership and stale-job recovery

## 1. Purpose

This specification replaces the lease and stale-job behavior in sections 6.5, 7.3, 8.1, 8.2,
8.4, and 9.2 of the original
[Redis Streams Queue Spec](research-harness-redis-streams-queue-spec-2026-07-26.md).

It addresses the production incident in which `XAUTOCLAIM` reassigned a healthy long-running Job,
and the claiming Worker published `StalePendingJob` while the original Worker later completed
successfully.

The desired behavior is:

```text
valid lease exists
-> the current Run remains authoritative
-> no other Worker changes Pending ownership or publishes a terminal event

valid lease does not exist
-> a queued Job may be claimed and started
-> a previously running Job fails closed and is not executed again
```

## 2. Scope

### 2.1 In scope

- unique ownership for each concrete execution of a Generation;
- owner-checked lease renewal and release;
- stale Pending discovery without changing its Redis consumer;
- atomic lease check and `XCLAIM`;
- prevention of stale Worker progress, terminal events, ACK, deletion, and lease release;
- deterministic handling of queued, running, terminal, and ambiguous stale Jobs;
- focused concurrency verification and production rollout.

### 2.2 Non-goals

- no automatic rerun after a Generation reaches `RUNNING`;
- no recovery of in-memory Agent state or an in-flight model request;
- no exactly-once guarantee for external provider calls;
- no monotonic fencing sequence propagated into Java, MySQL, Qdrant, or model providers;
- no new queue, scheduler, coordinator, dependency, or Redis module;
- no Redis Cluster support for the cross-key Lua operations in this version;
- no change to user retry, answer revision, quota, or frontend protocols.

The production deployment uses one Redis instance. All atomic operations in this specification are
therefore valid on the current topology. A future Redis Cluster migration requires a separate queue
partitioning design because the global Job Stream and per-Generation keys do not share one hash slot.

## 3. Terms

| Term | Definition |
| --- | --- |
| Job | One Redis Stream message representing one Generation. |
| Pending | A Job delivered to a consumer but not yet ACKed. It does not imply failure. |
| Pending Idle Time | Time since Redis last delivered or claimed the Pending entry. It is not Worker CPU idle time. |
| Lease | A renewable Redis key proving that one execution currently owns the Generation. |
| Owner Token | A random identifier created once for one concrete execution. It is not the reusable Worker ID. |
| Worker ID | Diagnostic identity of a Worker process, such as `harness-host-1`. |
| Claim | Change the Pending entry's Redis consumer with `XCLAIM`. |
| Terminal Commit | Atomically publish the terminal event, update Status, ACK/delete the Job, and release the Lease. |

The Owner Token distinguishes two executions performed by the same Worker:

```text
worker-A, old execution -> owner token 7a8f...
worker-A, new execution -> owner token c219...
```

The token is an ownership version, not a credential and not a monotonic fencing number.

## 4. Formal Model

For Generation `g`:

```text
Job(g)       = Redis Stream entry
Status(g)    in {QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, STALE_FAILED}
Lease(g)     = absent | owner_token
Consumer(g)  = Redis consumer recorded in the Pending Entries List
```

Define:

```text
owns(g, t) := GET Lease(g) == t
terminal(s) := s in {SUCCEEDED, FAILED, CANCELLED, STALE_FAILED}
```

### 4.1 Safety invariants

**S1. Single lease**

```text
For every Generation g, Redis stores at most one current Lease(g).
```

**S2. Owner-only mutation**

```text
Renew, progress publication, terminal commit, ACK, XDEL, and lease release
are allowed only when owns(g, token) is true inside the same Redis operation.
```

**S3. No live reclaim**

```text
Lease(g) exists -> no stale Worker may XCLAIM Job(g).
```

**S4. No started rerun**

```text
Status(g) == RUNNING and Lease(g) is absent
-> Status(g) becomes STALE_FAILED
-> ResearchHarnessService.run_job is not called again for g.
```

**S5. Monotonic lifecycle**

```text
QUEUED -> RUNNING -> terminal
```

No transition may move a terminal Status back to `QUEUED` or `RUNNING`.

**S6. One accepted terminal commit**

```text
Only the current Owner Token can commit a normal terminal result.
After terminal commit, Job(g) and Lease(g) are absent and Status(g) is terminal.
```

### 4.2 Liveness properties

**L1. Queued recovery**

A Worker that disappears after Redis delivery but before starting the Run must not leave the Job
Pending forever. After the Pending threshold, another Worker may claim and start it if no Lease
exists and Status is `QUEUED`.

**L2. Running failure detection**

A Worker that disappears after starting the Run must eventually stop renewing its Lease. After both
the Lease expires and the Pending entry becomes stale, another Worker marks the Run
`STALE_FAILED` without re-executing it.

## 5. Redis Data Contract

Existing keys remain unchanged:

```text
paperloom:research:harness:jobs
paperloom:research:harness:status:{generationId}
paperloom:research:harness:events:{generationId}
paperloom:research:harness:lock:{generationId}
paperloom:research:harness:cancel:{generationId}
```

### 5.1 Lease value

The Lock value becomes one opaque Owner Token:

```text
paperloom:research:harness:lock:{generationId} = 2f56cb8c-...
```

Generate it with Python `uuid.uuid4()` before attempting to start or recover a Job. Worker identity
remains in Status and events; it must not be used as the ownership comparison value.

### 5.2 Time configuration

Lease duration is separated from the maximum Run duration:

```text
RESEARCH_HARNESS_WORKER_HEARTBEAT_SECONDS=10
RESEARCH_HARNESS_LEASE_TTL_SECONDS=60
RESEARCH_HARNESS_STALE_PENDING_SECONDS=120
RESEARCH_HARNESS_JOB_TIMEOUT_SECONDS=900
```

Required relationship:

```text
heartbeat < lease_ttl <= stale_pending < job_timeout
lease_ttl >= 3 * heartbeat
```

`JOB_TIMEOUT_SECONDS` is an execution bound. It must no longer be reused as the Lease TTL. A
60-second Lease tolerates several missed heartbeats while allowing a truly lost Worker to be
detected without waiting 15 minutes.

## 6. Required Atomic Operations

Use redis-py's existing script support (`register_script` or `EVALSHA`). Do not add a Lua framework
or a new Python dependency.

Redis script atomicity means no other Redis command runs between the checks and mutations in one
script. It does not roll back writes after a runtime script error. Implementations must therefore
validate arguments and decode JSON before the first mutation.

### 6.1 Enqueue Job

Java must create the Stream entry and `QUEUED` Status in one script:

```text
XADD Job
SET Status(g) = QUEUED with returned Stream ID
return Stream ID
```

This removes the current race where a fast Worker can write `RUNNING` after `XADD`, followed by Java
overwriting it with `QUEUED`.

### 6.2 Start Fresh Job

After `XREADGROUP` delivers a new Job, the assigned Worker runs one start script:

```text
require Status(g) == QUEUED
require Lease(g) is absent
SET Lease(g) = owner_token NX PX lease_ttl
SET Status(g) = RUNNING
XADD job_started
return STARTED
```

If Status is terminal, clean up the duplicate Job without executing it. If Status is `RUNNING`,
and a Lease exists, clean up only this duplicate Stream entry and leave the live Run unchanged. If
Status is `RUNNING` without a Lease, or Status is missing/malformed, publish a technical failure and
clean up without executing; the fresh-delivery path must not guess whether a model call has already
started. `QUEUED` with an existing Lease is also an invariant failure and must not execute.

`ResearchHarnessService.run_job(...)` may be called only after this operation returns `STARTED`.

### 6.3 Renew Lease

Renewal is compare-and-expire, not an unconditional `EXPIRE`:

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return 0
```

Return value `0` means ownership has been lost. A renewal exception also fails closed: the heartbeat
marks the local execution as lease-lost because it can no longer prove ownership. In either case the
Worker must not attempt further Redis-visible progress or completion.

### 6.4 Publish Progress

Progress publication is compare-and-append in one script:

```text
require GET Lease(g) == owner_token
XADD Event(g) progress payload
EXPIRE Event(g) event_ttl
```

If ownership does not match, discard the stale progress event and signal lease loss to the local
Run. This check belongs in the shared `RedisResearchEventSink`, not in each individual tool caller.

### 6.5 Discover Stale Candidates

Do not use `XAUTOCLAIM` for discovery because it changes the Pending consumer before PaperLoom has
checked the Lease.

Use `XPENDING ... IDLE stale_pending` to read candidate IDs without changing ownership. For each
candidate, use `XRANGE id id` to read its immutable `generation_id`, then call the atomic recovery
operation below.

Candidate scanning must use the `XPENDING` exclusive-start cursor and a bounded batch. A live long
Job must not remain the first candidate forever and prevent later crashed Jobs from being examined.

### 6.6 Recover Stale Candidate

One Lua operation receives the Job Stream, Status key, Lease key, Event Stream, candidate Stream ID,
consumer group, new Worker ID, new Owner Token, and timing values.

It must execute this decision atomically:

```text
if Lease(g) exists:
    return LIVE

claimed = XCLAIM Job(g) group new_worker stale_pending candidate_id
if claimed is empty:
    return RACE_LOST

require claimed Job generation_id == g

if Status(g) == QUEUED:
    SET Lease(g) = new_owner_token PX lease_ttl
    SET Status(g) = RUNNING
    XADD job_started with recovery metadata
    return EXECUTE with claimed Job fields

if Status(g) == RUNNING or Status(g) is missing/malformed:
    XADD job_failed
    XADD error with error_type=StalePendingJob
    SET Status(g) = STALE_FAILED
    XACK and XDEL Job(g)
    return FAILED_CLOSED

if Status(g) is terminal:
    XACK and XDEL Job(g)
    return CLEANED
```

`XCLAIM` receives `stale_pending` as its minimum idle time. It rechecks eligibility inside the same
atomic operation, so a candidate changed after `XPENDING` is not incorrectly claimed.

The `RUNNING` branch intentionally does not establish a new execution Lease and does not call the
Harness. PaperLoom cannot prove whether an external model call was already billed or whether partial
Agent state existed, so it returns a technical failure and requires a new user-initiated Generation.

### 6.7 Commit Terminal Result

Normal completion, controlled limit, cancellation, and runtime exception all use one owner-checked
terminal operation:

```text
require GET Lease(g) == owner_token
XADD terminal progress summaries, when applicable
XADD one Java-consumed terminal event: result | error | cancelled
SET Status(g) = terminal status
XACK Job(g)
XDEL Job(g)
DEL Lease(g)
return COMMITTED
```

If the token does not match, return `LEASE_LOST` and perform none of these writes. The stale Worker
must discard its local result. It must not send a second terminal event, ACK another Worker's Job,
or delete the current Lease.

### 6.8 Release Without Terminal Commit

An unconditional `DEL Lease(g)` is forbidden. Cleanup outside terminal commit uses compare-and-delete:

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
```

This operation is only for startup failures before the Run becomes externally active. After
`RUNNING`, every exit must attempt the terminal operation or leave the Lease to expire for recovery.

## 7. Worker State Machine

Local Worker state:

```text
DELIVERED
  -> STARTED(owner_token)
  -> LEASE_LOST
  -> TERMINAL_COMMITTED
```

Allowed actions:

| Local state | Execute Harness | Emit progress | Commit terminal | Release Lease |
| --- | --- | --- | --- | --- |
| `DELIVERED` | No | No | No | No |
| `STARTED(token)` | Yes | Only if token matches | Only if token matches | Only with token comparison |
| `LEASE_LOST` | Stop at next cancellation boundary | No | No | No |
| `TERMINAL_COMMITTED` | No | No | Already done | Already released atomically |

The existing cancellation callback must return true when either condition is true:

```text
Cancel key exists OR local lease-lost flag is set
```

A provider request already in flight may still consume tokens before returning. When it returns,
the old Worker observes lease loss and discards the result. This is why the specification promises
single accepted Redis terminal state, not exactly-once provider billing.

## 8. Failure Scenarios

### 8.1 Healthy long Run

```text
A starts and renews token-A every 10 seconds
Pending Idle exceeds 120 seconds
B discovers the Pending candidate
recovery script sees Lease(g)=token-A and returns LIVE
B does not XCLAIM or execute the Job
A commits the result atomically
```

### 8.2 Worker disappears before start

```text
A receives the Job but never completes the start operation
Status remains QUEUED and Lease is absent
B discovers the stale candidate
B atomically XCLAIMs, creates token-B, marks RUNNING, and starts the Harness
```

### 8.3 Worker disappears after start

```text
A starts the Run and then disappears
token-A expires
B discovers the stale candidate
Status is RUNNING and Lease is absent
B atomically publishes StalePendingJob, marks STALE_FAILED, ACKs, and deletes the Job
B does not rerun the Harness
```

### 8.4 Old Worker resumes after recovery

```text
A resumes with token-A
Lease is absent or contains a different token
A cannot renew, publish progress, commit a result, ACK, XDEL, or delete the Lease
A stops at the next cancellation boundary
```

### 8.5 Two Workers recover simultaneously

```text
B and C discover the same candidate
Redis serializes their recovery scripts
one XCLAIM succeeds and changes or removes the candidate
the other XCLAIM returns no eligible message
only one recovery decision is applied
```

## 9. Code Changes

### 9.1 Python

Primary files:

- `harness_py/transport/redis_worker.py`
- `harness_py/tests/test_redis_worker.py`

Required changes:

1. add `lease_ttl_seconds` to `RedisWorkerConfig`;
2. generate a unique Owner Token per start attempt;
3. replace unconditional heartbeat `EXPIRE` with compare-and-`PEXPIRE`;
4. replace unconditional final `delete(lock_key)` with owner-checked terminal/release operations;
5. make `RedisResearchEventSink` owner-aware;
6. replace `XAUTOCLAIM` discovery with cursor-based `XPENDING` plus atomic `XCLAIM` recovery;
7. include lease loss in the cancellation check;
8. remove the deployed post-`XAUTOCLAIM` `EXISTS` guard after the atomic recovery path supersedes it.

### 9.2 Java

Primary file:

- `src/main/java/io/github/chzarles/paperloom/service/RedisResearchHarnessTransport.java`

Required change:

- replace separate `XADD` then Status write in `enqueue(...)` with the atomic enqueue operation.

No change is required to `ChatHandler`, result mapping, WebSocket payloads, conversation persistence,
or quota settlement.

## 10. Verification

### 10.1 Focused deterministic checks

Required cases:

| Case | Expected result |
| --- | --- |
| Healthy Run exceeds stale threshold | Candidate remains with original consumer; no error or duplicate execution. |
| Heartbeat uses wrong token | Lease TTL is not extended. |
| Old Worker releases with wrong token | Current Lease remains. |
| Old Worker emits progress with wrong token | Event is not appended. |
| Queued Worker disappears | Exactly one recovering Worker starts the Job. |
| Running Worker disappears | One `StalePendingJob`; Harness is not called again. |
| Old Worker returns after stale failure | Its result is discarded; no second terminal event. |
| Two Workers claim one candidate | Only one recovery script returns a winning result. |
| Java enqueue races with fast Worker | Status never regresses from `RUNNING` to `QUEUED`. |

Use the existing lightweight Fake Redis checks for local branch behavior. Add one opt-in real Redis
check for Lua command semantics and the two-Worker race; a fake cannot prove Redis script atomicity.
No full benchmark, Playwright run, or unrelated application test is required.

### 10.2 Production acceptance

After a separate commit and coordinated Worker-pool restart:

1. all four Worker units are `active` with zero restart loops;
2. one Research Run lasting longer than `RESEARCH_HARNESS_STALE_PENDING_SECONDS` completes normally;
3. its Event Stream contains exactly one Java-consumed terminal event;
4. Worker logs contain no `StalePendingJob` for that Generation;
5. Redis `XPENDING` contains no entry for the completed Generation;
6. Status is terminal and its Lease key is absent.

## 11. Rollout and Rollback

Implementation order:

```text
1. deploy atomic Java enqueue while retaining compatibility with existing Workers
2. stop all old Workers
3. deploy Python lease-token and recovery operations, then start the complete new Worker pool
4. run the production acceptance case
```

Do not run old and new Python Worker protocols concurrently: old Workers use unconditional `EXPIRE`
and `DEL` and do not understand Owner Tokens. Stop all four old Workers, deploy the Python code, then
start all four new Workers. The Java backend can remain online; queued Jobs wait in Redis during the
short Worker restart window.

Rollback restores the previous Worker build as one coordinated pool and retains Commit `99fb62f`'s
live-Lock guard. Do not mix old and new Worker versions during rollback.

## 12. Completion Criteria

This specification is implemented only when all of the following are true:

- no unconditional Lease renewal or deletion remains in the Worker path;
- live stale candidates are observed but never claimed;
- stale queued Jobs can be started by exactly one Worker;
- stale running Jobs fail closed without Harness re-execution;
- stale Workers cannot publish or commit after losing ownership;
- Java enqueue cannot regress Status;
- focused fake and real Redis checks pass;
- production acceptance confirms one long Run has exactly one terminal outcome.
