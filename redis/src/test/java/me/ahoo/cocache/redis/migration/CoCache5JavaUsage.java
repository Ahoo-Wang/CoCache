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

package me.ahoo.cocache.redis.migration;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisURI;
import me.ahoo.cocache.core.CacheSpec;
import me.ahoo.cocache.core.SetCacheSpec;
import me.ahoo.cocache.core.codec.StringCodec;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 从 Java 调用迁移 API（静态工厂、默认参数重载、{@code Function} 解码器），保证 Java 调用方能编译并使用。
 * 断言在 Kotlin 测试中完成。
 */
final class CoCache5JavaUsage {
    private CoCache5JavaUsage() {
    }

    /**
     * 五个迁移：字符串（自定义解码）、原生 Set 到标量、Hash、按 spec 的标量与按 spec 的集合（显式 key）。
     */
    static List<CoCache5Migration> migrations(String run, String indexKey) {
        CacheSpec<String, String> upper = CacheSpec.<String, String>builder("upper-" + run).codec(StringCodec.INSTANCE).build();
        SetCacheSpec<String, String> index = SetCacheSpec.<String, String>builder("index-" + run).elementCodec(StringCodec.INSTANCE).build();
        return List.of(
            CoCache5Migration.scalar(upper, CoCache5Format.string(text -> text.toUpperCase(Locale.ROOT))).build(),
            CoCache5Migration.scalar(
                "joined-" + run,
                StringCodec.INSTANCE,
                CoCache5Format.<String>set(members -> String.join(",", new TreeSet<>(members)))
            ).keyPrefix("java:").namespace("joined-" + run).sourcePrefix("cocache:joined-" + run + ":").build(),
            CoCache5Migration.scalar(
                "pairs-" + run,
                StringCodec.INSTANCE,
                CoCache5Format.<String>hash(fields -> fields.get("a") + "=" + fields.get("b"))
            ).build(),
            CoCache5Migration.set(index).sourceKey(indexKey, "").build(),
            CoCache5Migration.set("tags-" + run, StringCodec.INSTANCE).keyMapper(suffix -> suffix.toLowerCase(Locale.ROOT)).build()
        );
    }

    static CoCache5MigrationOptions defaultOptions() {
        return new CoCache5MigrationOptions();
    }

    static CoCache5MigrationOptions backupOptions(String backupPrefix) {
        return new CoCache5MigrationOptions(backupPrefix);
    }

    static CoCache5Migrator of(AbstractRedisClient client) {
        return CoCache5Migrator.of(client);
    }

    static CoCache5Migrator standalone(RedisURI uri) {
        return CoCache5Migrator.standalone(uri);
    }

    static CoCache5Migrator cluster(List<RedisURI> uris) {
        return CoCache5Migrator.cluster(uris);
    }
}
