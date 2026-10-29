<script setup lang="ts">
import {inject, shallowRef, watch, type Ref} from "vue";
import {Platform} from "../platform/platform.js";
import {bossPanelKind} from '../utils/panelMount';

const platform = inject('$platform') as Platform;
const pageUrl = inject('$pageUrl') as Ref<string>;

// 获取平台对应组件
const renderComponent = shallowRef(null)
watch(() => bossPanelKind(pageUrl.value), async (kind, _previous, onCleanup) => {
    let cancelled = false;
    onCleanup(() => { cancelled = true; });
    // Preserve settings during job filters; change views only between jobs and chat.
    renderComponent.value = null;
    if (!kind) return;
    const component = await platform.getRenderComponent();
    if (!cancelled) renderComponent.value = component;
}, {immediate: true});

</script>

<template>
    <component :is="renderComponent"></component>
</template>

<style scoped>

</style>
