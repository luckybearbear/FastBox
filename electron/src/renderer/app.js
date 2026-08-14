/**
 * FastBox 渲染层逻辑
 * 搜索输入 → 调 Java 网关 /api/search → 渲染结果列表 → 点击执行动作
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

  let items = [];
  let activeIdx = -1;
  let gatewayReady = false;
  let toastTimer = null;
  let argItem = null; // 等待参数输入的插件结果项

  const KIND_ICON = { tool: '工', file: '文', python: 'Py', js: 'JS', java: 'Ja', sql: 'SQL', plugin: '插', command: '命' };
  const KIND_LABEL = { tool: '工具', file: '文件', python: '脚本', js: 'JS', java: 'Java', sql: 'SQL', plugin: '插件', command: '命令' };

  /* ---- 网关状态 ---- */
  window.fastbox.on('status:gateway', (s) => {
    gatewayReady = s === 'ready';
    badge.textContent = gatewayReady ? '网关就绪' : '网关离线';
    badge.className = `badge ${gatewayReady ? 'badge-ready' : 'badge-fail'}`;
  });
  window.fastbox.on('status:toast', (msg) => showToast(msg));
  window.fastbox.on('panel:shown', () => {
    input.focus();
    input.select();
  });

  function showToast(msg) {
    toastEl.textContent = msg;
    toastEl.style.opacity = 1;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toastEl.style.opacity = 0), 3000);
  }

  /* ---- 搜索 ---- */
  let debounceTimer = null;
  input.addEventListener('input', () => {
    clearTimeout(debounceTimer);
    const q = input.value.trim();
    debounceTimer = setTimeout(() => doSearch(q), 150);
  });

  async function doSearch(q) {
    if (!q) {
      renderEmpty('输入关键词开始搜索', '支持：内置工具 · 本地文件 · Python 脚本 · SQL 脚本');
      items = [];
      activeIdx = -1;
      return;
    }
    statusText.textContent = '搜索中…';
    try {
      const { status, body } = await window.fastbox.call('GET', `/api/search?q=${encodeURIComponent(q)}`);
      if (status === 200 && body && Array.isArray(body.results)) {
        items = body.results;
        renderResults();
      } else {
        renderEmpty('网关返回异常', `HTTP ${status}`);
      }
    } catch (e) {
      gatewayReady = false;
      badge.textContent = '网关离线';
      badge.className = 'badge badge-fail';
      renderEmpty('无法连接网关', e.message || '请检查 Java 网关服务');
      showToast('网关连接失败');
    }
    statusText.textContent = items.length ? `${items.length} 条结果` : '就绪';
  }

  function renderEmpty(title, hint) {
    emptyState.style.display = 'flex';
    list.querySelectorAll('.result-item').forEach((n) => n.remove());
    emptyState.querySelector('.empty-title').textContent = title;
    emptyState.querySelector('.empty-hint').textContent = hint;
  }

  function renderResults() {
    emptyState.style.display = 'none';
    list.querySelectorAll('.result-item').forEach((n) => n.remove());
    activeIdx = items.length ? 0 : -1;

    items.forEach((item, i) => {
      const el = document.createElement('div');
      el.className = 'result-item' + (i === activeIdx ? ' active' : '');
      el.innerHTML = `
        <div class="type-icon type-${item.kind || 'tool'}">${KIND_ICON[item.kind] || '工'}</div>
        <div class="item-main">
          <div class="item-title"></div>
          <div class="item-sub"></div>
        </div>
        <span class="item-kind">${KIND_LABEL[item.kind] || item.kind || '工具'}</span>`;
      el.querySelector('.item-title').textContent = item.title;
      el.querySelector('.item-sub').textContent = item.subtitle || '';
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

  /* ---- 执行动作 ---- */
  async function activate(i) {
    const item = items[i];
    if (!item) return;
    // 插件带必填参数声明 → 先弹参数表单；全可选参数直接执行
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
    statusText.textContent = '执行中…';

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
        showToast('JS 插件执行失败: ' + (e.message || ''));
      }
      statusText.textContent = items.length ? `${items.length} 条结果` : '就绪';
      return;
    }

    // Python 插件 / 其他动作：走 Java 网关
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
        showToast(`执行失败: HTTP ${status}`);
      }
    } catch (e) {
      showToast('执行失败: ' + (e.message || ''));
    }
    statusText.textContent = items.length ? `${items.length} 条结果` : '就绪';
  }

  /* ---- 参数输入表单 ---- */
  function openArgForm(item, schema) {
    argItem = item;
    argTitle.textContent = `参数 · ${item.title}`;
    argForm.innerHTML = '';
    schema.forEach((field, idx) => {
      const required = field.required === true || field.required === 'true';
      const name = field.name || `参数 ${idx + 1}`;
      const desc = field.description || '';
      const defaultValue = field.default != null ? String(field.default) : '';
      const row = document.createElement('div');
      row.className = 'arg-field';
      row.innerHTML = `
        <label for="arg-${idx}">${name}${required ? '<span class="req">*</span>' : ''}</label>
        <input id="arg-${idx}" type="text" placeholder="${desc}" ${required ? 'data-required="1"' : ''} />
        ${field.hint ? `<span class="arg-hint">${field.hint}</span>` : ''}`;
      const input = row.querySelector('input');
      if (defaultValue) input.value = defaultValue;
      argForm.appendChild(row);
    });
    argOverlay.classList.remove('hidden');
    const first = argForm.querySelector('input');
    if (first) setTimeout(() => first.focus(), 30);

    // UI 自动化：测试模式下自动填入测试数据并提交
    if (uiTest === 'args') {
      setTimeout(() => {
        const inputs = [...argForm.querySelectorAll('.arg-field input')];
        if (inputs[0]) inputs[0].value = 'D:/devloper/project/myselfProject/my-tool/FastBox/python-runtime/plugins';
        setTimeout(confirmArgForm, 600);
      }, 800);
    } else if (uiTest === 'js') {
      setTimeout(() => {
        const inputs = [...argForm.querySelectorAll('.arg-field input')];
        if (inputs[0]) inputs[0].value = 'Hello FastBox';
        setTimeout(confirmArgForm, 600);
      }, 800);
    } else if (uiTest === 'java') {
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
    inputs.forEach((inp, i) => {
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
      showToast('请填写必填参数');
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
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (items.length) setActive((activeIdx + 1) % items.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (items.length) setActive((activeIdx - 1 + items.length) % items.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      activate(activeIdx);
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
      badge.textContent = gatewayReady ? '网关就绪' : '网关离线';
      badge.className = `badge ${gatewayReady ? 'badge-ready' : 'badge-fail'}`;
      if (gatewayReady) statusText.textContent = '就绪 · ' + (body && body.version ? 'v' + body.version : '');
    } catch {
      badge.textContent = '网关离线';
      badge.className = 'badge badge-fail';
    }
  })();

  /* ---- UI 自动化冒烟测试 ---- */
  const uiTest = new URLSearchParams(location.search).get('uiTest');
  if (uiTest === 'args') {
    setTimeout(async () => {
      // 模拟输入"文件统计"触发搜索
      input.value = '文件统计';
      input.dispatchEvent(new Event('input'));
      // 等搜索结果渲染后点击第一个结果（应弹出参数表单）
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
    // 异常回归：触发 _fail-test 插件，验证错误 toast + 主进程不崩 + 失败留痕
    setTimeout(async () => {
      input.value = 'fail-test';
      input.dispatchEvent(new Event('input'));
      setTimeout(() => {
        const first = list.querySelector('.result-item');
        if (first) first.click();
        console.log('[uitest] js fail plugin triggered');
      }, 2000);
    }, 2500);
  }
})();
