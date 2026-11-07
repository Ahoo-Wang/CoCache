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

package me.ahoo.cocache.core.policy

import java.time.Duration

/**
 * 策略字段的 Java 门面：Kotlin 直接写 [PolicyField.TTL]，Java 中它是 `PolicyField.TTL.INSTANCE`，这里给每个字段一个静态常量。
 *
 * ```java
 * Locks locks = Locks.builder("my-lib")
 *     .lock(PolicyFields.UNKNOWN_READ)
 *     .constrain(PolicyFields.TTL, ttl -> ttl.finiteOr(Duration.ofHours(1)))
 *     .build();
 * ```
 *
 * 与 [PolicyField] 的子对象一一对应，是同一个实例。
 */
object PolicyFields {
    @JvmField
    val TTL: PolicyField<Ttl> = PolicyField.TTL

    @JvmField
    val TTL_JITTER: PolicyField<Duration> = PolicyField.TTL_JITTER

    @JvmField
    val ABSENT_TTL: PolicyField<Duration> = PolicyField.ABSENT_TTL

    @JvmField
    val LOCAL_MAX_AGE: PolicyField<Duration> = PolicyField.LOCAL_MAX_AGE

    @JvmField
    val DEGRADED_LOCAL_MAX_AGE: PolicyField<Duration> = PolicyField.DEGRADED_LOCAL_MAX_AGE

    @JvmField
    val TOMBSTONE_TTL: PolicyField<Ttl> = PolicyField.TOMBSTONE_TTL

    @JvmField
    val REFRESH_WINDOW: PolicyField<RefreshWindow> = PolicyField.REFRESH_WINDOW

    @JvmField
    val LEASE_TTL: PolicyField<Duration> = PolicyField.LEASE_TTL

    @JvmField
    val LEASE_WAIT: PolicyField<Duration> = PolicyField.LEASE_WAIT

    @JvmField
    val LOAD_MAX_CONCURRENT: PolicyField<Concurrency> = PolicyField.LOAD_MAX_CONCURRENT

    @JvmField
    val LOAD_ACQUIRE_TIMEOUT: PolicyField<Duration> = PolicyField.LOAD_ACQUIRE_TIMEOUT

    @JvmField
    val LOAD_TIMEOUT: PolicyField<Duration> = PolicyField.LOAD_TIMEOUT

    @JvmField
    val READ_TIMEOUT: PolicyField<Duration> = PolicyField.READ_TIMEOUT

    @JvmField
    val LOCAL_MAXIMUM_SIZE: PolicyField<Long> = PolicyField.LOCAL_MAXIMUM_SIZE

    @JvmField
    val INVALIDATE_FAILURE: PolicyField<InvalidateFailureMode> = PolicyField.INVALIDATE_FAILURE

    @JvmField
    val READ_FAILURE: PolicyField<ReadFailureMode> = PolicyField.READ_FAILURE

    @JvmField
    val UNKNOWN_READ: PolicyField<UnknownReadMode> = PolicyField.UNKNOWN_READ
}
