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

package me.ahoo.cocache.core.proxy.hidden;

import me.ahoo.cocache.api.SuspendingStore;

import java.util.Locale;

/**
 * 挂起视图的包可见基接口（#827）：default 方法经视图的 {@code async()} 再次调用缓存。
 */
interface HiddenSuspendingBase extends SuspendingStore<String, String> {
    default String upper(String key) {
        String value = async().get(key).join();
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
