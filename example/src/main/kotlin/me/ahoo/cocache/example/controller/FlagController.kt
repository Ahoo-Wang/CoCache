/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cocache.example.controller

import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.example.cache.FeatureFlagStore
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.HexFormat

/**
 * `Store` 的用法：值只在 Redis 中，写入方直接写入；需要“读—改—写”时用摘要做条件写入（[FeatureFlagStore.putIfMatch]）。
 */
@RestController
@RequestMapping("flag")
class FlagController(private val flags: FeatureFlagStore) {
    @GetMapping("{name}")
    fun get(@PathVariable name: String): String? = flags[name]

    @PutMapping("{name}")
    fun set(@PathVariable name: String, @RequestParam value: String) = flags.put(name, value)

    /**
     * 只在开关仍是调用方读到的那一份时修改：[expected] 为上一次写入返回的摘要（SHA-256 的十六进制），为空表示“还没有这个开关”。
     *
     * @return 写入时为新值的摘要（十六进制），冲突时为 `null`
     */
    @PutMapping("{name}/if-match")
    fun setIfMatch(
        @PathVariable name: String,
        @RequestParam value: String,
        @RequestParam(required = false) expected: String?
    ): String? {
        val digest = expected?.takeIf { it.isNotBlank() }?.let { Digest(HEX.parseHex(it)) }
        return when (val result = flags.putIfMatch(name, digest, value)) {
            is ConditionalPut.Written -> HEX.formatHex(result.digest.bytes)
            is ConditionalPut.Conflict -> null
        }
    }

    /**
     * 删除同步完成；L1 失败时抛出 `CacheWriteException`，不排队重试。
     */
    @DeleteMapping("{name}")
    fun delete(@PathVariable name: String) = flags.delete(name)

    private companion object {
        val HEX: HexFormat = HexFormat.of()
    }
}
