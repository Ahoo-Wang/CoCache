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

import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Token
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * L1 协议的 Lua 脚本（RFC 0003 §4.3–§4.4）与线格式 2（RFC 0005 §4.7，`docs/architecture.md` §3.3）。
 *
 * 线格式：
 * - 标量、负缓存、lease 占位是 String：36 字节的核心头部 + 标志位声明的段 + 载荷。
 *   核心头部：`[0]` magic `0xC0`、`[1]` 格式版本（`2`）、`[2]` 状态（`V` 值 / `M` 负缓存 / `L` lease）、`[3]` 标志位、
 *   `[4..27]` 令牌（lease 或刷新令牌，全零表示无）、`[28..35]` 令牌签发时间（服务端 `TIME`，毫秒，大端 double）。
 *   段按标志位的顺序紧随其后：bit 0 版本（8 字节，大端有符号）、bit 1 摘要（32 字节，M4c 起写入）、bit 7 扩展区
 *   （2 字节总长 + TLV），之后是载荷。bit 2–6 是未知的必须理解的位：带有它们的内容等同于更高格式。
 * - 集合是 Hash：字段 `!f` 为格式版本（`2`），`!m` 为必须理解的位图（缺省为 0），成员字段为 `+` + 成员字节，值为空串；
 *   其余以 `!` 开头的字段预留，读者忽略。
 *
 * 载荷偏移只有一个定义：Lua 的 `payload_start`（[FORMAT_RULES]）与客户端的 [payloadOffset]，两者逐行对应。
 *
 * 约定：
 * - 脚本不带 shebang 标志：带标志的脚本在 OOM 时被拒绝，而失效恰恰会释放内存。
 * - 每次状态转换都重设到期时间（`PX` / `PEXPIRE`；永不过期时新建的 key 本就没有到期时间）。
 * - TTL 参数以毫秒传入，`0` 表示永不过期；频道参数为空串时不广播（后端不支持原子广播）。
 */
internal object RedisScripts {
    const val FORMAT = 2
    const val HEADER_SIZE = 36
    const val MAGIC: Byte = 0xC0.toByte()

    /**
     * 核心头部中状态与标志位的偏移。
     */
    const val STATE_OFFSET = 2
    const val FLAGS_OFFSET = 3

    /**
     * 标志位（RFC 0005 §4.7）：bit 0–6 是必须理解的，bit 7 的扩展区只放旧读者可以忽略的信息。
     */
    const val FLAG_VERSION = 0x01
    const val FLAG_DIGEST = 0x02
    const val FLAG_EXTENSION = 0x80

    /**
     * 本版本不认识的必须理解的位（bit 2–6）。
     */
    const val UNKNOWN_FLAGS = 0x7C

    /**
     * 全部必须理解的位（bit 0–6）：只有这些位都为 0（至多带扩展区）的值能取得刷新令牌。
     */
    private const val MUST_UNDERSTAND_FLAGS = 0x7F

    /**
     * 声明了版本段或摘要段的位：带这两种段的内容从不回收。
     */
    private const val SEGMENT_FLAGS = FLAG_VERSION or FLAG_DIGEST

    const val VERSION_SIZE = 8
    const val DIGEST_SIZE = 32

    /**
     * 扩展区开头的总长字段（大端无符号 16 位）：其后 TLV 的字节数，不含它自己。
     */
    const val EXTENSION_LENGTH_SIZE = 2

    /**
     * TLV 的长度：`0..127` 为 1 字节；`128..32767` 为 2 字节，首字节为 `0x80 | (长度 >> 8)`、次字节为长度的低 8 位。
     */
    const val TLV_SHORT_LENGTH_LIMIT = 0x80
    const val TLV_MAX_LENGTH = 0x7FFF

    /**
     * TLV 类型 1：写入标识（24 字节，与 [Token] 同形，D2）。
     */
    const val TLV_WRITER: Byte = 1

    /**
     * 读不懂的格式 2 内容（未知的必须理解的位、未知的状态、`!m` 中有位的集合）报告的格式版本：高于本节点，
     * 引擎因此按更高格式处理，既不回收也不写回（RFC 0005 §4.7，P1、K15）。头部中的格式字节仍是 `2`。
     */
    const val NEWER_FORMAT = FORMAT + 1

    /**
     * 不是本协议写入的内容（不以 magic 开头、太短、格式字节为 `0`、段超出值的长度）报告的格式版本：引擎按读不懂处理。
     */
    const val UNRECOGNIZED = 0

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
local function header(state, flags, token, issued)
  return '\192\2' .. state .. flags .. token .. struct.pack('>d', issued)
end
local function is_ours(v)
  return string.len(v) >= 36 and string.byte(v, 1) == 192
end
"""

    /**
     * 写脚本额外使用的辅助函数。
     *
     * 形状参数 `kind`：集合为 `S`；标量为状态（`V` 值、`M` 负缓存）+ 标志位（1 字节）+ 段，段即写入之后核心头部与载荷之间的字节，
     * 由客户端按标志位的顺序构造（[scalarShape]）。版本段中不得是 `NO_VERSION`（K15）：在第一次写入之前以错误回复拒绝。
     */
    internal const val PRELUDE = CORE + """
local NO_VERSION = '\128\0\0\0\0\0\0\0'
local function expire(key, ttl)
  local ms = tonumber(ttl)
  if ms > 0 then redis.call('PEXPIRE', key, ms) end
end
local function check_shape(kind)
  if string.sub(kind, 1, 1) ~= 'S' and bit.band(string.byte(kind, 2), 1) ~= 0 and string.sub(kind, 3, 10) == NO_VERSION then
    error('CoCache: a version segment must not hold NO_VERSION')
  end
end
local function write(key, kind, ttl, first, last)
  check_shape(kind)
  redis.call('DEL', key)
  local shape = string.sub(kind, 1, 1)
  if shape == 'S' then
    redis.call('HSET', key, '!f', '$FORMAT')
    for i = first, last or #ARGV do redis.call('HSET', key, '+' .. ARGV[i], '') end
  else
    local v = header(shape, string.sub(kind, 2, 2), ZERO_TOKEN, 0) .. string.sub(kind, 3)
    if shape == 'V' then v = v .. ARGV[first] end
    redis.call('SET', key, v)
  end
  expire(key, ttl)
end
local function publish(channel, frame)
  if channel ~= '' then redis.call('PUBLISH', channel, frame) end
end
"""

    /**
     * 交回令牌的脚本（[FILL]、[RELEASE]）的检查：`h` 是核心头部（`GETRANGE key 0 35`，K19：不为比较 24 字节的令牌读入整个值），
     * 只有格式 2、没有未知的必须理解的位的内容中的令牌能匹配。
     */
    private const val HOLDS = """
local function holds(h, token)
  return string.len(h) == 36 and string.byte(h, 1) == 192 and string.byte(h, 2) == $FORMAT and
    bit.band(string.byte(h, 4), $UNKNOWN_FLAGS) == 0 and string.sub(h, 5, 28) == token
end
"""

    /**
     * 格式的判定（回收、条件读写、按版本写入与集合元素写入的脚本引入），与客户端的 [payloadOffset]、
     * `RedisL1Store.decodeRead` 一致：
     * - `payload_start(v, flags)`：载荷的起点（1 起），按标志位依次跳过版本段（8 字节）、摘要段（32 字节）与扩展区（2 + 总长）；
     *   段超出值的长度时为 `nil`。
     * - `scalar_format(v)`：报告的格式版本，本格式时另返回状态、标志位与载荷起点。`2` 是本格式；低于 2 的是读不懂的内容：
     *   `0` 是不是本协议写入的（不以 magic 开头、格式字节为 0、段超出长度），`1` 是 6.x 写入的（原样报告）；高于 2 的是更高的格式，
     *   原样报告，格式 2 中未知的必须理解的位或未知的状态报告为 [NEWER_FORMAT]。
     * - `set_format(key)`：集合的格式版本（K10）。`!f` 为十进制的格式版本，缺失或不是数字时为 0；`!f` 为 2 而 `!m` 存在且不是 `'0'`
     *   时报告为 [NEWER_FORMAT]（本版本没有已知的位）。
     */
    private const val FORMAT_RULES = """
local function payload_start(v, flags)
  local i = 37
  if bit.band(flags, $FLAG_VERSION) ~= 0 then i = i + $VERSION_SIZE end
  if bit.band(flags, $FLAG_DIGEST) ~= 0 then i = i + $DIGEST_SIZE end
  if bit.band(flags, $FLAG_EXTENSION) ~= 0 then
    if string.len(v) < i + 1 then return nil end
    i = i + 2 + struct.unpack('>I2', v, i)
  end
  if i > string.len(v) + 1 then return nil end
  return i
end
local function scalar_format(v)
  if not is_ours(v) then return 0 end
  local f = string.byte(v, 2)
  if f ~= $FORMAT then return f end
  local flags, state = string.byte(v, 4), string.sub(v, 3, 3)
  if bit.band(flags, $UNKNOWN_FLAGS) ~= 0 or (state ~= 'V' and state ~= 'M' and state ~= 'L') then return $NEWER_FORMAT end
  local start = payload_start(v, flags)
  if start == nil then return 0 end
  return f, state, flags, start
end
local function set_format(key)
  local fields = redis.call('HMGET', key, '!f', '!m')
  local f = fields[1]
  if not f or string.len(f) > 9 or not string.match(f, '^%d+$') then return 0 end
  f = tonumber(f)
  if f == $FORMAT and fields[2] and fields[2] ~= '0' then return $NEWER_FORMAT end
  return f
end
"""

    /**
     * 读取。ARGV：lease 令牌（空串为不申请）、lease 寿命、刷新窗口（空串为不申请）、刷新令牌、刷新令牌寿命。
     *
     * 授予令牌之前先看格式与标志位（K15）：格式不是 2 或有未知的必须理解的位时原样返回、不授予令牌（客户端按读不懂或更高的格式处理），
     * 其中的 `L` 也不当作 lease。刷新令牌只授予没有段的值（标志位至多是 bit 7）：带版本段的暂不授予（同 6.x，M4d 放开），
     * 带摘要段的不授予（D4）。
     */
    val READ = Script(
        CORE + """
local function is_wrongtype(reply)
  return string.sub(reply['err'], 1, 9) == 'WRONGTYPE'
end
local key = KEYS[1]
local pttl = redis.call('PTTL', key)
if pttl == -2 then
  if ARGV[1] ~= '' then
    -- 内存不足（noeviction）等写入失败时降级为不带 lease 的未命中，而不是让读失败
    local placed = redis.pcall('SET', key, header('L', '\0', ARGV[1], now_ms()), 'PX', ARGV[2])
    if placed['err'] then return {0, 0} end
    return {0, 1}
  end
  return {0, 0}
end
-- 常见路径是 String：直接 GET（与 5.x 相同的两条命令）；类型不符时退回集合的 Hash：
-- HKEYS 只取字段名（成员的值都是空串，不必返回），格式与必须理解的位图另由 HMGET 取；HKEYS 与 HGETALL 的字段顺序相同
-- 只有 WRONGTYPE 表示类型不符；其它错误（如 ACL 缺少命令）原样抛给调用方，不能当作无法识别的内容去回收
local v = redis.pcall('GET', key)
if type(v) == 'table' then
  if not is_wrongtype(v) then return v end
  local fields = redis.pcall('HKEYS', key)
  if fields['err'] then
    if not is_wrongtype(fields) then return fields end
    -- 其它类型（原生 Set / List / ZSet 等，如 5.x 遗留）：以空内容的无法识别值返回，由引擎按损坏回收（摘要为空串）
    return {2, pttl, '', 0}
  end
  local meta = redis.call('HMGET', key, '!f', '!m')
  return {3, pttl, fields, meta[1] or '', meta[2] or ''}
end
if not is_ours(v) then return {2, pttl, v, 0} end
local flags = string.byte(v, 4)
if string.byte(v, 2) ~= $FORMAT or bit.band(flags, $UNKNOWN_FLAGS) ~= 0 then return {2, pttl, v, 0} end
local state = string.sub(v, 3, 3)
if state == 'L' then return {1, pttl} end
if state == 'V' and bit.band(flags, $MUST_UNDERSTAND_FLAGS) == 0 and ARGV[3] ~= '' and pttl > 0 and pttl <= tonumber(ARGV[3]) then
  local token = string.sub(v, 5, 28)
  local now = now_ms()
  local issued = struct.unpack('>d', string.sub(v, 29, 36))
  if token == ZERO_TOKEN or now - issued > tonumber(ARGV[5]) then
    -- 写入失败（内存不足等）时降级为不授予刷新令牌的普通命中
    local granted = redis.pcall('SETRANGE', key, 4, ARGV[4] .. struct.pack('>d', now))
    if type(granted) == 'table' then return {2, pttl, v, 0} end
    return {2, pttl, v, 1}
  end
end
return {2, pttl, v, 0}
"""
    )

    /**
     * 令牌保护的填充。ARGV：令牌、形状、TTL、载荷或成员…
     */
    val FILL = Script(
        PRELUDE + HOLDS + """
local key = KEYS[1]
if redis.call('TYPE', key)['ok'] ~= 'string' then return 0 end
if not holds(redis.call('GETRANGE', key, 0, 35), ARGV[1]) then return 0 end
write(key, ARGV[2], ARGV[3], 4)
return 1
"""
    )

    /**
     * 释放令牌：`L` 删除，其它状态只清零令牌（只触及字节 4–35，保留到期时间）。ARGV：令牌。
     */
    val RELEASE = Script(
        CORE + HOLDS + """
local key = KEYS[1]
if redis.call('TYPE', key)['ok'] ~= 'string' then return 0 end
local h = redis.call('GETRANGE', key, 0, 35)
if not holds(h, ARGV[1]) then return 0 end
if string.sub(h, 3, 3) == 'L' then
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
     * 批量整体覆盖写入并广播一次。KEYS：同一 slot 内的 key；ARGV：频道、帧，然后每个 key 依次为形状、TTL、载荷个数、载荷或成员…
     * 每个 key 的转换与 [PUT] 相同；先检查全部形状，版本段的拒绝（K15）发生在第一次写入之前。
     */
    val PUT_ALL = Script(
        PRELUDE + """
local i = 3
for k = 1, #KEYS do
  check_shape(ARGV[i])
  i = i + 3 + tonumber(ARGV[i + 2])
end
i = 3
for k = 1, #KEYS do
  local last = i + 2 + tonumber(ARGV[i + 2])
  write(KEYS[k], ARGV[i], ARGV[i + 1], i + 3, last)
  i = last + 1
end
publish(ARGV[1], ARGV[2])
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
     * 摘要：本格式的标量为载荷（从 `payload_start` 起），读不懂的 String（低于本格式，含 6.x 的格式 1）为整个值，
     * 负缓存与其它类型（原生 Set / List 等）为空串，集合为按返回顺序拼接的 `int32 长度 + 成员`。
     * 从不回收：lease；更高的格式与带未知的必须理解的位的内容（K15）；带版本段的内容（lease 占位放不下版本，
     * 回收会让迟到的旧版本写入；读不懂它的节点绕过 L1 回源，等新版本覆盖它）；带摘要段的内容（权威标量，D4）。
     */
    val RECLAIM = Script(
        PRELUDE + FORMAT_RULES + """
local key = KEYS[1]
local kind = redis.call('TYPE', key)['ok']
local content
if kind == 'string' then
  local v = redis.call('GET', key)
  local f, state, flags, start = scalar_format(v)
  if f < $FORMAT then
    content = v
  elseif f > $FORMAT or state == 'L' or bit.band(flags, $SEGMENT_FLAGS) ~= 0 then
    return 0
  else
    content = string.sub(v, start)
  end
elseif kind == 'hash' then
  local f = set_format(key)
  if f > $FORMAT then return 0 end
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
  -- 其它类型（原生 Set / List / ZSet 等）：读脚本以空内容返回，摘要按空串计算
  content = ''
end
if redis.sha1hex(content) ~= ARGV[1] then return 0 end
redis.call('DEL', key)
redis.call('SET', key, header('L', '\0', ARGV[2], now_ms()), 'PX', ARGV[3])
return 1
"""
    )

    /**
     * 元素增删前的检查（只有 `*_IF_READABLE` 脚本引入）：返回 key 中不能当作本格式集合的内容的格式版本，可以写入时返回 `nil`。
     * 不能当作集合：其它 Redis 类型（5.x 的原生 Set、List 等）与读不懂的 String、`!f` 缺失或无效的 Hash（`0`），
     * 本格式的标量值（`2`），其它格式（低于或高于本格式）的标量或集合与带未知的必须理解的位的内容（见 `scalar_format`、`set_format`）。
     * 不存在、本格式的负缓存与 lease 占位可以写入。
     */
    private const val UNREADABLE_SET = """
local function unreadable_set(key)
  local kind = redis.call('TYPE', key)['ok']
  if kind == 'none' then return nil end
  if kind == 'string' then
    local f, state = scalar_format(redis.call('GET', key))
    if f == $FORMAT and state ~= 'V' then return nil end
    return f
  end
  if kind ~= 'hash' then return 0 end
  local f = set_format(key)
  if f == $FORMAT then return nil end
  return f
end
"""

    /**
     * 加入集合成员，但 key 中是读不懂的内容时不修改、不广播：整体覆盖它就是删除集合缓存的唯一副本。ARGV：TTL、频道、帧、成员…
     * 返回 `{1}` 表示已写入，`{0, 格式版本}` 表示拒绝。
     */
    val ADD_MEMBERS_IF_READABLE = Script(
        PRELUDE + FORMAT_RULES + UNREADABLE_SET + """
local key = KEYS[1]
local unreadable = unreadable_set(key)
if unreadable then return {0, unreadable} end
-- 只剩不存在、负缓存、lease 与本格式的集合：前三者先清除再创建集合
if redis.call('TYPE', key)['ok'] ~= 'hash' then
  redis.call('DEL', key)
  redis.call('HSET', key, '!f', '$FORMAT')
end
for i = 4, #ARGV do redis.call('HSET', key, '+' .. ARGV[i], '') end
if tonumber(ARGV[1]) > 0 then redis.call('PEXPIRE', key, ARGV[1]) else redis.call('PERSIST', key) end
publish(ARGV[2], ARGV[3])
return {1}
"""
    )

    /**
     * 移除集合成员，移空则删除；key 中是读不懂的内容时不修改、不广播。ARGV 与返回值同 [ADD_MEMBERS_IF_READABLE]。
     * 只剩 `!f` 时即为移空（O(1)）：本版本写入的元数据字段只有 `!f`。
     */
    val REMOVE_MEMBERS_IF_READABLE = Script(
        PRELUDE + FORMAT_RULES + UNREADABLE_SET + """
local key = KEYS[1]
local unreadable = unreadable_set(key)
if unreadable then return {0, unreadable} end
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
return {1}
"""
    )

    /**
     * 条件读取的结果码：没有值（不存在、lease、负缓存）、摘要未变（附格式版本）、值（附格式版本与载荷）、读不懂的内容（附格式版本）。
     */
    const val COND_ABSENT = 0L
    const val COND_UNCHANGED = 1L
    const val COND_VALUE = 2L
    const val COND_UNREADABLE = 3L

    /**
     * 条件写入的结果码：冲突（附当前摘要，没有值时缺省）、已写入、读不懂的内容（附格式版本）。
     */
    const val PUT_CONFLICT = 0L
    const val PUT_WRITTEN = 1L
    const val PUT_UNREADABLE = 3L

    /**
     * 条件读写的检查（只有 [READ_IF_CHANGED] 与 [PUT_IF_MATCH] 引入）：返回 key 的状态与附加值，不修改 key。
     * - `'none'`：不存在、lease 占位、负缓存；
     * - `'value', 格式版本, 载荷`：本格式的标量值（载荷从 `payload_start` 起）；
     * - `'unreadable', 格式版本`：读不懂的 String、其它 Redis 类型、`!f` 缺失或无效的 Hash（`0`），本格式的集合（`2`），
     *   其它格式与带未知的必须理解的位的内容（见 `scalar_format`、`set_format`）。只看集合的元数据，不取成员。
     */
    private const val CONDITIONAL_STATE = """
local function conditional_state(key)
  local kind = redis.call('TYPE', key)['ok']
  if kind == 'none' then return 'none' end
  if kind == 'string' then
    local v = redis.call('GET', key)
    local f, state, flags, start = scalar_format(v)
    if f ~= $FORMAT then return 'unreadable', f end
    if state ~= 'V' then return 'none' end
    return 'value', f, string.sub(v, start)
  end
  if kind ~= 'hash' then return 'unreadable', 0 end
  return 'unreadable', set_format(key)
end
"""

    /**
     * 条件读取。ARGV：已知摘要（十六进制，空串为没有）。
     * 返回 `{0}` 没有值、`{1, 格式版本}` 摘要未变（不返回正文）、`{2, 格式版本, 载荷}`、`{3, 格式版本}` 读不懂的内容。
     * 摘要同 [RECLAIM]：标量为载荷的 SHA-1。
     */
    val READ_IF_CHANGED = Script(
        CORE + FORMAT_RULES + CONDITIONAL_STATE + """
local state, format, payload = conditional_state(KEYS[1])
if state == 'none' then return {0} end
if state == 'unreadable' then return {3, format} end
if ARGV[1] ~= '' and redis.sha1hex(payload) == ARGV[1] then return {1, format} end
return {2, format, payload}
"""
    )

    /**
     * 条件写入：当前内容符合期望时整体覆盖写入标量值（同 [PUT]）并广播，否则不修改、不广播。
     * ARGV：期望摘要（十六进制，空串为“没有值”）、形状（`V` + 标志位 + 段，没有版本段）、TTL、频道、帧、载荷。
     * 读不懂的内容（不是本协议写入的内容、本格式的集合、其它格式与带未知的必须理解的位的内容）总是拒绝，从不视为没有值。
     * 返回 `{1}` 已写入、`{0}` 冲突且当前没有值、`{0, 当前摘要}` 冲突、`{3, 格式版本}` 读不懂的内容。
     */
    val PUT_IF_MATCH = Script(
        PRELUDE + FORMAT_RULES + CONDITIONAL_STATE + """
local key = KEYS[1]
local state, format, payload = conditional_state(key)
if state == 'unreadable' then return {3, format} end
if state == 'none' then
  if ARGV[1] ~= '' then return {0} end
else
  local current = redis.sha1hex(payload)
  if current ~= ARGV[1] then return {0, current} end
end
write(key, ARGV[2], ARGV[3], 6)
publish(ARGV[4], ARGV[5])
return {1}
"""
    )

    /**
     * 按版本写入的结果码：版本不更新（附当前版本，8 字节）、已写入、读不懂的内容（附格式版本）。
     */
    const val VERSION_STALE = 0L
    const val VERSION_WRITTEN = 1L
    const val VERSION_UNREADABLE = 3L

    /**
     * 按版本写入的检查（只有 [PUT_IF_NEWER] 引入）：返回 key 的状态与附加值，不修改 key。分类与 [CONDITIONAL_STATE] 相同：
     * - `'none'`：不存在、lease 占位、没有版本段的值或负缓存（视为没有版本，总是写入）；
     * - `'versioned', 格式版本, 版本`：带版本段的值或负缓存（版本为 8 字节，从字节 36 起）；
     * - `'unreadable', 格式版本`：同 [CONDITIONAL_STATE]。
     */
    private const val VERSIONED_STATE = """
local function versioned_state(key)
  local kind = redis.call('TYPE', key)['ok']
  if kind == 'none' then return 'none' end
  if kind == 'string' then
    local v = redis.call('GET', key)
    local f, state, flags = scalar_format(v)
    if f ~= $FORMAT then return 'unreadable', f end
    if state ~= 'L' and bit.band(flags, $FLAG_VERSION) ~= 0 then return 'versioned', f, string.sub(v, 37, 44) end
    return 'none'
  end
  if kind ~= 'hash' then return 'unreadable', 0 end
  return 'unreadable', set_format(key)
end
-- a > b：8 字节大端有符号整数。Lua 的数是 double，按高 32 位（有符号）与低 32 位（无符号）分别比较才精确
local function newer(a, b)
  local ah, bh = struct.unpack('>i4', a), struct.unpack('>i4', b)
  if ah ~= bh then return ah > bh end
  return struct.unpack('>I4', a, 5) > struct.unpack('>I4', b, 5)
end
"""

    /**
     * 按版本写入（6.8）：给出的版本大于 key 中的版本时整体覆盖写入（同 [PUT]，销毁令牌、重设到期时间）并广播，否则不修改、不广播。
     * ARGV：形状（`V` / `M` + 标志位 + 段，必须带版本段且不是 `NO_VERSION`，K15）、TTL、频道、帧、载荷（`M` 没有）。
     * 读不懂的内容（不是本协议写入的内容、本格式的集合、其它格式与带未知的必须理解的位的内容）总是拒绝，从不视为没有版本。
     * 返回 `{1}` 已写入、`{0, 当前版本}` 版本不更新、`{3, 格式版本}` 读不懂的内容；形状不合规时为错误回复。
     */
    val PUT_IF_NEWER = Script(
        PRELUDE + FORMAT_RULES + VERSIONED_STATE + """
local key, kind = KEYS[1], ARGV[1]
check_shape(kind)
if bit.band(string.byte(kind, 2), $FLAG_VERSION) == 0 then error('CoCache: putIfNewer needs a version segment') end
local state, format, current = versioned_state(key)
if state == 'unreadable' then return {3, format} end
if state == 'versioned' and not newer(string.sub(kind, 3, 10), current) then return {0, current} end
write(key, kind, ARGV[2], 5)
publish(ARGV[3], ARGV[4])
return {1}
"""
    )

    /**
     * 探测脚本：以 `KEYS[1]` 执行，向频道 `ARGV[1]` 发布 `ARGV[2]`，与写入脚本的广播相同。两处使用：
     * - 原子广播探测：向一个与 key 不在同一 slot 的频道发布。把 PUBLISH 当作 SPUBLISH 执行的环境会报跨 slot 错误。
     * - 按存储探测实际频道（RFC 0005 §4.7，K8）：以该存储的 key 前缀为 `KEYS[1]`，向它的频道发布一个零 key 的帧。
     */
    val PROBE = Script(
        """
redis.call('PUBLISH', ARGV[1], ARGV[2])
return 1
"""
    )

    private val SHAPE_VALUE = byteArrayOf('V'.code.toByte(), 0)
    private val SHAPE_MISSING = byteArrayOf('M'.code.toByte(), 0)

    /**
     * 写入标识的扩展区（D2）：总长 26，TLV 类型 1、长度 24、[Token] 的 24 字节，共 28 字节。
     */
    private const val WRITER_AREA_SIZE = EXTENSION_LENGTH_SIZE + 2 + Token.SIZE

    /**
     * 标量的写入形状（[PRELUDE]）：状态、标志位，然后按标志位的顺序是版本段（[version] 不是 [Versioned.NO_VERSION] 时）
     * 与扩展区（[writer] 非 `null` 时，只有写入标识一个 TLV）。没有段时返回共享的常量，派生缓存的写入不分配。
     * 摘要段（bit 1）在 M4c 之前从不写入。
     */
    fun scalarShape(state: Byte, version: Long, writer: Token?): ByteArray {
        val versioned = version != Versioned.NO_VERSION
        if (!versioned && writer == null) {
            return if (state == SHAPE_VALUE[0]) SHAPE_VALUE else SHAPE_MISSING
        }
        var flags = 0
        var size = 2
        if (versioned) {
            flags = flags or FLAG_VERSION
            size += VERSION_SIZE
        }
        if (writer != null) {
            flags = flags or FLAG_EXTENSION
            size += WRITER_AREA_SIZE
        }
        val shape = ByteBuffer.allocate(size).put(state).put(flags.toByte())
        if (versioned) {
            shape.putLong(version)
        }
        if (writer != null) {
            shape.putShort((WRITER_AREA_SIZE - EXTENSION_LENGTH_SIZE).toShort())
                .put(TLV_WRITER)
                .put(Token.SIZE.toByte())
                .put(writer.toBytes())
        }
        return shape.array()
    }

    /**
     * 载荷的起点（0 起），与 Lua 的 `payload_start` 一致：核心头部之后按标志位依次跳过版本段、摘要段与扩展区（2 + 总长）。
     * 段超出 [raw] 的长度时返回 `-1`（头部损坏）。
     */
    fun payloadOffset(raw: ByteArray, flags: Int): Int {
        var offset = HEADER_SIZE
        if (flags and FLAG_VERSION != 0) {
            offset += VERSION_SIZE
        }
        if (flags and FLAG_DIGEST != 0) {
            offset += DIGEST_SIZE
        }
        if (flags and FLAG_EXTENSION != 0) {
            if (raw.size < offset + EXTENSION_LENGTH_SIZE) {
                return -1
            }
            offset += EXTENSION_LENGTH_SIZE + unsignedShort(raw, offset)
        }
        return if (offset > raw.size) -1 else offset
    }

    /**
     * 扩展区中的写入标识（TLV 类型 1）：[area] 是扩展区的起点（总长字段）。逐个读 TLV、跳过未知的类型，取第一个类型 1；
     * 长度不是 24、或 TLV 超出扩展区（读者可以忽略扩展区，不把它当作损坏）时返回 `null`。调用方已确认扩展区在值的长度之内。
     */
    fun writerOf(raw: ByteArray, area: Int): Token? {
        var index = area + EXTENSION_LENGTH_SIZE
        val end = index + unsignedShort(raw, area)
        while (index < end) {
            val type = raw[index++]
            if (index >= end) {
                return null
            }
            var length = raw[index++].toInt() and BYTE_MASK
            if (length >= TLV_SHORT_LENGTH_LIMIT) {
                if (index >= end) {
                    return null
                }
                length = ((length - TLV_SHORT_LENGTH_LIMIT) shl Byte.SIZE_BITS) or (raw[index++].toInt() and BYTE_MASK)
            }
            if (length > end - index) {
                return null
            }
            if (type == TLV_WRITER) {
                return if (length == Token.SIZE) Token.fromBytes(raw.copyOfRange(index, index + length)) else null
            }
            index += length
        }
        return null
    }

    /**
     * 扩展区的起点：核心头部之后跳过版本段与摘要段。
     */
    fun extensionOffset(flags: Int): Int {
        var offset = HEADER_SIZE
        if (flags and FLAG_VERSION != 0) {
            offset += VERSION_SIZE
        }
        if (flags and FLAG_DIGEST != 0) {
            offset += DIGEST_SIZE
        }
        return offset
    }

    private const val BYTE_MASK = 0xFF

    private fun unsignedShort(raw: ByteArray, offset: Int): Int =
        ((raw[offset].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or (raw[offset + 1].toInt() and BYTE_MASK)

    /**
     * 集合的格式版本（与 Lua 的 `set_format` 一致，K10）：[format] 为 `!f` 的值、[mustUnderstand] 为 `!m` 的值（字段不存在时为空串）。
     * `!f` 不是至多 9 位的十进制数字时为 [UNRECOGNIZED]；`!f` 为 2 而 `!m` 存在且不是 `"0"` 时为 [NEWER_FORMAT]。
     */
    fun setFormat(format: ByteArray, mustUnderstand: ByteArray): Int {
        if (!isFormatNumber(format)) {
            return UNRECOGNIZED
        }
        val version = String(format, Charsets.US_ASCII).toInt()
        if (version == FORMAT && hasMustUnderstandBits(mustUnderstand)) {
            return NEWER_FORMAT
        }
        return version
    }

    private const val MAX_FORMAT_DIGITS = 9
    private val NO_MUST_UNDERSTAND_BITS = byteArrayOf('0'.code.toByte())

    private fun isFormatNumber(field: ByteArray): Boolean =
        field.isNotEmpty() && field.size <= MAX_FORMAT_DIGITS && field.all { it in '0'.code.toByte()..'9'.code.toByte() }

    /**
     * `!m` 缺失（空串）或为 `"0"` 时没有必须理解的位。
     */
    private fun hasMustUnderstandBits(
        field: ByteArray
    ): Boolean = field.isNotEmpty() && !field.contentEquals(NO_MUST_UNDERSTAND_BITS)

    /**
     * 脚本及其 SHA-1（EVALSHA 用）。
     */
    class Script(source: String) {
        val source: ByteArray = source.trimIndent().toByteArray(Charsets.UTF_8)
        val sha: String = MessageDigest.getInstance("SHA-1").digest(this.source).joinToString("") { "%02x".format(it) }
    }
}
