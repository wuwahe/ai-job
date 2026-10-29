<template>
    <div class="container">
    <el-menu
            default-active="1"
            class="el-menu-demo"
            mode="horizontal"
            style="margin-left: 30px;"
            @select="handleSelect">
        <el-menu-item :index="key" v-for="[key,value] of visibleComponents" :key="key">{{ value.name }}</el-menu-item>
    </el-menu>
    <div class="show-component">
        <component :is="showComponent"></component>
    </div>
    </div>
</template>

<script lang="ts" setup>
import AiJob from "./AiJob.vue";
import Preference from "./Preference.vue";
import RunRecord from "./RunRecord.vue";
import {computed, shallowRef, watch} from "vue";
import {UserStore} from "../../stores";
import {isProdEnv} from "../../utils/tools";
import Test from "../test/Test.vue";
import ReadMe from "./UseDocument.vue";
import InvitationExchange from "./InvitationExchange.vue";
import AiConfig from "./AiConfig.vue";

interface Comp {
    component: any;
    name: string;
}

// 菜单切换显示的ui组件
const showComponent = shallowRef(AiJob) as any
const componentMap = new Map<string, Comp>();

// 注册菜单项及对应组件
componentMap.set('1', {component: AiJob, name: 'AI 助手'});
componentMap.set('2', {component: Preference, name: '偏好设置'});
componentMap.set('3', {component: RunRecord, name: '运行记录'});
componentMap.set('4', {component: AiConfig, name: 'AI 配置'});
componentMap.set('5', {component: InvitationExchange, name: '邀请兑换'});
componentMap.set('6', {component: ReadMe, name: '使用文档'});
if (!isProdEnv()) {
    componentMap.set('7', {component: Test, name: '调试测试'});
}

const userStore = UserStore();
// Billing-only navigation follows the current server's user response.
const visibleComponents = computed(() => [...componentMap.entries()]
    .filter(([key]) => !userStore.user.selfUseMode || key !== '5'));
watch(() => userStore.user.selfUseMode, enabled => {
    // Close a billing view if user data arrives after navigation.
    if (enabled && showComponent.value === InvitationExchange) showComponent.value = AiJob;
});

const handleSelect = (key: string, keyPath: string[]) => {
    showComponent.value = componentMap.get(key)?.component;
}

</script>

<style scoped>

.container {
    max-width: 1200px;
    margin: 0 auto;
}

.show-component {
    padding: 30px;
}
</style>
