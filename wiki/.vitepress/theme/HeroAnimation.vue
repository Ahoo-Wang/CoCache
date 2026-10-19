<!--
 Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
      http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

<!--
  首页动效：一个 6 秒的循环讲清 CoCache 的读写路径。
  0–60%：实例 A 读未命中 → Redis（L1）→ 数据源 → 带回值填充 A 的本地副本（L2）。
  65–90%：一次写入，Redis 原子地广播失效，B 与 C 丢弃本地副本。
  “减少动态效果”时暂停在静止画面。
-->
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const svg = ref<SVGSVGElement>()
let media: MediaQueryList | undefined

const applyMotionPreference = () => {
  if (!svg.value) return
  if (media?.matches) {
    svg.value.pauseAnimations()
    svg.value.setCurrentTime(0)
  } else {
    svg.value.unpauseAnimations()
  }
}

onMounted(() => {
  media = window.matchMedia('(prefers-reduced-motion: reduce)')
  media.addEventListener('change', applyMotionPreference)
  applyMotionPreference()
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', applyMotionPreference)
})
</script>

<template>
  <svg
    ref="svg"
    class="cocache-hero"
    viewBox="0 0 400 380"
    role="img"
    aria-label="Three instances keep local L2 copies in front of shared Redis (L1). A miss loads once through Redis from the source; a write in Redis broadcasts an invalidation that drops the other instances' local copies."
  >
    <defs>
      <linearGradient id="cc-brand" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#6d5dfc" />
        <stop offset="1" stop-color="#00d4aa" />
      </linearGradient>
      <filter id="cc-glow" x="-50%" y="-50%" width="200%" height="200%">
        <feGaussianBlur stdDeviation="3" result="blur" />
        <feMerge>
          <feMergeNode in="blur" />
          <feMergeNode in="SourceGraphic" />
        </feMerge>
      </filter>
      <path id="cc-a-l1" d="M80 118 L200 196" />
      <path id="cc-l1-src" d="M200 236 L200 300" />
      <path id="cc-src-l1" d="M200 300 L200 236" />
      <path id="cc-l1-a" d="M200 196 L80 118" />
      <path id="cc-l1-b" d="M200 196 L200 118" />
      <path id="cc-l1-c" d="M200 196 L320 118" />
    </defs>

    <!-- 连线 -->
    <g class="cc-links">
      <line x1="80" y1="118" x2="200" y2="196" />
      <line x1="200" y1="118" x2="200" y2="196" />
      <line x1="320" y1="118" x2="200" y2="196" />
      <line x1="200" y1="236" x2="200" y2="300" />
    </g>

    <!-- 失效广播：从 Redis 扩散的光环 -->
    <circle class="cc-ring" cx="200" cy="216" r="0">
      <animate attributeName="r" dur="6s" repeatCount="indefinite" keyTimes="0;0.65;0.88;1" values="0;0;150;150" />
      <animate attributeName="opacity" dur="6s" repeatCount="indefinite" keyTimes="0;0.65;0.66;0.88;1" values="0;0;0.7;0;0" />
    </circle>

    <!-- 三个实例，每个都有本地副本 L2 -->
    <g v-for="(x, i) in [80, 200, 320]" :key="x" class="cc-instance">
      <rect class="cc-node" :x="x - 52" y="40" width="104" height="78" rx="14" />
      <text class="cc-label" :x="x" y="62">Instance {{ ['A', 'B', 'C'][i] }}</text>
      <rect :class="['cc-l2', i === 0 ? 'cc-l2-load' : 'cc-l2-invalidate']" :x="x - 34" y="74" width="68" height="30" rx="8" />
      <text class="cc-l2-label" :x="x" y="94">L2</text>
    </g>

    <!-- Redis（L1） -->
    <rect class="cc-redis" x="128" y="196" width="144" height="40" rx="12" />
    <text class="cc-redis-label" x="200" y="221">Redis · L1</text>

    <!-- 数据源 -->
    <g class="cc-source">
      <ellipse cx="200" cy="304" rx="46" ry="10" />
      <path d="M154 304 L154 336 A46 10 0 0 0 246 336 L246 304" />
      <ellipse class="cc-source-top" cx="200" cy="304" rx="46" ry="10" />
      <text class="cc-label" x="200" y="331">Source</text>
    </g>

    <!-- 读未命中：A → L1 → 数据源 → L1 → A -->
    <circle class="cc-dot cc-read" r="5" filter="url(#cc-glow)">
      <animateMotion dur="6s" repeatCount="indefinite" keyTimes="0;0.15;1" keyPoints="0;1;1" calcMode="linear">
        <mpath href="#cc-a-l1" />
      </animateMotion>
      <animate attributeName="opacity" dur="6s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.15" values="1;0" />
    </circle>
    <circle class="cc-dot cc-read" r="5" filter="url(#cc-glow)">
      <animateMotion dur="6s" repeatCount="indefinite" keyTimes="0;0.15;0.27;1" keyPoints="0;0;1;1" calcMode="linear">
        <mpath href="#cc-l1-src" />
      </animateMotion>
      <animate attributeName="opacity" dur="6s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.15;0.27" values="0;1;0" />
    </circle>
    <circle class="cc-dot cc-value" r="5" filter="url(#cc-glow)">
      <animateMotion dur="6s" repeatCount="indefinite" keyTimes="0;0.3;0.42;1" keyPoints="0;0;1;1" calcMode="linear">
        <mpath href="#cc-src-l1" />
      </animateMotion>
      <animate attributeName="opacity" dur="6s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.3;0.42" values="0;1;0" />
    </circle>
    <circle class="cc-dot cc-value" r="5" filter="url(#cc-glow)">
      <animateMotion dur="6s" repeatCount="indefinite" keyTimes="0;0.42;0.58;1" keyPoints="0;0;1;1" calcMode="linear">
        <mpath href="#cc-l1-a" />
      </animateMotion>
      <animate attributeName="opacity" dur="6s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.42;0.58" values="0;1;0" />
    </circle>

    <!-- 失效广播：L1 → B、C -->
    <circle v-for="path in ['#cc-l1-b', '#cc-l1-c']" :key="path" class="cc-dot cc-invalidate" r="5" filter="url(#cc-glow)">
      <animateMotion dur="6s" repeatCount="indefinite" keyTimes="0;0.66;0.8;1" keyPoints="0;0;1;1" calcMode="linear">
        <mpath :href="path" />
      </animateMotion>
      <animate attributeName="opacity" dur="6s" repeatCount="indefinite" calcMode="discrete" keyTimes="0;0.66;0.8" values="0;1;0" />
    </circle>

    <!-- 图例 -->
    <g class="cc-legend">
      <circle class="cc-read" cx="70" cy="368" r="4" />
      <text x="80" y="372">load once</text>
      <circle class="cc-invalidate" cx="230" cy="368" r="4" />
      <text x="240" y="372">invalidate everywhere</text>
    </g>
  </svg>
</template>

<style scoped>
.cocache-hero {
  position: relative;
  width: 100%;
  max-width: 400px;
  height: auto;
  font-family: var(--vp-font-family-base);
}

.cc-links line {
  stroke: var(--vp-c-divider);
  stroke-width: 1.5;
  stroke-dasharray: 4 4;
}

.cc-node {
  fill: var(--vp-c-bg-soft);
  stroke: var(--vp-c-border);
  stroke-width: 1.5;
}

.cc-label {
  fill: var(--vp-c-text-2);
  font-size: 12px;
  text-anchor: middle;
}

.cc-l2 {
  fill: var(--vp-c-bg-alt);
  stroke: var(--vp-c-brand-1);
  stroke-width: 1.5;
  transition: fill 0.3s;
}

/* 与 SMIL 动画同为 6 秒循环：A 在回源完成后填充（60%–95%），B、C 持有旧副本，收到失效后闪动并清空（80%–90%） */
.cc-l2-load {
  animation: cc-fill 6s linear infinite;
}

.cc-l2-invalidate {
  animation: cc-drop 6s linear infinite;
}

@keyframes cc-fill {
  0%, 59.9% { fill: var(--vp-c-bg-alt); }
  60%, 94.9% { fill: var(--vp-c-brand-soft); }
  95%, 100% { fill: var(--vp-c-bg-alt); }
}

@keyframes cc-drop {
  0%, 79.9% { fill: var(--vp-c-brand-soft); stroke: var(--vp-c-brand-1); }
  80%, 89.9% { fill: rgba(0, 212, 170, 0.3); stroke: #00d4aa; }
  90%, 100% { fill: var(--vp-c-bg-alt); stroke: var(--vp-c-brand-1); }
}

@media (prefers-reduced-motion: reduce) {
  .cc-l2-load,
  .cc-l2-invalidate {
    animation: none;
  }
}

.cc-l2-label {
  fill: var(--vp-c-text-1);
  font-size: 12px;
  font-weight: 600;
  text-anchor: middle;
}

.cc-redis {
  fill: url(#cc-brand);
}

.cc-redis-label {
  fill: #fff;
  font-size: 14px;
  font-weight: 700;
  text-anchor: middle;
}

.cc-source path,
.cc-source ellipse {
  fill: var(--vp-c-bg-soft);
  stroke: var(--vp-c-border);
  stroke-width: 1.5;
}

.cc-source .cc-source-top {
  fill: var(--vp-c-bg-alt);
}

.cc-dot {
  opacity: 0;
}

.cc-read {
  fill: var(--vp-c-brand-1);
}

.cc-value {
  fill: #a99cff;
}

.cc-invalidate {
  fill: #00d4aa;
}

.cc-ring {
  fill: none;
  stroke: #00d4aa;
  stroke-width: 2;
  opacity: 0;
}

.cc-legend text {
  fill: var(--vp-c-text-2);
  font-size: 11px;
}
</style>
