------------------------------- MODULE CoCache -------------------------------
(***************************************************************************)
(* Copyright [2021-present] [ahoo wang <ahoowang@qq.com>                   *)
(* (https://github.com/Ahoo-Wang)].                                        *)
(* Licensed under the Apache License, Version 2.0 (the "License");         *)
(* you may not use this file except in compliance with the License.        *)
(* You may obtain a copy of the License at                                 *)
(*      http://www.apache.org/licenses/LICENSE-2.0                         *)
(* Unless required by applicable law or agreed to in writing, software     *)
(* distributed under the License is distributed on an "AS IS" BASIS,       *)
(* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.*)
(* See the License for the specific language governing permissions and     *)
(* limitations under the License.                                          *)
(***************************************************************************)

(***************************************************************************)
(* The CoCache 8.x coherence protocol for one key (RFC 0005 §4.10, V9).    *)
(*                                                                         *)
(* N nodes share one L1 (Redis). Each node keeps an L2 copy guarded by an  *)
(* invalidation stamp, runs readers that each execute the miss path on     *)
(* their own (SingleFlight is not modelled: readers bypass it, P11), and   *)
(* may run a writer. The data source is a counter of committed versions;   *)
(* a value is identified by the version it was loaded from, so the commit  *)
(* history is the judge for staleness.                                     *)
(*                                                                         *)
(* Faults: commands that time out and still execute later, connection      *)
(* replacement (a drained command may run after a newer one), lost,        *)
(* reordered and duplicated frames, resets, loader failures, lease and     *)
(* L1/L2 expiry, and crashes while holding a token.                        *)
(*                                                                         *)
(* docs/spec/README.md maps every property to docs/architecture.md.        *)
(***************************************************************************)
EXTENDS Integers, Sequences, FiniteSets

CONSTANTS
    Nodes,          \* runtime instances
    Writers,        \* nodes that run a writer
    Rids,           \* reader slots per node (1..n)
    MaxVersion,     \* the source commits versions 1..MaxVersion
    Deleted,        \* committed versions at which the source has no entry
    MaxTokens,      \* tokens issued in one behaviour
    MaxFaults,      \* injected faults in one behaviour
    Faults,         \* fault kinds: "timeout", "reconnect", "crash", "loss", "dup", "reset", "loader"
    MaxEvicts,      \* VersionedCache.evict calls in one behaviour
    MaxNoise,       \* L1 expiry or eviction, L2 expiry and token ageing in one behaviour
    Versioned,      \* TRUE: VersionedCache (putIfNewer, evict, state E); FALSE: Cache with a loader
    Refresh,        \* model early refresh (refresh tokens)
    LaggingLoader,  \* the loader may return any committed version (a stale replica): outside the protocol
    L1Expiry        \* values, tombstones and E may expire or be evicted at any time

ASSUME
    /\ Writers \subseteq Nodes
    /\ Rids \subseteq Nat \ {0}
    /\ MaxVersion \in Nat /\ Deleted \subseteq 1..MaxVersion
    /\ MaxTokens \in Nat /\ MaxFaults \in Nat /\ MaxEvicts \in Nat /\ MaxNoise \in Nat
    /\ Faults \subseteq {"timeout", "reconnect", "crash", "loss", "dup", "reset", "loader"}
    /\ {Versioned, Refresh, LaggingLoader, L1Expiry} \subseteq BOOLEAN

VARIABLES
    db,         \* latest committed version of the data source (0: the key never existed)
    l1,         \* the key's L1 content
    net,        \* net[n]: L1 commands node n has sent and Redis has not executed, in send order
    conn,       \* conn[n]: epoch of node n's current command connection
    alive,      \* alive[n]
    l2,         \* l2[n]: node n's L2 copy
    rd,         \* rd[<<n, i>>]: reader i of node n
    wr,         \* wr[n]: node n's writer
    pending,    \* pending[n]: the invalidation is being retried (RETRY); reads bypass L1
    unfilter,   \* unfilter[n]: a write's outcome is unknown, own frames are processed (§4.6, P12)
    chan,       \* chan[n]: invalidation frames in flight to n (a set: reordering, duplication)
    nextTok,    \* tokens are never issued twice
    faults,
    evicts,
    noise,
    \* ghost state: the judges, never read by the protocol
    l1db,       \* db when an L1 change last destroyed every token (DEL, overwrite, E)
    l1ver,      \* highest version L1 has broadcast (VersionedCache)
    floorS,     \* floorS[n]: oldest data node n may still expose, by single-key invalidations it processed
    floorR,     \* floorR[n]: the same, by resets it processed
    floorF,     \* floorF[n]: the same, if a version-advancing fill that ran after its timeout counted as a write
    owed,       \* owed[n]: a write that has run in L1 and that node n has not processed yet
    lost        \* lost[n]: a frame to n was lost and no resubscription has reset n since

vars == <<db, l1, net, conn, alive, l2, rd, wr, pending, unfilter, chan, nextTok, faults, evicts, noise, noise,
          l1db, l1ver, floorS, floorR, floorF, owed, lost>>

-----------------------------------------------------------------------------
(* Values *)

NoVer    == -1      \* Versioned.NO_VERSION: orders before every version
Orphan   == -1      \* a command nobody waits for any more
WriterId == 0
Readers  == Nodes \X Rids

Max(a, b) == IF a >= b THEN a ELSE b
CanFault(kind) == kind \in Faults /\ faults < MaxFaults
RemoveAt(s, k) == SubSeq(s, 1, k - 1) \o SubSeq(s, k + 1, Len(s))

(* L1 content: st is A (absent), L (lease placeholder), V (value), M (negative entry), E (§4.7).
   tok is the lease token (L, E) or the refresh token (V); aged: issued more than lease.ttl ago.
   snap is the source version the content was loaded from; src tells fills from writer writes.
   late (ghost): the token was granted by a READ that ran after its issuer had given up. *)
Absent == [st |-> "A", tok |-> 0, aged |-> FALSE, late |-> FALSE, ver |-> NoVer, snap |-> 0, src |-> "fill"]
VerOf(s) == IF Versioned /\ s > 0 THEN s ELSE NoVer
Content(s, src) ==
    [st |-> IF s = 0 \/ s \in Deleted THEN "M" ELSE "V", tok |-> 0, aged |-> FALSE, late |-> FALSE,
     ver |-> VerOf(s), snap |-> s, src |-> IF Versioned THEN src ELSE "fill"]
Tombstone(v) == [st |-> "E", tok |-> 0, aged |-> FALSE, late |-> FALSE, ver |-> v, snap |-> 0, src |-> "write"]
TokenFree == l1.tok = 0 \/ l1.aged

(* L2 copy: own is the reader whose compute wrote it and has not rechecked its stamp yet. *)
NoCopy == [snap |-> -1, own |-> 0]
HasCopy(n) == l2[n].snap >= 0
IsValue(s) == s > 0 /\ s \notin Deleted

NoRep == [k |-> "none", snap |-> 0, g |-> FALSE, adv |-> FALSE, cur |-> NoVer]
Rep(k) == [NoRep EXCEPT !.k = k]

Cmd(op, from, ep, tok, s) == [op |-> op, from |-> from, ep |-> ep, tok |-> tok, snap |-> s]
WriteOps == {"INV", "PIN", "EVICT"}

(* Reader modes: miss (a caller waits for the result), refresh (holds the refresh guard),
   refill (refresh without a token: refills L2 from L1), reload (loadThrough after a refresh). *)
RIdle == [pc |-> "idle", mode |-> "miss", tok |-> 0, ok |-> FALSE, snap |-> 0, f0 |-> 0,
          seen |-> 0, rr |-> FALSE, rep |-> NoRep]
WIdle == [pc |-> "idle", op |-> "INV", v |-> 0, rep |-> NoRep]

LoaderSnaps == IF LaggingLoader THEN 0..db ELSE {db}
Floor(n) == Max(floorS[n], floorR[n])
Refreshing(n) == \E j \in Rids : rd[<<n, j>>].pc # "idle" /\ rd[<<n, j>>].mode \in {"refresh", "refill", "reload"}
\* Caffeine serializes a single-key eviction with a compute on the same key (§4); a reset does not wait.
NoCompute(n) == \A i \in Rids : rd[<<n, i>>].pc # "c2"
\* a stamp bump on node n: every stamp taken before it is invalid
Bumped(r, n) == [x \in Readers |-> IF x[1] = n THEN [r[x] EXCEPT !.ok = FALSE] ELSE r[x]]
Mine(n, from) == {k \in 1..Len(net[n]) : net[n][k].from = from}
Orphaned(s, from) == [k \in 1..Len(s) |-> IF s[k].from = from THEN [s[k] EXCEPT !.from = Orphan] ELSE s[k]]
\* the issuer can no longer get an answer: the command was dropped or its connection replaced
Unanswerable(n, from, rep) == rep.k = "none" /\ \A k \in Mine(n, from) : net[n][k].ep # conn[n]
(* A miss returns s to its caller. A return that breaks G2Read parks the reader in "stale" (a state
   only a violation reaches, so passing runs pay nothing for the check). *)
Returned(x, s) ==
    IF x.mode = "miss" /\ ~LaggingLoader /\ s < x.f0
    THEN [RIdle EXCEPT !.pc = "stale", !.snap = s, !.f0 = x.f0]
    ELSE RIdle

(* The value the reader will return and offer to L2 is fixed: check the stamp inside compute (c1 of
   fillGuarded). Valid: write (c2). Invalid: no L2 write, the value is returned. *)
Fixed(x, s) ==
    IF x.ok THEN [x EXCEPT !.pc = "c2", !.snap = s, !.tok = 0, !.seen = 0, !.rr = FALSE, !.rep = NoRep]
    ELSE Returned(x, s)

-----------------------------------------------------------------------------
(* Redis: one script runs atomically. Exec is the result of running command c on l1. *)

Res(nl1, rep, bc, f, inv) == [l1 |-> nl1, rep |-> rep, bc |-> bc, f |-> f, inv |-> inv]
Quiet(rep) == Res(l1, rep, FALSE, 0, FALSE)
Hit(g) == [Rep("hit") EXCEPT !.snap = l1.snap, !.g = g]
Granted == [Rep("miss") EXCEPT !.g = TRUE]
Grant(c) == [l1 EXCEPT !.tok = c.tok, !.aged = FALSE, !.late = (c.from = Orphan)]
ClearToken == [l1 EXCEPT !.tok = 0, !.aged = FALSE, !.late = FALSE]

ExecRead(c) ==                                  \* READ with a lease request (§4.1, §4.7 table E)
    CASE l1.st = "A" -> Res([Absent EXCEPT !.st = "L", !.tok = c.tok, !.late = (c.from = Orphan)], Granted, FALSE, 0, FALSE)
      [] l1.st = "E" /\ TokenFree -> Res(Grant(c), Granted, FALSE, 0, FALSE)
      [] l1.st \in {"L", "E"} -> Quiet(Rep("leased"))
      [] OTHER -> Quiet(Hit(FALSE))

ExecRefresh(c, win) ==                          \* READ with a refresh request; win: L1 is in the window
    CASE l1.st = "V" /\ TokenFree /\ win -> Res(Grant(c), Hit(TRUE), FALSE, 0, FALSE)
      [] l1.st \in {"V", "M"} -> Quiet(Hit(FALSE))
      [] l1.st = "L" -> Quiet(Rep("leased"))
      [] OTHER -> Quiet(Rep("miss"))            \* A, and E read without a lease request

(* FILL: the token, then the version rules of RFC §4.7: a smaller version (or none) is rejected and the
   token cleared; an equal one renews; a greater one, and any fill accepted on E, broadcasts and is
   Filled(advanced = TRUE), which the engine treats as a write. *)
ExecFill(c) ==
    LET w == VerOf(c.snap)
        v == l1.ver
    IN CASE l1.tok # c.tok -> Quiet(Rep("rejected"))
         [] v = NoVer -> Res(Content(c.snap, "fill"), Rep("filled"), FALSE, 0, FALSE)
         [] w < v -> Res(ClearToken, [Rep("rejected") EXCEPT !.cur = v], FALSE, 0, FALSE)
         [] w = v /\ l1.st # "E" -> Res(Content(c.snap, "fill"), Rep("filled"), FALSE, 0, FALSE)
         [] OTHER -> Res(Content(c.snap, "fill"), [Rep("filled") EXCEPT !.adv = TRUE], TRUE, w, FALSE)

ExecRelease(c) ==                               \* RELEASE: L is deleted, V and E keep their content
    CASE l1.tok # c.tok -> Quiet(Rep("ok"))
      [] l1.st = "L" -> Res(Absent, Rep("ok"), FALSE, 0, FALSE)
      [] OTHER -> Res(ClearToken, Rep("ok"), FALSE, 0, FALSE)

(* PUT_IF_NEWER (also the tombstone of invalidateIfNewer). Stale with the same version still
   publishes (RFC §4.7, TLA+ finding F1): the equal version may have entered L1 through a fill that
   did not broadcast, and other nodes would keep older copies. *)
ExecPutIfNewer(c) ==
    CASE c.snap > l1.ver -> Res(Content(c.snap, "write"), Rep("written"), TRUE, c.snap, TRUE)
      [] c.snap = l1.ver -> Res(l1, [Rep("stale") EXCEPT !.cur = l1.ver], TRUE, c.snap, FALSE)
      [] OTHER -> Quiet([Rep("stale") EXCEPT !.cur = l1.ver])

ExecEvict ==                                    \* EVICT_KEEPING_VERSION
    IF l1.ver # NoVer
    THEN Res(Tombstone(l1.ver), [Rep("ok") EXCEPT !.cur = l1.ver], TRUE, l1.ver, TRUE)
    ELSE Res(Absent, Rep("ok"), TRUE, 0, TRUE)

Exec(c, win) ==
    CASE c.op = "READ"  -> ExecRead(c)
      [] c.op = "READR" -> ExecRefresh(c, win)
      [] c.op = "FILL"  -> ExecFill(c)
      [] c.op = "REL"   -> ExecRelease(c)
      [] c.op = "INV"   -> Res(Absent, Rep("ok"), TRUE, db, TRUE)
      [] c.op = "PIN"   -> ExecPutIfNewer(c)
      [] c.op = "EVICT" -> ExecEvict

\* what the write promised every other node: INV the data up to now, PIN its version, EVICT the kept version
Intent(c, r) == CASE c.op = "INV" -> db [] c.op = "PIN" -> c.snap [] OTHER -> r.f

(* Redis runs a command that is first among its connection's commands still in flight. The
   reply reaches the issuer only on the same connection.
   Frames: chan[m] holds [own, f, late]; f is the floor the frame establishes (the data version for
   invalidate, the version for a versioned write). A node's own frame only matters when the command
   ran after its issuer gave up ("late"): otherwise it is filtered, or the issuer's second stamp bump
   covers it, so the model does not send it. *)
Execute(n, k) ==
    /\ k \in 1..Len(net[n])
    /\ \A j \in 1..(k - 1) : net[n][j].ep # net[n][k].ep
    /\ LET c == net[n][k] IN
       \E win \in (IF c.op = "READR" THEN BOOLEAN ELSE {FALSE}) :
         LET r    == Exec(c, win)
             late == IF c.from # Orphan THEN "no" ELSE IF c.op = "FILL" THEN "fill" ELSE "write"
             fr(m) == [own |-> m = n, f |-> r.f, late |-> IF m = n THEN late ELSE "no"]
             live == c.from # Orphan /\ c.ep = conn[n] /\ alive[n]
         IN /\ l1' = r.l1
            /\ net' = [net EXCEPT ![n] = RemoveAt(@, k)]
            /\ chan' = IF r.bc
                       THEN [m \in Nodes |-> IF alive[m] /\ (m # n \/ late # "no") THEN chan[m] \cup {fr(m)} ELSE chan[m]]
                       ELSE chan
            /\ l1db' = IF r.inv THEN db ELSE l1db
            /\ l1ver' = IF r.bc /\ Versioned THEN Max(l1ver, r.f) ELSE l1ver
            /\ owed' = IF c.op \in WriteOps
                       THEN [m \in Nodes |-> IF m # n /\ alive[m] THEN Max(owed[m], Intent(c, r)) ELSE owed[m]]
                       ELSE owed
            /\ wr' = IF live /\ c.from = WriterId THEN [wr EXCEPT ![n].rep = r.rep] ELSE wr
            /\ rd' = IF live /\ c.from # WriterId THEN [rd EXCEPT ![<<n, c.from>>].rep = r.rep] ELSE rd
    /\ UNCHANGED <<db, conn, alive, l2, pending, unfilter, nextTok, faults, evicts, noise, floorS, floorR, floorF, lost>>

(* A command that will never run: on a replaced connection, from a crashed node, or abandoned
   before it was written. A token release the store owes is resent until it runs (K14). *)
Drop(n, k) ==
    /\ k \in 1..Len(net[n])
    /\ LET c == net[n][k] IN c.ep # conn[n] \/ ~alive[n] \/ (c.from = Orphan /\ c.op # "REL")
    /\ net' = [net EXCEPT ![n] = RemoveAt(@, k)]
    /\ UNCHANGED <<db, l1, conn, alive, l2, rd, wr, pending, unfilter, chan, nextTok, faults, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

ExpireL1 ==                                     \* PX expiry or eviction; the lease placeholder always expires
    /\ l1.st # "A" /\ (L1Expiry \/ l1.st = "L") /\ noise < MaxNoise
    /\ l1' = Absent
    /\ noise' = noise + 1
    /\ UNCHANGED <<db, net, conn, alive, l2, rd, wr, pending, unfilter, chan, nextTok, faults, evicts,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

\* lease.ttl passes: E and V tokens may be taken over (READ never takes over an L placeholder, which expires)
AgeToken ==
    /\ l1.tok # 0 /\ ~l1.aged /\ l1.st \in {"E", "V"} /\ noise < MaxNoise
    /\ l1' = [l1 EXCEPT !.aged = TRUE]
    /\ noise' = noise + 1
    /\ UNCHANGED <<db, net, conn, alive, l2, rd, wr, pending, unfilter, chan, nextTok, faults, evicts,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

-----------------------------------------------------------------------------
(* Readers: CoherentEngine.loadMiss / loadThrough / refreshThrough without SingleFlight. *)

UnchangedForReaders == UNCHANGED <<db, l1, conn, alive, wr, pending, unfilter, chan, faults, evicts, noise,
                                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

\* L2 miss: take the stamp, then read L1 asking for a lease (bypass L1 while the invalidation is retried)
StartMiss(n, i) ==
    LET r == <<n, i>> IN
    /\ alive[n] /\ rd[r].pc = "idle" /\ ~HasCopy(n)
    /\ \/ /\ pending[n]
          /\ rd' = [rd EXCEPT ![r] = [RIdle EXCEPT !.pc = "by", !.ok = TRUE, !.f0 = Floor(n)]]
          /\ UNCHANGED <<net, nextTok>>
       \/ /\ ~pending[n] /\ nextTok <= MaxTokens
          /\ rd' = [rd EXCEPT ![r] = [RIdle EXCEPT !.pc = "rd", !.ok = TRUE, !.f0 = Floor(n), !.tok = nextTok]]
          /\ net' = [net EXCEPT ![n] = Append(@, Cmd("READ", i, conn[n], nextTok, 0))]
          /\ nextTok' = nextTok + 1
    /\ UNCHANGED l2
    /\ UnchangedForReaders

\* an L2 hit at refreshAt: one refresh per node and key at a time (§5.5)
StartRefresh(n, i) ==
    LET r == <<n, i>> IN
    /\ Refresh /\ alive[n] /\ rd[r].pc = "idle" /\ IsValue(l2[n].snap) /\ ~Refreshing(n)
    /\ \/ /\ pending[n]
          /\ rd' = [rd EXCEPT ![r] = [RIdle EXCEPT !.pc = "by", !.mode = "reload", !.ok = TRUE]]
          /\ UNCHANGED <<net, nextTok>>
       \/ /\ ~pending[n] /\ nextTok <= MaxTokens
          /\ rd' = [rd EXCEPT ![r] = [RIdle EXCEPT !.pc = "rd", !.mode = "refresh", !.ok = TRUE, !.tok = nextTok]]
          /\ net' = [net EXCEPT ![n] = Append(@, Cmd("READR", i, conn[n], nextTok, 0))]
          /\ nextTok' = nextTok + 1
    /\ UNCHANGED l2
    /\ UnchangedForReaders

(* loadThrough after a refresh could not use L1: a new stamp, then L1 with a new lease, or the source
   while the invalidation is retried. Without tokens left the model bypasses L1 as on a read failure. *)
ReloadReads(n) == ~pending[n] /\ nextTok <= MaxTokens
ReloadState(i, n) ==
    IF ReloadReads(n)
    THEN [RIdle EXCEPT !.pc = "rd", !.mode = "reload", !.ok = TRUE, !.tok = nextTok]
    ELSE [RIdle EXCEPT !.pc = "by", !.mode = "reload", !.ok = TRUE]
ReloadCmds(i, n) == IF ReloadReads(n) THEN <<Cmd("READ", i, conn[n], nextTok, 0)>> ELSE <<>>
ReloadTok(n) == IF ReloadReads(n) THEN nextTok + 1 ELSE nextTok

OnRead(n, i) ==
    LET r == <<n, i>>
        x == rd[r]
        p == x.rep
    IN
    /\ alive[n] /\ x.pc = "rd" /\ p.k # "none"
    /\ \/ /\ p.k = "hit" /\ ~p.g                \* L1 hit, or a refresh without the token: fill L2 from L1
          /\ rd' = [rd EXCEPT ![r] = Fixed([x EXCEPT !.mode = IF @ = "refresh" THEN "refill" ELSE @], p.snap)]
          /\ UNCHANGED <<net, nextTok>>
       \/ /\ p.g                                \* holds a lease or a refresh token: load (a refresh keeps what it read)
          /\ rd' = [rd EXCEPT ![r].pc = "ld", ![r].rr = FALSE, ![r].rep = NoRep,
                              ![r].seen = IF x.mode = "refresh" THEN p.snap ELSE 0]
          /\ UNCHANGED <<net, nextTok>>
       \/ /\ p.k = "leased" /\ x.mode # "refresh" /\ ~x.rr   \* back off and read again with the same token
          /\ rd' = [rd EXCEPT ![r].rr = TRUE, ![r].rep = NoRep]
          /\ net' = [net EXCEPT ![n] = Append(@, Cmd("READ", i, conn[n], x.tok, 0))]
          /\ UNCHANGED nextTok
       \/ /\ p.k = "leased" /\ x.mode # "refresh" /\ x.rr    \* lease.wait is over: bypass L1
          /\ rd' = [rd EXCEPT ![r].pc = "by", ![r].tok = 0, ![r].rr = FALSE, ![r].rep = NoRep]
          /\ UNCHANGED <<net, nextTok>>
       \/ /\ x.mode = "refresh" /\ p.k \in {"miss", "leased"}
          /\ rd' = [rd EXCEPT ![r] = ReloadState(i, n)]
          /\ net' = [net EXCEPT ![n] = @ \o ReloadCmds(i, n)]
          /\ nextTok' = ReloadTok(n)
    /\ UNCHANGED l2
    /\ UnchangedForReaders

\* the loader runs after the token was granted; it reads the source once
Load(n, i) ==
    LET r == <<n, i>> IN
    /\ alive[n] /\ rd[r].pc = "ld"
    /\ \E s \in LoaderSnaps :
         /\ rd' = [rd EXCEPT ![r].pc = "fl", ![r].snap = s]
         /\ net' = [net EXCEPT ![n] = Append(@, Cmd("FILL", i, conn[n], rd[r].tok, s))]
    /\ UNCHANGED <<l2, nextTok>>
    /\ UnchangedForReaders

\* the loader, ttlOf or the codec throws: release the token, then throw
LoadFails(n, i) ==
    LET r == <<n, i>> IN
    /\ alive[n] /\ rd[r].pc = "ld" /\ CanFault("loader")
    /\ rd' = [rd EXCEPT ![r].pc = "rl"]
    /\ net' = [net EXCEPT ![n] = Append(@, Cmd("REL", i, conn[n], rd[r].tok, 0))]
    /\ faults' = faults + 1
    /\ UNCHANGED <<db, l1, conn, alive, l2, wr, pending, unfilter, chan, nextTok, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

OnFill(n, i) ==
    LET r == <<n, i>>
        x == rd[r]
        p == x.rep
    IN
    /\ alive[n] /\ x.pc = "fl" /\ p.k # "none"
    /\ \/ /\ p.k = "filled" /\ p.adv            \* the version advanced: a write on this node (§4.7, P11)
          /\ NoCompute(n)
          /\ rd' = Bumped([rd EXCEPT ![r] = Returned(x, x.snap)], n)
          /\ l2' = [l2 EXCEPT ![n] = NoCopy]
          /\ floorS' = [floorS EXCEPT ![n] = Max(@, x.snap)]
       \/ /\ p.k = "filled" /\ ~p.adv
          /\ rd' = [rd EXCEPT ![r] = Fixed(x, x.snap)]
          /\ UNCHANGED <<l2, floorS>>
       \/ /\ p.k = "rejected" /\ p.cur # NoVer /\ x.mode = "refresh"   \* version rejection (P2): refill L2 with
          /\ rd' = [rd EXCEPT ![r] = Fixed([x EXCEPT !.mode = "refill"], x.seen)]  \* what the token's read saw
          /\ UNCHANGED <<l2, floorS>>
       \/ /\ p.k = "rejected" /\ ~(p.cur # NoVer /\ x.mode = "refresh")
          /\ rd' = [rd EXCEPT ![r] = Fixed(x, x.snap)]          \* not filled: degraded L2 copy
          /\ UNCHANGED <<l2, floorS>>
    /\ UNCHANGED <<db, l1, net, conn, alive, wr, pending, unfilter, chan, nextTok, faults, evicts, noise,
                   l1db, l1ver, floorR, floorF, owed, lost>>

OnRelease(n, i) ==
    LET r == <<n, i>> IN
    /\ alive[n] /\ rd[r].pc = "rl" /\ rd[r].rep.k # "none"
    /\ rd' = [rd EXCEPT ![r] = RIdle]
    /\ UNCHANGED <<net, l2, nextTok>>
    /\ UnchangedForReaders

\* loadBypassingRemote: the source only, a degraded L2 copy still guarded by the stamp
Bypass(n, i) ==
    LET r == <<n, i>> IN
    /\ alive[n] /\ rd[r].pc = "by"
    /\ \E s \in LoaderSnaps : rd' = [rd EXCEPT ![r].pc = "c1", ![r].snap = s]
    /\ UNCHANGED <<net, l2, nextTok>>
    /\ UnchangedForReaders

(* fillGuarded (§4.5): c1 checks the stamp inside compute (Fixed), c2 writes, c3 rechecks and removes
   the copy by identity. A single-key eviction waits for the compute; a reset does not. *)
Compute(n, i) ==
    LET r == <<n, i>> IN
    /\ alive[n] /\ rd[r].pc = "c1"
    /\ rd' = [rd EXCEPT ![r] = Fixed(rd[r], rd[r].snap)]
    /\ UNCHANGED <<net, l2, nextTok>>
    /\ UnchangedForReaders

Write(n, i) ==
    LET r == <<n, i>> IN
    /\ alive[n] /\ rd[r].pc = "c2"
    /\ l2' = [l2 EXCEPT ![n] = [snap |-> rd[r].snap, own |-> i]]
    /\ rd' = [rd EXCEPT ![r].pc = "c3"]
    /\ UNCHANGED <<net, nextTok>>
    /\ UnchangedForReaders

Recheck(n, i) ==
    LET r == <<n, i>> IN
    /\ alive[n] /\ rd[r].pc = "c3"
    /\ l2' = [l2 EXCEPT ![n] = IF @.own # i THEN @ ELSE IF rd[r].ok THEN [@ EXCEPT !.own = 0] ELSE NoCopy]
    /\ rd' = [rd EXCEPT ![r] = Returned(rd[r], rd[r].snap)]
    /\ UNCHANGED <<net, nextTok>>
    /\ UnchangedForReaders

(* The command times out or its connection is gone: outcome unknown. The store releases the token
   on the current connection (K14); the reader takes the failure path. *)
ReaderTimeout(n, i) ==
    LET r    == <<n, i>>
        x    == rd[r]
        free == Unanswerable(n, i, x.rep)
        base == Append(Orphaned(net[n], i), Cmd("REL", Orphan, conn[n], x.tok, 0))
    IN
    /\ alive[n] /\ x.pc \in {"rd", "fl", "rl"}
    /\ free \/ CanFault("timeout")
    /\ faults' = IF free THEN faults ELSE faults + 1
    /\ \/ /\ x.pc = "rd" /\ x.mode # "refresh"           \* L1 read failed: degrade to the source (§5)
          /\ rd' = [rd EXCEPT ![r].pc = "by", ![r].tok = 0, ![r].rr = FALSE, ![r].rep = NoRep]
          /\ net' = [net EXCEPT ![n] = base]
          /\ UNCHANGED nextTok
       \/ /\ x.pc = "rd" /\ x.mode = "refresh"           \* refresh read failed: loadThrough
          /\ rd' = [rd EXCEPT ![r] = ReloadState(i, n)]
          /\ net' = [net EXCEPT ![n] = base \o ReloadCmds(i, n)]
          /\ nextTok' = ReloadTok(n)
       \/ /\ x.pc = "fl"                                 \* fill failed: completeLoad(filled = false)
          /\ rd' = [rd EXCEPT ![r] = Fixed(x, x.snap)]
          /\ net' = [net EXCEPT ![n] = base]
          /\ UNCHANGED nextTok
       \/ /\ x.pc = "rl"
          /\ rd' = [rd EXCEPT ![r] = RIdle]
          /\ net' = [net EXCEPT ![n] = base]
          /\ UNCHANGED nextTok
    /\ UNCHANGED <<db, l1, conn, alive, l2, wr, pending, unfilter, chan, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

-----------------------------------------------------------------------------
(* Writers (§4.2): commit to the source first, then write the cache.
   Cache: invalidate (failure mode RETRY). VersionedCache: putIfNewer / invalidateIfNewer, evict. *)

Commit(n) ==
    /\ n \in Writers /\ alive[n] /\ wr[n].pc = "idle" /\ db < MaxVersion
    /\ db' = db + 1
    /\ wr' = [wr EXCEPT ![n] = [WIdle EXCEPT !.pc = "b1", !.op = IF Versioned THEN "PIN" ELSE "INV", !.v = db + 1]]
    /\ UNCHANGED <<l1, net, conn, alive, l2, rd, pending, unfilter, chan, nextTok, faults, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

Evict(n) ==
    /\ Versioned /\ n \in Writers /\ alive[n] /\ wr[n].pc = "idle" /\ evicts < MaxEvicts
    /\ evicts' = evicts + 1
    /\ wr' = [wr EXCEPT ![n] = [WIdle EXCEPT !.pc = "b1", !.op = "EVICT"]]
    /\ UNCHANGED <<db, l1, net, conn, alive, l2, rd, pending, unfilter, chan, nextTok, faults, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

\* stamp and evict L2; a reader may take its stamp and read L1 before the script is sent (§4.3)
WriteStamp(n) ==
    /\ alive[n] /\ wr[n].pc = "b1" /\ NoCompute(n)
    /\ rd' = Bumped(rd, n)
    /\ l2' = [l2 EXCEPT ![n] = NoCopy]
    /\ wr' = [wr EXCEPT ![n].pc = "b2"]
    /\ UNCHANGED <<db, l1, net, conn, alive, pending, unfilter, chan, nextTok, faults, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

WriteSend(n) ==
    /\ alive[n] /\ wr[n].pc = "b2"
    /\ net' = [net EXCEPT ![n] = Append(@, Cmd(wr[n].op, WriterId, conn[n], 0, IF wr[n].op = "PIN" THEN wr[n].v ELSE 0))]
    /\ wr' = [wr EXCEPT ![n].pc = "w"]
    /\ UNCHANGED <<db, l1, conn, alive, l2, rd, pending, unfilter, chan, nextTok, faults, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

\* the script answered: stamp and evict L2 again; the call returns and the node has processed the write
WriteReturn(n) ==
    /\ alive[n] /\ wr[n].pc = "w" /\ wr[n].rep.k # "none" /\ NoCompute(n)
    /\ rd' = Bumped(rd, n)
    /\ l2' = [l2 EXCEPT ![n] = NoCopy]
    /\ floorS' = [floorS EXCEPT ![n] = Max(@, IF wr[n].op = "EVICT" THEN Max(wr[n].rep.cur, 0) ELSE wr[n].v)]
    /\ wr' = [wr EXCEPT ![n] = WIdle]
    /\ UNCHANGED <<db, l1, net, conn, alive, pending, unfilter, chan, nextTok, faults, evicts, noise,
                   l1db, l1ver, floorR, floorF, owed, lost>>

(* Outcome unknown. The second stamp and eviction still run; the node stops filtering its own
   frames (§4.6, P12). invalidate registers a retry and returns (RETRY); a versioned write throws. *)
WriteTimeout(n) ==
    LET free == Unanswerable(n, WriterId, wr[n].rep) IN
    /\ alive[n] /\ wr[n].pc = "w" /\ NoCompute(n)
    /\ free \/ CanFault("timeout")
    /\ faults' = IF free THEN faults ELSE faults + 1
    /\ net' = [net EXCEPT ![n] = Orphaned(@, WriterId)]
    /\ unfilter' = [unfilter EXCEPT ![n] = TRUE]
    /\ rd' = Bumped(rd, n)
    /\ l2' = [l2 EXCEPT ![n] = NoCopy]
    /\ IF Versioned
       THEN /\ wr' = [wr EXCEPT ![n] = WIdle]
            /\ UNCHANGED <<pending, floorS>>
       ELSE /\ wr' = [wr EXCEPT ![n] = [WIdle EXCEPT !.pc = "retry", !.v = wr[n].v]]
            /\ pending' = [pending EXCEPT ![n] = TRUE]
            /\ floorS' = [floorS EXCEPT ![n] = Max(@, wr[n].v)]
    /\ UNCHANGED <<db, l1, conn, alive, chan, nextTok, evicts, noise, l1db, l1ver, floorR, floorF, owed, lost>>

RetrySend(n) ==
    /\ alive[n] /\ wr[n].pc = "retry"
    /\ net' = [net EXCEPT ![n] = Append(@, Cmd("INV", WriterId, conn[n], 0, 0))]
    /\ wr' = [wr EXCEPT ![n].pc = "wr"]
    /\ UNCHANGED <<db, l1, conn, alive, l2, rd, pending, unfilter, chan, nextTok, faults, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

\* the retry succeeded: stamp and evict once more, reads use L1 again
RetryReturn(n) ==
    /\ alive[n] /\ wr[n].pc = "wr" /\ wr[n].rep.k # "none" /\ NoCompute(n)
    /\ pending' = [pending EXCEPT ![n] = FALSE]
    /\ rd' = Bumped(rd, n)
    /\ l2' = [l2 EXCEPT ![n] = NoCopy]
    /\ wr' = [wr EXCEPT ![n] = WIdle]
    /\ UNCHANGED <<db, l1, net, conn, alive, unfilter, chan, nextTok, faults, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

RetryTimeout(n) ==
    LET free == Unanswerable(n, WriterId, wr[n].rep) IN
    /\ alive[n] /\ wr[n].pc = "wr"
    /\ free \/ CanFault("timeout")
    /\ faults' = IF free THEN faults ELSE faults + 1
    /\ net' = [net EXCEPT ![n] = Orphaned(@, WriterId)]
    /\ unfilter' = [unfilter EXCEPT ![n] = TRUE]
    /\ wr' = [wr EXCEPT ![n] = [@ EXCEPT !.pc = "retry", !.rep = NoRep]]
    /\ UNCHANGED <<db, l1, conn, alive, l2, rd, pending, chan, nextTok, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

(* P12: filtering resumes once a later command on the same connection has completed, plus a
   delivery margin. The model clears the flag whenever that confirmation would be possible: no
   abandoned write of n can still run and its frame has reached n. *)
ConfirmWrites(n) ==
    /\ unfilter[n]
    /\ \A k \in 1..Len(net[n]) : ~(net[n][k].from = Orphan /\ net[n][k].op \in WriteOps)
    /\ \A fr \in chan[n] : ~(fr.own /\ fr.late = "write")
    /\ unfilter' = [unfilter EXCEPT ![n] = FALSE]
    /\ UNCHANGED <<db, l1, net, conn, alive, l2, rd, wr, pending, chan, nextTok, faults, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

-----------------------------------------------------------------------------
(* The invalidation channel (§8.1) *)

\* stamp and evict L2; a node drops its own frames unless a write's outcome is unknown
Deliver(m, fr, keep) ==
    /\ alive[m] /\ fr \in chan[m] /\ NoCompute(m)
    /\ keep => CanFault("dup")
    /\ faults' = IF keep THEN faults + 1 ELSE faults
    /\ chan' = IF keep THEN chan ELSE [chan EXCEPT ![m] = @ \ {fr}]
    /\ IF fr.own /\ ~unfilter[m]
       THEN /\ UNCHANGED <<rd, l2, owed>>
            /\ floorS' = IF fr.late = "write" THEN [floorS EXCEPT ![m] = Max(@, fr.f)] ELSE floorS
            /\ floorF' = IF fr.late = "fill" THEN [floorF EXCEPT ![m] = Max(@, fr.f)] ELSE floorF
       ELSE /\ rd' = Bumped(rd, m)
            /\ l2' = [l2 EXCEPT ![m] = NoCopy]
            /\ floorS' = [floorS EXCEPT ![m] = Max(@, fr.f)]
            /\ owed' = [owed EXCEPT ![m] = IF fr.f >= @ THEN 0 ELSE @]
            /\ UNCHANGED floorF
    /\ UNCHANGED <<db, l1, net, conn, alive, wr, pending, unfilter, nextTok, evicts, noise, l1db, l1ver, floorR, lost>>

LoseFrame(m, fr) ==
    /\ fr \in chan[m] /\ CanFault("loss")
    /\ chan' = [chan EXCEPT ![m] = @ \ {fr}]
    /\ lost' = [lost EXCEPT ![m] = TRUE]
    /\ faults' = faults + 1
    /\ UNCHANGED <<db, l1, net, conn, alive, l2, rd, wr, pending, unfilter, nextTok, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed>>

(* onReset: every stamp, then clear L2 by snapshot, which misses a compute in progress (I-C).
   Resubscription after a loss (heartbeat) is the protocol's own; any other reset is a fault. *)
ResetNode(m) ==
    /\ rd' = Bumped(rd, m)
    /\ l2' = [l2 EXCEPT ![m] = NoCopy]
    /\ floorR' = [floorR EXCEPT ![m] = Max(@, IF Versioned THEN l1ver ELSE l1db)]
    /\ owed' = [owed EXCEPT ![m] = 0]
    /\ lost' = [lost EXCEPT ![m] = FALSE]
    /\ UNCHANGED <<db, l1, net, conn, alive, wr, pending, unfilter, chan, nextTok, evicts, noise,
                   l1db, l1ver, floorS, floorF>>

Resubscribe(m) == alive[m] /\ lost[m] /\ ResetNode(m) /\ UNCHANGED faults
Reset(m) == alive[m] /\ CanFault("reset") /\ ResetNode(m) /\ faults' = faults + 1

-----------------------------------------------------------------------------
(* Node faults *)

\* commands in flight may still run, nobody hears their replies, tokens wait for lease.ttl
Crash(n) ==
    /\ alive[n] /\ CanFault("crash")
    /\ alive' = [alive EXCEPT ![n] = FALSE]
    /\ faults' = faults + 1
    /\ rd' = [r \in Readers |-> IF r[1] = n THEN [RIdle EXCEPT !.pc = "dead"] ELSE rd[r]]
    /\ wr' = [wr EXCEPT ![n] = [WIdle EXCEPT !.pc = "dead"]]
    /\ net' = [net EXCEPT ![n] = [k \in 1..Len(net[n]) |-> [net[n][k] EXCEPT !.from = Orphan]]]
    /\ l2' = [l2 EXCEPT ![n] = NoCopy]
    /\ chan' = [chan EXCEPT ![n] = {}]
    /\ pending' = [pending EXCEPT ![n] = FALSE]
    /\ unfilter' = [unfilter EXCEPT ![n] = FALSE]
    /\ UNCHANGED <<db, l1, conn, nextTok, evicts, noise, l1db, l1ver, floorS, floorR, floorF, owed, lost>>

\* a new connection: commands on the old one may still run later, after newer ones (P8, P9, P12)
Reconnect(n) ==
    /\ alive[n] /\ CanFault("reconnect")
    /\ conn' = [conn EXCEPT ![n] = @ + 1]
    /\ net' = [net EXCEPT ![n] = [k \in 1..Len(net[n]) |->
                 IF net[n][k].from = Orphan /\ net[n][k].op = "REL"
                 THEN [net[n][k] EXCEPT !.ep = conn[n] + 1] ELSE net[n][k]]]
    /\ faults' = faults + 1
    /\ UNCHANGED <<db, l1, alive, l2, rd, wr, pending, unfilter, chan, nextTok, evicts, noise,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

ExpireL2(n) ==                                  \* localMaxAge, degradedLocalMaxAge, Caffeine eviction
    /\ alive[n] /\ HasCopy(n) /\ noise < MaxNoise
    /\ l2' = [l2 EXCEPT ![n] = NoCopy]
    /\ noise' = noise + 1
    /\ UNCHANGED <<db, l1, net, conn, alive, rd, wr, pending, unfilter, chan, nextTok, faults, evicts,
                   l1db, l1ver, floorS, floorR, floorF, owed, lost>>

-----------------------------------------------------------------------------

Init ==
    /\ db = 0
    /\ l1 = Absent
    /\ net = [n \in Nodes |-> <<>>]
    /\ conn = [n \in Nodes |-> 0]
    /\ alive = [n \in Nodes |-> TRUE]
    /\ l2 = [n \in Nodes |-> NoCopy]
    /\ rd = [r \in Readers |-> RIdle]
    /\ wr = [n \in Nodes |-> WIdle]
    /\ pending = [n \in Nodes |-> FALSE]
    /\ unfilter = [n \in Nodes |-> FALSE]
    /\ chan = [n \in Nodes |-> {}]
    /\ nextTok = 1
    /\ faults = 0
    /\ evicts = 0
    /\ noise = 0
    /\ l1db = 0
    /\ l1ver = 0
    /\ floorS = [n \in Nodes |-> 0]
    /\ floorR = [n \in Nodes |-> 0]
    /\ floorF = [n \in Nodes |-> 0]
    /\ owed = [n \in Nodes |-> 0]
    /\ lost = [n \in Nodes |-> FALSE]

ReaderStep(n, i) ==
    \/ OnRead(n, i) \/ Load(n, i) \/ OnFill(n, i) \/ OnRelease(n, i) \/ Bypass(n, i)
    \/ Compute(n, i) \/ Write(n, i) \/ Recheck(n, i)

WriterStep(n) == WriteStamp(n) \/ WriteSend(n) \/ WriteReturn(n) \/ RetrySend(n) \/ RetryReturn(n)

Next ==
    \/ \E n \in Nodes : \E k \in 1..Len(net[n]) : Execute(n, k) \/ Drop(n, k)
    \/ \E n \in Nodes, i \in Rids :
         \/ StartMiss(n, i) \/ StartRefresh(n, i) \/ ReaderStep(n, i)
         \/ LoadFails(n, i) \/ ReaderTimeout(n, i)
    \/ \E n \in Nodes :
         \/ Commit(n) \/ Evict(n) \/ WriterStep(n) \/ WriteTimeout(n) \/ RetryTimeout(n)
         \/ ConfirmWrites(n) \/ Resubscribe(n) \/ Reset(n) \/ Crash(n) \/ Reconnect(n) \/ ExpireL2(n)
         \/ \E fr \in chan[n] : Deliver(n, fr, FALSE) \/ Deliver(n, fr, TRUE) \/ LoseFrame(n, fr)
    \/ ExpireL1
    \/ AgeToken

Spec == Init /\ [][Next]_vars

(* Liveness: the protocol's own steps are weakly fair; faults, expiry and token ageing are not. *)
Fairness ==
    /\ \A n \in Nodes : WF_vars(\E k \in 1..Len(net[n]) : Execute(n, k))
    /\ \A n \in Nodes, i \in Rids : WF_vars(ReaderStep(n, i))
    /\ \A n \in Nodes : WF_vars(WriterStep(n))
    /\ \A n \in Nodes : WF_vars(\E fr \in chan[n] : Deliver(n, fr, FALSE))
    /\ \A n \in Nodes : WF_vars(ConfirmWrites(n))
    /\ \A n \in Nodes : WF_vars(Resubscribe(n))
    \* a command that can no longer be answered fails (disconnect, command timeout): not a fault
    /\ \A n \in Nodes, i \in Rids : WF_vars(Unanswerable(n, i, rd[<<n, i>>].rep) /\ ReaderTimeout(n, i))
    /\ \A n \in Nodes : WF_vars(Unanswerable(n, WriterId, wr[n].rep) /\ (WriteTimeout(n) \/ RetryTimeout(n)))

FairSpec == Spec /\ Fairness

-----------------------------------------------------------------------------
(* Properties. docs/spec/README.md maps each to docs/architecture.md §1 and §14. *)

TypeOK ==
    /\ db \in 0..MaxVersion
    /\ l1.st \in {"A", "L", "V", "M", "E"} /\ l1.tok \in 0..MaxTokens /\ l1.aged \in BOOLEAN /\ l1.late \in BOOLEAN
    /\ l1.ver \in {NoVer} \cup 0..MaxVersion /\ l1.snap \in 0..MaxVersion /\ l1.src \in {"fill", "write"}
    /\ \A n \in Nodes : l2[n].snap \in -1..MaxVersion /\ l2[n].own \in {0} \cup Rids
    /\ \A n \in Nodes : \A k \in 1..Len(net[n]) : net[n][k].ep \in 0..conn[n]
    /\ nextTok \in 1..(MaxTokens + 1) /\ faults \in 0..MaxFaults /\ noise \in 0..MaxNoise

(* G2, L1 clause: a fill whose read began before an invalidation never lands after it. The token
   was granted after the last L1 change that destroyed tokens, and the loader ran after the grant. *)
G2L1 == ~LaggingLoader =>
    (l1.st \in {"V", "M"} /\ l1.src = "fill" => l1.snap >= l1db)

(* G2, L2 clause: once node n processed an invalidation (its own call returned, it handled the
   frame, or it reset), its L2 never shows data older than that invalidation. After a reset the
   copy of a compute already in progress may stay until that filler's recheck (I-C). *)
G2L2 == ~LaggingLoader =>
    \A n \in Nodes : alive[n] /\ HasCopy(n) =>
        /\ l2[n].snap >= floorS[n]
        /\ \/ l2[n].snap >= floorR[n]
           \/ l2[n].own # 0 /\ rd[<<n, l2[n].own>>].pc = "c3" /\ ~rd[<<n, l2[n].own>>].ok

(* G2 for callers: a read that began after node n processed an invalidation returns newer data.
   (Returned parks a reader that broke it in "stale".) *)
G2Read == \A r \in Readers : rd[r].pc # "stale"

(* No invalidation is lost: a node whose L2 holds data older than a write that ran in L1 will
   still evict it, by a frame in flight or by the reset that follows a lost frame. *)
NoLostInvalidation == ~LaggingLoader =>
    \A m \in Nodes : alive[m] /\ HasCopy(m) /\ l2[m].snap < owed[m] =>
        lost[m] \/ \E fr \in chan[m] : fr.f >= owed[m]

(* I-K: through VersionedCache writes and fills the L1 version never goes back, and is never
   erased; only expiry (or eviction) erases it. *)
VersionMonotonic ==
    [][ExpireL1 \/ (l1.ver # NoVer => (l1'.ver # NoVer /\ l1'.ver >= l1.ver))]_vars

(* An invalidation that will evict node n's L2 is on its way: a frame n will process, the second
   stamp bump of n's own write, or the reset after a lost frame. *)
EvictionPending(n) ==
    \/ lost[n]
    \/ wr[n].pc = "w"
    \/ \E fr \in chan[n] : ~fr.own \/ unfilter[n]

(* P2: a refresh never puts a version older than L1's into L2, unless an eviction is pending. *)
RefreshKeepsNewer ==
    \A r \in Readers : rd[r].pc = "c2" /\ rd[r].mode = "refresh" /\ VerOf(rd[r].snap) < l1.ver =>
        EvictionPending(r[1])

(* Stronger than G2L2: a version-advancing fill that ran after its reader gave up counts as a
   write on that node, like a write whose outcome is unknown (P11 with P12). *)
G2L2LateFill == ~LaggingLoader =>
    \A n \in Nodes : alive[n] /\ HasCopy(n) => l2[n].snap >= floorF[n]

(* I-F: every token is filled or released, unless it ages out (lease.ttl). *)
TokensReleased ==
    \A t \in 1..MaxTokens : (l1.tok = t /\ ~l1.aged) ~> (l1.tok # t \/ l1.aged)

(* With connection replacement (TLA+ finding F4, an accepted bound): the store's release can run before
   a drained READ that grants the token after its reader gave up; nobody releases that token and it
   lives until lease.ttl. Every other token is filled or released. *)
TokensBounded ==
    \A t \in 1..MaxTokens : (l1.tok = t /\ ~l1.aged /\ ~l1.late) ~> (l1.tok # t \/ l1.aged)

(* G1 where it can be stated without time: once writers stop, every live node's L2 converges to
   the last commit without relying on L2 expiry (localMaxAge). *)
Converges ==
    <>[](\A n \in Nodes : alive[n] /\ HasCopy(n) => l2[n].snap = db)

=============================================================================
