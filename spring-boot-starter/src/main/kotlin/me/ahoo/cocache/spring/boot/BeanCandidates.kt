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
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.ResolvableType

/**
 * 按泛型匹配使用者提供的组件（loader、codec、`JoinKeyExtractor`），与按名称 `getBean` 看到的 bean 一致：
 *
 * - 包含祖先容器：子容器的同名 bean（不论类型）遮蔽父容器的，父容器的不算候选（`BeanFactoryUtils.beanNamesForTypeIncludingAncestors`）；
 *   子容器与父容器各有一个不同名的候选时仍是多个候选，与 Spring 按类型注入的歧义规则一致。
 * - 作用域代理：完整泛型只能在目标的定义上匹配到（`scopedTarget.x`），直接取它会持有目标、绕过作用域。
 *   目标名换成代理名 `x`，前提是 bean `x` 确实是指向这个目标的代理（目标源为 [AbstractBeanFactoryBasedTargetSource]，目标名相同）；
 *   否则按原名使用（只是名称带有这个前缀的普通 bean）。换名之后去重：Spring AOT 生成的代理定义带有目标的泛型，代理与目标都会匹配到。
 *   代理是否指向目标按实例判断，不按定义：AOT 生成的代理定义不保留被装饰的目标定义。
 */
internal object BeanCandidates {
    /**
     * 匹配 [type] 的 bean 名（作用域代理的目标换成代理名，去重，保持顺序）。
     */
    fun namesOf(beanFactory: ListableBeanFactory, type: ResolvableType): List<String> {
        return BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, type)
            .map { publicNameOf(beanFactory, it) }
            .distinct()
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

    private fun publicNameOf(beanFactory: BeanFactory, name: String): String {
        if (!ScopedProxyUtils.isScopedTarget(name)) {
            return name
        }
        val proxyName = ScopedProxyUtils.getOriginalBeanName(name)
        val proxies = beanFactory.containsBean(proxyName) &&
            targetSourceOf(beanFactory.getBean(proxyName))?.targetBeanName == name
        return if (proxies) proxyName else name
    }

    private fun targetSourceOf(bean: Any): AbstractBeanFactoryBasedTargetSource? {
        return (bean as? Advised)?.targetSource as? AbstractBeanFactoryBasedTargetSource
    }
}
