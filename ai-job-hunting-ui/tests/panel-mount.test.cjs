const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const {parse, compileScript} = require('vue/compiler-sfc')
const compile = source => ts.transpileModule(source, {
    compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020}
}).outputText
const javascript = compile(fs.readFileSync(path.join(__dirname, '../src/utils/panelMount.ts'), 'utf8'))

// Model browser lifecycle events with a deterministic clock and no external services.
function fixture(t, loading = false) {
    let now = 0, nextTimer = 0, mounts = 0
    const timers = new Map(), selectors = new Map(), observers = [], routes = [], stops = []
    class Element {
        constructor(id = '') { this.id = id; this.children = []; this.parentElement = null; this.dataset = {} }
        get isConnected() { return this === html || !!this.parentElement?.isConnected }
        get firstElementChild() { return this.children[0] || null }
        appendChild(node) { node.remove(); node.parentElement = this; this.children.push(node) }
        insertBefore(node, before) {
            node.remove(); node.parentElement = this
            this.children.splice(before ? this.children.indexOf(before) : this.children.length, 0, node)
        }
        remove() {
            if (this.parentElement) {
                this.parentElement.children.splice(this.parentElement.children.indexOf(this), 1)
                this.parentElement = null
            }
        }
    }
    const html = new Element(), root = new Element('ai-job'), doc = new EventTarget(), win = new EventTarget()
    doc.readyState = loading ? 'loading' : 'complete'
    doc.body = null
    doc.querySelector = selector => selectors.get(selector)?.isConnected ? selectors.get(selector) : null
    doc.getElementById = id => {
        const walk = el => el.id === id ? el : el.children.map(walk).find(Boolean)
        return walk(html) || null
    }
    win.location = {href: 'https://www.zhipin.com/web/geek/jobs?ka=header-jobs'}
    win.onload = () => 'host handler'
    win.setTimeout = (fn, delay) => { timers.set(++nextTimer, {fn, time: now + delay}); return nextTimer }
    win.setInterval = (fn, delay) => { timers.set(++nextTimer, {fn, time: now + delay, interval: delay}); return nextTimer }
    win.clearTimeout = win.clearInterval = id => timers.delete(id)
    class Observer {
        constructor(callback) { this.callback = callback; observers.push(this) }
        observe() { this.connected = true }
        disconnect() { this.connected = false }
    }
    const module = {exports: {}}
    vm.runInNewContext('(function(module, exports) {' + javascript + '\n})', {
        URL, MutationObserver: Observer, Date: {now: () => now}
    })(module, module.exports)
    const api = module.exports
    const f = {
        doc, win, root, timers, api, routes,
        get mounts() { return mounts },
        body() { doc.body = new Element(); html.appendChild(doc.body) },
        target(selector = '.job-recommend-result') {
            const el = new Element(); doc.body.appendChild(el); selectors.set(selector, el); return el
        },
        start() {
            const stop = api.startPanelMount({root, doc, win,
                resolveTarget: () => api.findBossPanelTarget(doc, win.location.href),
                mount: () => { mounts++; root.formState = 'unsaved settings' }, routeChanged: url => routes.push(url)})
            stops.push(stop); return stop
        },
        mutate() { observers.filter(o => o.connected).forEach(o => o.callback([])) },
        advance(duration) {
            const until = now + duration
            for (;;) {
                const due = [...timers.entries()].filter(([, timer]) => timer.time <= until)
                    .sort((a, b) => a[1].time - b[1].time)[0]
                if (!due) break
                const [id, timer] = due; now = timer.time
                if (timer.interval) timer.time += timer.interval
                else timers.delete(id)
                timer.fn()
            }
            now = until
        }
    }
    if (!loading) f.body()
    t.after(() => stops.forEach(stop => stop()))
    return f
}

test('late injection mounts without waiting for or replacing window.onload', t => {
    const f = fixture(t), target = f.target(), load = f.win.onload
    f.start()
    assert.equal(f.mounts, 1)
    assert.equal(f.root.parentElement, target)
    assert.equal(f.win.onload, load)
})

test('a target arriving after the old 1.2 second deadline still mounts', t => {
    const f = fixture(t, true)
    f.start(); f.advance(2500)
    assert.equal(f.mounts, 0)
    f.body()
    const target = f.target()
    f.doc.dispatchEvent(new Event('DOMContentLoaded')); f.mutate(); f.advance(50)
    assert.equal(f.root.parentElement, target)
    assert.equal(f.mounts, 1)
})

test('SPA container replacement preserves Vue state and mounts only once', t => {
    const f = fixture(t), old = f.target()
    f.start(); old.remove()
    const replacement = f.target()
    f.mutate(); f.advance(50)
    assert.equal(f.root.parentElement, replacement)
    assert.equal(f.root.formState, 'unsaved settings')
    assert.equal(f.mounts, 1)
})

test('host removal of only the panel is repaired without remounting Vue', t => {
    const f = fixture(t), target = f.target()
    f.start(); f.root.remove(); f.mutate(); f.advance(50)
    assert.equal(f.root.parentElement, target)
    assert.equal(f.mounts, 1)
})

test('changed layouts get a fallback and move to the preferred container when it arrives', t => {
    const f = fixture(t)
    f.start(); f.advance(4999)
    assert.equal(f.mounts, 0)
    f.advance(1)
    assert.equal(f.root.parentElement, f.doc.body)
    assert.equal(f.root.dataset.mountFallback, 'true')
    const target = f.target()
    f.mutate(); f.advance(50)
    assert.equal(f.root.parentElement, target)
    assert.equal(f.root.dataset.mountFallback, 'false')
    assert.equal(f.mounts, 1)
})

test('URL-only navigation updates targets and hides unsupported routes', t => {
    const f = fixture(t), jobs = f.target(), chat = f.target('.chat-conversation')
    f.start()
    f.win.location.href = 'https://www.zhipin.com/web/geek/chat'; f.advance(1000)
    assert.equal(f.root.parentElement, chat)
    f.win.location.href = 'https://www.zhipin.com/web/geek/resume'
    f.win.dispatchEvent(new Event('popstate')); f.advance(6050)
    assert.equal(f.root.isConnected, false)
    f.win.location.href = 'https://www.zhipin.com/web/geek/jobs?query=java'
    f.win.dispatchEvent(new Event('pageshow')); f.advance(50)
    assert.equal(f.root.parentElement, jobs)
    assert.equal(f.mounts, 1)
    assert.equal(f.routes.length, 4)
})

test('recommendation routes are not overwritten by the generic job selector', t => {
    const f = fixture(t), recommended = f.target('.recommend-search-inner'), child = f.target('.child')
    recommended.appendChild(child); f.target('.page-job-inner')
    f.win.location.href = 'https://www.zhipin.com/web/geek/job-recommend'; f.start()
    assert.equal(f.root.parentElement, recommended)
    assert.equal(recommended.children.at(-1), f.root)
})

test('mutation bursts are coalesced and do not duplicate the panel', t => {
    const f = fixture(t), target = f.target()
    f.start()
    for (let i = 0; i < 100; i++) f.mutate()
    assert.equal(f.timers.size, 2)
    f.advance(50)
    assert.equal(target.children.filter(node => node.id === 'ai-job').length, 1)
    assert.equal(f.mounts, 1)
})

test('cleanup releases observers, timers and navigation handlers', t => {
    const f = fixture(t); f.target()
    const stop = f.start()
    f.mutate(); stop(); f.root.remove(); f.mutate()
    f.win.dispatchEvent(new Event('popstate')); f.advance(10000)
    assert.equal(f.timers.size, 0)
    assert.equal(f.root.isConnected, false)
})

test('route view ignores stale imports and preserves the view during job-filter changes', async t => {
    const {api} = fixture(t), pageUrl = vue.ref('https://www.zhipin.com/web/geek/jobs'), pending = []
    const platform = {getRenderComponent: () => new Promise(resolve => pending.push(resolve))}
    const source = fs.readFileSync(path.join(__dirname, '../src/components/ViewRouter.vue'), 'utf8')
    const {descriptor} = parse(source)
    const js = compile(compileScript(descriptor, {id: 'view-router-test'}).content)
    const modules = {vue: {...vue, inject: key => key === '$platform' ? platform : pageUrl}, '../utils/panelMount': api}
    const module = {exports: {}}
    vm.runInNewContext('(function(require, module, exports) {' + js + '\n})', {console})(name => {
        assert.ok(name in modules, 'Unexpected dependency: ' + name); return modules[name]
    }, module, module.exports)
    const scope = vue.effectScope(); t.after(() => scope.stop())
    const view = scope.run(() => module.exports.default.setup({}, {expose() {}}))
    pageUrl.value = 'https://www.zhipin.com/web/geek/chat'; await vue.nextTick()
    pending[1]('chat-panel'); await Promise.resolve()
    pending[0]('stale-jobs-panel'); await Promise.resolve()
    assert.equal(view.renderComponent.value, 'chat-panel')
    pageUrl.value = 'https://www.zhipin.com/web/geek/jobs'; await vue.nextTick()
    pending[2]('jobs-panel'); await Promise.resolve()
    pageUrl.value = 'https://www.zhipin.com/web/geek/jobs?query=java'; await vue.nextTick()
    assert.equal(pending.length, 3)
    assert.equal(view.renderComponent.value, 'jobs-panel')
})
