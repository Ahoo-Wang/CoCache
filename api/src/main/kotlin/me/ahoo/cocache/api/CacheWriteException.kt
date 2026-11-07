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

package me.ahoo.cocache.api

/**
 * 显式写入（`put`、条件写入、按版本写入、删除、元素增删，或配置为同步失败的失效）在 L1 上失败。
 *
 * [outcomeUnknown] 按客户端能观察到的事实区分两类失败（RFC 0005 §4.3、§4.6）：
 * - `false`：确定未执行。存储在写出之前就拒绝了命令（`me.ahoo.cocache.api.spi.L1RejectedException`，如断线时被拒绝），
 *   或后端拒绝了这次写入、没有修改 L1（如 key 中是读不懂的内容）。
 * - `true`：结果未知。命令可能已经生效（超时、断线时被排空、脚本的错误回复），也可能在之后的任何时刻生效，
 *   并排在其它连接上更晚发出的写入之后。让迟到的执行无害的只有顺序：按版本写入（重放同一版本是安全的）与条件写入（有 ABA）。
 *
 * 消息的末尾是原因的摘要：日志只打一行时也能看出原因（如读不懂的内容的格式号）。
 */
class CacheWriteException @JvmOverloads constructor(
    val cacheName: String,
    val operation: String,
    cause: Throwable,
    val outcomeUnknown: Boolean = true,
) : RuntimeException(
    "Cache[$cacheName] - $operation failed on the remote store" +
        (if (outcomeUnknown) " (outcome unknown): " else " (not executed): ") + summaryOf(cause),
    cause
)
