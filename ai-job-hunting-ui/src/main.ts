import {createApp, ref} from 'vue';
import './style.css';
import 'element-plus/dist/index.css'
import PlatformFactory, {Platform} from "./platform/platform";
import App from './App.vue';
import logger, {Logger, LogLevel} from "./logging";
import './webSocket/hookMain'
import {createPinia} from 'pinia'
import axios from "./axios";
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {isProdEnv} from "./utils/tools";

import {ServerStore} from "./stores/server";
import {startPanelMount} from './utils/panelMount';

const app = createApp(App);
if (!isProdEnv()) {
    Logger.setGlobalLogLevel(LogLevel.Debug)
}
const pinia = createPinia()
app.use(pinia)

// 初始化服务器检查
const serverStore = ServerStore(pinia)
serverStore.checkConnection()

// 使用本地化语言包(主要是运行记录中时间筛选组件显示中文)
app.use(ElementPlus, {
    locale: zhCn,
})

// 创建平台
const platform: Platform = PlatformFactory.getInstance(location.href);
app.provide('$platform', platform)
app.provide('$axios', axios)
const pageUrl = ref(location.href);
app.provide('$pageUrl', pageUrl);

// 挂载
const rootApp = document.createElement('div');
rootApp.id = "ai-job"
rootApp.classList.add('page-job-content');

// Userscripts may execute after load; observe the DOM instead of replacing window.onload.
startPanelMount({
    root: rootApp,
    resolveTarget: () => platform.getMountEle(),
    mount: () => app.mount(rootApp),
    routeChanged: url => {
        platform.curUrl = url;
        pageUrl.value = url;
    },
});

