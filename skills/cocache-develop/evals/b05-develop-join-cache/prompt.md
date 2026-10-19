---
name: b05-develop-join-cache
tags: [behavior, join-cache]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Our app (CoCache 6.0.0) already declares `OrderCache : Cache<String, Order>` and `CustomerCache : Cache<Long, Customer>`; `Order` has `customerId: Long`. Add a typed view that returns an order with its customer for both single and batch reads, and explain how it stays fresh.
