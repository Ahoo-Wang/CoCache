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

package me.ahoo.cocache.core

import me.ahoo.cocache.core.policy.PolicyDraft
import me.ahoo.cocache.core.policy.PolicyField
import java.util.function.Consumer
import java.util.function.UnaryOperator

/**
 * 不随配置改变的存储布局（RFC 0005 §4.8，U2）：锁定之后，配置层与定制器都不能改变它。
 * 例如锁定了 [KEY_PREFIX] 的撤销表不受 `cocache.key-prefix` 影响：存储 key 随应用的前缀改变时，已撤销的 token 会重新可用。
 */
enum class LayoutField {
    NAMESPACE,
    KEY_PREFIX,
    KEY_ENCODER,
    CODEC,
    KEY_FILTER,
    TTL_OF,

    /**
     * 数据来源：`loader`、`asyncLoader`、`externallySourced()`（带版本的数据源同理）。
     */
    SOURCE,
}

/**
 * 库对一个缓存的锁定与约束（RFC 0005 §4.8，U2、S12），随声明交给 [CoCacheFactory]：
 *
 * - [Builder.lock]：锁定策略字段。全局层（`defaults`、`overrides`）跳过它并标注“被 [by] 锁定”；`cocache.caches.[name]` 明确写出它时
 *   启动失败，除非该缓存的 `unlock` 列出了它（破窗开关，启动时告警）；定制器改变它时抛出。
 * - [Builder.lockLayout]：锁定存储布局（[LayoutField]），规则同上。
 * - [Builder.constrain]：在合并之后、校验之前对字段的生效值执行约束，值域包含哨兵（如 `Ttl.Forever`）：
 *   `constrain(PolicyField.TTL) { it.finiteOr(Duration.ofMinutes(1)) }` 保证条目一定过期。
 * - [Builder.pin]：锁定字段并把生效值固定为给出的值（锁定加约束一步完成），报告标注“pinned by [by]”；
 *   `cocache.caches.[name].unlock` 不能解除它（启动失败），否则报告会先显示被解锁、随后又被约束改回。
 * - [Builder.verify]：生效声明构建之后的检查（如 `require(spec is StoreSpec)`），失败时创建失败。
 *
 * @property by 锁定者（一般是库的名称），出现在启动日志、端点与错误信息中
 */
class Locks private constructor(builder: Builder) {
    val by: String = builder.by
    val fields: Set<PolicyField<*>> = LinkedHashSet(builder.fields)
    val layout: Set<LayoutField> = LinkedHashSet(builder.layout)

    /**
     * 被固定的字段与它们的值（[Builder.pin]）；这些字段同时在 [fields] 中。
     */
    val pinned: Map<PolicyField<*>, Any> = LinkedHashMap(builder.pins)
    private val constraints: List<Constraint<*>> = builder.constraints.toList()
    private val pins: List<Constraint<*>> = builder.pins.map { (field, value) -> pinOf(field, value) }
    private val checks: List<Consumer<Spec<*, *>>> = builder.checks.toList()

    fun isLocked(field: PolicyField<*>): Boolean = field in fields

    fun isLocked(field: LayoutField): Boolean = field in layout

    fun isPinned(field: PolicyField<*>): Boolean = field in pinned

    /**
     * 按声明的顺序执行约束，最后执行固定（[Builder.pin]，之后的约束改不了被固定的值），返回生效值被改变的字段。
     */
    fun constrain(draft: PolicyDraft): Set<PolicyField<*>> {
        val changed = linkedSetOf<PolicyField<*>>()
        (constraints + pins).forEach { constraint ->
            if (constraint.apply(draft)) {
                changed += constraint.field
            }
        }
        return changed
    }

    /**
     * 生效声明构建之后的检查（[Builder.verify]）。
     */
    fun verify(spec: Spec<*, *>) {
        checks.forEach { it.accept(spec) }
    }

    override fun toString(): String =
        "Locks(by=$by, fields=$fields, layout=$layout, pinned=$pinned, constraints=${constraints.map { it.field }})"

    internal class Constraint<T : Any>(val field: PolicyField<T>, val operator: UnaryOperator<T>) {
        fun apply(draft: PolicyDraft): Boolean {
            val current = draft[field]
            val constrained = requireNotNull(
                operator.apply(current)
            ) { "The constraint on ${field.key} returned null." }
            if (constrained == current) {
                return false
            }
            draft[field] = constrained
            return true
        }
    }

    class Builder(var by: String) {
        internal val fields = linkedSetOf<PolicyField<*>>()
        internal val layout = linkedSetOf<LayoutField>()
        internal val constraints = mutableListOf<Constraint<*>>()
        internal val pins = linkedMapOf<PolicyField<*>, Any>()
        internal val checks = mutableListOf<Consumer<Spec<*, *>>>()

        fun lock(vararg fields: PolicyField<*>) = apply { this.fields += fields }

        /**
         * 锁定 [field] 并把它的生效值固定为 [value]：等价于 `lock(field)` 加上返回 [value] 的约束，报告标注“pinned by [by]”。
         * 值域与 [constrain] 相同，含哨兵：`pin(PolicyField.UNKNOWN_READ, UnknownReadMode.ABSENT)`、`pin(PolicyField.TTL, Ttl.Forever)`。
         * 固定在全部约束之后执行；`cocache.caches.[name]` 写出它或在 `unlock` 中列出它时启动失败。同一字段再次固定时以后一次为准。
         */
        fun <T : Any> pin(field: PolicyField<T>, value: T) = apply {
            fields += field
            pins[field] = value
        }

        fun lockLayout(vararg fields: LayoutField) = apply { this.layout += fields }

        fun <T : Any> constrain(field: PolicyField<T>, constraint: UnaryOperator<T>) = apply {
            constraints += Constraint(field, constraint)
        }

        fun verify(check: Consumer<Spec<*, *>>) = apply { checks += check }

        fun build(): Locks = Locks(this)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun pinOf(field: PolicyField<*>, value: Any): Constraint<*> =
            Constraint(field as PolicyField<Any>) { value }

        /**
         * 不锁定任何东西。
         */
        @JvmField
        val NONE: Locks = Builder("none").build()

        @JvmStatic
        fun builder(by: String): Builder = Builder(by)
    }
}
