(function () {
  'use strict';

  var COLLECTION = 'allalservice';
  // Bumped together with the `assets/app.js?v=` query in every page. If a
  // browser shows an old build, this makes it obvious which one is running.
  var BUILD = '2026-09-21-services-table';
  var LANGUAGES = ['zh', 'ja', 'en', 'ko'];
  var DEFAULT_STORAGE_BUCKET = 'vibecodingjapan.firebasestorage.app';
  var SKILL_MAX_BYTES = 26214400;
  var page = document.body.dataset.page || '';
  var currentUser = null;
  var services = [];
  var unsubscribe = null;
  var selectedSkillFile = null;
  // Version documents loaded for the service being edited, newest first.
  // `skillVersions` only ever holds documents from the `skillVersions`
  // subcollection; documents uploaded before version control existed are
  // synthesised as V1 by `skillVersionRows()` until they are migrated.
  var skillVersions = [];

  function byId(id) { return document.getElementById(id); }
  function show(element, visible, displayClass) {
    if (!element) return;
    element.classList.toggle('hidden', !visible);
    if (displayClass) element.classList.toggle(displayClass, visible);
  }
  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>'"]/g, function (char) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[char];
    });
  }
  function readableError(error) {
    var messages = {
      'auth/invalid-email': '邮箱格式不正确。',
      'auth/user-disabled': '此账号已被停用，请联系管理员。',
      'auth/user-not-found': '邮箱或密码不正确。',
      'auth/wrong-password': '邮箱或密码不正确。',
      'auth/invalid-login-credentials': '邮箱或密码不正确。',
      'auth/too-many-requests': '尝试次数过多，请稍后再试。',
      'auth/network-request-failed': '网络连接失败，请检查网络后重试。',
      'auth/operation-not-allowed': '邮箱登录尚未启用，请联系管理员。',
      'permission-denied': '没有执行此操作的权限。',
      'failed-precondition': '服务索引尚未就绪，请稍后重试。',
      'unavailable': '服务暂时不可用，请检查网络后重试。',
      'not-found': '服务不存在或已被移除。',
      'storage/unauthorized': '没有上传权限，请确认已登录并已部署最新的 Storage 规则。',
      'storage/canceled': '上传已取消。',
      'storage/retry-limit-exceeded': '上传超时，请检查网络后重试。',
      'storage/quota-exceeded': '存储空间不足，请联系管理员。',
      'storage/unknown': '文件上传失败，请稍后重试。'
    };
    console.error('Error:', error);
    return messages[error && error.code] || '操作失败，请稍后重试。';
  }
  function storageBucket() {
    try {
      var options = firebase.app().options || {};
      return options.storageBucket || DEFAULT_STORAGE_BUCKET;
    } catch (error) {
      return DEFAULT_STORAGE_BUCKET;
    }
  }
  function skillPublicUrl(path) {
    return 'https://firebasestorage.googleapis.com/v0/b/' + encodeURIComponent(storageBucket()) + '/o/' + encodeURIComponent(path) + '?alt=media';
  }
  function formatBytes(value) {
    var bytes = Number(value) || 0;
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
  }
  function safeZipName(name) {
    var base = String(name || '').replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^[._-]+/, '');
    if (!/\.zip$/i.test(base)) base += '.zip';
    return base.slice(-140) || 'skills.zip';
  }
  function isSkillService(service) { return Boolean(service) && service.type === 'deepseek_harness'; }
  function hasSkillFile(service) { return Boolean(service && service.skillZipUrl); }

  // ---- Skill version control -------------------------------------------
  // Every upload creates one immutable document under
  //   /allalservice/{serviceId}/skillVersions/{version}
  // (`version` is a positive integer and doubles as the document ID).
  // The parent document keeps `skillActiveVersion` plus the mirrored
  // `skillZipUrl/skillStoragePath/skillFileName/skillFileSize` fields, which
  // always describe the currently effective version so that aiskillsrunner can
  // keep reading `doc.skillZipUrl` as `--installurl`.
  function skillVersionNumber(value) {
    var number = Number(value);
    return Number.isInteger(number) && number >= 1 && number <= 999999 ? number : 0;
  }
  function skillVersionCollection(serviceId) {
    return serviceCollection().doc(serviceId).collection('skillVersions');
  }
  function hasStoredVersions() {
    return skillVersions.some(function (row) { return !row.legacy; });
  }
  function skillVersionRows(service) {
    var rows = skillVersions.slice();
    if (!rows.length && hasSkillFile(service)) {
      rows = [{
        id: '1',
        legacy: true,
        version: skillVersionNumber(service.skillActiveVersion) || 1,
        zipUrl: service.skillZipUrl,
        storagePath: service.skillStoragePath || '',
        fileName: service.skillFileName || '',
        fileSize: Number(service.skillFileSize) || 0,
        createdAt: service.updatedAt || null
      }];
    }
    return rows.sort(function (a, b) { return b.version - a.version; });
  }
  function currentVersionNumber(service) {
    if (!service) return 0;
    var rows = skillVersionRows(service);
    if (!rows.length) return 0;
    var stored = skillVersionNumber(service.skillActiveVersion);
    if (stored && rows.some(function (row) { return row.version === stored; })) return stored;
    // Documents created before version control keep the pointer only in the
    // mirrored fields, so fall back to the oldest (and only) file.
    return hasSkillFile(service) ? rows[rows.length - 1].version : 0;
  }
  function nextVersionNumber(service) {
    return skillVersionRows(service).reduce(function (max, row) { return Math.max(max, row.version); }, 0) + 1;
  }
  function activeVersionLabel(service) {
    var current = currentVersionNumber(service);
    return current ? 'V' + current : '';
  }
  function loadSkillVersions(serviceId) {
    return skillVersionCollection(serviceId).get().then(function (snapshot) {
      skillVersions = snapshot.docs.map(function (doc) {
        var data = doc.data() || {};
        return {
          id: doc.id,
          legacy: false,
          // The rules constrain the document ID to 1..999999, so it is the
          // authoritative version number; the `version` field mirrors it.
          version: skillVersionNumber(doc.id) || skillVersionNumber(data.version),
          zipUrl: data.zipUrl || '',
          storagePath: data.storagePath || '',
          fileName: data.fileName || '',
          fileSize: Number(data.fileSize) || 0,
          createdAt: data.createdAt || null
        };
      }).filter(function (row) { return row.version > 0; }).sort(function (a, b) { return b.version - a.version; });
      return skillVersions;
    });
  }
  function skillVersionPayload(version, uploaded) {
    return {
      version: version,
      zipUrl: uploaded.url,
      storagePath: uploaded.path,
      fileName: uploaded.name,
      fileSize: Number(uploaded.size) || 0,
      createdAt: serverTimestamp(),
      userUid: currentUser.uid
    };
  }
  // `skillActiveVersion` is written together with the mirrored fields so the
  // two never disagree.
  function skillActiveFields(version, row) {
    return {
      skillActiveVersion: version,
      skillZipUrl: row.zipUrl,
      skillStoragePath: row.storagePath,
      skillFileName: row.fileName,
      skillFileSize: Number(row.fileSize) || 0
    };
  }
  function emptySkillFields() {
    return { skillActiveVersion: 0, skillZipUrl: '', skillStoragePath: '', skillFileName: '', skillFileSize: 0 };
  }
  function copyText(value) {
    if (navigator.clipboard && navigator.clipboard.writeText) return navigator.clipboard.writeText(String(value));
    return new Promise(function (resolve, reject) {
      var area = document.createElement('textarea');
      area.value = String(value);
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      try {
        if (document.execCommand('copy')) resolve();
        else reject(new Error('copy-failed'));
      } catch (error) {
        reject(error);
      } finally {
        area.remove();
      }
    });
  }
  function toast(message, type) {
    var region = byId('toast-region');
    if (!region) return;
    var item = document.createElement('div');
    var success = type !== 'error';
    item.className = 'toast pointer-events-auto flex items-start gap-3 rounded-xl border px-4 py-3 text-sm ' + (success ? 'border-green-300 border-opacity-20 bg-gray-900 text-green-100' : 'border-red-400 border-opacity-25 bg-gray-900 text-red-100');
    item.innerHTML = '<span class="mt-0.5 ' + (success ? 'text-green-300' : 'text-red-300') + '">' + (success ? '✓' : '!') + '</span><span class="flex-1 leading-5">' + escapeHtml(message) + '</span>';
    region.appendChild(item);
    window.setTimeout(function () { item.remove(); }, 4200);
  }
  function showPageError(message) {
    var error = byId('page-error');
    if (!error) return;
    error.textContent = message;
    error.classList.remove('hidden');
  }
  function setUserHeader(user) {
    var userEmail = byId('user-email');
    if (userEmail) userEmail.textContent = user.email || ('UID: ' + user.uid);
    var logout = byId('logout-button');
    if (logout) {
      logout.addEventListener('click', function () {
        logout.disabled = true;
        firebase.auth().signOut().catch(function (error) {
          toast(readableError(error), 'error');
          logout.disabled = false;
        });
      });
    }
  }
  function preferredLanguages() {
    var raw = String(navigator.language || 'zh').toLowerCase();
    var preferred = raw.indexOf('ja') === 0 ? 'ja' : raw.indexOf('ko') === 0 ? 'ko' : raw.indexOf('en') === 0 ? 'en' : 'zh';
    return [preferred].concat(LANGUAGES).filter(function (lang, index, all) { return all.indexOf(lang) === index; });
  }
  function localizedContent(service) {
    var result = { name: '未命名服务', description: '' };
    preferredLanguages().some(function (lang) {
      var suffix = lang.charAt(0).toUpperCase() + lang.slice(1);
      var name = String(service['name' + suffix] || '').trim();
      var description = String(service['description' + suffix] || '').trim();
      if (name && description) {
        result = { name: name, description: description };
        return true;
      }
      return false;
    });
    return result;
  }
  function typeLabel(type) { return type === 'google_workspace_studio' ? 'Google Workspace Studio' : 'DeepSeek Harness'; }
  function typeTone(type) { return type === 'google_workspace_studio' ? 'text-blue-300 bg-blue-400' : 'text-purple-300 bg-purple-400'; }
  function formatDate(value) {
    if (!value) return '刚刚';
    var date = typeof value.toDate === 'function' ? value.toDate() : new Date(value);
    if (Number.isNaN(date.getTime())) return '—';
    return new Intl.DateTimeFormat(navigator.language || 'zh-CN', {
      year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
    }).format(date);
  }
  function serverTimestamp() { return firebase.firestore.FieldValue.serverTimestamp(); }
  function serviceCollection() { return firebase.firestore().collection(COLLECTION); }
  function userServiceQuery() {
    return serviceCollection().where('userUid', '==', currentUser.uid).orderBy('updatedAt', 'desc');
  }
  function showNoticeFromUrl() {
    var notice = new URLSearchParams(window.location.search).get('notice');
    var messages = { created: '服务已创建。', updated: '服务已更新。', trashed: '服务已移入回收站。', restored: '服务已恢复为停用状态。' };
    if (messages[notice]) {
      toast(messages[notice]);
      window.history.replaceState({}, '', window.location.pathname);
    }
  }

  function initLogin() {
    var form = byId('login-form');
    var email = byId('email');
    var password = byId('password');
    var authError = byId('auth-error');
    var emailError = byId('email-error');
    var passwordError = byId('password-error');
    var button = byId('login-button');
    var label = byId('login-button-label');
    var spinner = byId('login-spinner');
    var arrow = byId('login-arrow');

    function clearErrors() {
      [authError, emailError, passwordError].forEach(function (item) { item.textContent = ''; item.classList.add('hidden'); });
      email.removeAttribute('aria-invalid');
      password.removeAttribute('aria-invalid');
    }
    function fieldError(element, input, message) {
      element.textContent = message;
      element.classList.remove('hidden');
      input.setAttribute('aria-invalid', 'true');
    }
    function setLoading(loading) {
      button.disabled = loading;
      email.disabled = loading;
      password.disabled = loading;
      label.textContent = loading ? '正在登录…' : '登录';
      spinner.classList.toggle('hidden', !loading);
      arrow.classList.toggle('hidden', loading);
    }

    byId('toggle-password').addEventListener('click', function () {
      var visible = password.type === 'password';
      password.type = visible ? 'text' : 'password';
      this.setAttribute('aria-label', visible ? '隐藏密码' : '显示密码');
      this.setAttribute('aria-pressed', String(visible));
      byId('eye-open').classList.toggle('hidden', visible);
      byId('eye-closed').classList.toggle('hidden', !visible);
      password.focus();
    });
    form.addEventListener('submit', function (event) {
      event.preventDefault();
      clearErrors();
      var valid = true;
      var emailValue = email.value.trim();
      if (!emailValue) { fieldError(emailError, email, '请输入邮箱地址。'); valid = false; }
      else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(emailValue)) { fieldError(emailError, email, '请输入有效的邮箱地址。'); valid = false; }
      if (!password.value) { fieldError(passwordError, password, '请输入密码。'); valid = false; }
      if (!valid) return;
      setLoading(true);
      firebase.auth().signInWithEmailAndPassword(emailValue, password.value).catch(function (error) {
        authError.textContent = readableError(error);
        authError.classList.remove('hidden');
        password.value = '';
        password.focus();
        setLoading(false);
      });
    });
  }

  function renderListLoading() {
    var list = byId('service-list');
    if (list) list.innerHTML = '';
    byId('list-status').innerHTML = '<div class="flex items-center justify-center gap-3 rounded-2xl border border-gray-800 py-16 text-sm text-gray-400"><span class="h-5 w-5 animate-spin rounded-full border-2 border-gray-700 border-t-green-300"></span>正在加载服务…</div>';
  }
  function renderListError(error) {
    var list = byId('service-list');
    if (list) list.innerHTML = '';
    byId('list-status').innerHTML = '<div class="rounded-2xl border border-red-400 border-opacity-20 bg-red-400 bg-opacity-5 px-6 py-10 text-center"><h2 class="font-semibold text-red-200">无法加载服务</h2><p class="mt-2 text-sm text-gray-400">' + escapeHtml(readableError(error)) + '</p><button class="secondary-button mt-5 px-5 py-2.5 text-sm font-semibold" data-retry>重新加载</button></div>';
  }
  function searchMatches(service, search) {
    if (!search) return true;
    var values = LANGUAGES.reduce(function (all, lang) {
      var suffix = lang.charAt(0).toUpperCase() + lang.slice(1);
      return all.concat(service['name' + suffix], service['description' + suffix]);
    }, []);
    values.push(service.googleDriveUrl, service.applicationName, service.info, service.skillFileName, service.skillZipUrl, service.skillStoragePath, typeLabel(service.type));
    return values.some(function (value) { return String(value || '').toLowerCase().indexOf(search) >= 0; });
  }
  function serviceDataBlock(service, withCopyButton) {
    if (service.type === 'google_workspace_studio') {
      var drive = service.googleDriveUrl || '';
      var driveHtml = '<div class="mt-4 rounded-xl border border-gray-800 bg-black bg-opacity-10 px-3 py-2.5"><p class="text-xs text-gray-600">Google Drive URL</p><p class="mt-1 truncate text-xs text-gray-400" title="' + escapeHtml(drive) + '">' + escapeHtml(drive) + '</p>';
      if (service.info) driveHtml += '<p class="mt-2 text-xs leading-5 text-gray-400">' + escapeHtml(service.info) + '</p>';
      driveHtml += '<p class="mt-2 text-xs text-gray-600">显示顺序：' + escapeHtml(String(service.sort == null ? 0 : service.sort)) + '</p></div>';
      return driveHtml;
    }
    var html = '<div class="mt-4 rounded-xl border border-gray-800 bg-black bg-opacity-10 px-3 py-2.5">' +
      '<p class="text-xs text-gray-600">Skill 名称（--skill）</p><p class="mt-1 truncate text-xs text-gray-400" title="' + escapeHtml(service.applicationName) + '">' + escapeHtml(service.applicationName || '—') + '</p>';
    if (hasSkillFile(service)) {
      var versionLabel = activeVersionLabel(service);
      html += '<p class="mt-2 text-xs text-gray-600">Skill 包（--installurl' + (versionLabel ? ' · ' + versionLabel : '') + '）</p><p class="mt-1 truncate text-xs text-gray-400" title="' + escapeHtml(service.skillZipUrl) + '">' + escapeHtml(service.skillFileName || 'skills.zip') + ' · ' + escapeHtml(formatBytes(service.skillFileSize)) + '</p>' +
        '<div class="mt-2 flex flex-wrap gap-2"><a class="secondary-button px-2.5 py-1.5 text-xs font-semibold" href="' + escapeHtml(service.skillZipUrl) + '" target="_blank" rel="noopener">下载 zip</a>' +
        (withCopyButton === false ? '' : '<button class="secondary-button px-2.5 py-1.5 text-xs font-semibold" type="button" data-action="copy-url" data-id="' + escapeHtml(service.id) + '">复制 URL</button>') +
        '</div>';
    } else {
      html += '<p class="mt-2 text-xs text-yellow-300">尚未上传 Skill 包</p>';
    }
    if (service.info) html += '<p class="mt-2 text-xs leading-5 text-gray-400">' + escapeHtml(service.info) + '</p>';
    html += '<p class="mt-2 text-xs text-gray-600">显示顺序：' + escapeHtml(String(service.sort == null ? 0 : service.sort)) + '</p></div>';
    return html;
  }
  // 服务列表用表格展示；「显示顺序」列提供 ↑↓ 调整（写回 Firestore 的 sort 字段）。
  function serviceMoveCell(service, index, total, reorderable) {
    var id = escapeHtml(service.id);
    var hint = reorderable ? '' : ' title="请先清除搜索或筛选条件"';
    var canUp = reorderable && index > 0;
    var canDown = reorderable && index < total - 1;
    return '<div class="flex items-center gap-1">' +
      '<button class="secondary-button px-2.5 py-1.5 text-xs font-semibold" type="button" data-action="move-up" data-id="' + id + '"' + (canUp ? '' : ' disabled') + hint + ' aria-label="上移">↑</button>' +
      '<button class="secondary-button px-2.5 py-1.5 text-xs font-semibold" type="button" data-action="move-down" data-id="' + id + '"' + (canDown ? '' : ' disabled') + hint + ' aria-label="下移">↓</button>' +
      '</div><p class="mt-1.5 text-xs text-gray-600">sort ' + escapeHtml(String(Number(service.sort) || 0)) + '</p>';
  }
  function serviceNameCell(service, content) {
    var html = '<p class="text-sm font-semibold">' + escapeHtml(content.name) + '</p>' +
      '<p class="mt-1.5"><span class="rounded-md bg-opacity-10 px-2 py-0.5 text-xs font-semibold ' + typeTone(service.type) + '">' + escapeHtml(typeLabel(service.type)) + '</span></p>';
    if (isSkillService(service)) {
      html += '<p class="mt-1.5 truncate text-xs text-gray-500" title="' + escapeHtml(service.applicationName) + '">--skill: ' + escapeHtml(service.applicationName || '—') + '</p>';
    }
    return html;
  }
  function serviceIntroCell(service, content) {
    var html = '<p class="leading-5 text-gray-400">' + escapeHtml(content.description || '—') + '</p>';
    // if (service.info) html += '<p class="mt-1.5 leading-5 text-gray-500">' + escapeHtml(service.info) + '</p>';
    return html;
  }
  function serviceDataCell(service) {
    if (service.type === 'google_workspace_studio') {
      var drive = service.googleDriveUrl || '';
      return '<p class="text-xs text-gray-600">Google Drive</p>' +
        '<p class="mt-1 max-w-xs truncate text-xs text-gray-400" title="' + escapeHtml(drive) + '">' + escapeHtml(drive || '—') + '</p>' +
        (drive ? '<a class="secondary-button mt-2 inline-block px-2.5 py-1.5 text-xs font-semibold" href="' + escapeHtml(drive) + '" target="_blank" rel="noopener">打开</a>' : '');
    }
    if (!hasSkillFile(service)) {
      return '<p class="text-xs text-yellow-300">尚未上传 Skill 包</p>';
    }
    var versionLabel = activeVersionLabel(service);
    return '<p class="text-xs text-gray-400">' +
      (versionLabel ? '<span class="rounded bg-green-400 bg-opacity-10 px-1.5 py-0.5 text-xs font-semibold text-green-300">' + escapeHtml(versionLabel) + '</span> ' : '') +
      escapeHtml(service.skillFileName || 'skills.zip') + '</p>' +
      '<p class="mt-1 text-xs text-gray-600">' + escapeHtml(formatBytes(service.skillFileSize)) + '</p>' +
      '<div class="mt-2 flex flex-wrap gap-2">' +
      '<a class="secondary-button px-2.5 py-1.5 text-xs font-semibold" href="' + escapeHtml(service.skillZipUrl) + '" target="_blank" rel="noopener">下载 zip</a>' +
      '<button class="secondary-button px-2.5 py-1.5 text-xs font-semibold" type="button" data-action="copy-url" data-id="' + escapeHtml(service.id) + '">复制 URL</button>' +
      '</div>';
  }
  function serviceRowHtml(service, index, total, reorderable) {
    var content = localizedContent(service);
    var active = service.status === 'active';
    return '<tr class="border-b border-gray-800 align-top">' +
      '<td class="whitespace-nowrap px-3 py-4">' + serviceMoveCell(service, index, total, reorderable) + '</td>' +
      '<td class="px-3 py-4">' + serviceNameCell(service, content) + '</td>' +
      '<td class="max-w-sm px-3 py-4 text-xs">' + serviceIntroCell(service, content) + '</td>' +
      '<td class="px-3 py-4 text-xs">' + serviceDataCell(service) + '</td>' +
      '<td class="whitespace-nowrap px-3 py-4 text-xs ' + (active ? 'text-green-300' : 'text-yellow-300') + '"><span class="flex items-center gap-2"><span class="h-1.5 w-1.5 rounded-full ' + (active ? 'bg-green-300' : 'bg-yellow-300') + '"></span>' + (active ? '使用中' : '已停用') + '</span></td>' +
      '<td class="whitespace-nowrap px-3 py-4 text-xs text-gray-500">' + escapeHtml(formatDate(service.updatedAt)) + '</td>' +
      '<td class="whitespace-nowrap px-3 py-4"><div class="flex flex-col items-stretch gap-2">' +
      '<a class="secondary-button px-3 py-2 text-center text-xs font-semibold" href="service-edit.html?id=' + encodeURIComponent(service.id) + '">编辑</a>' +
      '<button class="secondary-button px-3 py-2 text-xs font-semibold" type="button" data-action="toggle" data-id="' + escapeHtml(service.id) + '">' + (active ? '停用' : '启用') + '</button>' +
      '<button class="secondary-button danger-button px-3 py-2 text-xs font-semibold" type="button" data-action="trash" data-id="' + escapeHtml(service.id) + '">移入回收站</button>' +
      '</div></td>' +
      '</tr>';
  }
  function liveServices() { return services.filter(function (service) { return !service.isDeleted; }); }
  function isListFiltered() {
    return Boolean(byId('service-search').value.trim())
      || byId('status-filter').value !== 'all'
      || byId('type-filter').value !== 'all';
  }
  function sortedLiveServices() {
    return liveServices().sort(function (a, b) {
      // 按显示顺序（sort）升序；同值时保持查询返回的「更新时间倒序」（Array.sort 稳定）。
      return (Number(a.sort) || 0) - (Number(b.sort) || 0);
    });
  }
  // 上移/下移一位：与相邻服务交换位置，然后把整串顺序重写成 0,1,2…
  // 这样即使原有 sort 有重复值或空洞，顺序也不会变得含糊。
  function moveService(service, offset) {
    var ordered = sortedLiveServices();
    var index = ordered.findIndex(function (item) { return item.id === service.id; });
    var target = index + offset;
    if (index < 0 || target < 0 || target >= ordered.length) return Promise.resolve();
    var reordered = ordered.slice();
    reordered[index] = ordered[target];
    reordered[target] = ordered[index];
    var batch = firebase.firestore().batch();
    var changed = 0;
    reordered.forEach(function (item, position) {
      if ((Number(item.sort) || 0) === position) return;
      batch.update(serviceCollection().doc(item.id), { sort: position, updatedAt: serverTimestamp() });
      changed += 1;
    });
    if (!changed) return Promise.resolve();
    return batch.commit().then(function () {
      toast('显示顺序已更新。');
    }).catch(function (error) {
      toast(readableError(error), 'error');
      throw error;
    });
  }
  function updateStats() {
    var live = liveServices();
    byId('stat-total').textContent = live.length;
    byId('stat-active').textContent = live.filter(function (service) { return service.status === 'active'; }).length;
    byId('stat-inactive').textContent = live.filter(function (service) { return service.status === 'inactive'; }).length;
  }
  function renderServices() {
    var search = byId('service-search').value.trim().toLowerCase();
    var status = byId('status-filter').value;
    var type = byId('type-filter').value;
    var live = liveServices();
    // 有搜索/筛选时列表不是完整顺序，禁止调整顺序以免产生歧义。
    var reorderable = !isListFiltered();
    updateStats();
    var filtered = live.filter(function (service) {
      return searchMatches(service, search) && (status === 'all' || service.status === status) && (type === 'all' || service.type === type);
    }).sort(function (a, b) {
      // 按显示顺序（sort）升序；同值时保持查询返回的「更新时间倒序」（Array.sort 稳定）。
      return (Number(a.sort) || 0) - (Number(b.sort) || 0);
    });
    if (!filtered.length) {
      byId('service-list').innerHTML = '';
      byId('list-status').innerHTML = '<div class="rounded-2xl border border-dashed border-gray-700 px-6 py-14 text-center"><div class="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-gray-800 text-gray-400">+</div><h2 class="mt-4 text-base font-semibold">' + (live.length ? '没有符合条件的服务' : '还没有服务') + '</h2><p class="mt-2 text-sm text-gray-500">' + (live.length ? '请调整搜索或筛选条件。' : '创建第一个服务，开始管理服务资料。') + '</p>' + (!live.length ? '<a class="primary-button mt-5 inline-block px-5 py-2.5 text-sm font-bold" href="service-new.html">新建服务</a>' : '') + '</div>';
      return;
    }
    byId('list-status').innerHTML = '<div class="flex flex-wrap items-center justify-between gap-2 text-xs text-gray-500"><p>服务列表 · ' + filtered.length + ' 项</p><p>' + (reorderable ? '按显示顺序（sort）排序 · 用 ↑ ↓ 调整' : '清除搜索/筛选后可调整显示顺序') + '</p></div>';
    byId('service-list').innerHTML = filtered.map(function (service, index) {
      return serviceRowHtml(service, index, filtered.length, reorderable);
    }).join('');
  }
  function subscribeServices(onData, onError) {
    if (unsubscribe) unsubscribe();
    unsubscribe = userServiceQuery().onSnapshot(function (snapshot) {
      services = snapshot.docs.map(function (doc) { return Object.assign({ id: doc.id }, doc.data()); });
      onData();
    }, onError);
  }
  function startServicesListener() {
    renderListLoading();
    subscribeServices(renderServices, renderListError);
  }
  function findService(id) { return services.find(function (service) { return service.id === id; }); }
  function updateService(id, values, message) {
    return serviceCollection().doc(id).update(Object.assign({}, values, { updatedAt: serverTimestamp() })).then(function () { toast(message); }).catch(function (error) { toast(readableError(error), 'error'); throw error; });
  }
  function initServices() {
    showNoticeFromUrl();
    ['service-search', 'status-filter', 'type-filter'].forEach(function (id) { byId(id).addEventListener(id === 'service-search' ? 'input' : 'change', renderServices); });
    byId('list-status').addEventListener('click', function (event) { if (event.target.closest('[data-retry]')) startServicesListener(); });
    byId('service-list').addEventListener('click', async function (event) {
      var button = event.target.closest('[data-action]');
      if (!button) return;
      var service = findService(button.dataset.id);
      if (!service) return toast('服务不存在或已经更新。', 'error');
      var content = localizedContent(service);
      var action = button.dataset.action;
      if (action === 'copy-url') {
        copyText(service.skillZipUrl).then(function () { toast('Skill 下载 URL 已复制。'); }, function () { toast('复制失败，请手动复制。', 'error'); });
        return;
      }
      if (action === 'move-up' || action === 'move-down') {
        if (isListFiltered()) return toast('请先清除搜索或筛选条件，再调整显示顺序。', 'error');
        button.disabled = true;
        try { await moveService(service, action === 'move-up' ? -1 : 1); }
        catch (error) { /* moveService has shown the message */ }
        finally { button.disabled = false; }
        return;
      }
      if (action === 'toggle' && service.status === 'active' && !window.confirm('确定停用“' + content.name + '”吗？')) return;
      if (action === 'trash' && !window.confirm('确定将“' + content.name + '”移入回收站吗？')) return;
      button.disabled = true;
      try {
        if (action === 'toggle') await updateService(service.id, { status: service.status === 'active' ? 'inactive' : 'active' }, service.status === 'active' ? '服务已停用。' : '服务已启用。');
        if (action === 'trash') await updateService(service.id, { status: 'inactive', isDeleted: true, deletedAt: serverTimestamp() }, '服务已移入回收站。');
      } catch (error) { /* updateService has shown the message */ }
      finally { button.disabled = false; }
    });
    startServicesListener();
  }

  function trashCard(service) {
    var content = localizedContent(service);
    return '<article class="service-card flex min-h-64 flex-col rounded-2xl p-5"><div class="flex items-start justify-between gap-3"><span class="rounded-lg bg-opacity-10 px-2.5 py-1.5 text-xs font-semibold ' + typeTone(service.type) + '">' + escapeHtml(typeLabel(service.type)) + '</span><span class="rounded-full bg-gray-700 px-2.5 py-1 text-xs text-gray-300">已删除</span></div><h2 class="mt-5 text-lg font-semibold">' + escapeHtml(content.name) + '</h2><p class="mt-2 text-sm leading-6 text-gray-400">' + escapeHtml(content.description) + '</p>' + serviceDataBlock(service, false) + '<div class="mt-auto pt-5"><p class="mb-3 text-xs text-gray-600">删除于 ' + escapeHtml(formatDate(service.deletedAt)) + '</p><button class="secondary-button w-full px-4 py-2.5 text-sm font-semibold" data-restore="' + escapeHtml(service.id) + '">恢复为停用状态</button></div></article>';
  }
  function renderTrash() {
    var search = byId('trash-search').value.trim().toLowerCase();
    var deleted = services.filter(function (service) { return service.isDeleted && searchMatches(service, search); });
    byId('trash-total').textContent = services.filter(function (service) { return service.isDeleted; }).length;
    if (!deleted.length) {
      byId('trash-list').innerHTML = '';
      byId('list-status').innerHTML = '<div class="rounded-2xl border border-dashed border-gray-700 px-6 py-14 text-center"><div class="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-gray-800 text-gray-400">♲</div><h2 class="mt-4 text-base font-semibold">' + (search ? '没有符合条件的服务' : '回收站为空') + '</h2><p class="mt-2 text-sm text-gray-500">' + (search ? '请调整搜索条件。' : '移入回收站的服务会显示在这里。') + '</p></div>';
      return;
    }
    byId('list-status').innerHTML = '<div class="text-xs text-gray-500">回收站 · ' + deleted.length + ' 项</div>';
    byId('trash-list').innerHTML = deleted.map(trashCard).join('');
  }
  function initTrash() {
    showNoticeFromUrl();
    byId('trash-search').addEventListener('input', renderTrash);
    byId('trash-list').addEventListener('click', async function (event) {
      var button = event.target.closest('[data-restore]');
      if (!button) return;
      button.disabled = true;
      try { await updateService(button.dataset.restore, { status: 'inactive', isDeleted: false, deletedAt: null }, '服务已恢复为停用状态。'); }
      catch (error) { /* updateService has shown the message */ }
      finally { button.disabled = false; }
    });
    byId('list-status').innerHTML = '<div class="flex items-center justify-center gap-3 rounded-2xl border border-gray-800 py-16 text-sm text-gray-400"><span class="h-5 w-5 animate-spin rounded-full border-2 border-gray-700 border-t-green-300"></span>正在加载回收站…</div>';
    unsubscribe = userServiceQuery().onSnapshot(function (snapshot) {
      services = snapshot.docs.map(function (doc) { return Object.assign({ id: doc.id }, doc.data()); });
      renderTrash();
    }, function (error) { showPageError(readableError(error)); });
  }

  function selectLanguage(language) {
    document.querySelectorAll('[data-language]').forEach(function (button) { button.setAttribute('aria-selected', String(button.dataset.language === language)); });
    document.querySelectorAll('[data-language-panel]').forEach(function (panel) { panel.classList.toggle('hidden', panel.dataset.languagePanel !== language); });
  }
  function updateLanguageProgress() {
    var complete = 0;
    LANGUAGES.forEach(function (lang) {
      var name = byId('name-' + lang).value.trim();
      var description = byId('description-' + lang).value.trim();
      var done = Boolean(name && description);
      if (done) complete += 1;
      var dot = document.querySelector('[data-language-dot="' + lang + '"]');
      dot.className = 'ml-1 ' + (done ? 'text-green-300' : name || description ? 'text-yellow-300' : 'text-gray-600');
    });
    byId('language-summary').textContent = '已完成 ' + complete + ' / 4';
    byId('language-summary').className = 'text-xs font-medium ' + (complete ? 'text-green-300' : 'text-gray-500');
  }
  function updateTypeFields() {
    var type = document.querySelector('input[name="service-type"]:checked').value;
    show(byId('google-data-field'), type === 'google_workspace_studio');
    show(byId('deepseek-data-field'), type === 'deepseek_harness');
  }
  function collectServiceData(existing) {
    var data = {
      type: document.querySelector('input[name="service-type"]:checked').value,
      googleDriveUrl: byId('google-drive-url').value.trim(),
      applicationName: byId('application-name').value.trim(),
      info: byId('skill-info') ? byId('skill-info').value.trim() : '',
      sort: 0,
      skillZipUrl: '',
      skillStoragePath: '',
      skillFileName: '',
      skillFileSize: 0,
      skillActiveVersion: 0
    };
    LANGUAGES.forEach(function (lang) {
      var suffix = lang.charAt(0).toUpperCase() + lang.slice(1);
      data['name' + suffix] = byId('name-' + lang).value.trim();
      data['description' + suffix] = byId('description-' + lang).value.trim();
    });
    if (data.type === 'google_workspace_studio') {
      data.applicationName = '';
      data.info = '';
    } else {
      data.googleDriveUrl = '';
      var rawSort = byId('skill-sort') ? String(byId('skill-sort').value).trim() : '';
      data.sort = rawSort === '' ? 0 : Number(rawSort);
      if (!Number.isFinite(data.sort)) data.sort = 0;
      // Carry over whatever version is currently effective. A newly uploaded
      // file only replaces these values once the upload succeeds, so the
      // existing package is never lost when validation or upload fails.
      if (existing && !selectedSkillFile) {
        data.skillZipUrl = existing.skillZipUrl || '';
        data.skillStoragePath = existing.skillStoragePath || '';
        data.skillFileName = existing.skillFileName || '';
        data.skillFileSize = Number(existing.skillFileSize) || 0;
        data.skillActiveVersion = currentVersionNumber(existing);
      }
    }
    return data;
  }
  function validateServiceData(data) {
    var errorBox = byId('service-form-error');
    errorBox.classList.add('hidden');
    document.querySelectorAll('#service-form [aria-invalid="true"]').forEach(function (field) { field.removeAttribute('aria-invalid'); });
    var problems = [];
    var completed = 0;
    var firstProblemLanguage = '';
    LANGUAGES.forEach(function (lang) {
      var suffix = lang.charAt(0).toUpperCase() + lang.slice(1);
      var name = data['name' + suffix];
      var description = data['description' + suffix];
      if (name && description) completed += 1;
      if ((name && !description) || (!name && description)) {
        problems.push('同一语言的服务名称和介绍必须同时填写。');
        if (!firstProblemLanguage) firstProblemLanguage = lang;
      }
    });
    if (!completed) problems.push('请至少完整填写一种语言的服务名称和介绍。');
    if (data.type === 'google_workspace_studio') {
      try {
        var url = new URL(data.googleDriveUrl);
        if (url.protocol !== 'https:' || url.hostname !== 'drive.google.com') throw new Error();
      } catch (error) {
        problems.push('请输入有效的 Google Drive HTTPS URL。');
        byId('google-drive-url').setAttribute('aria-invalid', 'true');
      }
    } else {
      if (!data.applicationName || data.applicationName.length > 120) {
        problems.push('请输入不超过 120 个字符的 Skill 名称（--skill）。');
        byId('application-name').setAttribute('aria-invalid', 'true');
      }
      if (!Number.isInteger(data.sort) || data.sort < 0 || data.sort > 1000000) {
        problems.push('显示顺序需为 0 到 1000000 之间的整数。');
        byId('skill-sort').setAttribute('aria-invalid', 'true');
      }
      if (data.info.length > 2000) {
        problems.push('Skill 说明不能超过 2000 个字符。');
        byId('skill-info').setAttribute('aria-invalid', 'true');
      }
      if (selectedSkillFile) {
        if (!/\.zip$/i.test(selectedSkillFile.name)) {
          problems.push('Skill 包必须是 .zip 文件。');
          byId('skill-zip').setAttribute('aria-invalid', 'true');
        } else if (!selectedSkillFile.size) {
          problems.push('Skill 包内容为空。');
          byId('skill-zip').setAttribute('aria-invalid', 'true');
        } else if (selectedSkillFile.size > SKILL_MAX_BYTES) {
          problems.push('Skill 包不能超过 25 MB。');
          byId('skill-zip').setAttribute('aria-invalid', 'true');
        }
      }
    }
    if (firstProblemLanguage) selectLanguage(firstProblemLanguage);
    if (problems.length) {
      errorBox.textContent = problems.filter(function (value, index, all) { return all.indexOf(value) === index; }).join(' ');
      errorBox.classList.remove('hidden');
      return false;
    }
    return true;
  }
  function setFormLoading(loading) {
    byId('save-service-button').disabled = loading;
    byId('save-service-spinner').classList.toggle('hidden', !loading);
  }
  function setUploadProgress(percent) {
    var wrap = byId('skill-upload-progress');
    if (!wrap) return;
    if (percent == null) { wrap.classList.add('hidden'); return; }
    wrap.classList.remove('hidden');
    byId('skill-upload-bar').style.width = percent + '%';
    byId('skill-upload-text').textContent = '正在上传 Skill 包… ' + percent + '%';
  }
  function uploadSkillZip(file, onProgress) {
    var path = 'skills/' + currentUser.uid + '/' + Date.now() + '-' + safeZipName(file.name);
    var ref = firebase.storage().ref(path);
    return new Promise(function (resolve, reject) {
      var task = ref.put(file, { contentType: 'application/zip' });
      task.on('state_changed', function (snapshot) {
        if (onProgress && snapshot.totalBytes) onProgress(Math.round((snapshot.bytesTransferred / snapshot.totalBytes) * 100));
      }, reject, function () {
        resolve({ path: path, url: skillPublicUrl(path), name: file.name, size: file.size });
      });
    });
  }
  function deleteSkillObject(path) {
    if (!path) return Promise.resolve();
    // Removing the Firestore reference matters; deleting the object is best effort.
    return firebase.storage().ref(path).delete().catch(function () { });
  }
  // ---- Skill version control UI ----------------------------------------
  function skillVersionStatusCell(service, row) {
    return currentVersionNumber(service) === row.version
      ? '<span class="rounded-full bg-green-400 bg-opacity-10 px-2.5 py-1 text-xs font-semibold text-green-300">当前生效</span>'
      : '<span class="rounded-full bg-gray-700 px-2.5 py-1 text-xs text-gray-300">历史版本</span>';
  }
  function skillVersionRowHtml(service, row) {
    var current = currentVersionNumber(service) === row.version;
    var actions = '<div class="flex flex-wrap gap-2">' +
      (current ? '' : '<button class="secondary-button px-2.5 py-1.5 text-xs font-semibold" type="button" data-version-action="activate" data-version="' + row.version + '">设为当前</button>') +
      '<a class="secondary-button px-2.5 py-1.5 text-xs font-semibold" href="' + escapeHtml(row.zipUrl) + '" target="_blank" rel="noopener">下载</a>' +
      '<button class="secondary-button danger-button px-2.5 py-1.5 text-xs font-semibold" type="button" data-version-action="delete" data-version="' + row.version + '">删除</button>' +
      '</div>';
    return '<tr class="border-b border-gray-800 align-top">' +
      '<td class="whitespace-nowrap px-3 py-4 text-sm font-semibold">V' + row.version + (row.legacy ? '<span class="ml-1 text-xs font-normal text-gray-500">既有</span>' : '') + '</td>' +
      '<td class="max-w-xs px-3 py-4 text-xs text-gray-300"><span class="block truncate" title="' + escapeHtml(row.fileName || 'skills.zip') + '">' + escapeHtml(row.fileName || 'skills.zip') + '</span></td>' +
      '<td class="whitespace-nowrap px-3 py-4 text-xs text-gray-400">' + escapeHtml(formatBytes(row.fileSize)) + '</td>' +
      '<td class="whitespace-nowrap px-3 py-4 text-xs text-gray-500">' + escapeHtml(formatDate(row.createdAt)) + '</td>' +
      '<td class="whitespace-nowrap px-3 py-4">' + skillVersionStatusCell(service, row) + '</td>' +
      '<td class="px-3 py-4">' + actions + '</td>' +
      '</tr>';
  }
  function renderSkillVersionTable(service) {
    var list = byId('skill-version-list');
    if (!list) return;
    var rows = service ? skillVersionRows(service) : [];
    var current = service ? currentVersionNumber(service) : 0;
    byId('skill-next-version').textContent = 'V' + (service ? nextVersionNumber(service) : 1);
    byId('skill-active-version').textContent = current ? 'V' + current : '未设定';
    byId('skill-version-summary').textContent = '共 ' + rows.length + ' 个版本';
    if (!rows.length) {
      list.innerHTML = '';
      byId('skill-version-status').innerHTML = '<div class="rounded-xl border border-dashed border-gray-700 px-4 py-6 text-center text-xs text-gray-500">尚无历史版本。上传 zip 保存后会自动记录为 V1 并设为当前生效版本。</div>';
      return;
    }
    byId('skill-version-status').innerHTML = '';
    list.innerHTML = rows.map(function (row) { return skillVersionRowHtml(service, row); }).join('');
  }
  // Persist "this version is the effective one" on the parent document. The
  // mirrored fields move together with the pointer so the documented
  // `doc.skillZipUrl` contract for aiskillsrunner keeps working.
  function activateSkillVersion(service, row) {
    return serviceCollection().doc(service.id).update(Object.assign(
      skillActiveFields(row.version, row),
      { updatedAt: serverTimestamp() }
    ));
  }
  function deleteSkillVersion(service, row) {
    var batch = firebase.firestore().batch();
    if (!row.legacy) batch.delete(skillVersionCollection(service.id).doc(row.id));
    var update = { updatedAt: serverTimestamp() };
    if (currentVersionNumber(service) === row.version) Object.assign(update, emptySkillFields());
    batch.update(serviceCollection().doc(service.id), update);
    return batch.commit().then(function () {
      // The Storage object belongs to this version only, so it goes away with
      // it. Objects of the remaining versions are intentionally kept.
      if (!row.legacy) return deleteSkillObject(row.storagePath);
    });
  }
  function populateForm(service) {
    document.querySelector('input[name="service-type"][value="' + service.type + '"]').checked = true;
    byId('google-drive-url').value = service.googleDriveUrl || '';
    byId('application-name').value = service.applicationName || '';
    byId('skill-info').value = service.info || '';
    // byId('skill-sort').value = String(typeof service.sort === 'number' ? service.sort : 0);
    byId('skill-sort').value = Date.now() % 1000000; // 临时使用时间戳作为默认排序，避免重复
    byId('service-active').checked = service.status === 'active';
    LANGUAGES.forEach(function (lang) {
      var suffix = lang.charAt(0).toUpperCase() + lang.slice(1);
      byId('name-' + lang).value = service['name' + suffix] || '';
      byId('description-' + lang).value = service['description' + suffix] || '';
    });
    updateTypeFields();
    updateLanguageProgress();
    byId('form-loading').classList.add('hidden');
    byId('service-form').classList.remove('hidden');
  }
  function initServiceForm(isEdit) {
    selectedSkillFile = null;
    skillVersions = [];
    document.querySelectorAll('input[name="service-type"]').forEach(function (input) { input.addEventListener('change', updateTypeFields); });
    document.querySelectorAll('[data-language]').forEach(function (button) { button.addEventListener('click', function () { selectLanguage(this.dataset.language); }); });
    document.querySelectorAll('.language-input').forEach(function (input) { input.addEventListener('input', updateLanguageProgress); });
    var zipInput = byId('skill-zip');
    if (zipInput) {
      zipInput.addEventListener('change', function () {
        selectedSkillFile = this.files && this.files[0] ? this.files[0] : null;
        var label = byId('skill-file-selected');
        if (!label) return;
        if (selectedSkillFile) {
          label.textContent = '已选择：' + selectedSkillFile.name + ' · ' + formatBytes(selectedSkillFile.size);
          label.classList.remove('hidden');
        } else {
          label.textContent = '';
          label.classList.add('hidden');
        }
      });
    }
    selectLanguage('zh');
    updateTypeFields();
    updateLanguageProgress();

    var editingService = null;
    // Set once the version list has been read successfully. Saving is blocked
    // while it is false so a failed read can never produce a duplicate version.
    var versionsLoaded = false;

    function renderSkillVersionError(error) {
      var status = byId('skill-version-status');
      if (!status) return;
      var list = byId('skill-version-list');
      if (list) list.innerHTML = '';
      // A denied read almost always means the Firestore rules were not deployed
      // yet; say so instead of leaving the section silently blank.
      var hint = error && error.code === 'permission-denied'
        ? ' 请确认已部署最新的 firestore.rules（firebase deploy --only firestore:rules）。'
        : '';
      status.innerHTML = '<div class="rounded-xl border border-red-400 border-opacity-20 bg-red-400 bg-opacity-5 px-4 py-3 text-xs leading-5 text-red-200">Skill 版本列表加载失败：' + escapeHtml(readableError(error)) + escapeHtml(hint) + '<button class="secondary-button ml-3 px-2.5 py-1.5 text-xs font-semibold" type="button" data-retry-versions>重试</button></div>';
    }

    function loadVersionSection() {
      if (!editingService) return Promise.resolve();
      return loadSkillVersions(editingService.id).then(function () {
        versionsLoaded = true;
        renderSkillVersionTable(editingService);
      }).catch(function (error) {
        versionsLoaded = false;
        renderSkillVersionError(error);
      });
    }

    // Re-read the service document and its versions after an in-page version
    // action, so a later "save" never writes back stale mirror fields.
    function refreshVersionSection() {
      if (!editingService) return Promise.resolve();
      return serviceCollection().doc(editingService.id).get().then(function (doc) {
        if (doc.exists) editingService = Object.assign({ id: doc.id }, doc.data());
        return loadVersionSection();
      });
    }

    var versionStatus = byId('skill-version-status');
    if (versionStatus) {
      versionStatus.addEventListener('click', function (event) {
        if (!event.target.closest('[data-retry-versions]')) return;
        loadVersionSection();
      });
    }

    var versionList = byId('skill-version-list');
    if (versionList) {
      versionList.addEventListener('click', async function (event) {
        var button = event.target.closest('[data-version-action]');
        if (!button || !editingService) return;
        var row = skillVersionRows(editingService).filter(function (item) {
          return item.version === Number(button.dataset.version);
        })[0];
        if (!row) return toast('该版本已不存在，请刷新页面后重试。', 'error');
        var action = button.dataset.versionAction;
        if (action === 'delete' && !window.confirm('确定删除 V' + row.version + ' 吗？该操作无法撤销。')) return;
        button.disabled = true;
        try {
          if (action === 'activate') {
            await activateSkillVersion(editingService, row);
            await refreshVersionSection();
            toast('V' + row.version + ' 已设为当前生效版本。');
          } else if (action === 'delete') {
            var wasCurrent = currentVersionNumber(editingService) === row.version;
            await deleteSkillVersion(editingService, row);
            await refreshVersionSection();
            toast(wasCurrent ? 'V' + row.version + ' 已删除，请重新设定当前生效版本。' : 'V' + row.version + ' 已删除。');
          }
        } catch (error) {
          toast(readableError(error), 'error');
        } finally {
          button.disabled = false;
        }
      });
    }
    var loadPromise = Promise.resolve();
    if (isEdit) {
      var id = new URLSearchParams(window.location.search).get('id');
      if (!id) {
        showPageError('缺少服务 ID。');
        byId('form-loading').classList.add('hidden');
        return;
      }
      loadPromise = serviceCollection().doc(id).get().then(function (doc) {
        if (!doc.exists) throw { code: 'not-found' };
        editingService = Object.assign({ id: doc.id }, doc.data());
        if (editingService.userUid !== currentUser.uid || editingService.isDeleted) throw { code: 'permission-denied' };
        populateForm(editingService);
        // Versions load separately: a failure here is reported inside the
        // version section and must not hide the rest of the form.
        return loadVersionSection();
      }).catch(function (error) {
        byId('form-loading').classList.add('hidden');
        showPageError(readableError(error));
      });
    } else {
      byId('form-loading').classList.add('hidden');
      byId('service-form').classList.remove('hidden');
    }

    loadPromise.then(function () {
      if (isEdit && !editingService) return;
      byId('service-form').addEventListener('submit', function (event) {
        event.preventDefault();
        var data = collectServiceData(editingService);
        if (!validateServiceData(data)) return;
        // Without the version list we cannot know the next version number, and
        // guessing would collide with an existing version document.
        if (isEdit && data.type === 'deepseek_harness' && !versionsLoaded) {
          showPageError('Skill 版本列表尚未加载成功，已阻止保存以免覆盖版本记录。请点击版本区域的「重试」，或刷新页面后重试。');
          return;
        }
        setFormLoading(true);
        var uploadedObject = null;
        var uploadPromise = data.type === 'deepseek_harness' && selectedSkillFile
          ? uploadSkillZip(selectedSkillFile, setUploadProgress)
          : Promise.resolve(null);
        uploadPromise.then(function (uploaded) {
          uploadedObject = uploaded;
          setUploadProgress(null);
          return isEdit ? saveEditedService(editingService, data, uploaded) : saveNewService(data, uploaded);
        }).then(function () {
          window.location.assign('services.html?notice=' + (isEdit ? 'updated' : 'created'));
        }).catch(function (error) {
          setUploadProgress(null);
          showPageError(readableError(error));
          setFormLoading(false);
          // The document was not saved, so drop the freshly uploaded object
          // instead of leaving an orphan in Storage.
          if (uploadedObject) deleteSkillObject(uploadedObject.path);
        });
      });
    });
  }

  // Saving an existing service updates the parent document and, when a new zip
  // was uploaded, records it as the next immutable version. Both happen in one
  // atomic batch so the active pointer can never drift from the version list.
  function saveEditedService(service, data, uploaded) {
    var batch = firebase.firestore().batch();
    // First save of a package uploaded before version control existed: turn the
    // mirrored fields into a real V1 document so it shows up in the table.
    var seedLegacy = isSkillService(service) && !hasStoredVersions() && hasSkillFile(service);
    var seededVersion = 0;
    if (seedLegacy) {
      seededVersion = skillVersionNumber(service.skillActiveVersion) || 1;
      batch.set(skillVersionCollection(service.id).doc(String(seededVersion)), {
        version: seededVersion,
        zipUrl: service.skillZipUrl,
        storagePath: service.skillStoragePath || '',
        fileName: service.skillFileName || '',
        fileSize: Number(service.skillFileSize) || 0,
        createdAt: serverTimestamp(),
        userUid: currentUser.uid
      });
    }
    if (uploaded) {
      var next = nextVersionNumber(service);
      // `WriteBatch` in the v8 compat SDK only offers set/update/delete, so we
      // use `set` on a fresh document ID. Should the ID ever collide,
      // firestore.rules rejects the overwrite (`allow update: if false` on the
      // subcollection), so a version can never be silently replaced.
      batch.set(skillVersionCollection(service.id).doc(String(next)), skillVersionPayload(next, uploaded));
      Object.assign(data, skillActiveFields(next, {
        zipUrl: uploaded.url, storagePath: uploaded.path, fileName: uploaded.name, fileSize: uploaded.size
      }));
    } else if (seedLegacy) {
      data.skillActiveVersion = seededVersion;
    }
    data.status = byId('service-active').checked ? 'active' : 'inactive';
    data.updatedAt = serverTimestamp();
    batch.update(serviceCollection().doc(service.id), data);
    return batch.commit();
  }

  function saveNewService(data, uploaded) {
    data.userUid = currentUser.uid;
    data.status = 'active';
    data.isDeleted = false;
    data.createdAt = serverTimestamp();
    data.updatedAt = serverTimestamp();
    data.deletedAt = null;
    if (uploaded) {
      Object.assign(data, skillActiveFields(1, {
        zipUrl: uploaded.url, storagePath: uploaded.path, fileName: uploaded.name, fileSize: uploaded.size
      }));
    }
    return serviceCollection().add(data).then(function (ref) {
      if (!uploaded) return null;
      // The parent document now exists, so the subcollection rules can resolve
      // its owner. A failure here still leaves a working service: the edit page
      // falls back to showing the mirrored package as V1.
      return ref.collection('skillVersions').doc('1').set(skillVersionPayload(1, uploaded));
    });
  }

  function skillRow(service) {
    var content = localizedContent(service);
    var url = service.skillZipUrl || '';
    var versionLabel = activeVersionLabel(service);
    return '<tr class="border-b border-gray-800 align-top">' +
      '<td class="px-3 py-4 text-xs text-gray-500">' + escapeHtml(String(service.sort == null ? 0 : service.sort)) + '</td>' +
      '<td class="px-3 py-4"><p class="text-sm font-semibold">' + escapeHtml(service.applicationName || '—') + '</p><p class="mt-1 text-xs text-gray-500">' + escapeHtml(content.name) + '</p></td>' +
      '<td class="max-w-xs px-3 py-4 text-xs leading-5 text-gray-400">' + escapeHtml(service.info || '—') + '</td>' +
      '<td class="px-3 py-4 text-xs text-gray-400">' + (url ? (versionLabel ? '<span class="rounded bg-green-400 bg-opacity-10 px-1.5 py-0.5 text-xs font-semibold text-green-300">' + escapeHtml(versionLabel) + '</span> ' : '') + escapeHtml(service.skillFileName || 'skills.zip') + '<br><span class="text-gray-600">' + escapeHtml(formatBytes(service.skillFileSize)) + '</span>' : '<span class="text-yellow-300">未上传</span>') + '</td>' +
      '<td class="px-3 py-4">' + (url ? '<div class="flex flex-wrap gap-2"><a class="secondary-button px-2.5 py-1.5 text-xs font-semibold" href="' + escapeHtml(url) + '" target="_blank" rel="noopener">下载</a><button class="secondary-button px-2.5 py-1.5 text-xs font-semibold" type="button" data-copy-skill="' + escapeHtml(service.id) + '">复制 URL</button></div>' : '—') + '</td>' +
      '<td class="px-3 py-4 text-xs ' + (service.status === 'active' ? 'text-green-300' : 'text-yellow-300') + '">' + (service.status === 'active' ? '使用中' : '已停用') + '</td>' +
      '<td class="whitespace-nowrap px-3 py-4 text-xs text-gray-500">' + escapeHtml(formatDate(service.updatedAt)) + '</td>' +
      '<td class="px-3 py-4"><a class="secondary-button px-2.5 py-1.5 text-xs font-semibold" href="service-edit.html?id=' + encodeURIComponent(service.id) + '">编辑</a></td>' +
      '</tr>';
  }
  function renderSkills() {
    var search = byId('skill-search').value.trim().toLowerCase();
    var live = services.filter(function (service) { return !service.isDeleted && isSkillService(service); });
    var rows = live.filter(function (service) { return searchMatches(service, search); }).sort(function (a, b) {
      var left = Number(a.sort) || 0;
      var right = Number(b.sort) || 0;
      if (left !== right) return left - right;
      return String(a.applicationName || '').localeCompare(String(b.applicationName || ''));
    });
    byId('stat-skill-total').textContent = live.length;
    byId('stat-skill-ready').textContent = live.filter(hasSkillFile).length;
    byId('stat-skill-missing').textContent = live.filter(function (service) { return !hasSkillFile(service); }).length;
    if (!rows.length) {
      byId('skill-list').innerHTML = '';
      byId('list-status').innerHTML = '<div class="rounded-2xl border border-dashed border-gray-700 px-6 py-14 text-center"><div class="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-gray-800 text-gray-400">/</div><h2 class="mt-4 text-base font-semibold">' + (live.length ? '没有符合条件的 Skill' : '还没有上传 Skill') + '</h2><p class="mt-2 text-sm text-gray-500">' + (live.length ? '请调整搜索条件。' : '在新建服务中选择 DeepSeek Harness 并上传 .zip 包。') + '</p>' + (!live.length ? '<a class="primary-button mt-5 inline-block px-5 py-2.5 text-sm font-bold" href="service-new.html">新建 Skill 服务</a>' : '') + '</div>';
      return;
    }
    byId('list-status').innerHTML = '<div class="flex items-center justify-between text-xs text-gray-500"><p>Skills · ' + rows.length + ' 项</p><p>按显示顺序排序</p></div>';
    byId('skill-list').innerHTML = rows.map(skillRow).join('');
  }
  function initSkills() {
    showNoticeFromUrl();
    byId('skill-search').addEventListener('input', renderSkills);
    byId('skill-list').addEventListener('click', function (event) {
      var button = event.target.closest('[data-copy-skill]');
      if (!button) return;
      var service = findService(button.dataset.copySkill);
      if (!service || !service.skillZipUrl) return;
      copyText(service.skillZipUrl).then(function () { toast('Skill 下载 URL 已复制。'); }, function () { toast('复制失败，请手动复制。', 'error'); });
    });
    byId('list-status').innerHTML = '<div class="flex items-center justify-center gap-3 rounded-2xl border border-gray-800 py-16 text-sm text-gray-400"><span class="h-5 w-5 animate-spin rounded-full border-2 border-gray-700 border-t-green-300"></span>正在加载 Skills…</div>';
    subscribeServices(renderSkills, function (error) { showPageError(readableError(error)); });
  }

  document.addEventListener('DOMContentLoaded', function () {
    var year = byId('current-year');
    if (year) year.textContent = new Date().getFullYear();
    document.body.dataset.build = BUILD;
    console.info('[AI Service CMS] build ' + BUILD);
    try {
      firebase.auth().onAuthStateChanged(function (user) {
        if (page === 'login') {
          if (user) window.location.replace('services.html');
          else {
            byId('auth-loading').classList.add('hidden');
            byId('login-content').classList.remove('hidden');
            initLogin();
            window.setTimeout(function () { byId('email').focus(); }, 0);
          }
          return;
        }
        if (!user) {
          window.location.replace('index.html');
          return;
        }
        currentUser = user;
        setUserHeader(user);
        byId('page-loading').classList.add('hidden');
        byId('protected-content').classList.remove('hidden');
        if (page === 'services') initServices();
        if (page === 'trash') initTrash();
        if (page === 'skills') initSkills();
        if (page === 'service-new') initServiceForm(false);
        if (page === 'service-edit') initServiceForm(true);
      });
    } catch (error) {
      var target = byId('auth-error') || byId('page-error');
      if (target) {
        target.textContent = 'Firebase 初始化失败。请通过 Firebase Hosting 或 Emulator 打开此页面。';
        target.classList.remove('hidden');
      }
      if (byId('auth-loading')) byId('auth-loading').classList.add('hidden');
      if (byId('login-content')) byId('login-content').classList.remove('hidden');
      if (byId('page-loading')) byId('page-loading').classList.add('hidden');
      if (byId('protected-content')) byId('protected-content').classList.remove('hidden');
    }
  });
})();
