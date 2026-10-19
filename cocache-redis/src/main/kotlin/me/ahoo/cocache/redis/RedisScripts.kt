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

package me.ahoo.cocache.redis

import java.security.MessageDigest

/**
 * L1 协议的 Lua 脚本（RFC 0003 §4.3–§4.4）。
 *
 * 线格式：
 * - 标量、负缓存、lease 占位是 String：36 字节定长头 + 载荷。
 *   头：`[0]` magic `0xC0`、`[1]` 格式版本、`[2]` 状态（`V` 值 / `M` 负缓存 / `L` lease）、`[3]` 保留、
 *   `[4..27]` 令牌（lease 或刷新令牌，全零表示无）、`[28..35]` 令牌签发时间（服务端 `TIME`，毫秒，大端 double）。
 * - 集合是 Hash：字段 `!f` 为格式版本，成员字段为 `+` + 成员字节，值为空串。
 *
 * 约定：
 * - 脚本不带 shebang 标志：带标志的脚本在 OOM 时被拒绝，而失效恰恰会释放内存。
 * - 每次状态转换都重设到期时间（`PX` / `PEXPIRE`；永不过期时新建的 key 本就没有到期时间）。
 * - TTL 参数以毫秒传入，`0` 表示永不过期；频道参数为空串时不广播（后端不支持原子广播）。
 */
internal object RedisScripts {
    const val FORMAT = 1
    const val HEADER_SIZE = 36
    const val MAGIC: Byte = 0xC0.toByte()

    /**
     * 读取结果码：未命中（附 lease 是否取得）、他人持有 lease（附剩余毫秒）、标量命中、集合命中。
     */
    const val RESULT_MISS = 0L
    const val RESULT_LEASED = 1L
    const val RESULT_SCALAR = 2L
    const val RESULT_SET = 3L

    /**
     * 所有脚本共用的辅助函数。读脚本只引入这一部分：每次执行都会重新定义脚本中的函数，读路径不应为写路径的辅助函数付出成本。
     */
    private const val CORE = """
local ZERO_TOKEN = string.rep('\0', 24)
local function now_ms()
  local t = redis.call('TIME')
  return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end
local function header(state, token, issued)
  return '\192\1' .. state .. '\0' .. token .. struct.pack('>d', issued)
end
local function is_ours(v)
  return string.len(v) >= 36 and string.byte(v, 1) == 192
end
"""

    /**
     * 写脚本额外使用的辅助函数。
     */
    private const val PRELUDE = CORE + """
local function expire(key, ttl)
  local ms = tonumber(ttl)
  if ms > 0 then redis.call('PEXPIRE', key, ms) end
end
local function write(key, kind, ttl, first)
  redis.call('DEL', key)
  if kind == 'S' then
    redis.call('HSET', key, '!f', '1')
    for i = first, #ARGV do redis.call('HSET', key, '+' .. ARGV[i], '') end
  elseif kind == 'V' then
    redis.call('SET', key, header('V', ZERO_TOKEN, 0) .. ARGV[first])
  else
    redis.call('SET', key, header('M', ZERO_TOKEN, 0))
  end
  expire(key, ttl)
end
local function publish(channel, frame)
  if channel ~= '' then redis.call('PUBLISH', channel, frame) end
end
"""

    /**
     * 读取。ARGV：lease 令牌（空串为不申请）、lease 寿命、刷新窗口（空串为不申请）、刷新令牌、刷新令牌寿命。
     */
    val READ = Script(
        CORE + """
local key = KEYS[1]
local pttl = redis.call('PTTL', key)
if pttl == -2 then
  if ARGV[1] ~= '' then
    redis.call('SET', key, header('L', ARGV[1], now_ms()), 'PX', ARGV[2])
    return {0, 1}
  end
  return {0, 0}
end
-- 常见路径是 String：直接 GET（与 5.x 相同的两条命令）；类型不符（集合的 Hash）时退回 HGETALL
local v = redis.pcall('GET', key)
if type(v) == 'table' then
  return {3, pttl, redis.call('HGETALL', key)}
end
if not is_ours(v) then return {2, pttl, v, 0} end
local state = string.sub(v, 3, 3)
if state == 'L' then return {1, pttl} end
if state == 'V' and ARGV[3] ~= '' and pttl > 0 and pttl <= tonumber(ARGV[3]) then
  local token = string.sub(v, 5, 28)
  local now = now_ms()
  local issued = struct.unpack('>d', string.sub(v, 29, 36))
  if token == ZERO_TOKEN or now - issued > tonumber(ARGV[5]) then
    redis.call('SETRANGE', key, 4, ARGV[4] .. struct.pack('>d', now))
    return {2, pttl, v, 1}
  end
end
return {2, pttl, v, 0}
"""
    )

    /**
     * 令牌保护的填充。ARGV：令牌、形状（V/M/S）、TTL、载荷或成员…
     */
    val FILL = Script(
        PRELUDE + """
local key = KEYS[1]
if redis.call('TYPE', key)['ok'] ~= 'string' then return 0 end
local v = redis.call('GET', key)
if not is_ours(v) or string.sub(v, 5, 28) ~= ARGV[1] then return 0 end
write(key, ARGV[2], ARGV[3], 4)
return 1
"""
    )

    /**
     * 释放令牌。ARGV：令牌。
     */
    val RELEASE = Script(
        PRELUDE + """
local key = KEYS[1]
if redis.call('TYPE', key)['ok'] ~= 'string' then return 0 end
local v = redis.call('GET', key)
if not is_ours(v) or string.sub(v, 5, 28) ~= ARGV[1] then return 0 end
if string.sub(v, 3, 3) == 'L' then
  redis.call('DEL', key)
else
  redis.call('SETRANGE', key, 4, ZERO_TOKEN .. struct.pack('>d', 0))
end
return 1
"""
    )

    /**
     * 整体覆盖写入并广播。ARGV：形状、TTL、频道、帧、载荷或成员…
     */
    val PUT = Script(
        PRELUDE + """
write(KEYS[1], ARGV[1], ARGV[2], 5)
publish(ARGV[3], ARGV[4])
return 1
"""
    )

    /**
     * 删除并广播。KEYS：同一 slot 内的 key；ARGV：频道、帧。
     */
    val INVALIDATE = Script(
        PRELUDE + """
for i = 1, #KEYS do redis.call('DEL', KEYS[i]) end
publish(ARGV[1], ARGV[2])
return 1
"""
    )

    /**
     * 条件回收。ARGV：期望摘要（十六进制）、lease 令牌、lease 寿命。
     * 摘要：标量为载荷（无法识别的 String 为整个值），负缓存为空串，集合为按返回顺序拼接的 `int32 长度 + 成员`。
     */
    val RECLAIM = Script(
        PRELUDE + """
local key = KEYS[1]
local kind = redis.call('TYPE', key)['ok']
local content
if kind == 'string' then
  local v = redis.call('GET', key)
  if is_ours(v) then
    if string.sub(v, 3, 3) == 'L' then return 0 end
    content = string.sub(v, 37)
  else
    content = v
  end
elseif kind == 'hash' then
  local parts = {}
  local flat = redis.call('HGETALL', key)
  for i = 1, #flat, 2 do
    local field = flat[i]
    if string.sub(field, 1, 1) == '+' then
      local member = string.sub(field, 2)
      parts[#parts + 1] = struct.pack('>I4', string.len(member)) .. member
    end
  end
  content = table.concat(parts)
else
  return 0
end
if redis.sha1hex(content) ~= ARGV[1] then return 0 end
redis.call('DEL', key)
redis.call('SET', key, header('L', ARGV[2], now_ms()), 'PX', ARGV[3])
return 1
"""
    )

    /**
     * 加入集合成员。ARGV：TTL、频道、帧、成员…
     */
    val ADD_MEMBERS = Script(
        PRELUDE + """
local key = KEYS[1]
if redis.call('TYPE', key)['ok'] ~= 'hash' then
  redis.call('DEL', key)
  redis.call('HSET', key, '!f', '1')
end
for i = 4, #ARGV do redis.call('HSET', key, '+' .. ARGV[i], '') end
if tonumber(ARGV[1]) > 0 then redis.call('PEXPIRE', key, ARGV[1]) else redis.call('PERSIST', key) end
publish(ARGV[2], ARGV[3])
return 1
"""
    )

    /**
     * 移除集合成员，移空则删除。ARGV：TTL、频道、帧、成员…
     */
    val REMOVE_MEMBERS = Script(
        PRELUDE + """
local key = KEYS[1]
if redis.call('TYPE', key)['ok'] == 'hash' then
  for i = 4, #ARGV do redis.call('HDEL', key, '+' .. ARGV[i]) end
  if redis.call('HLEN', key) <= 1 then
    redis.call('DEL', key)
  elseif tonumber(ARGV[1]) > 0 then
    redis.call('PEXPIRE', key, ARGV[1])
  else
    redis.call('PERSIST', key)
  end
end
publish(ARGV[2], ARGV[3])
return 1
"""
    )

    /**
     * 原子广播探测：向一个与 key 不在同一 slot 的频道发布。把 PUBLISH 当作 SPUBLISH 执行的环境会报跨 slot 错误。
     */
    val PROBE = Script(
        """
redis.call('PUBLISH', ARGV[1], '')
return 1
"""
    )

    /**
     * 脚本及其 SHA-1（EVALSHA 用）。
     */
    class Script(source: String) {
        val source: ByteArray = source.trimIndent().toByteArray(Charsets.UTF_8)
        val sha: String = MessageDigest.getInstance("SHA-1").digest(this.source).joinToString("") { "%02x".format(it) }
    }
}
