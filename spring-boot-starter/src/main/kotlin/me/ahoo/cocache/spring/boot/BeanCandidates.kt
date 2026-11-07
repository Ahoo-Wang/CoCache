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

package me.ahoo.cocache.spring.boot

import org.springframework.aop.framework.Advised
import org.springframework.aop.scope.ScopedProxyUtils
import org.springframework.aop.target.AbstractBeanFactoryBasedTargetSource
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryUtils
import org.springframework.beans.factory.HierarchicalBeanFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.AliasRegistry
import org.springframework.core.ResolvableType

/**
 * 按泛型匹配使用者提供的组件（loader、codec、`JoinKeyExtractor`），与按名称 `getBean` 看到的 bean 一致：
 *
 * - 包含祖先容器：子容器声明的同名 bean（不论类型）遮蔽父容器的，父容器的不算候选；子容器与父容器各有一个不同名的候选时仍是多个候选，
 *   与 Spring 按类型注入的歧义规则一致。“声明”包括别名（[declares]）：子容器的别名 `x` 指向父容器的 bean 时，按名称 `getBean("x")`
 *   得到的是别名的目标，父容器自己的 `x` 不可见，因此不算候选（#887；`BeanFactoryUtils.beanNamesForTypeIncludingAncestors` 只看
 *   `containsLocalBean`，没有这一条）。每一级给出的名称在这一级按名称解析到的 bean 匹配类型（Spring 按类型查找时跳过与本级别名同名的定义），
 *   更近的容器又都没有声明它，所以结果中的每个名称在本容器按名称 `getBean` / `isTypeMatch` 得到的都是这个候选。
 * - 作用域代理：完整泛型只能在目标的定义上匹配到（`scopedTarget.x`），直接取它会持有目标、绕过作用域。
 *   目标名换成代理名 `x`，前提是 bean `x` 确实是指向这个目标的代理（目标源为 [AbstractBeanFactoryBasedTargetSource]，目标名相同）；
 *   否则按原名使用（只是名称带有这个前缀的普通 bean）。换名之后去重：Spring AOT 生成的代理定义带有目标的泛型，代理与目标都会匹配到。
 *   代理是否指向目标按实例判断，不按定义：AOT 生成的代理定义不保留被装饰的目标定义。
 * - 两者组合：候选按所属的容器逐级收集，换名在所属的容器中进行（代理与目标定义在同一个容器），遮蔽按换名之后的公开名判断。
 *   父容器的作用域代理 `x` 被子容器同名的 bean 遮蔽时，它的目标 `scopedTarget.x` 随之不可见，不是另一个候选（#865）。
 */
internal object BeanCandidates {
    /**
     * 匹配 [type] 的 bean 名（作用域代理的目标换成代理名，去重，保持顺序）。
     */
    fun namesOf(
        beanFactory: ListableBeanFactory,
        type: ResolvableType
    ): List<String> = namesOf(beanFactory, type, eager = true)

    /**
     * 构建期（Spring AOT）的同一规则（D9）：遮蔽（含别名）与作用域代理换名都与 [namesOf] 相同，但不创建任何 bean、不初始化
     * `FactoryBean`。作用域代理按定义判断（`ScopedProxyUtils` 建立的代理定义带着目标名），构建期的定义就是原来的定义。
     * AOT 据此判断接口式缓存是否用默认的 Jackson 编解码，与运行时 [namesOf] 的选择一致。
     */
    fun definedNamesOf(beanFactory: ListableBeanFactory, type: ResolvableType): List<String> =
        namesOf(beanFactory, type, eager = false)

    private fun namesOf(beanFactory: ListableBeanFactory, type: ResolvableType, eager: Boolean): List<String> {
        val local = beanFactory.getBeanNamesForType(type, true, eager).map { publicNameOf(beanFactory, it, eager) }
        val hierarchical = beanFactory as? HierarchicalBeanFactory ?: return local.distinct()
        val parent = hierarchical.parentBeanFactory as? ListableBeanFactory ?: return local.distinct()
        // 父容器的候选已按它自己的公开名给出（其中祖先容器的已被它遮蔽）；本容器声明的同名 bean 或别名遮蔽它们
        val inherited = namesOf(parent, type, eager).filterNot { declares(hierarchical, it) }
        return (local + inherited).distinct()
    }

    /**
     * [level] 自己声明了名称 [name]：bean 定义、单例，或者别名（目标可以在祖先容器中）。按名称 `getBean` 在这一级解析这个名称，
     * 祖先容器中同名的 bean 被它遮蔽。
     *
     * 只看 `containsLocalBean` 不够：它先按本级的别名换名、再查本级的定义与单例，别名指向祖先容器的 bean 时为 `false`（#887）。
     * 别名只查本级的别名表（[AliasRegistry.isAlias] 不包含祖先容器）；`&` 前缀（取 `FactoryBean` 本身）不改变归属，先去掉。
     */
    fun declares(level: HierarchicalBeanFactory, name: String): Boolean {
        return level.containsLocalBean(name) || isAlias(level, name)
    }

    /**
     * [name] 是 [level] 本级的别名：按名称 `getBean` 在这一级换成别名的目标，同名的定义（允许覆盖定义时才可能同时存在）不可见。
     */
    fun isAlias(level: BeanFactory, name: String): Boolean {
        return level is AliasRegistry && level.isAlias(BeanFactoryUtils.transformedBeanName(name))
    }

    /**
     * bean [name] 是否匹配 [type]：按 bean 自身的类型，或者（bean 是由 bean 工厂提供目标的代理时）按其目标的类型。
     * 代理实例是 JDK 代理，丢失了泛型，按名称指定的作用域代理因此要按目标校验。
     */
    fun isTypeMatch(beanFactory: BeanFactory, name: String, type: ResolvableType): Boolean {
        if (beanFactory.isTypeMatch(name, type)) {
            return true
        }
        val targetSource = targetSourceOf(beanFactory.getBean(name)) ?: return false
        return targetSource.beanFactory.isTypeMatch(targetSource.targetBeanName, type)
    }

    /**
     * [owner] 中定义的 bean [name] 的公开名。代理按 [owner] 查找：作用域代理与它的目标定义在同一个容器。
     * [eager] 时按代理实例的目标源判断，否则按代理的定义判断（[proxiesByDefinition]）。
     */
    private fun publicNameOf(owner: ListableBeanFactory, name: String, eager: Boolean): String {
        if (!ScopedProxyUtils.isScopedTarget(name)) {
            return name
        }
        val proxyName = ScopedProxyUtils.getOriginalBeanName(name)
        val proxies = if (eager) {
            owner.containsBean(proxyName) && targetSourceOf(owner.getBean(proxyName))?.targetBeanName == name
        } else {
            proxiesByDefinition(owner, proxyName, name)
        }
        return if (proxies) proxyName else name
    }

    /**
     * [owner] 本级的定义 [proxyName] 是指向 [targetName] 的作用域代理：`ScopedProxyUtils.createScopedProxy` 建立的代理定义
     * 带着属性 `targetBeanName`。
     */
    private fun proxiesByDefinition(owner: ListableBeanFactory, proxyName: String, targetName: String): Boolean {
        if (owner !is ConfigurableListableBeanFactory || !owner.containsBeanDefinition(proxyName)) {
            return false
        }
        return owner.getBeanDefinition(proxyName).propertyValues.get(TARGET_BEAN_NAME) == targetName
    }

    /**
     * `ScopedProxyFactoryBean` 的目标名属性。
     */
    private const val TARGET_BEAN_NAME = "targetBeanName"

    private fun targetSourceOf(bean: Any): AbstractBeanFactoryBasedTargetSource? {
        return (bean as? Advised)?.targetSource as? AbstractBeanFactoryBasedTargetSource
    }
}
