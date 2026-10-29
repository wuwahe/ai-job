const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const {parse, compileScript} = require('vue/compiler-sfc')
const ts = require('typescript')
const vue = require('vue')

// Exercise the real component scripts, isolating only browser and network dependencies.
function fixture(t, name, selfUseMode) {
    const userStore = vue.reactive({user: {selfUseMode}})
    const productStore = vue.reactive({showProduct: false, setShowProduct(value) {this.showProduct = value}})
    const calls = []
    const activity = {sseStarts: 0}
    const axios = {defaults: {baseURL: 'https://example.invalid/'}, post: async url => {
        calls.push(url)
        return {data: {code: 200, data: []}}
    }}
    const components = new Map()
    const modules = {
        vue: {...vue, inject: () => axios},
        '../../stores': {UserStore: () => userStore, ProductStore: () => productStore},
        '../../utils/tools': {isProdEnv: () => true, loginInterceptor: () => true, ElMessage() {}},
        '../../logging': {default: {}},
        '../icons': {},
        '../../utils/sse': {SSEClient: class {addOnMsgCallback() {} start() {activity.sseStarts++} close() {}}}
    }
    const {descriptor} = parse(fs.readFileSync(path.join(__dirname, '../src/components/ui/', name), 'utf8'))
    const compiled = compileScript(descriptor, {id: 'self-use-test'})
    const javascript = ts.transpileModule(compiled.content, {
        compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020}
    }).outputText
    const module = {exports: {}}
    vm.runInNewContext('(function(require, module, exports) {' + javascript + '\n})', {
        console, setTimeout() {}, setInterval() {}, clearInterval() {}
    })(name => {
        // Component identity is retained for navigation assertions.
        if (name.endsWith('.vue')) {
            if (!components.has(name)) components.set(name, {default: {name}})
            return components.get(name)
        }
        assert.ok(name in modules, 'Unexpected dependency: ' + name)
        return modules[name]
    }, module, module.exports)
    const scope = vue.effectScope()
    const component = scope.run(() => module.exports.default.setup({}, {expose() {}}))
    t.after(() => scope.stop())
    return {component, userStore, productStore, calls, axios, activity}
}

test('self-use purchase events do not query products or create payment orders', async t => {
    const {component, productStore, calls} = fixture(t, 'Product.vue', true)
    productStore.setShowProduct(true)
    await vue.nextTick()
    assert.equal(productStore.showProduct, false)
    await component.openProductDialog()
    await component.showOrderGroup()
    assert.deepEqual(calls, [])
})

test('an older backend without a mode field retains the paid purchase flow', async t => {
    const {component, calls} = fixture(t, 'Product.vue', undefined)
    await component.openProductDialog()
    assert.deepEqual(calls, ['/api/product/user/product/list', '/api/pay/generate/order/group'])
})

test('navigation reacts to server mode and leaves a hidden invitation view', async t => {
    const {component, userStore} = fixture(t, 'Panel.vue', false)
    assert.ok(component.visibleComponents.value.some(([key]) => key === '5'))
    component.handleSelect('5', [])
    assert.equal(component.showComponent.value.name, './InvitationExchange.vue')
    userStore.user.selfUseMode = true
    await vue.nextTick()
    assert.equal(component.visibleComponents.value.some(([key]) => key === '5'), false)
    assert.equal(component.showComponent.value.name, './AiJob.vue')
    userStore.user.selfUseMode = false
    await vue.nextTick()
    assert.ok(component.visibleComponents.value.some(([key]) => key === '5'))
})

test('late self-use mode closes an already open purchase dialog', async t => {
    const {userStore, productStore} = fixture(t, 'Product.vue', false)
    productStore.setShowProduct(true)
    await vue.nextTick()
    userStore.user.selfUseMode = true
    await vue.nextTick()
    assert.equal(productStore.showProduct, false)
})


test('an in-flight payment response cannot start polling after switching to self-use', async t => {
    const {component, userStore, axios, activity} = fixture(t, 'Product.vue', false)
    let complete
    axios.post = () => new Promise(resolve => {complete = resolve})
    const pending = component.showOrderGroup()
    userStore.user.selfUseMode = true
    complete({data: {code: 200, data: [{orderId: 1}]}})
    await pending
    assert.equal(activity.sseStarts, 0)
    assert.equal(component.productGroupLoading.value, false)
})
