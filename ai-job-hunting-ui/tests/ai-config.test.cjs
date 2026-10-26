const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const {parse, compileScript} = require('vue/compiler-sfc')
const ts = require('typescript')
const vue = require('vue')

// Compile the real component with existing tooling; replace only its network and browser dependencies.
const source = fs.readFileSync(path.join(__dirname, '../src/components/ui/AiConfig.vue'), 'utf8')
const {descriptor} = parse(source)
const compiled = compileScript(descriptor, {id: 'ai-config-test'})
const javascript = ts.transpileModule(compiled.content, {
    compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020}
}).outputText

function fixture(t, current) {
    const notifications = []
    const mounted = []
    const mirror = new Map()
    const axios = {
        get: async url => ({data: {code: 200, data: url.endsWith('/all/provider')
            ? [{code: 1, defaultBaseUrl: 'https://api.deepseek.com/v1'},
                {code: 7, defaultBaseUrl: 'https://api.anthropic.com/v1'}] : current}}),
        post: async () => ({data: {code: 200, data: 'test answer'}})
    }
    const modules = {
        vue: {...vue, onMounted: callback => mounted.push(callback)},
        '../../axios': {default: axios},
        '../../utils/tools': {ElMessage: value => notifications.push(value)},
        'element-plus': {ElNotification: value => notifications.push(value)},
        '../../platform/utils': {
            Tools: {window: {_PAGE: {uid: 'test-user'}}},
            TampermonkeyApi: {
                GmSetValue: (key, value) => mirror.set(key, value),
                GmGetValue: (key, fallback) => mirror.get(key) ?? fallback
            }
        },
        '../../stores/server': {ServerStore: () => ({
            getMirrorKey: key => key, getGlobalMirrorKey: key => 'global-' + key
        })}
    }
    const module = {exports: {}}
    vm.runInNewContext('(function(require, module, exports) {' + javascript + '\n})', {console})(name => {
        assert.ok(name in modules, 'Unexpected dependency: ' + name)
        return modules[name]
    }, module, module.exports)
    const scope = vue.effectScope()
    const component = scope.run(() => module.exports.default.setup({}, {expose() {}}))
    t.after(() => scope.stop())
    component.formRef.value = {validate: async callback => callback(true)}
    return {component, axios, notifications, mount: () => mounted[0]()}
}

function savedConfig() {
    return {
        userId: 42, provider: 1, apiFormat: 'ANTHROPIC', maxTokens: 4096,
        baseUrl: 'https://custom-gateway.example/v1', completionsPath: '/messages',
        apiKey: 'test-placeholder', modelName: 'arbitrary-model', timeout: 60,
        status: 1, testPassed: 1
    }
}

test('loading a saved protocol preserves custom endpoint and successful test state', async t => {
    const {component, mount} = fixture(t, savedConfig())
    await mount()
    assert.equal(component.form.value.baseUrl, 'https://custom-gateway.example/v1')
    assert.equal(component.form.value.apiFormat, 'ANTHROPIC')
    assert.equal(component.form.value.testPassed, 1)
})

test('legacy configurations default to OpenAI and provider URL only when missing', async t => {
    const config = savedConfig()
    delete config.apiFormat
    delete config.maxTokens
    config.baseUrl = ''
    const {component, mount} = fixture(t, config)
    await mount()
    assert.equal(component.form.value.apiFormat, 'OPENAI')
    assert.equal(component.form.value.maxTokens, 1024)
    assert.equal(component.form.value.baseUrl, 'https://api.deepseek.com/v1')
})

test('format changes preserve custom gateways and invalidate the tested configuration', async t => {
    const {component, mount} = fixture(t, savedConfig())
    await mount()
    component.form.value.apiFormat = 'OPENAI'
    component.handleFormatChange()
    assert.equal(component.form.value.baseUrl, 'https://custom-gateway.example/v1')
    assert.equal(component.form.value.completionsPath, '')
    assert.equal(component.form.value.testPassed, 0)
    component.handleProviderChange(7)
    assert.equal(component.form.value.apiFormat, 'ANTHROPIC')
    assert.equal(component.form.value.baseUrl, 'https://api.anthropic.com/v1')
})

test('a successful in-flight test cannot validate a different model selected meanwhile', async t => {
    const {component, axios, mount} = fixture(t, savedConfig())
    await mount()
    let complete
    axios.post = async () => new Promise(resolve => {complete = resolve})
    const pending = component.handleTest()
    component.form.value.modelName = 'changed-model'
    complete({data: {code: 200, data: 'old-model answer'}})
    await pending
    assert.equal(component.form.value.testPassed, 0)
})

test('token and timeout edits require a new test; a failed retest cannot restore old success', async t => {
    const {component, axios, mount} = fixture(t, savedConfig())
    await mount()
    component.form.value.maxTokens = 2048
    assert.equal(component.form.value.testPassed, 0)
    await component.handleTest()
    assert.equal(component.form.value.testPassed, 1)
    component.form.value.timeout = 30
    assert.equal(component.form.value.testPassed, 0)
    axios.post = async () => ({data: {code: 500, message: 'test failed'}})
    await component.handleTest()
    component.form.value.timeout = 60
    assert.equal(component.form.value.testPassed, 0)
})
