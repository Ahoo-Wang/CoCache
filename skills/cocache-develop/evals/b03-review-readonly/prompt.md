---
name: b03-review-readonly
tags: [behavior, review, read-only]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Review this diff of a downstream service that uses CoCache 6.0.0 and report only merge-blocking findings with file and line. Do not modify anything.

```diff
--- a/src/main/kotlin/shop/ProductCache.kt
+++ b/src/main/kotlin/shop/ProductCache.kt
@@ -0,0 +1,9 @@
+@CoCache(ttl = 600)
+interface ProductCache : Cache<ProductKey, Product>
+
+class ProductKey(val tenant: String, val id: Long)
+
+@CoCache
+interface ProductTagsCache : SetCache<Long, String>   // tags are stored in the product_tag table
+
--- a/src/main/kotlin/shop/ProductService.kt
+++ b/src/main/kotlin/shop/ProductService.kt
@@ -10,6 +10,18 @@
+    @Cacheable(cacheNames = ["ProductCache"], key = "#key")
+    fun find(key: ProductKey): Product? = repo.find(key.tenant, key.id)
+
+    @Transactional
+    fun updatePrice(key: ProductKey, price: BigDecimal) {
+        productCache.invalidate(key)
+        repo.updatePrice(key.tenant, key.id, price)
+    }
+
+    fun addTag(id: Long, tag: String) {
+        tagRepo.insert(id, tag)
+        productTagsCache.add(id, listOf(tag))
+    }
```
