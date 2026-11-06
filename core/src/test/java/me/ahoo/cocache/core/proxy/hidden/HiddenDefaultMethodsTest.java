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
import me.ahoo.cocache.core.CacheRuntime;
import me.ahoo.cocache.core.CoCacheFactory;
import me.ahoo.cocache.core.StoreSpec;
import me.ahoo.cocache.core.codec.StringCodec;
import me.ahoo.cocache.core.join.SimpleJoinView;
import me.ahoo.cocache.core.memory.InMemoryBackend;
import me.ahoo.cocache.core.proxy.JoinCacheProxies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 包可见接口中的 default 方法（#827）：代理的处理器在 {@code me.ahoo.cocache.core.proxy}，声明 default 方法的接口在这个包中、
 * 不是 {@code public}。{@code InvocationHandler.invokeDefault} 按处理器检查可访问性，会以 {@code IllegalAccessException} 失败；
 * 代理改经声明接口的私有查找执行。
 */
class HiddenDefaultMethodsTest {
    private final CacheRuntime runtime = CacheRuntime.builder().backend(new InMemoryBackend()).build();
    private final CoCacheFactory factory = new CoCacheFactory(runtime);

    @AfterEach
    void close() {
        runtime.close();
    }

    private static StoreSpec<String, String> spec(String name) {
        return StoreSpec.<String, String>builder(name).codec(StringCodec.INSTANCE).build();
    }

    @Test
    void aPackagePrivateDeclaredInterface() {
        HiddenTokens tokens = factory.create(HiddenTokens.class, spec("hidden"));
        tokens.put("a", "value");
        assertEquals("value", tokens.get("a"));
        assertEquals("VALUE", tokens.upper("a"));
        assertNull(tokens.upper("missing"));
    }

    @Test
    void aPublicInterfaceInheritingFromAPackagePrivateBase() {
        Declarations.PublicTokens tokens = factory.create(Declarations.PublicTokens.class, spec("tokens"));
        tokens.put("a", "value");
        assertEquals("value", tokens.get("a"));
        assertEquals("VALUE", tokens.upper("a"));
        assertEquals("VALUEVALUE", tokens.twice("a"));
        assertEquals(7, tokens.lengthOf("a", 2));
        tokens.putUpper("b", "other");
        assertEquals("OTHER", tokens.get("b"));
    }

    @Test
    void aPublicDeclaringInterface() {
        Declarations.PublicControl tokens = factory.create(Declarations.PublicControl.class, spec("control"));
        tokens.put("a", "value");
        assertEquals("VALUE", tokens.upper("a"));
    }

    /**
     * default 方法自己的异常原样到达调用方：运行时异常与声明的受检异常都是同一个实例。
     */
    @Test
    void exceptionsArriveUnwrapped() {
        Declarations.PublicTokens tokens = factory.create(Declarations.PublicTokens.class, spec("tokens"));
        IllegalStateException failure = new IllegalStateException("boom");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> tokens.rethrow(failure)));
        IOException checked = new IOException("io");
        assertSame(checked, assertThrows(IOException.class, () -> tokens.rethrowChecked(checked)));
    }

    @Test
    void asyncAndSuspendingViews() {
        Declarations.PublicAsyncTokens async = factory.create(Declarations.PublicAsyncTokens.class, spec("async"));
        async.put("a", "value").join();
        assertEquals("VALUE", async.upper("a").join());

        Declarations.PublicSuspendingTokens suspending =
            factory.create(Declarations.PublicSuspendingTokens.class, spec("suspending"));
        suspending.async().put("a", "value").join();
        assertEquals("VALUE", suspending.upper("a"));
    }

    @Test
    void joinViews() {
        Store<String, String> first = factory.create(spec("first"));
        Store<String, String> second = factory.create(spec("second"));
        first.put("a", "k");
        second.put("k", "v");
        Declarations.PublicJoin view = JoinCacheProxies.create(
            Declarations.PublicJoin.class,
            new SimpleJoinView<>("view", first, second, value -> value)
        );
        assertEquals("v", view.secondOf("a"));
        assertNull(view.secondOf("missing"));
    }
}
