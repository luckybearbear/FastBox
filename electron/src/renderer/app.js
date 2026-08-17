/**
 * FastBox 渲染层逻辑
 * 搜索输入 → 调 Java 网关 /api/search → 渲染结果列表 → 点击执行动作
 * 历史联想：空输入时展示最近搜索
 * 收藏管理：结果项星标 → 收藏面板列表 → 点击执行/删除
 */
(() => {
  const $ = (id) => document.getElementById(id);
  const input = $('searchInput');
  const list = $('resultList');
  const emptyState = $('emptyState');
  const statusText = $('statusText');
  const toastEl = $('toast');
  const badge = $('gatewayBadge');
  const detailOverlay = $('detailOverlay');
  const detailTitle = $('detailTitle');
  const detailContent = $('detailContent');
  const argOverlay = $('argOverlay');
  const argTitle = $('argTitle');
  const argForm = $('argForm');
  const tabHistory = $('tabHistory');
  const tabFavorites = $('tabFavorites');

  let items = [];
  let activeIdx = -1;
  let gatewayReady = false;
  let toastTimer = null;
  let argItem = null;
  let currentView = 'search'; // 'search' | 'history' | 'favorites'
  let favoriteIds = new Set(); // 已收藏的 action+payload 组合，用于星标高亮

  const KIND_ICON = { tool: '\u5DE5', file: '\u6587', python: 'Py', js: 'JS', java: 'Ja', sql: 'SQL', plugin: '\u63D2', command: '\u547D', favorite: '\u2605' };
  const KIND_LABEL = { tool: '\u5DE5\u5177', file: '\u6587\u4EF6', python: '\u811A\u672C', js: 'JS', java: 'Java', sql: 'SQL', plugin: '\u63D2\u4EF6', command: '\u547D\u4EE4', favorite: '\u6536\u85CF' };

  /* ---- 网关状态 ---- */
  window.fastbox.on('status:gateway', (s) => {
    gatewayReady = s === 'ready';
    badge.textContent = gatewayReady ? '\u7F51\u5173\u5C31\u7EEA' : '\u7F51\u5173\u79BB\u7EBF';
    badge.className = `badge ${gatewayReady ? 'badge-ready' : 'badge-fail'}`;
  });
  window.fastbox.on('status:toast', (msg) => showToast(msg));
  window.fastbox.on('panel:shown', () => {
    input.focus();
    input.select();
    // 面板唤起时，空输入展示历史
    if (!input.value.trim()) {
      switchView('search');
      loadHistory();
    }
  });

  function showToast(msg) {
    toastEl.textContent = msg;
    toastEl.style.opacity = 1;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toastEl.style.opacity = 0), 3000);
  }

  /* ---- 视图切换 ---- */
  function switchView(view) {
    currentView = view;
    tabHistory.classList.toggle('active', view === 'history');
    tabFavorites.classList.toggle('active', view === 'favorites');
  }

  tabHistory.addEventListener('click', () => {
    if (currentView === 'history') {
      switchView('search');
      if (input.value.trim()) doSearch(input.value.trim());
      else loadHistory();
    } else {
      switchView('history');
      loadHistory();
    }
  });

  tabFavorites.addEventListener('click', () => {
    if (currentView === 'favorites') {
      switchView('search');
      if (input.value.trim()) doSearch(input.value.trim());
      else loadHistory();
    } else {
      switchView('favorites');
      loadFavorites();
    }
  });

  /* ---- 搜索 ---- */
  let debounceTimer = null;
  input.addEventListener('input', () => {
    clearTimeout(debounceTimer);
    const q = input.value.trim();
    debounceTimer = setTimeout(() => {
      if (q) {
        if (currentView !== 'search') switchView('search');
        doSearch(q);
      } else {
        loadHistory();
      }
    }, 150);
  });

  async function doSearch(q) {
    if (!q) {
      loadHistory();
      return;
    }
    statusText.textContent = '\u641C\u7D22\u4E2D\u2026';
    try {
      const { status, body } = await window.fastbox.call('GET', `/api/search?q=${encodeURIComponent(q)}`);
      if (status === 200 && body && Array.isArray(body.results)) {
        items = body.results;
        // 检查哪些结果已收藏
        await refreshFavoriteStatus();
        renderResults();
      } else {
        renderEmpty('\u7F51\u5173\u8FD4\u56DE\u5F02\u5E38', `HTTP ${status}`);
      }
    } catch (e) {
      gatewayReady = false;
      badge.textContent = '\u7F51\u5173\u79BB\u7EBF';
      badge.className = 'badge badge-fail';
      renderEmpty('\u65E0\u6CD5\u8FDE\u63A5\u7F51\u5173', e.message || '\u8BF7\u68C0\u67E5 Java \u7F51\u5173\u670D\u52A1');
      showToast('\u7F51\u5173\u8FDE\u63A5\u5931\u8D25');
    }
    statusText.textContent = items.length ? `${items.length} \u6761\u7ED3\u679C` : '\u5C31\u7EEA';
  }

  function renderEmpty(title, hint) {
    emptyState.style.display = 'flex';
    list.querySelectorAll('.result-item, .panel-header, .panel-item').forEach((n) => n.remove());
    emptyState.querySelector('.empty-title').textContent = title;
    emptyState.querySelector('.empty-hint').textContent = hint;
  }

  function renderResults() {
    emptyState.style.display = 'none';
    list.querySelectorAll('.result-item, .panel-header, .panel-item').forEach((n) => n.remove());
    activeIdx = items.length ? 0 : -1;

    items.forEach((item, i) => {
      const el = document.createElement('div');
      el.className = 'result-item' + (i === activeIdx ? ' active' : '');
      el.innerHTML = `
        <div class="type-icon type-${item.kind || 'tool'}">${KIND_ICON[item.kind] || '\u5DE5'}</div>
        <div class="item-main">
          <div class="item-title"></div>
          <div class="item-sub"></div>
        </div>
        <button class="fav-star" title="\u6536\u85CF">\u2606</button>
        <span class="item-kind">${KIND_LABEL[item.kind] || item.kind || '\u5DE5\u5177'}</span>`;
      el.querySelector('.item-title').textContent = item.title;
      el.querySelector('.item-sub').textContent = item.subtitle || '';

      // 收藏星标
      const starBtn = el.querySelector('.fav-star');
      const favKey = makeFavKey(item);
      if (favoriteIds.has(favKey)) {
        starBtn.classList.add('starred');
        starBtn.textContent = '\u2605';
        starBtn.title = '\u53D6\u6D88\u6536\u85CF';
      }
      starBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        toggleFavorite(item, starBtn);
      });

      el.addEventListener('click', () => activate(i));
      el.addEventListener('mousemove', () => setActive(i));
      list.appendChild(el);
    });
  }

  function setActive(i) {
    activeIdx = i;
    [...list.querySelectorAll('.result-item')].forEach((n, idx) =>
      n.classList.toggle('active', idx === i)
    );
  }

  /* ---- 历史 ---- */
  async function loadHistory() {
    switchView('search');
    try {
      const { status, body } = await window.fastbox.call('GET', '/api/history?limit=20');
      if (status === 200 && body && Array.isArray(body.history)) {
        renderHistory(body.history);
      } else {
        renderEmpty('\u8F93\u5165\u5173\u952E\u8BCD\u5F00\u59CB\u641C\u7D22', '\u652F\u6301\uFF1A\u5185\u7F6E\u5DE5\u5177 \u00B7 \u672C\u5730\u6587\u4EF6 \u00B7 Python \u811A\u672C \u00B7 SQL \u811A\u672C');
      }
    } catch {
      renderEmpty('\u8F93\u5165\u5173\u952E\u8BCD\u5F00\u59CB\u641C\u7D22', '\u652F\u6301\uFF1A\u5185\u7F6E\u5DE5\u5177 \u00B7 \u672C\u5730\u6587\u4EF6 \u00B7 Python \u811A\u672C \u00B7 SQL \u811A\u672C');
    }
    statusText.textContent = '\u5C31\u7EEA';
  }

  function renderHistory(history) {
    emptyState.style.display = 'none';
    list.querySelectorAll('.result-item, .panel-header, .panel-item').forEach((n) => n.remove());
    activeIdx = -1;

    if (history.length === 0) {
      renderEmpty('\u8F93\u5165\u5173\u952E\u8BCD\u5F00\u59CB\u641C\u7D22', '\u652F\u6301\uFF1A\u5185\u7F6E\u5DE5\u5177 \u00B7 \u672C\u5730\u6587\u4EF6 \u00B7 Python \u811A\u672C \u00B7 SQL \u811A\u672C');
      return;
    }

    const header = document.createElement('div');
    header.className = 'panel-header';
    header.textContent = '\u6700\u8FD1\u641C\u7D22';
    list.appendChild(header);

    history.forEach((h) => {
      const el = document.createElement('div');
      el.className = 'panel-item';
      el.innerHTML = `
        <div class="panel-icon type-tool">\u5DE5</div>
        <div class="panel-main">
          <div class="panel-title"></div>
        </div>
        <span class="panel-meta"></span>`;
      el.querySelector('.panel-title').textContent = h.keyword;
      el.querySelector('.panel-meta').textContent = formatTime(h.createdAt);
      el.addEventListener('click', () => {
        input.value = h.keyword;
        input.dispatchEvent(new Event('input'));
      });
      list.appendChild(el);
    });
  }

  /* ---- 收藏 ---- */
  async function loadFavorites() {
    try {
      const { status, body } = await window.fastbox.call('GET', '/api/favorites');
      if (status === 200 && body && Array.isArray(body.favorites)) {
        renderFavorites(body.favorites);
      } else {
        renderEmpty('\u6536\u85CF\u5217\u8868\u52A0\u8F7D\u5931\u8D25', '');
      }
    } catch {
      renderEmpty('\u65E0\u6CD5\u8FDE\u63A5\u7F51\u5173', '\u8BF7\u68C0\u67E5 Java \u7F51\u5173\u670D\u52A1');
    }
    statusText.textContent = '\u5C31\u7EEA';
  }

  function renderFavorites(favorites) {
    emptyState.style.display = 'none';
    list.querySelectorAll('.result-item, .panel-header, .panel-item').forEach((n) => n.remove());
    activeIdx = -1;

    if (favorites.length === 0) {
      renderEmpty('\u8FD8\u6CA1\u6709\u6536\u85CF', '\u70B9\u51FB\u641C\u7D22\u7ED3\u679C\u53F3\u4FA7\u7684 \u2606 \u53EF\u4EE5\u6536\u85CF\u5E38\u7528\u5DE5\u5177');
      return;
    }

    const header = document.createElement('div');
    header.className = 'panel-header';
    header.textContent = `\u6211\u7684\u6536\u85CF (${favorites.length})`;
    list.appendChild(header);

    favorites.forEach((fav) => {
      const kind = inferKind(fav.action, fav.payload);
      const el = document.createElement('div');
      el.className = 'panel-item';
      el.innerHTML = `
        <div class="panel-icon type-${kind}">${KIND_ICON[kind] || '\u5DE5'}</div>
        <div class="panel-main">
          <div class="panel-title"></div>
          <div class="panel-sub"></div>
        </div>
        <span class="panel-meta">${KIND_LABEL[kind] || fav.action}</span>
        <button class="panel-delete" title="\u5220\u9664">\u00D7</button>`;
      el.querySelector('.panel-title').textContent = fav.name;
      el.querySelector('.panel-sub').textContent = fav.action;

      // 点击执行
      el.addEventListener('click', () => {
        const item = { action: fav.action, payload: fav.payload, title: fav.name, kind };
        activateFavorite(item);
      });

      // 删除按钮
      el.querySelector('.panel-delete').addEventListener('click', async (e) => {
        e.stopPropagation();
        await deleteFavorite(fav.id);
        el.remove();
        // 更新收藏计数
        const h = list.querySelector('.panel-header');
        if (h) {
          const remaining = list.querySelectorAll('.panel-item').length;
          h.textContent = `\u6211\u7684\u6536\u85CF (${remaining})`;
          if (remaining === 0) renderEmpty('\u8FD8\u6CA1\u6709\u6536\u85CF', '\u70B9\u51FB\u641C\u7D22\u7ED3\u679C\u53F3\u4FA7\u7684 \u2606 \u53EF\u4EE5\u6536\u85CF\u5E38\u7528\u5DE5\u5177');
        }
      });
      list.appendChild(el);
    });
  }

  async function toggleFavorite(item, starBtn) {
    const favKey = makeFavKey(item);
    if (favoriteIds.has(favKey)) {
      // 已收藏 → 不支持取消（需要遍历查找 id），暂用 toast 提示
      showToast('\u8BF7\u5728\u6536\u85CF\u9762\u677F\u7BA1\u7406');
      return;
    }
    try {
      const { status, body } = await window.fastbox.call('POST', '/api/favorites', {
        name: item.title,
        action: item.action,
        payload: item.payload || {},
      });
      if (status === 200 && body && body.toast) {
        showToast(body.toast);
        favoriteIds.add(favKey);
        starBtn.classList.add('starred');
        starBtn.textContent = '\u2605';
        starBtn.title = '\u5DF2\u6536\u85CF';
      }
    } catch (e) {
      showToast('\u6536\u85CF\u5931\u8D25: ' + (e.message || ''));
    }
  }

  async function deleteFavorite(id) {
    try {
      const { status, body } = await window.fastbox.call('POST', '/api/favorites/delete', { id });
      if (status === 200 && body && body.toast) {
        showToast(body.toast);
      }
    } catch (e) {
      showToast('\u5220\u9664\u5931\u8D25: ' + (e.message || ''));
    }
  }

  /** 刷新当前搜索结果中哪些已收藏 */
  async function refreshFavoriteStatus() {
    favoriteIds.clear();
    try {
      const { status, body } = await window.fastbox.call('GET', '/api/favorites');
      if (status === 200 && body && Array.isArray(body.favorites)) {
        body.favorites.forEach((f) => {
          favoriteIds.add(`${f.action}|${JSON.stringify(f.payload)}`);
        });
      }
    } catch {
      // 静默失败
    }
  }

  function makeFavKey(item) {
    return `${item.action}|${JSON.stringify(item.payload || {})}`;
  }

  function inferKind(action, payload) {
    if (action === 'plugin' && payload && payload.kind) return payload.kind;
    if (action === 'sql_script' || action === 'sql') return 'sql';
    if (action === 'open_file') return 'file';
    if (action === 'calc' || action === 'help') return 'tool';
    return 'tool';
  }

  async function activateFavorite(item) {
    const schema = item.payload && item.payload.argsSchema;
    if (item.action === 'plugin' && Array.isArray(schema) && schema.length > 0 && hasRequired(schema)) {
      openArgForm(item, schema);
      return;
    }
    await doAction(item);
  }

  /* ---- 执行动作 ---- */
  async function activate(i) {
    const item = items[i];
    if (!item) return;
    const schema = item.payload && item.payload.argsSchema;
    if (item.action === 'plugin' && Array.isArray(schema) && schema.length > 0 && hasRequired(schema)) {
      openArgForm(item, schema);
      return;
    }
    await doAction(item);
  }

  function hasRequired(schema) {
    return schema.some((f) => f.required === true || f.required === 'true');
  }

  async function doAction(item, extraPayload) {
    const payload = Object.assign({}, item.payload || {}, extraPayload || {});
    statusText.textContent = '\u6267\u884C\u4E2D\u2026';

    // JS 插件：本地执行，不经 Java 网关
    if (item.action === 'plugin' && payload.kind === 'js') {
      try {
        const result = await window.fastbox.executeJs(payload);
        if (result.detail) {
          showDetail(result.detail.title || item.title, result.detail.content);
        } else if (result.toast) {
          showToast(result.toast);
        }
      } catch (e) {
        showToast('JS \u63D2\u4EF6\u6267\u884C\u5931\u8D25: ' + (e.message || ''));
      }
      statusText.textContent = items.length ? `${items.length} \u6761\u7ED3\u679C` : '\u5C31\u7EEA';
      return;
    }

    // Python / Java / 其他动作：走 Java 网关
    try {
      const { status, body } = await window.fastbox.call('POST', `/api/action`, {
        action: item.action,
        payload: payload,
      });
      if (status === 200 && body) {
        if (body.detail) {
          showDetail(body.detail.title || item.title, body.detail.content);
        } else if (body.toast) {
          showToast(body.toast);
        }
      } else {
        showToast(`\u6267\u884C\u5931\u8D25: HTTP ${status}`);
      }
    } catch (e) {
      showToast('\u6267\u884C\u5931\u8D25: ' + (e.message || ''));
    }
    statusText.textContent = items.length ? `${items.length} \u6761\u7ED3\u679C` : '\u5C31\u7EEA';
  }

  /* ---- 参数输入表单 ---- */
  function openArgForm(item, schema) {
    argItem = item;
    argTitle.textContent = `\u53C2\u6570 \u00B7 ${item.title}`;
    argForm.innerHTML = '';
    schema.forEach((field, idx) => {
      const required = field.required === true || field.required === 'true';
      const name = field.name || `\u53C2\u6570 ${idx + 1}`;
      const desc = field.description || '';
      const defaultValue = field.default != null ? String(field.default) : '';
      const row = document.createElement('div');
      row.className = 'arg-field';
      row.innerHTML = `
        <label for="arg-${idx}">${name}${required ? '<span class="req">*</span>' : ''}</label>
        <input id="arg-${idx}" type="text" placeholder="${desc}" ${required ? 'data-required="1"' : ''} />
        ${field.hint ? `<span class="arg-hint">${field.hint}</span>` : ''}`;
      const inp = row.querySelector('input');
      if (defaultValue) inp.value = defaultValue;
      argForm.appendChild(row);
    });
    argOverlay.classList.remove('hidden');
    const first = argForm.querySelector('input');
    if (first) setTimeout(() => first.focus(), 30);

    // UI 自动化：测试模式下自动填入测试数据并提交
    if (uiTest === 'args') {
      setTimeout(() => {
        const inputs = [...argForm.querySelectorAll('.arg-field input')];
        if (inputs[0]) inputs[0].value = 'D:/devloper/project/myselfProject/FastBox/python-runtime/plugins';
        setTimeout(confirmArgForm, 600);
      }, 800);
    } else if (uiTest === 'js' || uiTest === 'java') {
      setTimeout(() => {
        const inputs = [...argForm.querySelectorAll('.arg-field input')];
        if (inputs[0]) inputs[0].value = 'Hello FastBox';
        setTimeout(confirmArgForm, 600);
      }, 800);
    }
  }

  function collectArgs() {
    const inputs = [...argForm.querySelectorAll('.arg-field input')];
    const values = inputs.map((inp) => inp.value.trim());
    let valid = true;
    inputs.forEach((inp) => {
      const required = inp.dataset.required === '1';
      if (required && !inp.value.trim()) {
        inp.classList.add('invalid');
        valid = false;
      } else {
        inp.classList.remove('invalid');
      }
    });
    return valid ? values : null;
  }

  function closeArgForm() {
    argOverlay.classList.add('hidden');
    argItem = null;
  }

  async function confirmArgForm() {
    if (!argItem) return;
    const values = collectArgs();
    if (!values) {
      showToast('\u8BF7\u586B\u5199\u5FC5\u586B\u53C2\u6570');
      return;
    }
    const item = argItem;
    closeArgForm();
    await doAction(item, { args: values });
  }

  $('argClose').addEventListener('click', closeArgForm);
  $('argCancel').addEventListener('click', closeArgForm);
  $('argConfirm').addEventListener('click', confirmArgForm);
  argOverlay.addEventListener('click', (e) => {
    if (e.target === argOverlay) closeArgForm();
  });

  function showDetail(title, content) {
    detailTitle.textContent = title;
    detailContent.textContent = typeof content === 'string' ? content : JSON.stringify(content, null, 2);
    detailOverlay.classList.remove('hidden');
  }

  /* ---- 时间格式化 ---- */
  function formatTime(ts) {
    if (!ts) return '';
    // ts 格式: "2026-08-17 08:30:00"
    const now = new Date();
    const t = new Date(ts.replace(/-/g, '/'));
    const diff = (now - t) / 1000;
    if (diff < 60) return '\u521A\u521A';
    if (diff < 3600) return `${Math.floor(diff / 60)}\u5206\u949F\u524D`;
    if (diff < 86400) return `${Math.floor(diff / 3600)}\u5C0F\u65F6\u524D`;
    if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}\u5929\u524D`;
    return ts.substring(5, 16);
  }

  /* ---- 键盘导航 ---- */
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if (!argOverlay.classList.contains('hidden')) {
        closeArgForm();
      } else if (!detailOverlay.classList.contains('hidden')) {
        detailOverlay.classList.add('hidden');
      } else {
        window.fastbox.hide();
      }
      return;
    }
    if (!argOverlay.classList.contains('hidden')) {
      if (e.key === 'Enter') {
        e.preventDefault();
        confirmArgForm();
      }
      return;
    }
    // 历史/收藏面板中不处理上下键导航
    if (currentView !== 'search') return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (items.length) setActive((activeIdx + 1) % items.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (items.length) setActive((activeIdx - 1 + items.length) % items.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (activeIdx >= 0) activate(activeIdx);
    }
  });

  $('detailClose').addEventListener('click', () => detailOverlay.classList.add('hidden'));
  detailOverlay.addEventListener('click', (e) => {
    if (e.target === detailOverlay) detailOverlay.classList.add('hidden');
  });

  /* ---- 启动探测 ---- */
  (async () => {
    try {
      const { status, body } = await window.fastbox.call('GET', '/health');
      gatewayReady = status === 200;
      badge.textContent = gatewayReady ? '\u7F51\u5173\u5C31\u7EEA' : '\u7F51\u5173\u79BB\u7EBF';
      badge.className = `badge ${gatewayReady ? 'badge-ready' : 'badge-fail'}`;
      if (gatewayReady) statusText.textContent = '\u5C31\u7EEA' + (body && body.version ? ' \u00B7 v' + body.version : '');
      // 启动后加载历史
      loadHistory();
    } catch {
      badge.textContent = '\u7F51\u5173\u79BB\u7EBF';
      badge.className = 'badge badge-fail';
    }
  })();

  /* ---- UI 自动化冒烟测试 ---- */
  const uiTest = new URLSearchParams(location.search).get('uiTest');
  if (uiTest === 'args') {
    setTimeout(async () => {
      input.value = '\u6587\u4EF6\u7EDF\u8BA1';
      input.dispatchEvent(new Event('input'));
      setTimeout(() => {
        const first = list.querySelector('.result-item');
        if (first) first.click();
        console.log('[uitest] args form triggered');
      }, 2000);
    }, 2500);
  } else if (uiTest === 'js') {
    setTimeout(async () => {
      input.value = 'base64';
      input.dispatchEvent(new Event('input'));
      setTimeout(() => {
        const first = list.querySelector('.result-item');
        if (first) first.click();
        console.log('[uitest] js plugin triggered');
      }, 2000);
    }, 2500);
  } else if (uiTest === 'java') {
    setTimeout(async () => {
      input.value = 'sha256';
      input.dispatchEvent(new Event('input'));
      setTimeout(() => {
        const first = list.querySelector('.result-item');
        if (first) first.click();
        console.log('[uitest] java plugin triggered');
      }, 2000);
    }, 2500);
    } else if (uiTest === 'jsfail') {
    setTimeout(async () => {
      input.value = 'fail-test';
      input.dispatchEvent(new Event('input'));
      setTimeout(() => {
        const first = list.querySelector('.result-item');
        if (first) first.click();
        console.log('[uitest] js fail plugin triggered');
      }, 2000);
    }, 2500);
  } else if (uiTest === 'search') {
    // 仅搜索不点击，用于截图带收藏星标的搜索结果
    setTimeout(async () => {
      input.value = 'base64';
      input.dispatchEvent(new Event('input'));
      console.log('[uitest] search only, no click');
    }, 2500);
  } else if (uiTest === 'favorites') {
    // 点击收藏 Tab，用于截图收藏面板
    setTimeout(async () => {
      tabFavorites.click();
      console.log('[uitest] favorites tab clicked');
    }, 2500);
  }
})();
