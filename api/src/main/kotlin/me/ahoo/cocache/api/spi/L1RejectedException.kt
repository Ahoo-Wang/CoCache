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

package me.ahoo.cocache.api.spi

/**
 * 存储在写出之前就拒绝了命令：**确定未执行**（RFC 0005 §4.3，P8）。例如客户端断线时拒绝新命令
 * （Lettuce 的 `REJECT_COMMANDS`），或命令在发出之前同步失败。
 *
 * [L1Store] 及其能力面的实现只对这类失败抛出本异常（原始异常作为 [cause]）；其余失败都是“结果未知”，以其它异常报告。
 * 引擎据此：
 * - 不为它释放令牌：命令没有执行，没有令牌被取得或交回；
 * - 把写入失败报告为 `CacheWriteException(outcomeUnknown = false)`，没有原子广播的后端上也不补发广播。
 */
open class L1RejectedException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
