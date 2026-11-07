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

import me.ahoo.cocache.api.Store;

import java.io.IOException;
import java.util.Locale;

/**
 * 包可见的基接口：{@link Declarations.PublicTokens} 是 {@code public} 的，但 default 方法声明在这里（#827）。
 */
interface HiddenBase extends Store<String, String> {
    /**
     * 再次调用缓存。
     */
    default String upper(String key) {
        String value = get(key);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    /**
     * 调用另一个 default 方法（经代理回到处理器）。
     */
    default String twice(String key) {
        return upper(key) + upper(key);
    }

    /**
     * 基本类型的参数与返回值。
     */
    default int lengthOf(String key, int extra) {
        String value = get(key);
        return (value == null ? 0 : value.length()) + extra;
    }

    /**
     * {@code void}，并写入缓存。
     */
    default void putUpper(String key, String value) {
        put(key, value.toUpperCase(Locale.ROOT));
    }

    default String rethrow(RuntimeException error) {
        throw error;
    }

    default String rethrowChecked(IOException error) throws IOException {
        throw error;
    }
}
