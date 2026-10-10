// ==UserScript==
// @name         Trae 信息整合 · 采集伴侣（通道 B）
// @namespace    https://trae-info-integration.local
// @version      0.1.0
// @description  在你自己已登录的真实浏览器中，按细分类目采集知乎/小红书/抖音/Quora/中国大学MOOC 搜索结果并上报云端（云端无需账号、无需破解验证码）
// @match        https://www.zhihu.com/search*
// @match        https://www.xiaohongshu.com/search_result*
// @match        https://www.douyin.com/search*
// @match        https://www.quora.com/search*
// @match        https://www.icourse163.org/search*
// @grant        GM_xmlhttpRequest
// @grant        GM_setValue
// @grant        GM_getValue
// @connect      *
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';

    // ===== 配置：与云端 application.conf 的 companion.token 保持一致 =====
    const SERVER_URL = GM_getValue('trae_server', 'http://127.0.0.1:8765');
    const TOKEN = GM_getValue('trae_token', 'trae-companion');
    // ================================================================

    const PLATFORMS = {
        'www.zhihu.com': {
            id: 'zhihu', name: '知乎',
            item: 'div.SearchResult-Card, div.Card.List-item, div.List-item',
            pick: (el) => {
                const a = el.querySelector("h2 a, a[data-za-detail-view-element_name='Title'], a[href*='/question/']");
                if (!a || !a.href.includes('/question/')) return null;
                const title = (a.textContent || '').trim();
                const excerpt = (el.querySelector('span.RichText, .CopyrightRichText-richText, p')?.textContent || '').trim();
                return { id: a.href, title, url: a.href, summary: excerpt.slice(0, 300), author: '' };
            }
        },
        'www.xiaohongshu.com': {
            id: 'xiaohongshu', name: '小红书',
            item: 'section.note-item, div[class*="note-item"]',
            pick: (el) => {
                const a = el.querySelector("a.cover, a[href*='/explore/'], a[href*='/search_result/']");
                if (!a) return null;
                const title = (el.querySelector('.title, .footer .title, [class*="title"]')?.textContent || '').trim();
                const author = (el.querySelector('.name, .author .name')?.textContent || '').trim();
                if (!title) return null;
                return { id: a.href, title, url: a.href, summary: '', author };
            }
        },
        'www.douyin.com': {
            id: 'douyin', name: '抖音',
            item: "li[data-e2e='search-result-item'], ul[data-e2e='scroll-list'] > li",
            pick: (el) => {
                const a = el.querySelector("a[href*='/video/']");
                if (!a) return null;
                const title = (el.querySelector("[data-e2e='search-result-title'], [class*='title']")?.textContent
                    || a.textContent || '').trim();
                const author = (el.querySelector("[data-e2e='search-result-author'] span, [class*='author']")?.textContent || '').trim();
                if (title.length < 2) return null;
                return { id: a.href, title: title.slice(0, 160), url: a.href, summary: '', author };
            }
        },
        'www.quora.com': {
            id: 'quora', name: 'Quora',
            item: "a[href*='/answer/']",
            pick: (el) => {
                const title = (el.textContent || '').trim();
                if (title.length < 8) return null;
                return { id: el.href, title: title.slice(0, 160), url: el.href, summary: '', author: '' };
            }
        },
        'www.icourse163.org': {
            id: 'icourse163', name: '中国大学MOOC',
            item: ".moco-course-card, .u-cls__item, div[class*='course-card']",
            pick: (el) => {
                const a = el.querySelector("a[href*='/course/']");
                if (!a) return null;
                const title = (el.querySelector('.u-cls-cname, h3, [class*="title"]')?.textContent
                    || a.textContent || '').trim();
                const school = (el.querySelector('.u-cls-pschool, [class*="school"]')?.textContent || '').trim();
                if (!title) return null;
                return { id: a.href, title, url: a.href, summary: school, author: school };
            }
        }
    };

    const platform = PLATFORMS[location.hostname];
    if (!platform) return;

    let categories = [];

    // ---------- 浮动面板 ----------
    const panel = document.createElement('div');
    panel.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:99999;width:260px;' +
        'background:#fff;color:#222;border:1px solid #5C6BC0;border-radius:10px;padding:12px;' +
        'font:12px/1.6 -apple-system,Segoe UI,Roboto,sans-serif;box-shadow:0 6px 24px rgba(92,107,192,.35)';
    panel.innerHTML = `
      <div style="font-weight:700;color:#5C6BC0;margin-bottom:6px">Trae 采集伴侣 · ${platform.name}</div>
      <select id="trae-cat" style="width:100%;margin-bottom:6px"></select>
      <button id="trae-collect" style="width:100%;padding:6px;background:#5C6BC0;color:#fff;border:0;border-radius:6px;cursor:pointer">采集本页并上报</button>
      <div id="trae-status" style="margin-top:6px;color:#666;word-break:break-all"></div>
      <div style="margin-top:6px;color:#999">服务器：<span id="trae-srv"></span></div>`;
    document.body.appendChild(panel);
    document.getElementById('trae-srv').textContent = SERVER_URL;

    function setStatus(msg) { document.getElementById('trae-status').textContent = msg; }

    // 拉取细分类目
    GM_xmlhttpRequest({
        method: 'GET', url: SERVER_URL + '/api/categories',
        headers: { 'X-Companion-Token': TOKEN },
        onload: (r) => {
            try {
                const data = JSON.parse(r.responseText);
                categories = data.categories || [];
                const sel = document.getElementById('trae-cat');
                sel.innerHTML = categories
                    .map(c => `<option value="${c.id}">${c.name}</option>`).join('');
            } catch (e) { setStatus('无法连接云端：' + e.message); }
        },
        onerror: () => setStatus('云端连接失败（检查地址/跨域放行）')
    });

    // 采集
    document.getElementById('trae-collect').addEventListener('click', () => {
        const catId = document.getElementById('trae-cat').value;
        if (!catId) { setStatus('请先等待类目加载'); return; }

        // 滚动两屏触发懒加载
        window.scrollTo(0, document.body.scrollHeight / 2);
        setTimeout(() => window.scrollTo(0, document.body.scrollHeight), 800);

        setTimeout(() => {
            const nodes = document.querySelectorAll(platform.item);
            const seen = new Set();
            const items = [];
            nodes.forEach(el => {
                try {
                    const it = platform.pick(el);
                    if (it && it.url && !seen.has(it.url)) { seen.add(it.url); items.push(it); }
                } catch (_) { /* 跳过异常节点 */ }
            });
            if (!items.length) { setStatus('本页未提取到条目（可能未登录/需验证码）'); return; }
            setStatus('提取 ' + items.length + ' 条，上报中…');

            GM_xmlhttpRequest({
                method: 'POST',
                url: SERVER_URL + '/api/companion/ingest',
                headers: { 'Content-Type': 'application/json', 'X-Companion-Token': TOKEN },
                data: JSON.stringify({ platformId: platform.id, categoryId: catId, items }),
                onload: (r) => {
                    try {
                        const resp = JSON.parse(r.responseText);
                        setStatus(r.status === 200 ? '✓ ' + (resp.message || '已入库') : '上报失败：' + r.status);
                    } catch (_) { setStatus('上报响应异常'); }
                },
                onerror: () => setStatus('上报失败（网络/令牌）')
            });
        }, 2200);
    });
})();
