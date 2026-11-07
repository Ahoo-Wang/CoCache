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

package me.ahoo.cocache.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Java 调用方对 {@link Lookup} 与 {@link Lookup.Known} 的穷尽 {@code switch}：{@code Known} 是 {@code Lookup} 的密封子类型，
 * 三种情形仍然穷尽，只返回答案的读取（{@code lookupRemote}）只需匹配两种。
 */
class JavaLookupTest {
    private static String describe(Lookup<String> lookup) {
        return switch (lookup) {
            case Lookup.Present<String> present -> "present:" + present.getValue();
            case Lookup.Absent absent -> "absent";
            case Lookup.Unknown unknown -> "unknown";
        };
    }

    private static String describe(Lookup.Known<String> known) {
        return switch (known) {
            case Lookup.Present<String> present -> "present:" + present.getValue();
            case Lookup.Absent absent -> "absent";
        };
    }

    @Test
    void switchesAreExhaustive() {
        Lookup.Known<String> present = new Lookup.Present<>("v", Instant.EPOCH);
        Lookup.Known<String> absent = new Lookup.Absent(null);
        assertEquals("present:v", describe(present));
        assertEquals("absent", describe(absent));
        assertEquals("present:v", describe((Lookup<String>) present));
        Lookup<String> unknown = new Lookup.Unknown(new CacheReadException("c", "read", new IllegalStateException("down")));
        assertEquals("unknown", describe(unknown));
    }
}
