#!/usr/bin/env node

const readline = require('readline');

let playwright;
try {
  playwright = require('playwright');
} catch (error) {
  write({ id: null, error: `Playwright is not installed: ${error.message}` });
}

let browser;
let context;
let pages = new Map();
let activeTabId = 1;
let nextTabId = 1;
let userAgent;

async function ensureContext() {
  if (!playwright) {
    throw new Error('Playwright is not installed. Run npm install in desktop/browser-worker.');
  }
  if (!browser) {
    browser = await playwright.chromium.launch({ headless: true });
  }
  if (!context) {
    context = await browser.newContext(userAgent ? { userAgent } : undefined);
  }
  if (pages.size === 0) {
    await newTab({});
  }
}

async function getPage(tabId) {
  await ensureContext();
  const id = Number(tabId || activeTabId);
  const page = pages.get(id);
  if (!page) throw new Error(`browser tab not found: ${id}`);
  activeTabId = id;
  return { id, page };
}

async function newTab(params) {
  await ensureContextWithoutTab();
  const page = await context.newPage();
  const id = nextTabId++;
  pages.set(id, page);
  activeTabId = id;
  if (params && params.url) {
    await page.goto(normalizeUrl(params.url), { waitUntil: 'domcontentloaded', timeout: 45000 });
  }
  return snapshot(id);
}

async function ensureContextWithoutTab() {
  if (!playwright) {
    throw new Error('Playwright is not installed. Run npm install in desktop/browser-worker.');
  }
  if (!browser) {
    browser = await playwright.chromium.launch({ headless: true });
  }
  if (!context) {
    context = await browser.newContext(userAgent ? { userAgent } : undefined);
  }
}

async function snapshot(tabId) {
  await ensureContext();
  const id = Number(tabId || activeTabId);
  const page = pages.get(id) || [...pages.values()][0];
  const activeId = pages.get(id) ? id : [...pages.keys()][0];
  const title = page ? await safe(() => page.title(), '') : '';
  const currentUrl = page ? page.url() : '';
  const tabs = [];
  for (const [tid, tab] of pages.entries()) {
    tabs.push({
      tabId: tid,
      url: tab.url(),
      title: await safe(() => tab.title(), tab.url()),
      isActive: tid === activeId,
      isLoading: false,
      hasSslError: false,
      riskChallengeDetected: false,
    });
  }
  return {
    available: true,
    activeTabId: activeId,
    tabId: activeId,
    currentUrl,
    finalUrl: currentUrl,
    url: currentUrl,
    title,
    pageTitle: title,
    canGoBack: false,
    canGoForward: false,
    isLoading: false,
    hasSslError: false,
    riskChallengeDetected: false,
    tabs,
  };
}

async function navigate(params) {
  const { id, page } = await getPage(params.tabId);
  await page.goto(normalizeUrl(params.url), { waitUntil: 'domcontentloaded', timeout: params.timeoutMs || 45000 });
  return snapshot(id);
}

async function getText(params) {
  const { id, page } = await getPage(params.tabId);
  const text = await page.locator('body').innerText({ timeout: 5000 }).catch(() => '');
  return { ...(await snapshot(id)), text, content: text };
}

async function screenshot(params) {
  const { id, page } = await getPage(params.tabId);
  const bytes = await page.screenshot({ fullPage: params.fullPage !== false, type: 'png' });
  return { ...(await snapshot(id)), dataUrl: `data:image/png;base64,${bytes.toString('base64')}` };
}

async function findElements(params) {
  const { id, page } = await getPage(params.tabId);
  const selector = params.selector || 'a,button,input,textarea,select,[role=button]';
  const elements = await page.$$eval(selector, (nodes) => nodes.slice(0, 100).map((node, index) => {
    const rect = node.getBoundingClientRect();
    return {
      index,
      tag: node.tagName.toLowerCase(),
      text: (node.innerText || node.value || node.getAttribute('aria-label') || '').trim().slice(0, 200),
      href: node.href || null,
      selectorHint: node.id ? `#${node.id}` : null,
      x: rect.x,
      y: rect.y,
      width: rect.width,
      height: rect.height,
    };
  }));
  return { ...(await snapshot(id)), elements };
}

async function click(params) {
  const { id, page } = await getPage(params.tabId);
  if (params.selector) {
    await page.click(params.selector, { timeout: params.timeoutMs || 10000 });
  } else if (typeof params.x === 'number' && typeof params.y === 'number') {
    await page.mouse.click(params.x, params.y);
  } else {
    throw new Error('click requires selector or x/y');
  }
  return snapshot(id);
}

async function typeText(params) {
  const { id, page } = await getPage(params.tabId);
  const text = params.text || params.value || '';
  if (params.selector) {
    await page.fill(params.selector, text, { timeout: params.timeoutMs || 10000 });
  } else {
    await page.keyboard.type(text);
  }
  return snapshot(id);
}

async function hover(params) {
  const { id, page } = await getPage(params.tabId);
  if (params.selector) await page.hover(params.selector, { timeout: params.timeoutMs || 10000 });
  return snapshot(id);
}

async function scroll(params) {
  const { id, page } = await getPage(params.tabId);
  const deltaY = Number(params.deltaY || params.amount || 700);
  await page.mouse.wheel(0, deltaY);
  return snapshot(id);
}

async function executeJs(params) {
  const { id, page } = await getPage(params.tabId);
  const script = params.script || params.javascript || '';
  const value = await page.evaluate(script);
  return { ...(await snapshot(id)), value };
}

async function pressKey(params) {
  const { id, page } = await getPage(params.tabId);
  await page.keyboard.press(params.key || 'Enter');
  return snapshot(id);
}

async function waitForSelector(params) {
  const { id, page } = await getPage(params.tabId);
  await page.waitForSelector(params.selector, { timeout: params.timeoutMs || 10000 });
  return snapshot(id);
}

async function goBack(params) {
  const { id, page } = await getPage(params.tabId);
  await page.goBack({ waitUntil: 'domcontentloaded', timeout: params.timeoutMs || 30000 }).catch(() => null);
  return snapshot(id);
}

async function goForward(params) {
  const { id, page } = await getPage(params.tabId);
  await page.goForward({ waitUntil: 'domcontentloaded', timeout: params.timeoutMs || 30000 }).catch(() => null);
  return snapshot(id);
}

async function selectTab(params) {
  await getPage(params.tabId);
  return snapshot(activeTabId);
}

async function closeTab(params) {
  const id = Number(params.tabId || activeTabId);
  const page = pages.get(id);
  if (page) {
    await page.close().catch(() => null);
    pages.delete(id);
  }
  if (pages.size === 0) await newTab({});
  activeTabId = [...pages.keys()][0];
  return snapshot(activeTabId);
}

async function getCookies(params) {
  await ensureContext();
  return { ...(await snapshot(activeTabId)), cookies: await context.cookies(params.urls || undefined) };
}

async function setUserAgent(params) {
  userAgent = params.userAgent || params.user_agent || null;
  if (context) {
    await context.close().catch(() => null);
    context = null;
    pages = new Map();
    activeTabId = 1;
    nextTabId = 1;
    await ensureContext();
  }
  return snapshot(activeTabId);
}

async function dispatch(method, params) {
  switch (method) {
    case 'navigate': return navigate(params);
    case 'getText': return getText(params);
    case 'screenshot': return screenshot(params);
    case 'findElements': return findElements(params);
    case 'click': return click(params);
    case 'type': return typeText(params);
    case 'hover': return hover(params);
    case 'scroll': return scroll(params);
    case 'getPageInfo': return snapshot(params.tabId);
    case 'getBackbone': return findElements({ ...params, selector: params.selector || 'main,article,nav,header,footer,h1,h2,h3,a,button,input' });
    case 'executeJs': return executeJs(params);
    case 'pressKey': return pressKey(params);
    case 'waitForSelector': return waitForSelector(params);
    case 'goBack': return goBack(params);
    case 'goForward': return goForward(params);
    case 'newTab': return newTab(params);
    case 'selectTab': return selectTab(params);
    case 'closeTab': return closeTab(params);
    case 'getCookies': return getCookies(params);
    case 'setUserAgent': return setUserAgent(params);
    default: throw new Error(`unsupported browser worker method: ${method}`);
  }
}

function normalizeUrl(raw) {
  const value = String(raw || '').trim();
  if (!value) return 'about:blank';
  if (/^(https?|file):\/\//i.test(value) || value === 'about:blank') return value;
  return `https://${value}`;
}

async function safe(fn, fallback) {
  try {
    return await fn();
  } catch (_) {
    return fallback;
  }
}

function write(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', async (line) => {
  let request;
  try {
    request = JSON.parse(line);
    const result = await dispatch(request.method, request.params || {});
    write({ id: request.id, result });
  } catch (error) {
    write({ id: request && request.id, error: error && error.message ? error.message : String(error) });
  }
});

process.on('SIGTERM', async () => {
  if (browser) await browser.close().catch(() => null);
  process.exit(0);
});
