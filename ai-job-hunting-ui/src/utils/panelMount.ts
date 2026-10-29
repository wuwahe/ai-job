export interface PanelTarget {
    el: Element;
    p?: string;
}

export function bossPanelKind(url: string): 'jobs' | 'chat' | null {
    const {hostname, pathname} = new URL(url);
    if (hostname !== 'www.zhipin.com') return null;
    if (pathname === '/web/geek/chat' || pathname.startsWith('/web/geek/chat/')) return 'chat';
    if (/^\/web\/geek\/(jobs|job|job-recommend)(\/|$)/.test(pathname)
            || pathname.startsWith('/overseas/')) return 'jobs';
    return null;
}

export function findBossPanelTarget(doc: Document, url: string): PanelTarget | null {
    const kind = bossPanelKind(url);
    if (!kind) return null;
    const path = new URL(url).pathname;
    // Keep route-specific selectors exclusive: job-recommend also starts with "job".
    const [selector, position] = kind === 'chat' ? ['.chat-conversation', 'start']
        : path.startsWith('/web/geek/job-recommend') ? ['.recommend-search-inner', 'end']
        : path.startsWith('/web/geek/jobs') ? ['.job-recommend-result', 'start']
        : path.startsWith('/overseas/') ? ['.mod-header', 'start']
        : ['.page-job-inner', 'start'];
    const el = doc.querySelector(selector);
    return el ? {el, p: position} : null;
}

/** Mount once and move the same Vue root when the host SPA replaces its containers. */
export function startPanelMount(options: {
    root: HTMLElement;
    resolveTarget: () => PanelTarget | null;
    mount: () => void;
    routeChanged: (url: string) => void;
    doc?: Document;
    win?: Window;
}): () => void {
    const doc = options.doc ?? document;
    const win = options.win ?? window;
    const root = options.root;
    let mounted = false;
    let stopped = false;
    let currentUrl = '';
    let waitingSince = Date.now();
    let scheduled: number | undefined;

    const reconcile = () => {
        if (stopped) return;
        if (win.location.href !== currentUrl) {
            currentUrl = win.location.href;
            waitingSince = Date.now();
            options.routeChanged(currentUrl);
        }
        if (!bossPanelKind(currentUrl)) {
            // Preserve the app instance while keeping unrelated BOSS pages untouched.
            root.remove();
            return;
        }
        const existing = doc.getElementById(root.id);
        if (existing && existing !== root) return;
        let target = options.resolveTarget();
        const fallback = !target && Date.now() - waitingSince >= 5000;
        // A changed host layout must still leave settings accessible after a bounded wait.
        if (fallback && doc.body) target = {el: doc.body};
        if (!target || !target.el.isConnected) return;
        root.dataset.mountFallback = String(fallback);
        if (root.parentElement !== target.el) {
            if (target.p === 'end') target.el.appendChild(root);
            else target.el.insertBefore(root, target.el.firstElementChild);
        }
        if (!mounted) {
            mounted = true;
            options.mount();
        }
    };
    const schedule = () => {
        // Coalesce host and Vue DOM mutations instead of scanning once per changed node.
        if (stopped || scheduled !== undefined) return;
        scheduled = win.setTimeout(() => {
            scheduled = undefined;
            reconcile();
        }, 50);
    };
    const observer = new MutationObserver(schedule);
    observer.observe(doc, {childList: true, subtree: true});
    doc.addEventListener('DOMContentLoaded', schedule);
    win.addEventListener('popstate', schedule);
    win.addEventListener('hashchange', schedule);
    win.addEventListener('pageshow', schedule);
    // pushState may change the URL without a DOM event; avoid patching the site's history methods.
    const interval = win.setInterval(reconcile, 1000);
    reconcile();
    return () => {
        stopped = true;
        observer.disconnect();
        if (scheduled !== undefined) win.clearTimeout(scheduled);
        win.clearInterval(interval);
        doc.removeEventListener('DOMContentLoaded', schedule);
        win.removeEventListener('popstate', schedule);
        win.removeEventListener('hashchange', schedule);
        win.removeEventListener('pageshow', schedule);
    };
}
