/* =========================================================
   GB COLORS — CLIENT APPLICATION CONTROLLER
   Auth, Live Arena, Payment Center, Notifications & Drawer
   ========================================================= */

(function () {
  'use strict';

  const AUTH_TOKEN_KEY = 'gb_auth_token';
  const SETTINGS_KEY = 'gb_user_settings_v1';

  // State
  let authToken = localStorage.getItem(AUTH_TOKEN_KEY) || '';
  let currentUser = null;
  let isAdminSession = false;
  let currentAdminInfo = null;
  let latestAdminAnalytics = null;
  let currentGameMode = 'CLASSIC'; // 'CLASSIC' | 'SAFE'
  let histCurrentPage = 1;
  const HIST_PAGE_SIZE = 10;
  let attachedDepositProofBase64 = null;
  let selectedWithdrawMethod = 'UPI';
  let selectedWithdrawUpiApp = 'Any UPI';
  let socket = null;
  let paymentConfig = {
    razorpayEnabled: false,
    razorpayKeyId: '',
    upiId: 'gbpayment0@nyes',
    upiPayeeName: 'GB Colors',
    minDeposit: 10,
    maxDeposit: 50000,
    minWithdraw: 10,
    maxWithdraw: 50000
  };
  let gameState = {
    period: '--------',
    timeLeft: 30,
    status: 'BETTING',
    cycleDurationSeconds: 30,
    betCloseSeconds: 5,
    history: []
  };
  let pendingBet = { type: null, choice: null, unit: 10, mult: 1 };
  let pendingCancelBetId = null;

  // Settings
  let userSettings = loadSettings();

  function loadSettings() {
    try {
      const raw = JSON.parse(localStorage.getItem(SETTINGS_KEY) || '{}');
      return {
        sfx: raw.sfx !== false,
        bgm: Boolean(raw.bgm),
        vibrate: raw.vibrate !== false
      };
    } catch {
      return { sfx: true, bgm: false, vibrate: true };
    }
  }

  function saveSettings() {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(userSettings));
  }

  // Utilities
  function $(id) {
    return document.getElementById(id);
  }

  function escapeHtml(str) {
    return String(str == null ? '' : str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function formatINR(val) {
    const num = Number(val || 0);
    return '₹' + num.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  function formatShortINR(val) {
    const num = Number(val || 0);
    return Number.isInteger(num)
      ? '₹' + num.toLocaleString('en-IN')
      : formatINR(num);
  }

  function formatTime(iso) {
    if (!iso) return '—';
    try {
      const d = new Date(iso);
      return d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    } catch {
      return String(iso);
    }
  }

  function getApiUrl(path) {
    return typeof window.apiUrl === 'function' ? window.apiUrl(path) : path;
  }

  function getWsBaseUrl() {
    return typeof window.getSocketUrl === 'function' ? window.getSocketUrl() : window.location.origin;
  }

  function authHeaders(extra) {
    const h = { 'Content-Type': 'application/json' };
    if (authToken) h.Authorization = 'Bearer ' + authToken;
    return Object.assign(h, extra || {});
  }

  async function apiFetch(path, options) {
    const opts = Object.assign({ headers: authHeaders() }, options || {});
    if (options && options.headers) {
      opts.headers = Object.assign(authHeaders(), options.headers);
    }
    const res = await fetch(getApiUrl(path), opts);
    let data = {};
    try {
      data = await res.json();
    } catch {
      data = {};
    }
    return { ok: res.ok, status: res.status, data };
  }

  // Toast
  let toastTimer = null;
  function showToast(msg, type) {
    const el = $('toast');
    if (!el) return;
    el.textContent = msg;
    el.className = 'toast-banner show ' + (type || 'info');
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      el.classList.remove('show');
    }, 3400);
  }
  window.showToast = showToast;

  // Global Password Toggle (called by inline onclick in index.html)
  window.togglePassVis = function (inputId, btn) {
    const input = $(inputId);
    if (!input) return;
    const isPass = input.type === 'password';
    input.type = isPass ? 'text' : 'password';
    if (btn) btn.textContent = isPass ? '🙈' : '👁';
  };

  // =========================================================
  // SOUND ENGINE (Web Audio API)
  // =========================================================
  const SoundEngine = {
    ctx: null,
    bgmOsc: null,
    bgmGain: null,
    ensureCtx() {
      if (!this.ctx) {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (Ctx) this.ctx = new Ctx();
      }
      if (this.ctx && this.ctx.state === 'suspended') {
        this.ctx.resume().catch(() => {});
      }
      return this.ctx;
    },
    tone(freq, dur, type, vol) {
      if (!userSettings.sfx) return;
      const ctx = this.ensureCtx();
      if (!ctx) return;
      const o = ctx.createOscillator();
      const g = ctx.createGain();
      o.type = type || 'sine';
      o.frequency.value = freq;
      g.gain.setValueAtTime(vol || 0.06, ctx.currentTime);
      g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + dur);
      o.connect(g);
      g.connect(ctx.destination);
      o.start();
      o.stop(ctx.currentTime + dur);
    },
    click() {
      this.tone(620, 0.05, 'triangle', 0.04);
      vibrate(15);
    },
    tick() {
      this.tone(880, 0.06, 'sine', 0.035);
    },
    win() {
      if (!userSettings.sfx) return;
      [523, 659, 784, 1046].forEach((f, idx) => {
        setTimeout(() => this.tone(f, 0.16, 'triangle', 0.07), idx * 75);
      });
      vibrate([40, 30, 60]);
    },
    notify() {
      if (!userSettings.sfx) return;
      this.tone(740, 0.11, 'sine', 0.06);
      setTimeout(() => this.tone(987, 0.18, 'sine', 0.07), 110);
      vibrate([30, 25, 40]);
    },
    loss() {
      this.tone(240, 0.22, 'sawtooth', 0.04);
    },
    syncBgm() {
      if (!userSettings.bgm) {
        if (this.bgmGain) {
          this.bgmGain.gain.value = 0;
        }
        return;
      }
      const ctx = this.ensureCtx();
      if (!ctx) return;
      if (!this.bgmOsc) {
        this.bgmOsc = ctx.createOscillator();
        this.bgmGain = ctx.createGain();
        this.bgmOsc.type = 'sine';
        this.bgmOsc.frequency.value = 146.83;
        this.bgmGain.gain.value = 0.012;
        this.bgmOsc.connect(this.bgmGain);
        this.bgmGain.connect(ctx.destination);
        this.bgmOsc.start();
      } else {
        this.bgmGain.gain.value = 0.012;
      }
    }
  };

  function vibrate(pattern) {
    if (userSettings.vibrate && navigator.vibrate) {
      try { navigator.vibrate(pattern); } catch {}
    }
  }

  // =========================================================
  // LIVE NON-REPEATING TICKER ENGINE (Synchronized with Round Outcome)
  // =========================================================
  const LiveTickerEngine = (function () {
    const DUMMY_NAMES = [
      'Aarav_91', 'Karthik_K', 'Neha_Sharma', 'VikramR', 'Divya_M', 'Rohan_24',
      'SnehaP', 'Arjun_VIP', 'PriyaNair', 'RahulDev', 'Manish_77', 'AnanyaS',
      'Surya_TN', 'Meera_V', 'Deepak08', 'Kavya_R', 'HarishB', 'Nithya_K',
      'Kiran_G', 'PoojaM', 'Siddharth', 'Riya_99', 'Vivek_C', 'Lakshmi_S',
      'Varun_X', 'SwathiJ', 'Gokul_P', 'ShrutiD', 'Ajay_Star', 'Bhavana_T'
    ];
    const DUMMY_AMOUNTS = [180, 200, 360, 450, 500, 720, 900, 1000, 1450, 1800, 2250, 3600, 4500, 9000];

    let realEvents = [];
    let realBag = [];
    let dummyBag = [];
    const recentTexts = [];
    let rotationTimer = null;
    let latestRoundResult = null;

    function setLatestRoundResult(round) {
      if (!round) return;
      let colors = [];
      if (Array.isArray(round.colors)) {
        colors = round.colors.map((c) => String(c).trim().toUpperCase());
      } else if (typeof round.colors === 'string') {
        colors = round.colors.split(',').map((c) => c.trim().toUpperCase()).filter(Boolean);
      }
      latestRoundResult = {
        number: round.number != null ? Number(round.number) : (round.winningNumber != null ? Number(round.winningNumber) : null),
        colors: colors,
        size: round.size ? String(round.size).trim().toUpperCase() : null,
        safeColor: round.safeResultColor ? String(round.safeResultColor).trim().toUpperCase() : null,
        poolColor: round.poolResultColor ? String(round.poolResultColor).trim().toUpperCase() : null
      };
      dummyBag = [];
    }

    function getSynchronizedTargets() {
      const targets = [];
      if (latestRoundResult) {
        if (latestRoundResult.number != null) {
          targets.push('Number ' + latestRoundResult.number);
        }
        if (latestRoundResult.colors && latestRoundResult.colors.length) {
          latestRoundResult.colors.forEach((col) => {
            targets.push(col);
          });
        }
        if (latestRoundResult.safeColor) {
          targets.push('Safe ' + latestRoundResult.safeColor);
        }
        if (latestRoundResult.poolColor) {
          targets.push('Pool ' + latestRoundResult.poolColor);
        }
        if (latestRoundResult.size) {
          targets.push(latestRoundResult.size);
        }
      }
      if (targets.length === 0) {
        return ['GREEN', 'BIG', 'Number 7', 'Safe GREEN', 'Pool GREEN'];
      }
      return targets;
    }

    function shuffle(arr) {
      const copy = arr.slice();
      for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        const tmp = copy[i];
        copy[i] = copy[j];
        copy[j] = tmp;
      }
      return copy;
    }

    function buildDummyDeck() {
      const items = [];
      const names = shuffle(DUMMY_NAMES);
      const targets = shuffle(getSynchronizedTargets());
      const amts = shuffle(DUMMY_AMOUNTS);
      for (let i = 0; i < names.length; i++) {
        const name = names[i];
        const amt = amts[(i * 3 + Math.floor(Math.random() * amts.length)) % amts.length];
        const target = targets[(i * 5 + Math.floor(Math.random() * targets.length)) % targets.length];
        const text = (i % 4 === 0)
          ? `💸 ${name} withdrew ${formatShortINR(amt * 2)} to UPI (Verified)`
          : `🏆 ${name} won ${formatShortINR(amt)} on ${target}`;
        items.push({ id: 'dummy_' + Date.now() + '_' + i + '_' + Math.random().toString(36).slice(2, 6), text, isReal: false });
      }
      return shuffle(items);
    }

    function rememberText(text) {
      recentTexts.push(text);
      if (recentTexts.length > 22) recentTexts.shift();
    }

    function drawBatch(count) {
      const batch = [];
      const chosenThisBatch = new Set();

      // Priority 1: Pick unseen random REAL wins/withdrawals first
      if (realEvents.length > 0 && realBag.length === 0) {
        realBag = shuffle(realEvents);
      }
      let guard = 0;
      while (batch.length < count && realBag.length > 0 && guard < 30) {
        guard++;
        const item = realBag.pop();
        if (!item || !item.text) continue;
        if (chosenThisBatch.has(item.text)) continue;
        if (recentTexts.includes(item.text) && realEvents.length > 4) continue;
        chosenThisBatch.add(item.text);
        rememberText(item.text);
        batch.push(item);
      }

      // Priority 2: Fill remainder with non-repeating random DUMMY wins synchronized with round result
      let dGuard = 0;
      while (batch.length < count && dGuard < 80) {
        dGuard++;
        if (dummyBag.length === 0) {
          dummyBag = buildDummyDeck();
        }
        const item = dummyBag.pop();
        if (!item || chosenThisBatch.has(item.text) || recentTexts.includes(item.text)) {
          continue;
        }
        chosenThisBatch.add(item.text);
        rememberText(item.text);
        batch.push(item);
      }

      return shuffle(batch);
    }

    function renderBatch() {
      const track = $('liveTickerTrack');
      if (!track) return;
      const batch = drawBatch(8);
      track.innerHTML = batch.map((ev) => {
        const cls = ev.isReal ? 'ticker-item real-win' : 'ticker-item';
        return `<span class="${cls}">${escapeHtml(ev.text)}</span>`;
      }).join('');

      // Restart smooth right-to-left sweep animation with fresh batch each cycle
      track.style.animation = 'none';
      void track.offsetWidth;
      track.style.animation = 'gbTickerSweep 28s linear 1 forwards';
    }

    function addEvents(events) {
      if (!Array.isArray(events) || !events.length) return;
      const seenIds = new Set(realEvents.map((e) => e.id || e.text));
      events.forEach((ev) => {
        if (!ev || !ev.text) return;
        const key = ev.id || ev.text;
        if (!seenIds.has(key)) {
          seenIds.add(key);
          const item = { id: key, text: ev.text, isReal: true };
          realEvents.push(item);
          realBag.push(item);
        }
      });
      if (realEvents.length > 40) {
        realEvents = realEvents.slice(-40);
      }
      realBag = shuffle(realBag);
    }

    function start() {
      const track = $('liveTickerTrack');
      if (track && !track.dataset.boundAnim) {
        track.dataset.boundAnim = '1';
        track.addEventListener('animationend', () => {
          renderBatch();
        });
      }
      renderBatch();
      if (rotationTimer) clearInterval(rotationTimer);
      rotationTimer = setInterval(() => {
        if (!document.hidden && $('gameAppRoot') && $('gameAppRoot').style.display !== 'none') {
          renderBatch();
        }
      }, 28500);
    }

    function stop() {
      if (rotationTimer) {
        clearInterval(rotationTimer);
        rotationTimer = null;
      }
    }

    return { addEvents, start, stop, refresh: renderBatch, setLatestRoundResult };
  })();

  // USER SIMULATED LIVE BET POOL ENGINE
  // =========================================================
  const UserSimulatedPoolEngine = (function () {
    let currentPeriod = '';
    let pools = {};

    function hashPeriod(periodStr) {
      let h = 0;
      const s = String(periodStr || '1');
      for (let i = 0; i < s.length; i++) {
        h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
      }
      return Math.abs(h);
    }

    function resetForPeriod(periodStr) {
      currentPeriod = String(periodStr || '');
      pools = {
        'GREEN': 0,
        'RED': 0,
        'VIOLET': 0,
        'BIG': 0,
        'SMALL': 0,
        'SAFE-GREEN': 0,
        'SAFE-RED': 0,
        'POOL-GREEN': 0,
        'POOL-RED': 0
      };
      for (let i = 0; i <= 9; i++) {
        pools[String(i)] = 0;
      }
    }

    function tick(timeLeft) {
      if (timeLeft > 5) {
        const keys = Object.keys(pools);
        const numPicks = 2 + Math.floor(Math.random() * 3);
        const increments = [100, 200, 500, 1000, 1500];
        for (let k = 0; k < numPicks; k++) {
          const key = keys[Math.floor(Math.random() * keys.length)];
          const inc = increments[Math.floor(Math.random() * increments.length)];
          pools[key] = (pools[key] || 0) + inc;
        }
      }
    }

    function getPool(key) {
      return pools[key] || 0;
    }

    return {
      resetForPeriod,
      tick,
      getPool
    };
  })();

  // =========================================================
  // SIDE DRAWER & MULTI-PAGE NAVIGATION (PC & MOBILE)
  // =========================================================
  function openUserDrawer() {
    const drawer = $('userSideDrawer');
    const backdrop = $('userDrawerBackdrop');
    if (drawer) {
      drawer.classList.add('open');
      drawer.setAttribute('aria-hidden', 'false');
    }
    if (backdrop) backdrop.classList.add('active');
  }

  function closeUserDrawer() {
    const drawer = $('userSideDrawer');
    const backdrop = $('userDrawerBackdrop');
    if (drawer) {
      drawer.classList.remove('open');
      drawer.setAttribute('aria-hidden', 'true');
    }
    if (backdrop) backdrop.classList.remove('active');
  }

  function switchPaymentSubTab(tabName) {
    let target = tabName;
    if (target === 'status') {
      const latestW = Number((((currentUser && currentUser.withdrawRequests) || [])[0] || {}).requestedAt || 0);
      const latestD = Number((((currentUser && currentUser.depositRequests) || [])[0] || {}).requestedAt || 0);
      target = latestW > latestD ? 'withdraw' : 'recharge';
    }
    target = target === 'withdraw' ? 'withdraw' : 'recharge';
    document.querySelectorAll('.pay-subtab-btn').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.paytab === target);
    });
    ['recharge', 'withdraw'].forEach((t) => {
      const panel = $('payTab-' + t);
      if (panel) {
        panel.style.display = (t === target) ? '' : 'none';
        panel.classList.toggle('active', t === target);
      }
    });
    renderPaymentStatuses();
  }

  async function refreshCurrentUser() {
    if (!authToken) return;
    const res = await apiFetch('/api/auth/me', { method: 'GET' });
    if (res.ok && res.data && res.data.user) {
      const keepActiveBets = currentUser ? currentUser.activeBets : [];
      currentUser = Object.assign({}, currentUser || {}, res.data.user);
      if (!res.data.user.activeBets && keepActiveBets) currentUser.activeBets = keepActiveBets;
      renderUserSummary();
      renderPaymentStatuses();
    }
  }

  function navigateToView(viewName, subOpt) {
    if (!isAdminSession && viewName === 'admin') {
      viewName = 'game';
    }
    if (isAdminSession && viewName === 'payment') {
      return navigateToView('admin', 'admWithdrawTab');
    }
    if (viewName === 'settings') viewName = 'profile';
    if (viewName === 'history') viewName = 'game';

    const allowedViews = isAdminSession
      ? ['game', 'admin', 'profile', 'rules']
      : ['game', 'payment', 'profile', 'rules'];
    const target = allowedViews.includes(viewName) ? viewName : 'game';

    ['game', 'payment', 'profile', 'rules', 'admin'].forEach((v) => {
      const el = $('view-' + v);
      if (el) {
        const show = (v === target);
        el.style.display = show ? 'block' : 'none';
        el.classList.toggle('active', show);
      }
    });

    document.querySelectorAll('#userSideDrawer .drawer-nav-item[data-view]').forEach((btn) => {
      const matchView = btn.dataset.view === target;
      const matchTab = target === 'admin' && subOpt ? btn.dataset.admtab === subOpt : true;
      btn.classList.toggle('active', matchView && matchTab);
    });
    if ($('adminHeaderToggleBtn')) {
      $('adminHeaderToggleBtn').classList.toggle('active', target === 'admin');
    }
    closeUserDrawer();

    if (target === 'payment') {
      loadPaymentConfig();
      refreshCurrentUser();
      if (subOpt) switchPaymentSubTab(subOpt);
      renderPaymentStatuses();
    } else if (target === 'profile') {
      renderProfileView();
    } else if (target === 'admin' && typeof window.switchAdminTab === 'function') {
      window.switchAdminTab(subOpt || 'admWithdrawTab');
    }
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  window.gbNavToGame = () => navigateToView('game');

  // =========================================================
  // NOTIFICATIONS (LIVE + LOGIN-FIRST QUEUE)
  // =========================================================
  let sessionNotifications = [];
  let popupQueue = [];
  let activePopupNotif = null;

  function prioritizeNotifications(list) {
    const weight = {
      WITHDRAW_PAID: 1,
      WITHDRAW_REJECTED: 2,
      DEPOSIT_APPROVED: 3,
      DEPOSIT_REJECTED: 4,
      ADMIN_DIRECT: 5,
      ADMIN_BROADCAST: 6,
      INFO: 7
    };
    return (list || []).slice().sort((a, b) => {
      const wa = weight[a.type] || 9;
      const wb = weight[b.type] || 9;
      if (wa !== wb) return wa - wb;
      return new Date(b.createdAt || 0) - new Date(a.createdAt || 0);
    });
  }

  function updateNotificationBadges() {
    const unreadCount = sessionNotifications.filter((n) => !n.isRead).length;
    [$('notifBellBadge'), $('drawerNotifBadge')].forEach((badge) => {
      if (!badge) return;
      badge.textContent = String(unreadCount);
      badge.style.display = unreadCount > 0 ? '' : 'none';
    });
    renderNotificationsInbox();
  }

  function renderNotificationsInbox() {
    const container = $('notificationsHistoryList');
    if (!container) return;
    if (!sessionNotifications.length) {
      container.innerHTML = '<div class="empty-state-box">No notifications yet. Deposit and withdrawal updates appear here.</div>';
      return;
    }
    container.innerHTML = sessionNotifications.map((n) => {
      const isUnread = !n.isRead;
      return `
        <div class="status-card ${isUnread ? 'notif-unread' : ''}">
          <div class="sc-top">
            <strong>${escapeHtml(n.title || n.type)}</strong>
            <span class="sc-time">${escapeHtml(formatTime(n.createdAt))}</span>
          </div>
          <p class="sc-msg">${escapeHtml(n.message || '')}</p>
        </div>
      `;
    }).join('');
  }

  function showNextQueuedPopup() {
    if (activePopupNotif || popupQueue.length === 0) return;
    const notif = popupQueue.shift();
    activePopupNotif = notif;

    const iconMap = {
      WITHDRAW_PAID: '💸',
      WITHDRAW_REJECTED: '⚠️',
      DEPOSIT_APPROVED: '💳',
      DEPOSIT_REJECTED: '❌',
      ADMIN_DIRECT: '📩',
      ADMIN_BROADCAST: '📣',
      INFO: '🔔'
    };

    if ($('notifModalIcon')) $('notifModalIcon').textContent = iconMap[notif.type] || '🔔';
    if ($('notifModalTitle')) $('notifModalTitle').textContent = notif.title || 'Account Update';
    if ($('notifModalMessage')) $('notifModalMessage').textContent = notif.message || '';
    if ($('notifModalMeta')) {
      const amt = notif.amount != null ? `Amount: ${formatINR(notif.amount)} • ` : '';
      $('notifModalMeta').textContent = `${amt}${formatTime(notif.createdAt)}`;
    }
    if ($('notificationAlertModal')) $('notificationAlertModal').style.display = 'flex';
    SoundEngine.notify();
  }

  async function acknowledgeActivePopup(openStatusPage) {
    const current = activePopupNotif;
    activePopupNotif = null;
    if ($('notificationAlertModal')) $('notificationAlertModal').style.display = 'none';
    if (current && current.id) {
      await markNotificationsAsRead([current.id]);
    }
    if (openStatusPage) {
      popupQueue = [];
      navigateToView('payment', 'status');
      return;
    }
    if (popupQueue.length > 0) {
      setTimeout(showNextQueuedPopup, 180);
    }
  }

  async function markNotificationsAsRead(ids) {
    if (!Array.isArray(ids) || !ids.length) return;
    sessionNotifications.forEach((n) => {
      if (ids.includes(n.id)) n.isRead = true;
    });
    updateNotificationBadges();
    if (socket && socket.connected) {
      socket.emit('mark_notifications_read', ids);
    }
    try {
      await apiFetch('/api/notifications/read', {
        method: 'POST',
        body: JSON.stringify({ ids })
      });
    } catch {}
  }

  function ingestUnreadNotifications(list, isInitialLogin) {
    if (!Array.isArray(list) || !list.length) return;
    const existingById = new Map();
    sessionNotifications.forEach((item) => {
      if (item && item.id) existingById.set(item.id, item);
    });
    const ordered = prioritizeNotifications(list);
    ordered.forEach((n) => {
      if (!n || !n.id) return;
      if (existingById.has(n.id)) {
        const cur = existingById.get(n.id);
        cur.isRead = Boolean(cur.isRead || n.isRead);
        return;
      }
      existingById.set(n.id, n);
      if (!n.isRead && !popupQueue.some((q) => q.id === n.id) && (!activePopupNotif || activePopupNotif.id !== n.id)) {
        popupQueue.push(n);
      }
    });
    sessionNotifications = prioritizeNotifications(Array.from(existingById.values()));
    updateNotificationBadges();

    if (popupQueue.length > 0) {
      // On login, show unread withdrawal-completed notification IMMEDIATELY first!
      if (isInitialLogin) {
        popupQueue = prioritizeNotifications(popupQueue);
      }
      showNextQueuedPopup();
    }
  }

  // =========================================================
  // PAYMENT CENTER (RAZORPAY + LIVE UPI QR + WITHDRAW)
  // =========================================================
  function applyPaymentConfigToUi(cfg) {
    if (!cfg) return;
    paymentConfig = Object.assign(paymentConfig, cfg);

    const minDep = Number(paymentConfig.minDeposit || 10);
    const maxDep = Number(paymentConfig.maxDeposit || 50000);
    const minWth = Number(paymentConfig.minWithdraw || 10);
    const maxWth = Number(paymentConfig.maxWithdraw || 50000);

    if ($('minDepositHint')) $('minDepositHint').textContent = `Limit: ₹${minDep.toLocaleString('en-IN')} – ₹${maxDep.toLocaleString('en-IN')}`;
    if ($('minWithdrawHint')) $('minWithdrawHint').textContent = `Limit: ₹${minWth.toLocaleString('en-IN')} – ₹${maxWth.toLocaleString('en-IN')}`;
    if ($('rechargeAmountInput')) {
      $('rechargeAmountInput').min = String(minDep);
      $('rechargeAmountInput').max = String(maxDep);
    }
    if ($('withdrawAmountInput')) {
      $('withdrawAmountInput').min = String(minWth);
      $('withdrawAmountInput').max = String(maxWth);
    }

    const depFirstChip = document.querySelector('#rechargeQuickChips .chip-amt-btn');
    if (depFirstChip) {
      depFirstChip.dataset.val = String(minDep);
      depFirstChip.textContent = `₹${minDep.toLocaleString('en-IN')}`;
    }
    const wthFirstChip = document.querySelector('#withdrawQuickChips .chip-amt-btn');
    if (wthFirstChip) {
      wthFirstChip.dataset.val = String(minWth);
      wthFirstChip.textContent = `₹${minWth.toLocaleString('en-IN')}`;
    }

    const badge = $('rzpStatusBadge');
    const note = $('rzpFallbackNote');
    const rzpBtn = $('rzpPayBtn');
    if (paymentConfig.razorpayEnabled) {
      if (badge) { badge.textContent = 'INSTANT'; badge.className = 'status-pill good'; }
      if (note) note.style.display = 'none';
      if (rzpBtn) rzpBtn.disabled = false;
    } else {
      if (badge) { badge.textContent = 'SETUP NEEDED'; badge.className = 'status-pill amber'; }
      if (note) note.style.display = '';
      if (rzpBtn) rzpBtn.disabled = false;
    }

    const ppBtn = $('methodBtnPaypal');
    if (ppBtn) {
      ppBtn.style.display = paymentConfig.paypalEnabled ? '' : 'none';
      const ppBadge = $('paypalStatusBadge');
      if (ppBadge) ppBadge.textContent = paymentConfig.paypalMode === 'live' ? 'LIVE USD' : 'SANDBOX USD';
    }

    refreshStaticUpiBox();
  }

  function refreshStaticUpiBox() {
    const upiId = (paymentConfig.upiId || 'gbpayment0@nyes').trim();
    const payee = (paymentConfig.upiPayeeName || 'GB Colors').trim();
    if ($('staticUpiIdDisplay')) $('staticUpiIdDisplay').textContent = upiId;
    if ($('staticUpiPayeeDisplay')) $('staticUpiPayeeDisplay').textContent = payee;

    const amtVal = Number(($('rechargeAmountInput') && $('rechargeAmountInput').value) || 0);
    const params = new URLSearchParams({ pa: upiId, pn: payee, cu: 'INR' });
    if (Number.isFinite(amtVal) && amtVal >= 1) {
      params.set('am', amtVal.toFixed(2));
    }
    if ($('staticUpiAppLink')) $('staticUpiAppLink').href = 'upi://pay?' + params.toString();

    const qrImg = $('merchantStaticQrImg');
    if (qrImg) {
      qrImg.style.display = '';
    }
  }

  function switchWithdrawMethod(method) {
    selectedWithdrawMethod = method === 'BANK' ? 'BANK' : 'UPI';
    if ($('wthMethodBtnUpi')) $('wthMethodBtnUpi').classList.toggle('active', selectedWithdrawMethod === 'UPI');
    if ($('wthMethodBtnBank')) $('wthMethodBtnBank').classList.toggle('active', selectedWithdrawMethod === 'BANK');
    if ($('wthMethodPanel-upi')) {
      $('wthMethodPanel-upi').style.display = selectedWithdrawMethod === 'UPI' ? '' : 'none';
      $('wthMethodPanel-upi').classList.toggle('active', selectedWithdrawMethod === 'UPI');
    }
    if ($('wthMethodPanel-bank')) {
      $('wthMethodPanel-bank').style.display = selectedWithdrawMethod === 'BANK' ? '' : 'none';
      $('wthMethodPanel-bank').classList.toggle('active', selectedWithdrawMethod === 'BANK');
    }
  }

  function switchRechargeMethod(method) {
    const target = ['upi', 'razorpay', 'paypal'].includes(method) ? method : 'upi';
    document.querySelectorAll('.pay-method-btn').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.paymethod === target);
    });
    ['upi', 'razorpay', 'paypal'].forEach((m) => {
      const panel = $('payMethodPanel-' + m);
      if (panel) {
        panel.style.display = (m === target) ? '' : 'none';
        panel.classList.toggle('active', m === target);
      }
    });
  }

  function validatePayAmount(amt, isWithdraw) {
    const min = Number(isWithdraw ? (paymentConfig.minWithdraw || 10) : (paymentConfig.minDeposit || 10));
    const max = Number(isWithdraw ? (paymentConfig.maxWithdraw || 50000) : (paymentConfig.maxDeposit || 50000));
    const label = isWithdraw ? 'withdrawal' : 'recharge';
    if (!Number.isFinite(amt) || amt < min) {
      return `Minimum ${label} amount is ₹${min.toLocaleString('en-IN')}`;
    }
    if (amt > max) {
      return `Maximum ${label} amount is ₹${max.toLocaleString('en-IN')}`;
    }
    return null;
  }

  async function loadPaymentConfig() {
    const res = await apiFetch('/api/payment/config', { method: 'GET' });
    if (!res.ok || !res.data.success) return;
    applyPaymentConfigToUi(res.data);
  }

  let activeOrderPollerId = null;
  function startOrderAutoPoller(orderId, amt) {
    if (activeOrderPollerId) {
      clearInterval(activeOrderPollerId);
      activeOrderPollerId = null;
    }
    if (!orderId) return;
    let pollCount = 0;
    activeOrderPollerId = setInterval(async () => {
      pollCount++;
      if (pollCount > 120) {
        clearInterval(activeOrderPollerId);
        activeOrderPollerId = null;
        return;
      }
      try {
        const res = await apiFetch(`/api/payment/order-status/${encodeURIComponent(orderId)}`, { method: 'GET' });
        if (res.ok && res.data && res.data.status === 'APPROVED') {
          clearInterval(activeOrderPollerId);
          activeOrderPollerId = null;
          SoundEngine.win();
          showToast(`🎉 Payment Confirmed! ₹${amt} credited automatically to your wallet!`, 'success');
          await refreshCurrentUser();
          if ($('upiQrPreviewBox')) $('upiQrPreviewBox').style.display = 'none';
          switchPaymentSubTab('recharge');
        }
      } catch (err) {
        // quiet retry
      }
    }, 2500);
  }

  async function handleGenerateUpiQr() {
    const amt = Number(($('rechargeAmountInput') && $('rechargeAmountInput').value) || 0);
    const err = validatePayAmount(amt, false);
    if (err) {
      showToast(err, 'error');
      return;
    }
    const btn = $('generateUpiQrBtn');
    if (btn) { btn.disabled = true; btn.textContent = 'Generating QR...'; }
    try {
      const res = await apiFetch('/api/payment/upi/initiate', {
        method: 'POST',
        body: JSON.stringify({ amount: amt })
      });
      if (!res.ok || !res.data.success) {
        showToast(res.data.message || res.data.error || 'Could not generate UPI QR', 'error');
        return;
      }
      if ($('upiQrSvgWrap')) $('upiQrSvgWrap').innerHTML = res.data.qrSvg || '';
      if ($('upiPayeeDisplay')) $('upiPayeeDisplay').textContent = res.data.payeeName || 'GB Colors';
      if ($('upiIdDisplay')) $('upiIdDisplay').textContent = res.data.upiId || '';
      if ($('upiOrderRefDisplay')) $('upiOrderRefDisplay').textContent = res.data.orderId || res.data.refId || '—';
      if ($('upiAppDeepLink')) $('upiAppDeepLink').href = res.data.upiUri || '#';
      if ($('upiQrPreviewBox')) $('upiQrPreviewBox').style.display = '';
      showToast(`Live dynamic UPI QR generated for ${formatShortINR(amt)}`, 'success');
      startOrderAutoPoller(res.data.orderId || res.data.refId, amt);
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '🔄 Generate Amount-Locked QR for Entered ₹'; }
    }
  }

  async function handleSubmitUtr() {
    const amt = Number(($('rechargeAmountInput') && $('rechargeAmountInput').value) || 0);
    const utr = String(($('depositUtrInput') && $('depositUtrInput').value) || '').trim();
    const err = validatePayAmount(amt, false);
    if (err) {
      showToast(err, 'error');
      return;
    }
    if (!utr || utr.length < 6) {
      showToast('Enter a valid UPI UTR / transaction reference ID', 'error');
      return;
    }
    const btn = $('submitDepositBtn');
    if (btn) { btn.disabled = true; btn.textContent = 'Submitting...'; }
    try {
      const payload = { amount: amt, utr, method: 'UPI' };
      if (attachedDepositProofBase64) {
        payload.proofImage = attachedDepositProofBase64;
      }
      const res = await apiFetch('/api/payment/upi/submit-utr', {
        method: 'POST',
        body: JSON.stringify(payload)
      });
      if (!res.ok || !res.data.success) {
        showToast(res.data.message || res.data.error || 'Could not submit deposit request', 'error');
        return;
      }
      if ($('depositUtrInput')) $('depositUtrInput').value = '';
      attachedDepositProofBase64 = null;
      if ($('depositProofFileInput')) $('depositProofFileInput').value = '';
      if ($('depositProofPreviewWrap')) $('depositProofPreviewWrap').style.display = 'none';
      showToast(res.data.message || 'UTR submitted for verification', 'success');
      await refreshCurrentUser();
      switchPaymentSubTab('recharge');
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '✅ Submit UTR for Verification'; }
    }
  }

  async function handleRazorpayPay() {
    const amt = Number(($('rechargeAmountInput') && $('rechargeAmountInput').value) || 0);
    const err = validatePayAmount(amt, false);
    if (err) {
      showToast(err, 'error');
      return;
    }
    if (typeof window.Razorpay === 'undefined') {
      showToast('Razorpay SDK blocked or offline — please use UPI QR / App.', 'error');
      return;
    }
    const btn = $('rzpPayBtn');
    if (btn) { btn.disabled = true; btn.textContent = 'Creating Order...'; }
    try {
      const res = await apiFetch('/api/payment/razorpay/create-order', {
        method: 'POST',
        body: JSON.stringify({ amount: amt })
      });
      if (!res.ok || !res.data.success) {
        showToast(res.data.message || res.data.error || 'Razorpay checkout unavailable — use UPI QR / App', 'error');
        return;
      }
      const d = res.data;
      const rzp = new window.Razorpay({
        key: d.keyId,
        amount: d.amountPaise,
        currency: d.currency || 'INR',
        name: 'GB COLORS',
        description: `Wallet Recharge (${formatShortINR(amt)})`,
        order_id: d.orderId,
        prefill: {
          email: d.prefillEmail || (currentUser && currentUser.email) || '',
          name: d.prefillName || (currentUser && currentUser.username) || ''
        },
        theme: { color: '#10b981' },
        handler: async function (response) {
          const vRes = await apiFetch('/api/payment/razorpay/verify', {
            method: 'POST',
            body: JSON.stringify({
              razorpay_order_id: response.razorpay_order_id,
              razorpay_payment_id: response.razorpay_payment_id,
              razorpay_signature: response.razorpay_signature
            })
          });
          if (vRes.ok && vRes.data.success) {
            SoundEngine.win();
            showToast(vRes.data.message || 'Wallet credited instantly!', 'success');
            await refreshCurrentUser();
            switchPaymentSubTab('recharge');
          } else {
            showToast(vRes.data.message || vRes.data.error || 'Payment verification failed', 'error');
          }
        }
      });
      rzp.on('payment.failed', function (resp) {
        const desc = (resp && resp.error && resp.error.description) || 'Payment cancelled or failed';
        showToast(desc, 'error');
      });
      rzp.open();
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '⚡ Pay Instantly via Razorpay'; }
    }
  }

  async function handlePaypalPay() {
    const usdVal = Number(($('paypalAmountInput') && $('paypalAmountInput').value) || 10);
    if (!Number.isFinite(usdVal) || usdVal < 1) {
      showToast('Enter valid USD amount (minimum $1)', 'error');
      return;
    }
    const btn = $('paypalPayBtn');
    if (btn) { btn.disabled = true; btn.textContent = 'Contacting PayPal...'; }
    try {
      const res = await apiFetch('/api/payment/paypal/create-order', {
        method: 'POST',
        body: JSON.stringify({ amountUsd: usdVal })
      });
      if (!res.ok || !res.data.success) {
        showToast(res.data.message || 'PayPal order creation failed', 'error');
        return;
      }
      const orderId = res.data.orderId;
      showToast('Capturing PayPal payment...', 'info');
      const capRes = await apiFetch('/api/payment/paypal/capture', {
        method: 'POST',
        body: JSON.stringify({ orderId })
      });
      if (capRes.ok && capRes.data.success) {
        SoundEngine.win();
        showToast(`🎉 PayPal deposit verified! Credited to wallet!`, 'success');
        await refreshCurrentUser();
        switchPaymentSubTab('recharge');
      } else {
        showToast(capRes.data.message || 'Payment initiated: ' + orderId, 'info');
      }
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '🌐 Pay with PayPal'; }
    }
  }

  async function handleSubmitWithdraw() {
    const amt = Number(($('withdrawAmountInput') && $('withdrawAmountInput').value) || 0);
    const err = validatePayAmount(amt, true);
    if (err) {
      showToast(err, 'error');
      return;
    }

    const payload = { amount: amt, withdrawMethod: selectedWithdrawMethod };
    if (selectedWithdrawMethod === 'BANK') {
      const holder = String(($('withdrawBankHolderInput') && $('withdrawBankHolderInput').value) || '').trim();
      const accNo = String(($('withdrawBankAccNoInput') && $('withdrawBankAccNoInput').value) || '').replace(/\s+/g, '');
      const accConf = String(($('withdrawBankAccConfirmInput') && $('withdrawBankAccConfirmInput').value) || '').replace(/\s+/g, '');
      const ifsc = String(($('withdrawBankIfscInput') && $('withdrawBankIfscInput').value) || '').trim().toUpperCase();
      const bankName = String(($('withdrawBankNameInput') && $('withdrawBankNameInput').value) || '').trim();

      if (holder.length < 2) {
        showToast('Enter Account Holder Name as per bank records', 'error');
        return;
      }
      if (!/^\d{9,18}$/.test(accNo)) {
        showToast('Enter a valid 9 to 18 digit Bank Account Number', 'error');
        return;
      }
      if (accNo !== accConf) {
        showToast('Bank Account Number and Confirm Account Number do not match', 'error');
        return;
      }
      if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(ifsc)) {
        showToast('Enter a valid 11-character IFSC Code (e.g. HDFC0001234)', 'error');
        return;
      }
      payload.accountHolderName = holder;
      payload.bankAccountNumber = accNo;
      payload.ifscCode = ifsc;
      payload.bankName = bankName;
    } else {
      const upiId = String(($('withdrawUpiIdInput') && $('withdrawUpiIdInput').value) || '').trim();
      const holder = String(($('withdrawUpiHolderInput') && $('withdrawUpiHolderInput').value) || '').trim();
      if (!/^[a-zA-Z0-9.\-_]{2,}@[a-zA-Z0-9]{2,}$/.test(upiId)) {
        showToast('Enter a valid UPI ID (e.g. name@okhdfcbank or 98xxxx@ybl)', 'error');
        return;
      }
      payload.upiId = upiId;
      payload.accountHolderName = holder || selectedWithdrawUpiApp;
    }

    const btn = $('submitWithdrawBtn');
    if (btn) { btn.disabled = true; btn.textContent = 'Submitting Request...'; }
    try {
      const res = await apiFetch('/api/payment/withdraw', {
        method: 'POST',
        body: JSON.stringify(payload)
      });
      if (!res.ok || !res.data.success) {
        showToast(res.data.message || res.data.error || 'Could not submit withdrawal request', 'error');
        return;
      }
      if ($('withdrawAmountInput')) $('withdrawAmountInput').value = '';
      if ($('withdrawUpiIdInput')) $('withdrawUpiIdInput').value = '';
      if ($('withdrawBankAccNoInput')) $('withdrawBankAccNoInput').value = '';
      if ($('withdrawBankAccConfirmInput')) $('withdrawBankAccConfirmInput').value = '';
      SoundEngine.notify();
      showToast(res.data.message || 'Withdrawal requested!', 'success');
      await refreshCurrentUser();
      switchPaymentSubTab('withdraw');
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '🏦 Submit Withdrawal Request'; }
    }
  }

  // =========================================================
  // RENDERING HELPERS
  // =========================================================
  function getRoundColors(round) {
    if (!round) return [];
    if (Array.isArray(round.colors) && round.colors.length) {
      return round.colors.map((c) => String(c).trim().toLowerCase()).filter(Boolean);
    }
    return String(round.color || '')
      .split(/[+,]/)
      .map((c) => c.trim().toLowerCase())
      .filter(Boolean);
  }

  function getRoundBallClass(round) {
    const cols = getRoundColors(round);
    const hasGreen = cols.includes('green');
    const hasRed = cols.includes('red');
    const hasViolet = cols.includes('violet');
    if (hasRed && hasViolet) return 'n-red-violet';
    if (hasGreen && hasViolet) return 'n-green-violet';
    if (hasGreen) return 'n-green';
    if (hasRed) return 'n-red';
    return 'n-violet';
  }

  function colorChipsHtml(round) {
    const cols = getRoundColors(round);
    if (!cols.length) return '<span class="color-dot-pill">—</span>';
    return `<span class="color-chips-wrap">${cols.map((cls) => {
      const label = cls.charAt(0).toUpperCase() + cls.slice(1);
      return `<span class="color-dot-pill ${escapeHtml(cls)}"><span class="color-circle-dot"></span>${escapeHtml(label)}</span>`;
    }).join('')}</span>`;
  }

  function singleColorChipHtml(colorName) {
    const cls = String(colorName || 'green').toLowerCase();
    const label = cls.charAt(0).toUpperCase() + cls.slice(1);
    return `<span class="color-chips-wrap"><span class="color-dot-pill ${escapeHtml(cls)}"><span class="color-circle-dot"></span>${escapeHtml(label)}</span></span>`;
  }

  function formatPeriodNumber(rawPeriod) {
    if (!rawPeriod) return '--------';
    const s = String(rawPeriod).trim();
    if (/^\d{12,}$/.test(s)) {
      return s.slice(8);
    }
    return s;
  }

  function getModePeriodPrefix(mode) {
    const m = mode || currentGameMode;
    if (m === 'SAFE' || m === 'SAFE_COLOR') return 'SP';
    if (m === 'POOL' || m === 'POOL_SHARE') return 'PS';
    return 'WG';
  }

  function formatPeriodForMode(rawPeriod, mode) {
    if (!rawPeriod) return '--------';
    const prefix = getModePeriodPrefix(mode);
    const num = formatPeriodNumber(rawPeriod);
    return `${prefix}-${num}`;
  }

  function renderUserSummary() {
    if (!currentUser) return;
    const isReal = currentUser.mode === 'REAL';
    const activeBal = isReal ? currentUser.realBalance : currentUser.demoBalance;

    if ($('topNavIdentityTag')) {
      if (isAdminSession) {
        $('topNavIdentityTag').textContent = 'Admin: gbcolor@admin.com';
        $('topNavIdentityTag').className = 'top-nav-id-badge admin';
      } else {
        $('topNavIdentityTag').textContent = `UID: ${currentUser.userId || '—'} • ${currentUser.email || currentUser.username || ''}`;
        $('topNavIdentityTag').className = 'top-nav-id-badge user';
      }
    }

    if ($('headerWalletLabel')) $('headerWalletLabel').textContent = isReal ? 'REAL CASH' : 'DEMO COINS';
    if ($('activeBalanceDisplay')) $('activeBalanceDisplay').textContent = formatINR(activeBal);
    if ($('modeDemoBtn')) $('modeDemoBtn').classList.toggle('active', !isReal);
    if ($('modeRealBtn')) $('modeRealBtn').classList.toggle('active', isReal);

    // Drawer summary
    const uname = currentUser.username || 'Player';
    if ($('drawerAvatar')) $('drawerAvatar').textContent = uname.charAt(0).toUpperCase();
    if ($('drawerUsername')) $('drawerUsername').textContent = uname;
    if ($('drawerEmail')) $('drawerEmail').textContent = currentUser.email || '';
    if ($('drawerUserId')) $('drawerUserId').textContent = `UID: ${currentUser.userId || '—'}`;
    if ($('drawerRealBal')) $('drawerRealBal').textContent = formatINR(currentUser.realBalance);
    if ($('drawerDemoBal')) $('drawerDemoBal').textContent = formatINR(currentUser.demoBalance);

    // Payment stats
    if ($('payRealBal')) $('payRealBal').textContent = formatINR(currentUser.realBalance);
    if ($('payDemoBal')) $('payDemoBal').textContent = formatINR(currentUser.demoBalance);
    if ($('payTotalRecharged')) $('payTotalRecharged').textContent = formatINR(currentUser.totalRecharged);
    if ($('payTotalWithdrawn')) $('payTotalWithdrawn').textContent = formatINR(currentUser.totalWithdrawn);
    if ($('withdrawAvailableBal')) $('withdrawAvailableBal').textContent = formatINR(currentUser.realBalance);

    renderActiveBetBadges();
    renderUserBetAndTxLists();
    renderPaymentStatuses();
    renderProfileView();
    renderHistoryView();
  }

  function isBetActive(b) {
    return Boolean(b && (b.status === 'ACTIVE' || b.status === 'PENDING'));
  }

  function renderActiveBetBadges() {
    document.querySelectorAll('.bet-badge').forEach((b) => {
      b.style.display = 'none';
      b.classList.remove('admin-pool-badge', 'user-sim-pool', 'user-my-bet');
      b.textContent = '₹0';
    });

    if (isAdminSession) {
      if (!latestAdminAnalytics || !latestAdminAnalytics.liveTotals) return;
      const lt = latestAdminAnalytics.liveTotals;
      const colors = lt.colors || {};
      const safeColors = lt.safeColors || {};
      const poolShares = lt.poolShares || {};
      const sizes = lt.sizes || {};
      const numbers = lt.numbers || {};

      const pools = {
        'GREEN': colors.GREEN || 0,
        'VIOLET': colors.VIOLET || 0,
        'RED': colors.RED || 0,
        'BIG': sizes.BIG || 0,
        'SMALL': sizes.SMALL || 0,
        'SAFE-GREEN': safeColors.GREEN || 0,
        'SAFE-RED': safeColors.RED || 0,
        'POOL-GREEN': poolShares.GREEN || 0,
        'POOL-RED': poolShares.RED || 0
      };
      for (let i = 0; i <= 9; i++) {
        pools[String(i)] = numbers[String(i)] || 0;
      }

      Object.keys(pools).forEach((key) => {
        const badge = $('badge-' + key);
        if (badge) {
          const val = Number(pools[key] || 0);
          badge.textContent = '₹' + val.toLocaleString('en-IN');
          badge.classList.add('admin-pool-badge');
          badge.style.display = 'inline-block';
        }
      });
      return;
    }

    // REGULAR USER SESSION: Show simulated active pool activity & personal bet pills
    const myTotals = {};
    if (currentUser && Array.isArray(currentUser.activeBets)) {
      currentUser.activeBets.forEach((b) => {
        if (b.period === gameState.period && isBetActive(b)) {
          let prefix = '';
          if (b.betType === 'SAFE_COLOR') prefix = 'SAFE-';
          else if (b.betType === 'POOL_SHARE') prefix = 'POOL-';
          const key = prefix + b.choice;
          myTotals[key] = (myTotals[key] || 0) + Number(b.amount || 0);
        }
      });
    }

    const allKeys = ['GREEN', 'VIOLET', 'RED', 'BIG', 'SMALL', 'SAFE-GREEN', 'SAFE-RED', 'POOL-GREEN', 'POOL-RED'];
    for (let i = 0; i <= 9; i++) allKeys.push(String(i));

    allKeys.forEach((key) => {
      const badge = $('badge-' + key);
      if (!badge) return;
      if (myTotals[key] && myTotals[key] > 0) {
        badge.textContent = '✓ ' + formatShortINR(myTotals[key]);
        badge.classList.add('user-my-bet');
        badge.style.display = 'inline-block';
      } else {
        const simVal = UserSimulatedPoolEngine.getPool(key);
        badge.textContent = formatShortINR(simVal);
        badge.classList.add('user-sim-pool');
        badge.style.display = 'inline-block';
      }
    });
  }

  function buildHistoryRowsHtml(history) {
    if (!Array.isArray(history) || !history.length) {
      const colSpan = currentGameMode === 'CLASSIC' ? 4 : 2;
      return `<tr><td colspan="${colSpan}" class="empty-cell">No rounds completed yet.</td></tr>`;
    }
    if (currentGameMode === 'SAFE') {
      return history.map((r) => {
        const color = (r.safeResultColor || (r.number % 2 === 1 ? 'GREEN' : 'RED')).toUpperCase();
        return `
          <tr>
            <td class="mono">${escapeHtml(formatPeriodForMode(r.period, 'SAFE'))}</td>
            <td>${singleColorChipHtml(color)}</td>
          </tr>
        `;
      }).join('');
    }
    if (currentGameMode === 'POOL') {
      return history.map((r) => {
        const color = (r.poolResultColor || (r.number % 2 === 1 ? 'GREEN' : 'RED')).toUpperCase();
        return `
          <tr>
            <td class="mono">${escapeHtml(formatPeriodForMode(r.period, 'POOL'))}</td>
            <td>${singleColorChipHtml(color)}</td>
          </tr>
        `;
      }).join('');
    }
    return history.map((r) => {
      const numCls = getRoundBallClass(r);
      return `
        <tr>
          <td class="mono">${escapeHtml(formatPeriodForMode(r.period, 'CLASSIC'))}</td>
          <td><span class="hist-num-pill ${numCls}">${escapeHtml(r.number)}</span></td>
          <td><span class="size-tag ${(r.size || '').toLowerCase()}">${escapeHtml(r.size)}</span></td>
          <td>${colorChipsHtml(r)}</td>
        </tr>
      `;
    }).join('');
  }

  function renderGameHistory() {
    const history = gameState.history || [];

    const headEl = $('historyTableHead');
    if (headEl) {
      if (currentGameMode === 'SAFE' || currentGameMode === 'POOL') {
        headEl.innerHTML = `
          <tr>
            <th>Period</th>
            <th>Winning</th>
          </tr>
        `;
      } else {
        headEl.innerHTML = `
          <tr>
            <th>Period</th>
            <th>Number</th>
            <th>Size</th>
            <th>Color</th>
          </tr>
        `;
      }
    }

    const dotsEl = $('recentDotsList');
    if (dotsEl) {
      if (currentGameMode === 'SAFE') {
        dotsEl.innerHTML = history.slice(0, 14).map((r) => {
          const color = (r.safeResultColor || (r.number % 2 === 1 ? 'GREEN' : 'RED')).toUpperCase();
          const cls = color === 'GREEN' ? 'n-green' : 'n-red';
          const letter = color === 'GREEN' ? 'G' : 'R';
          const p = formatPeriodForMode(r.period, 'SAFE');
          return `<span class="recent-dot-ball ${cls}" title="Period ${p}: ${color}">${letter}</span>`;
        }).join('');
      } else if (currentGameMode === 'POOL') {
        dotsEl.innerHTML = history.slice(0, 14).map((r) => {
          const color = (r.poolResultColor || (r.number % 2 === 1 ? 'GREEN' : 'RED')).toUpperCase();
          const cls = color === 'GREEN' ? 'n-green' : 'n-red';
          const letter = color === 'GREEN' ? 'G' : 'R';
          const p = formatPeriodForMode(r.period, 'POOL');
          return `<span class="recent-dot-ball ${cls}" title="Period ${p}: ${color}">${letter}</span>`;
        }).join('');
      } else {
        dotsEl.innerHTML = history.slice(0, 14).map((r) => {
          const cls = getRoundBallClass(r);
          const p = formatPeriodForMode(r.period, 'CLASSIC');
          return `<span class="recent-dot-ball ${cls}" title="Period ${p}">${escapeHtml(r.number)}</span>`;
        }).join('');
      }
    }

    const totalRecords = history.length;
    const totalPages = Math.max(1, Math.ceil(totalRecords / HIST_PAGE_SIZE));
    if (histCurrentPage > totalPages) histCurrentPage = totalPages;
    if (histCurrentPage < 1) histCurrentPage = 1;

    const startIdx = (histCurrentPage - 1) * HIST_PAGE_SIZE;
    const pageRecords = history.slice(startIdx, startIdx + HIST_PAGE_SIZE);

    if ($('historyTableBody')) $('historyTableBody').innerHTML = buildHistoryRowsHtml(pageRecords);
    if ($('fullHistoryTableBody')) $('fullHistoryTableBody').innerHTML = buildHistoryRowsHtml(history.slice(0, 30));

    if ($('histPageIndicator')) {
      $('histPageIndicator').textContent = `Page ${histCurrentPage} of ${totalPages} (${totalRecords} rounds)`;
    }
    if ($('histPrevPageBtn')) $('histPrevPageBtn').disabled = histCurrentPage <= 1;
    if ($('histNextPageBtn')) $('histNextPageBtn').disabled = histCurrentPage >= totalPages;
  }

  function renderUserBetAndTxLists() {
    if (!currentUser) return;
    const activeBets = (currentUser.activeBets || []).filter(isBetActive);
    if ($('activeBetsCountBadge')) $('activeBetsCountBadge').textContent = String(activeBets.length);

    const activeEl = $('activeBetsList');
    if (activeEl) {
      if (!activeBets.length) {
        activeEl.innerHTML = '<div class="empty-state-box">No active bets placed in this round.</div>';
      } else {
        activeEl.innerHTML = activeBets.map((b) => {
          const bMode = b.betType === 'SAFE_COLOR' ? 'SAFE' : (b.betType === 'POOL_SHARE' ? 'POOL' : 'CLASSIC');
          const pDisp = formatPeriodForMode(b.period, bMode);
          return `
          <div class="feed-card-item">
            <div>
              <strong>${escapeHtml(b.choice)}</strong> <small>(${escapeHtml(b.mode)})</small>
              <div class="fc-sub">Period ${escapeHtml(pDisp)} • ${formatShortINR(b.amount)}</div>
            </div>
            <button type="button" class="btn-cancel-mini" data-cancel-bet="${escapeHtml(b.id)}" data-choice="${escapeHtml(b.choice)}" data-amt="${escapeHtml(b.amount)}" ${gameState.status !== 'BETTING' ? 'disabled' : ''}>Cancel</button>
          </div>
        `;
        }).join('');
      }
    }

    const betHistory = currentUser.betHistory || [];
    if ($('myBetsCount')) $('myBetsCount').textContent = String(betHistory.length);
    const myBetsHtml = !betHistory.length
      ? '<div class="empty-state-box">No settled bets yet.</div>'
      : betHistory.slice(0, 40).map((b) => {
          const won = b.win === true || b.status === 'WON';
          const payout = Number(b.payout || 0);
          const amt = Number(b.amount || 0);
          let rightText = '';
          let rightSub = b.status || (won ? 'WON' : 'LOST');
          if (won) {
            rightText = '+' + formatShortINR(payout);
          } else if (payout > 0) {
            rightText = '+' + formatShortINR(payout) + ' (Refund)';
            rightSub = 'REFUNDED';
          } else {
            rightText = '-' + formatShortINR(amt);
          }
          const bMode = (b.type === 'SAFE_COLOR' || b.betType === 'SAFE_COLOR') ? 'SAFE' : ((b.type === 'POOL_SHARE' || b.betType === 'POOL_SHARE') ? 'POOL' : 'CLASSIC');
          const pDisp = formatPeriodForMode(b.period, bMode);
          return `
            <div class="feed-card-item">
              <div>
                <strong>${escapeHtml(b.choice)}</strong> <span class="badge-mode">${escapeHtml(b.mode)}</span>
                <div class="fc-sub">Period ${escapeHtml(pDisp)} • Stake ${formatShortINR(b.amount)}</div>
              </div>
              <div class="fc-right ${won ? 'text-green' : (payout > 0 ? 'text-amber' : 'text-red')}">
                <strong>${rightText}</strong>
                <small>${escapeHtml(rightSub)}</small>
              </div>
            </div>
          `;
        }).join('');
    if ($('myBetsList')) $('myBetsList').innerHTML = myBetsHtml;
    if ($('fullMyBetsList')) $('fullMyBetsList').innerHTML = myBetsHtml;

    const txs = currentUser.transactions || [];
    const txHtml = !txs.length
      ? '<div class="empty-state-box">No wallet transactions recorded yet.</div>'
      : txs.slice(0, 40).map((t) => {
          const amt = Number(t.amount || 0);
          const pos = amt >= 0;
          return `
            <div class="feed-card-item">
              <div>
                <strong>${escapeHtml(t.note || t.type)}</strong>
                <div class="fc-sub">${escapeHtml(t.walletType || 'REAL')} • ${escapeHtml(formatTime(t.createdAt))}</div>
              </div>
              <div class="fc-right ${pos ? 'text-green' : 'text-red'}">
                <strong>${pos ? '+' : '-'}${formatShortINR(Math.abs(amt))}</strong>
              </div>
            </div>
          `;
        }).join('');
    if ($('transactionsList')) $('transactionsList').innerHTML = txHtml;
    if ($('paymentPageTxList')) $('paymentPageTxList').innerHTML = txHtml;
  }

  function renderPaymentStatuses() {
    if (!currentUser) return;

    const depositsRaw = Array.isArray(currentUser.depositRequests) ? currentUser.depositRequests : [];
    const deposits = depositsRaw.slice().sort((a, b) => Number(b.requestedAt || 0) - Number(a.requestedAt || 0));
    const pendingDeposit = deposits.find((d) => String(d.status || 'PENDING').toUpperCase() === 'PENDING');
    const prevDeposit = pendingDeposit
      ? deposits.find((d) => d.id !== pendingDeposit.id)
      : deposits[0];

    const rPill = $('rechargeStatusPill');
    if (rPill) {
      if (pendingDeposit) {
        rPill.textContent = 'Recharge Requested';
        rPill.className = 'status-pill amber';
      } else if (deposits.length) {
        rPill.textContent = deposits.length + (deposits.length === 1 ? ' Transaction' : ' Transactions');
        rPill.className = 'status-pill good';
      } else {
        rPill.textContent = 'No Requests';
        rPill.className = 'status-pill neutral';
      }
    }

    const rHighlight = $('rechargeActiveHighlight');
    if (rHighlight) {
      let hl = '';
      if (pendingDeposit) {
        const depRef = pendingDeposit.utrOrPaymentId || pendingDeposit.utr || pendingDeposit.orderId || '—';
        hl += `
          <div class="status-highlight-card pending">
            <div class="sh-head">
              <span class="sh-label">Recharge Requested</span>
              <span class="sc-badge pending">PENDING VERIFICATION</span>
            </div>
            <div class="sh-amount">${formatINR(pendingDeposit.amount)} <small>(${escapeHtml(pendingDeposit.method || 'UPI')})</small></div>
            <div class="sc-row"><span>UTR / Ref ID:</span><span class="mono">${escapeHtml(depRef)}</span></div>
            <div class="sc-time">Requested: ${escapeHtml(formatTime(pendingDeposit.requestedAt))}</div>
          </div>
        `;
      }
      if (prevDeposit) {
        const st = String(prevDeposit.status || 'PENDING').toUpperCase();
        const cls = st === 'APPROVED' ? 'paid' : (st === 'REJECTED' ? 'rejected' : 'pending');
        const depRef = prevDeposit.utrOrPaymentId || prevDeposit.utr || prevDeposit.orderId || '—';
        hl += `
          <div class="status-highlight-card ${cls}">
            <div class="sh-head">
              <span class="sh-label">Last Recharge Transaction</span>
              <span class="sc-badge ${cls}">${escapeHtml(st)}</span>
            </div>
            <div class="sh-amount">${formatINR(prevDeposit.amount)} <small>(${escapeHtml(prevDeposit.method || 'UPI')})</small></div>
            <div class="sc-row"><span>UTR / Ref ID:</span><span class="mono">${escapeHtml(depRef)}</span></div>
            ${(prevDeposit.adminNote || prevDeposit.adminRemark) ? `<div class="sc-row"><span>Note:</span><span>${escapeHtml(prevDeposit.adminNote || prevDeposit.adminRemark)}</span></div>` : ''}
            <div class="sc-time">Submitted: ${escapeHtml(formatTime(prevDeposit.requestedAt))}${prevDeposit.processedAt ? ' • Updated: ' + escapeHtml(formatTime(prevDeposit.processedAt)) : ''}</div>
          </div>
        `;
      }
      rHighlight.innerHTML = hl;
    }

    const dEl = $('userDepositStatusList');
    if (dEl) {
      if (!deposits.length) {
        dEl.innerHTML = '<div class="empty-state-box">No recharge requests submitted yet.</div>';
      } else {
        dEl.innerHTML = `<div class="status-list-subhead">All Recharge Requests (${deposits.length})</div>` + deposits.map((d) => {
          const st = String(d.status || 'PENDING').toUpperCase();
          const cls = st === 'APPROVED' ? 'paid' : (st === 'REJECTED' ? 'rejected' : 'pending');
          const depRef = d.utrOrPaymentId || d.utr || d.orderId || '—';
          const note = d.adminNote || d.adminRemark || '';
          return `
            <div class="status-card ${cls}">
              <div class="sc-top">
                <strong>${formatINR(d.amount)} <small>(${escapeHtml(d.method || 'UPI')})</small></strong>
                <span class="sc-badge ${cls}">${escapeHtml(st)}</span>
              </div>
              <div class="sc-row"><span>UTR / Ref:</span><span class="mono">${escapeHtml(depRef)}</span></div>
              ${note ? `<div class="sc-row"><span>Note:</span><span>${escapeHtml(note)}</span></div>` : ''}
              <div class="sc-time">Submitted: ${escapeHtml(formatTime(d.requestedAt))}</div>
            </div>
          `;
        }).join('');
      }
    }

    const withdrawsRaw = Array.isArray(currentUser.withdrawRequests) ? currentUser.withdrawRequests : [];
    const withdraws = withdrawsRaw.slice().sort((a, b) => Number(b.requestedAt || 0) - Number(a.requestedAt || 0));
    const pendingWithdraw = withdraws.find((w) => String(w.status || 'PENDING').toUpperCase() === 'PENDING');
    const prevWithdraw = pendingWithdraw
      ? withdraws.find((w) => w.id !== pendingWithdraw.id)
      : withdraws[0];

    const wPill = $('withdrawStatusPill');
    if (wPill) {
      if (pendingWithdraw) {
        wPill.textContent = 'Requested';
        wPill.className = 'status-pill amber';
      } else if (withdraws.length) {
        wPill.textContent = withdraws.length + (withdraws.length === 1 ? ' Request' : ' Requests');
        wPill.className = 'status-pill good';
      } else {
        wPill.textContent = 'No Requests';
        wPill.className = 'status-pill neutral';
      }
    }

    const wHighlight = $('withdrawActiveHighlight');
    if (wHighlight) {
      let wl = '';
      if (pendingWithdraw) {
        const dest = pendingWithdraw.accountDetails || pendingWithdraw.upiId || pendingWithdraw.bankAccountNumber || pendingWithdraw.upiOrBank || '—';
        wl += `
          <div class="status-highlight-card pending">
            <div class="sh-head">
              <span class="sh-label">Requested</span>
              <span class="sc-badge pending">PROCESSING</span>
            </div>
            <div class="sh-amount">${formatINR(pendingWithdraw.amount)} <small>(${escapeHtml(pendingWithdraw.withdrawMethod || 'UPI')})</small></div>
            <div class="sc-row"><span>Destination:</span><span>${escapeHtml(dest)}</span></div>
            <div class="sc-time">Requested: ${escapeHtml(formatTime(pendingWithdraw.requestedAt))}</div>
          </div>
        `;
      }
      if (prevWithdraw) {
        const st = String(prevWithdraw.status || 'PENDING').toUpperCase();
        const cls = st === 'PAID' ? 'paid' : (st === 'REJECTED' ? 'rejected' : 'pending');
        const statusLabel = st === 'PAID' ? 'COMPLETED & PAID' : (st === 'REJECTED' ? 'REJECTED (REFUNDED)' : 'PROCESSING');
        const dest = prevWithdraw.accountDetails || prevWithdraw.upiId || prevWithdraw.bankAccountNumber || prevWithdraw.upiOrBank || '—';
        wl += `
          <div class="status-highlight-card ${cls}">
            <div class="sh-head">
              <span class="sh-label">Last Requested</span>
              <span class="sc-badge ${cls}">${statusLabel}</span>
            </div>
            <div class="sh-amount">${formatINR(prevWithdraw.amount)} <small>(${escapeHtml(prevWithdraw.withdrawMethod || 'UPI')})</small></div>
            <div class="sc-row"><span>Destination:</span><span>${escapeHtml(dest)}</span></div>
            ${prevWithdraw.payoutRef ? `<div class="sc-row"><span>Payout Ref:</span><span class="mono">${escapeHtml(prevWithdraw.payoutRef)}</span></div>` : ''}
            ${prevWithdraw.adminRemark ? `<div class="sc-row"><span>Remark:</span><span>${escapeHtml(prevWithdraw.adminRemark)}</span></div>` : ''}
            <div class="sc-time">Requested: ${escapeHtml(formatTime(prevWithdraw.requestedAt))}${prevWithdraw.processedAt ? ' • Settled: ' + escapeHtml(formatTime(prevWithdraw.processedAt)) : ''}</div>
          </div>
        `;
      }
      wHighlight.innerHTML = wl;
    }

    const wEl = $('userWithdrawStatusList');
    if (wEl) {
      if (!withdraws.length) {
        wEl.innerHTML = '<div class="empty-state-box">No withdrawal requests submitted yet.</div>';
      } else {
        wEl.innerHTML = `<div class="status-list-subhead">All Withdrawal Requests (${withdraws.length})</div>` + withdraws.map((w) => {
          const st = String(w.status || 'PENDING').toUpperCase();
          const cls = st === 'PAID' ? 'paid' : (st === 'REJECTED' ? 'rejected' : 'pending');
          const statusLabel = st === 'PAID' ? 'COMPLETED & PAID' : (st === 'REJECTED' ? 'REJECTED (REFUNDED)' : 'PROCESSING');
          const dest = w.accountDetails || w.upiId || w.bankAccountNumber || w.upiOrBank || '—';
          return `
            <div class="status-card ${cls}">
              <div class="sc-top">
                <strong>${formatINR(w.amount)} <small>(${escapeHtml(w.withdrawMethod || 'UPI')})</small></strong>
                <span class="sc-badge ${cls}">${statusLabel}</span>
              </div>
              <div class="sc-row"><span>Destination:</span><span>${escapeHtml(dest)}</span></div>
              ${w.payoutRef ? `<div class="sc-row"><span>Payout Ref:</span><span class="mono">${escapeHtml(w.payoutRef)}</span></div>` : ''}
              ${w.adminRemark ? `<div class="sc-row"><span>Remark:</span><span>${escapeHtml(w.adminRemark)}</span></div>` : ''}
              <div class="sc-time">Requested: ${escapeHtml(formatTime(w.requestedAt))}${w.processedAt ? ' • Settled: ' + escapeHtml(formatTime(w.processedAt)) : ''}</div>
            </div>
          `;
        }).join('');
      }
    }
  }

  function renderProfileView() {
    if (!currentUser) return;
    const uname = currentUser.username || 'Player';
    if ($('profAvatarLg')) $('profAvatarLg').textContent = uname.charAt(0).toUpperCase();
    if ($('profUsername')) $('profUsername').textContent = uname;
    if ($('profEmail')) $('profEmail').textContent = currentUser.email || '';
    if ($('profUserId')) $('profUserId').textContent = `UID: ${currentUser.userId || '—'}`;
    if ($('profVipBadge')) $('profVipBadge').textContent = isAdminSession ? 'ADMIN PLAY' : `VIP ${currentUser.vipLevel || 1}`;
    if ($('profVerifiedBadge')) {
      const verified = currentUser.emailVerified !== false;
      $('profVerifiedBadge').textContent = verified ? '✅ Verified Email' : '⏳ Pending Email';
      $('profVerifiedBadge').className = `status-pill ${verified ? 'good' : 'amber'}`;
    }
    if ($('tempPassAlertBadge')) {
      $('tempPassAlertBadge').style.display = currentUser.mustChangePassword ? '' : 'none';
    }
    if ($('changePasswordCard')) {
      $('changePasswordCard').style.display = isAdminSession ? 'none' : '';
    }
    if ($('profStatusBadge')) $('profStatusBadge').textContent = currentUser.blockType && currentUser.blockType !== 'NONE' ? currentUser.blockType : 'ACTIVE';
    if ($('profRealBal')) $('profRealBal').textContent = formatINR(currentUser.realBalance);
    if ($('profDemoBal')) $('profDemoBal').textContent = formatINR(currentUser.demoBalance);
    if ($('profTotalWon')) $('profTotalWon').textContent = formatINR(currentUser.totalWon);
    if ($('profTotalLost')) $('profTotalLost').textContent = formatINR(currentUser.totalLost);
    if ($('profTotalRecharged')) $('profTotalRecharged').textContent = formatINR(currentUser.totalRecharged);
    if ($('profTotalWithdrawn')) $('profTotalWithdrawn').textContent = formatINR(currentUser.totalWithdrawn);
  }

  function renderHistoryView() {
    if (!currentUser) return;
    const hist = currentUser.betHistory || [];
    const wins = hist.filter((b) => b.status === 'WON').length;
    const rate = hist.length ? Math.round((wins / hist.length) * 100) : 0;
    if ($('histTotalBets')) $('histTotalBets').textContent = String(hist.length);
    if ($('histTotalWon')) $('histTotalWon').textContent = formatINR(currentUser.totalWon);
    if ($('histTotalLost')) $('histTotalLost').textContent = formatINR(currentUser.totalLost);
    if ($('histWinRate')) $('histWinRate').textContent = rate + '%';
  }

  function updateClockAndLock() {
    const t = Math.max(0, Number(gameState.timeLeft || 0));
    const m = Math.floor(t / 60);
    const s = t % 60;
    if ($('periodNumber')) $('periodNumber').textContent = formatPeriodForMode(gameState.period, currentGameMode);
    if ($('timerMin')) $('timerMin').textContent = String(m).padStart(2, '0');
    if ($('timerSec')) $('timerSec').textContent = String(s).padStart(2, '0');

    const cycleSec = Number(gameState.cycleDurationSeconds || 30);
    const lockSec = Number(gameState.betCloseSeconds || 5);
    if ($('modeTagDisplay')) {
      if (currentGameMode === 'SAFE') {
        $('modeTagDisplay').textContent = `SAFE PROTECT • ${cycleSec}S`;
      } else if (currentGameMode === 'POOL') {
        $('modeTagDisplay').textContent = `POOL SHARE • ${cycleSec}S`;
      } else {
        $('modeTagDisplay').textContent = `WIN GO • ${cycleSec}S`;
      }
    }
    if ($('rulesCycleSecondsTxt')) $('rulesCycleSecondsTxt').textContent = `${cycleSec}s`;
    if ($('rulesLockSecondsTxt')) $('rulesLockSecondsTxt').textContent = `${lockSec}s`;
    if ($('lockOverlayHint')) $('lockOverlayHint').textContent = `Closing in final ${lockSec}s — revealing soon...`;

    const isLocked = gameState.status !== 'BETTING';
    const overlay = $('lockOverlay');
    if (overlay) overlay.style.display = isLocked ? 'flex' : 'none';
    const clockEl = $('countdownClock');
    if (clockEl) clockEl.classList.toggle('urgent', isLocked || t <= lockSec);

    // Admin Real-Time Upcoming Result Lock Preview on Dashboard
    const previewEl = $('adminDashLockPreview');
    if (previewEl) {
      if (isAdminSession && (isLocked || t <= lockSec) && latestAdminAnalytics && latestAdminAnalytics.upcomingResult) {
        const up = latestAdminAnalytics.upcomingResult;
        const cols = Array.isArray(up.colors) ? up.colors.join(' + ') : '';
        previewEl.style.display = 'block';
        previewEl.innerHTML = `
          <div class="lock-preview-banner">
            <span class="lpb-tag">🔒 Admin Real-Time Lock Preview (Revealing in ${t}s)</span>
            <div class="lpb-content">
              <span class="lpb-num ball-${up.number}">${up.number}</span>
              <span class="lpb-pill ${(up.size || '').toLowerCase()}">${escapeHtml(up.size || '')}</span>
              <span class="lpb-colors">${escapeHtml(cols)}</span>
            </div>
          </div>
        `;
      } else {
        previewEl.style.display = 'none';
      }
    }

    if (isLocked && $('betModal') && $('betModal').style.display !== 'none') {
      $('betModal').style.display = 'none';
    }
    if (isLocked && t > 0) {
      SoundEngine.tick();
    }
  }

  function setGameMode(mode) {
    if (mode === 'SAFE') currentGameMode = 'SAFE';
    else if (mode === 'POOL') currentGameMode = 'POOL';
    else currentGameMode = 'CLASSIC';

    if ($('gameModeClassicBtn')) $('gameModeClassicBtn').classList.toggle('active', currentGameMode === 'CLASSIC');
    if ($('gameModeSafeBtn')) $('gameModeSafeBtn').classList.toggle('active', currentGameMode === 'SAFE');
    if ($('gameModePoolBtn')) $('gameModePoolBtn').classList.toggle('active', currentGameMode === 'POOL');

    if ($('classicBettingContainer')) $('classicBettingContainer').style.display = currentGameMode === 'CLASSIC' ? '' : 'none';
    if ($('safeBettingContainer')) $('safeBettingContainer').style.display = currentGameMode === 'SAFE' ? '' : 'none';
    if ($('poolBettingContainer')) $('poolBettingContainer').style.display = currentGameMode === 'POOL' ? '' : 'none';

    const cycleSec = Number(gameState.cycleDurationSeconds || 30);
    if ($('modeTagDisplay')) {
      if (currentGameMode === 'SAFE') {
        $('modeTagDisplay').textContent = `SAFE PROTECT • ${cycleSec}S`;
      } else if (currentGameMode === 'POOL') {
        $('modeTagDisplay').textContent = `POOL SHARE • ${cycleSec}S`;
      } else {
        $('modeTagDisplay').textContent = `WIN GO • ${cycleSec}S`;
      }
    }
    if ($('periodNumber')) $('periodNumber').textContent = formatPeriodForMode(gameState.period, currentGameMode);
    renderGameHistory();
  }

  // =========================================================
  // BETTING & CANCEL MODALS
  // =========================================================
  function updateBetSummary() {
    const customInput = $('betCustomAmountInput');
    const customVal = Number(customInput && customInput.value);
    const total = Number.isFinite(customVal) && customVal > 0 ? customVal : (pendingBet.unit * pendingBet.mult);

    let mult = 2;
    if (pendingBet.type === 'NUMBER') mult = 9;
    else if (pendingBet.type === 'COLOR' && pendingBet.choice === 'VIOLET') mult = 4.5;
    else if (pendingBet.type === 'SAFE_COLOR') mult = 1.2;
    else if (pendingBet.type === 'POOL_SHARE') mult = 1.0;

    if ($('summaryTotalAmt')) $('summaryTotalAmt').textContent = formatShortINR(total);
    if ($('summaryEstPayout')) {
      let payoutText;
      if (pendingBet.type === 'SAFE_COLOR') {
        payoutText = `${formatShortINR(Math.round(total * 1.2 * 100) / 100)} (or 80% refund ${formatShortINR(Math.floor(total * 0.8))})`;
      } else if (pendingBet.type === 'POOL_SHARE') {
        payoutText = `Stake + Pool Share (or 90% refund ${formatShortINR(Math.floor(total * 0.9))})`;
      } else {
        payoutText = formatShortINR(Math.round(total * mult * 100) / 100);
      }
      $('summaryEstPayout').textContent = payoutText;
    }
  }

  function openBetModal(type, choice) {
    if (isAdminSession) {
      showToast('Admin Mode: Betting is disabled. Viewing live pools only.', 'info');
      return;
    }
    if (gameState.status !== 'BETTING') {
      showToast('Betting is closed for this round', 'error');
      return;
    }
    pendingBet = { type, choice, unit: 10, mult: 1 };
    let typeLabel = type;
    if (type === 'SAFE_COLOR') typeLabel = 'Safe Protect';
    else if (type === 'POOL_SHARE') typeLabel = 'Pool Share';
    if ($('modalBetTarget')) $('modalBetTarget').textContent = `${choice} (${typeLabel})`;
    document.querySelectorAll('.unit-chip').forEach((b) => b.classList.toggle('active', b.dataset.unit === '10'));
    document.querySelectorAll('.mult-chip').forEach((b) => b.classList.toggle('active', b.dataset.mult === '1'));
    if ($('betCustomAmountInput')) $('betCustomAmountInput').value = '10';
    updateBetSummary();
    if ($('betModal')) $('betModal').style.display = 'flex';
    SoundEngine.click();
  }

  // =========================================================
  // WEBSOCKET CONNECTION
  // =========================================================
  function connectUserSocket(isAdmin, adminInfo) {
    if (socket) {
      try { socket.disconnect(); } catch {}
      socket = null;
    }
    if (typeof window.io !== 'function') {
      showToast('WebSocket library missing', 'error');
      return;
    }

    socket = window.io(getWsBaseUrl(), { auth: { token: authToken } });

    if (isAdmin && typeof window.initAdminDashboard === 'function') {
      window.initAdminDashboard(authToken, socket, adminInfo || {});
    }

    socket.on('init_state', (payload) => {
      if (!payload) return;
      if (payload.user) currentUser = payload.user;
      if (payload.gameState) {
        gameState = Object.assign(gameState, payload.gameState);
        if (Array.isArray(payload.gameState.tickerEvents)) {
          LiveTickerEngine.addEvents(payload.gameState.tickerEvents);
        }
        if (Array.isArray(payload.gameState.history) && payload.gameState.history.length > 0) {
          LiveTickerEngine.setLatestRoundResult(payload.gameState.history[0]);
        }
        UserSimulatedPoolEngine.resetForPeriod(gameState.period);
      }
      renderUserSummary();
      renderGameHistory();
      renderActiveBetBadges();
      updateClockAndLock();
      LiveTickerEngine.start();

      if (currentUser) {
        const merged = []
          .concat(Array.isArray(currentUser.recentNotifications) ? currentUser.recentNotifications : [])
          .concat(Array.isArray(currentUser.unreadNotifications) ? currentUser.unreadNotifications : []);
        if (merged.length) ingestUnreadNotifications(merged, true);
      }
    });

    let lastKnownPeriod = null;
    socket.on('game_tick', (tick) => {
      if (!tick) return;
      if (tick.period && tick.period !== lastKnownPeriod) {
        lastKnownPeriod = tick.period;
        UserSimulatedPoolEngine.resetForPeriod(tick.period);
      }
      gameState.period = tick.period || gameState.period;
      gameState.timeLeft = tick.timeLeft != null ? tick.timeLeft : gameState.timeLeft;
      gameState.status = tick.status || gameState.status;
      if (tick.cycleDurationSeconds) gameState.cycleDurationSeconds = tick.cycleDurationSeconds;
      if (tick.betCloseSeconds) gameState.betCloseSeconds = tick.betCloseSeconds;
      UserSimulatedPoolEngine.tick(gameState.timeLeft);
      renderActiveBetBadges();
      updateClockAndLock();
    });

    socket.on('admin_live_update', (analytics) => {
      if (!analytics) return;
      latestAdminAnalytics = analytics;
      if (isAdminSession) {
        renderActiveBetBadges();
        updateClockAndLock();
      }
    });

    socket.on('user_updated', (u) => {
      if (!u) return;
      const keepActiveBets = currentUser ? currentUser.activeBets : [];
      currentUser = Object.assign({}, currentUser || {}, u);
      if (!u.activeBets) currentUser.activeBets = keepActiveBets;
      renderUserSummary();
      const merged = []
        .concat(Array.isArray(u.recentNotifications) ? u.recentNotifications : [])
        .concat(Array.isArray(u.unreadNotifications) ? u.unreadNotifications : []);
      if (merged.length) ingestUnreadNotifications(merged, false);
    });

    socket.on('active_bets_updated', (bets) => {
      if (!currentUser) return;
      currentUser.activeBets = Array.isArray(bets) ? bets : [];
      renderActiveBetBadges();
      renderUserBetAndTxLists();
    });

    let lastPeriodSettledBets = [];
    socket.on('bet_settled', (d) => {
      if (!d) return;
      if (currentUser) {
        if (d.demoBalance != null) currentUser.demoBalance = d.demoBalance;
        if (d.realBalance != null) currentUser.realBalance = d.realBalance;
      }
      if (d.bet) lastPeriodSettledBets.push(d.bet);
      renderUserSummary();
    });

    socket.on('round_result', (data) => {
      if (!data) return;
      if (Array.isArray(data.history)) gameState.history = data.history;
      if (Array.isArray(data.newTickerEvents)) LiveTickerEngine.addEvents(data.newTickerEvents);
      if (data.result) {
        LiveTickerEngine.setLatestRoundResult(data.result);
      } else if (data.round) {
        LiveTickerEngine.setLatestRoundResult(data.round);
      } else if (Array.isArray(data.history) && data.history.length > 0) {
        LiveTickerEngine.setLatestRoundResult(data.history[0]);
      }
      if (currentUser) currentUser.activeBets = [];
      renderGameHistory();
      renderUserSummary();

      const settled = lastPeriodSettledBets.slice();
      lastPeriodSettledBets = [];
      if (settled.length > 0) {
        const totalPayout = settled.reduce((s, b) => s + Number(b.payout || 0), 0);
        const totalStake = settled.reduce((s, b) => s + Number(b.amount || 0), 0);
        const res = data.result || {};
        if (totalPayout > totalStake) {
          SoundEngine.win();
          showToast(`🏆 Round Won! +${formatShortINR(totalPayout - totalStake)} (Payout: ${formatShortINR(totalPayout)})`, 'success');
        } else if (totalPayout > 0) {
          SoundEngine.win();
          showToast(`🛡️ Protected Round: Received refund of ${formatShortINR(totalPayout)}`, 'info');
        } else {
          SoundEngine.loss();
          showToast(`Round Settled: -${formatShortINR(totalStake)}`, 'info');
        }
      }
    });

    socket.on('payment_config_updated', (cfg) => {
      applyPaymentConfigToUi(cfg);
    });

    socket.on('ticker_event', (ev) => {
      if (ev) LiveTickerEngine.addEvents([ev]);
    });

    socket.on('notification', (notif) => {
      if (notif) ingestUnreadNotifications([notif], false);
    });

    socket.on('account_blocked', (info) => {
      // Disconnect cleanly first so gb-ws reconnect loop stops permanently
      if (socket) {
        try { socket.disconnect(); } catch {}
        socket = null;
      }
      const reason = (info && info.reason) || 'Your account was restricted by Admin.';
      const msg = (info && info.message) || reason;
      if ($('blockedModalTitle')) {
        $('blockedModalTitle').textContent = (info && info.blockType === 'PERMANENT') ? 'Account Permanently Blocked' : 'Account Temporarily Blocked';
      }
      if ($('blockedModalReason')) $('blockedModalReason').textContent = msg;
      if ($('blockedModalUntil')) {
        $('blockedModalUntil').textContent = info && info.blockedUntil
          ? `Restricted until: ${formatTime(info.blockedUntil)}`
          : 'Permanent restriction — contact support.';
      }
      if ($('blockedAlertModal')) $('blockedAlertModal').style.display = 'flex';
      performLogout(true);
      if ($('loginErrorMsg')) {
        $('loginErrorMsg').textContent = msg;
        $('loginErrorMsg').style.display = 'block';
      }
    });

    socket.on('connect_error', (err) => {
      if (err && err.message === 'AUTH_REQUIRED') {
        performLogout();
        showToast('Session expired — please sign in again', 'error');
      }
    });
  }

  // =========================================================
  // AUTHENTICATION & PORTAL ROUTING
  // =========================================================
  function toggleAdminShellUi(enabled) {
    isAdminSession = Boolean(enabled);
    if ($('gameAppRoot')) $('gameAppRoot').classList.toggle('is-admin-session', isAdminSession);

    // Hide player controls when logged in as Admin
    const playerControls = [
      $('modeSwitchPill'),
      $('headerWalletPill'),
      $('headerRechargeBtn'),
      $('paymentNavBtn'),
      $('notifBellBtn'),
      $('soundToggleBtn'),
      $('userQuickPayBar'),
      $('profOpenPayBtn'),
      $('userDrawerNavGroup'),
      document.querySelector('#userSideDrawer .drawer-nav-item[data-view="payment"]')
    ];
    playerControls.forEach((el) => {
      if (el) el.style.display = isAdminSession ? 'none' : '';
    });

    // Admin specific controls (strictly hidden for regular users; admin navigates via Drawer Menu)
    if ($('adminHeaderToggleBtn')) $('adminHeaderToggleBtn').style.display = 'none';
    if ($('adminHeaderLogoutBtn')) $('adminHeaderLogoutBtn').style.display = isAdminSession ? 'inline-flex' : 'none';
    if ($('adminDrawerSection')) $('adminDrawerSection').style.display = isAdminSession ? '' : 'none';
    if ($('adminArenaShortcutBar')) $('adminArenaShortcutBar').style.display = isAdminSession ? '' : 'none';
    if ($('adminMonitorNotice')) $('adminMonitorNotice').style.display = isAdminSession ? 'flex' : 'none';
    if (!isAdminSession && $('adminDashLockPreview')) $('adminDashLockPreview').style.display = 'none';

    // Toggle view-only on betting buttons
    document.querySelectorAll('.bet-btn').forEach((b) => {
      b.classList.toggle('admin-view-only', isAdminSession);
    });

    if (currentUser) {
      renderUserSummary();
    }
  }

  async function mountAdminToolkit(adminInfo) {
    const root = $('adminPortalRoot');
    if (!root) return false;
    root.innerHTML = '<div class="admin-loading-box">Loading Admin Tools...</div>';
    try {
      const [htmlRes, jsRes] = await Promise.all([
        fetch(getApiUrl('/api/admin/view'), { headers: { Authorization: 'Bearer ' + authToken } }),
        fetch(getApiUrl('/api/admin/script'), { headers: { Authorization: 'Bearer ' + authToken } })
      ]);
      if (!htmlRes.ok || !jsRes.ok) {
        showToast('Admin authorization failed', 'error');
        return false;
      }
      root.innerHTML = await htmlRes.text();
      const prevScript = document.getElementById('gbAdminRuntimeScript');
      if (prevScript) prevScript.remove();
      const scriptText = await jsRes.text();
      const s = document.createElement('script');
      s.id = 'gbAdminRuntimeScript';
      s.textContent = scriptText;
      document.body.appendChild(s);
      return true;
    } catch (err) {
      root.innerHTML = `<div class="admin-loading-box">Failed to load Admin Tools: ${escapeHtml(err.message)}</div>`;
      return false;
    }
  }

  async function enterUnifiedPortal(user, isAdmin, adminInfo) {
    currentUser = user || {
      username: (adminInfo && adminInfo.username) || 'Admin',
      email: (adminInfo && adminInfo.email) || '',
      userId: 'ADMIN-PLAY',
      mode: 'DEMO',
      demoBalance: 10000,
      realBalance: 0,
      emailVerified: true
    };
    currentAdminInfo = isAdmin ? (adminInfo || {}) : null;
    toggleAdminShellUi(isAdmin);

    if ($('authScreen')) $('authScreen').style.display = 'none';
    if ($('gameAppRoot')) $('gameAppRoot').style.display = 'block';
    renderUserSummary();
    navigateToView('game');
    loadPaymentConfig();

    if (isAdmin) {
      const mounted = await mountAdminToolkit(adminInfo);
      if (!mounted) {
        performLogout();
        return;
      }
    } else if ($('adminPortalRoot')) {
      $('adminPortalRoot').innerHTML = '';
    }

    connectUserSocket(Boolean(isAdmin), adminInfo);
  }

  function performLogout(preserveError) {
    localStorage.removeItem(AUTH_TOKEN_KEY);
    authToken = '';
    currentUser = null;
    currentAdminInfo = null;
    toggleAdminShellUi(false);
    sessionNotifications = [];
    popupQueue = [];
    activePopupNotif = null;
    LiveTickerEngine.stop();
    if (socket) {
      try { socket.disconnect(); } catch {}
      socket = null;
    }
    if ($('adminPortalRoot')) {
      $('adminPortalRoot').innerHTML = '';
    }
    if ($('gameAppRoot')) $('gameAppRoot').style.display = 'none';
    if ($('authScreen')) $('authScreen').style.display = 'flex';
    if (!preserveError && $('loginErrorMsg')) {
      $('loginErrorMsg').style.display = 'none';
      $('loginErrorMsg').textContent = '';
    }
  }
  window.performLogout = performLogout;

  async function checkInitialAuth() {
    if (!authToken) {
      performLogout();
      return;
    }
    const res = await apiFetch('/api/auth/me', { method: 'GET' });
    if (!res.ok || !res.data.success) {
      performLogout(Boolean(res.data && res.data.blocked));
      if (res.data && res.data.blocked && $('loginErrorMsg')) {
        $('loginErrorMsg').textContent = res.data.message || 'Your account is blocked.';
        $('loginErrorMsg').style.display = 'block';
      }
      return;
    }
    if (res.data.role === 'ADMIN') {
      await enterUnifiedPortal(res.data.user, true, res.data.admin);
    } else {
      await enterUnifiedPortal(res.data.user, false, null);
    }
  }

  // Password validation UI helper
  function evaluatePasswordRules(pw) {
    const val = String(pw || '');
    const checks = {
      ruleLen: val.length >= 8,
      ruleUpper: /[A-Z]/.test(val),
      ruleLower: /[a-z]/.test(val),
      ruleNum: /\d/.test(val),
      ruleSym: /[^A-Za-z0-9\s]/.test(val),
      ruleNoSpace: val.length > 0 && !/\s/.test(val)
    };
    Object.keys(checks).forEach((id) => {
      const el = $(id);
      if (el) el.classList.toggle('valid', checks[id]);
    });
    return Object.values(checks).every(Boolean);
  }

  // =========================================================
  // EVENT BINDINGS
  // =========================================================
  function bindEvents() {
    // Auth Tabs & Forgot Password
    const showAuthPanel = (panel) => {
      if ($('showSignInTab')) $('showSignInTab').classList.toggle('active', panel !== 'signup');
      if ($('showSignUpTab')) $('showSignUpTab').classList.toggle('active', panel === 'signup');
      if ($('signInForm')) $('signInForm').style.display = panel === 'signin' ? '' : 'none';
      if ($('forgotPassForm')) $('forgotPassForm').style.display = panel === 'forgot' ? '' : 'none';
      if ($('signUpForm')) $('signUpForm').style.display = panel === 'signup' ? '' : 'none';
    };

    if ($('showSignInTab')) $('showSignInTab').addEventListener('click', () => showAuthPanel('signin'));
    if ($('showSignUpTab')) $('showSignUpTab').addEventListener('click', () => showAuthPanel('signup'));
    if ($('openForgotPassBtn')) {
      $('openForgotPassBtn').addEventListener('click', () => {
        if ($('forgotEmail') && $('loginEmail')) $('forgotEmail').value = $('loginEmail').value || '';
        if ($('forgotErrorMsg')) $('forgotErrorMsg').style.display = 'none';
        if ($('forgotNoticeBox')) $('forgotNoticeBox').style.display = 'none';
        showAuthPanel('forgot');
      });
    }
    if ($('backToSignInBtn')) {
      $('backToSignInBtn').addEventListener('click', () => showAuthPanel('signin'));
    }
    if ($('forgotPassForm')) {
      $('forgotPassForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const email = String(($('forgotEmail') && $('forgotEmail').value) || '').trim();
        const errBox = $('forgotErrorMsg');
        const noticeBox = $('forgotNoticeBox');
        if (errBox) errBox.style.display = 'none';
        if (noticeBox) noticeBox.style.display = 'none';
        const btn = $('forgotSubmitBtn');
        if (btn) { btn.disabled = true; btn.textContent = 'Sending Temp Password...'; }
        try {
          const res = await apiFetch('/api/auth/forgot-password', {
            method: 'POST',
            body: JSON.stringify({ email })
          });
          if (!res.ok || !res.data.success) {
            if (errBox) {
              errBox.textContent = res.data.message || res.data.error || 'Could not send temporary password';
              errBox.style.display = 'block';
            }
            return;
          }
          if (noticeBox) {
            noticeBox.textContent = res.data.devTempPassword
              ? `${res.data.message} (Dev Temp Password: ${res.data.devTempPassword})`
              : (res.data.message || 'Temporary password sent via SMTP email.');
            noticeBox.style.display = 'block';
          }
          showToast('Temporary password dispatched via SMTP!', 'success');
        } finally {
          if (btn) { btn.disabled = false; btn.textContent = '📧 Send Temporary Password'; }
        }
      });
    }

    if ($('regPassword')) {
      $('regPassword').addEventListener('input', (e) => evaluatePasswordRules(e.target.value));
    }

    // Send OTP
    if ($('sendOtpBtn')) {
      $('sendOtpBtn').addEventListener('click', async () => {
        const email = String(($('regEmail') && $('regEmail').value) || '').trim();
        if (!email) {
          showToast('Enter your email first', 'error');
          return;
        }
        const btn = $('sendOtpBtn');
        btn.disabled = true;
        btn.textContent = 'Sending...';
        try {
          const res = await apiFetch('/api/auth/send-otp', {
            method: 'POST',
            body: JSON.stringify({ email })
          });
          if (!res.ok || !res.data.success) {
            showToast(res.data.message || res.data.error || 'Could not send OTP', 'error');
            return;
          }
          if ($('otpNoticeBox')) {
            $('otpNoticeBox').textContent = res.data.devOtp
              ? `Verification Code (Dev Mode): ${res.data.devOtp}`
              : (res.data.message || 'OTP sent to your email address');
            $('otpNoticeBox').style.display = 'block';
          }
          showToast('Verification OTP sent!', 'success');
        } finally {
          btn.disabled = false;
          btn.textContent = 'Resend OTP';
        }
      });
    }

    // Sign Up
    if ($('signUpForm')) {
      $('signUpForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const errBox = $('signupErrorMsg');
        if (errBox) errBox.style.display = 'none';
        const email = String($('regEmail').value || '').trim();
        const otp = String($('regOtp').value || '').trim();
        const password = String($('regPassword').value || '');
        const confirm = String($('regConfirmPassword').value || '');
        if (!evaluatePasswordRules(password)) {
          errBox.textContent = 'Password must satisfy all security rules above.';
          errBox.style.display = 'block';
          return;
        }
        if (password !== confirm) {
          errBox.textContent = 'Passwords do not match.';
          errBox.style.display = 'block';
          return;
        }
        const btn = $('signUpSubmitBtn');
        btn.disabled = true;
        try {
          const res = await apiFetch('/api/auth/signup', {
            method: 'POST',
            body: JSON.stringify({ email, otp, password })
          });
          if (!res.ok || !res.data.success) {
            errBox.textContent = res.data.message || res.data.error || 'Signup failed';
            errBox.style.display = 'block';
            return;
          }
          authToken = res.data.token;
          localStorage.setItem(AUTH_TOKEN_KEY, authToken);
          await enterUnifiedPortal(res.data.user, false, null);
        } finally {
          btn.disabled = false;
        }
      });
    }

    // Sign In
    if ($('signInForm')) {
      $('signInForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const errBox = $('loginErrorMsg');
        if (errBox) errBox.style.display = 'none';
        const email = String($('loginEmail').value || '').trim();
        const password = String($('loginPassword').value || '');
        const btn = $('signInSubmitBtn');
        btn.disabled = true;
        try {
          const res = await apiFetch('/api/auth/signin', {
            method: 'POST',
            body: JSON.stringify({ email, password })
          });
          if (!res.ok || !res.data.success) {
            errBox.textContent = res.data.message || res.data.error || 'Invalid credentials';
            errBox.style.display = 'block';
            return;
          }
          authToken = res.data.token;
          localStorage.setItem(AUTH_TOKEN_KEY, authToken);
          if (res.data.role === 'ADMIN') {
            await enterUnifiedPortal(res.data.user, true, res.data.admin);
          } else {
            await enterUnifiedPortal(res.data.user, false, null);
          }
        } finally {
          btn.disabled = false;
        }
      });
    }

    // Side Drawer & Navigation
    if ($('userMenuBtn')) $('userMenuBtn').addEventListener('click', openUserDrawer);
    if ($('closeUserDrawerBtn')) $('closeUserDrawerBtn').addEventListener('click', closeUserDrawer);
    if ($('userDrawerBackdrop')) $('userDrawerBackdrop').addEventListener('click', closeUserDrawer);
    if ($('brandHomeBtn')) $('brandHomeBtn').addEventListener('click', () => navigateToView('game'));
    if ($('headerRechargeBtn')) $('headerRechargeBtn').addEventListener('click', () => navigateToView('payment', 'recharge'));
    if ($('paymentNavBtn')) $('paymentNavBtn').addEventListener('click', () => navigateToView('payment', 'recharge'));
    if ($('adminHeaderToggleBtn')) {
      $('adminHeaderToggleBtn').addEventListener('click', () => {
        const adminActive = $('view-admin') && $('view-admin').classList.contains('active');
        navigateToView(adminActive ? 'game' : 'admin');
      });
    }

    document.addEventListener('click', (e) => {
      const navBtn = e.target.closest('[data-view]');
      if (navBtn && navBtn.closest('#userSideDrawer')) {
        navigateToView(navBtn.dataset.view, navBtn.dataset.admtab || null);
        return;
      }
      const goNav = e.target.closest('[data-nav]');
      if (goNav) {
        navigateToView(goNav.dataset.nav, goNav.dataset.admtab || goNav.dataset.paytab || null);
      }
    });

    // Logout buttons
    [$('drawerLogoutBtn'), $('profLogoutBtn'), $('settingsLogoutBtn'), $('adminHeaderLogoutBtn')].forEach((btn) => {
      if (btn) {
        btn.addEventListener('click', async () => {
          closeUserDrawer();
          try { await apiFetch('/api/auth/logout', { method: 'POST' }); } catch {}
          performLogout();
          showToast('Signed out cleanly', 'info');
        });
      }
    });

    // Game Mode Switcher (Game 1 Classic vs Game 2 Safe Protect vs Game 3 Pool Share)
    if ($('gameModeClassicBtn')) $('gameModeClassicBtn').addEventListener('click', () => setGameMode('CLASSIC'));
    if ($('gameModeSafeBtn')) $('gameModeSafeBtn').addEventListener('click', () => setGameMode('SAFE'));
    if ($('gameModePoolBtn')) $('gameModePoolBtn').addEventListener('click', () => setGameMode('POOL'));

    // Game History Pagination
    if ($('histPrevPageBtn')) {
      $('histPrevPageBtn').addEventListener('click', () => {
        if (histCurrentPage > 1) {
          histCurrentPage--;
          renderGameHistory();
        }
      });
    }
    if ($('histNextPageBtn')) {
      $('histNextPageBtn').addEventListener('click', () => {
        const history = gameState.history || [];
        const totalPages = Math.max(1, Math.ceil(history.length / HIST_PAGE_SIZE));
        if (histCurrentPage < totalPages) {
          histCurrentPage++;
          renderGameHistory();
        }
      });
    }
    if ($('histJumpLatestBtn')) {
      $('histJumpLatestBtn').addEventListener('click', () => {
        histCurrentPage = 1;
        renderGameHistory();
      });
    }

    // Mode Switcher
    if ($('modeDemoBtn')) {
      $('modeDemoBtn').addEventListener('click', () => {
        if (socket) socket.emit('switch_mode', 'DEMO');
      });
    }
    if ($('modeRealBtn')) {
      $('modeRealBtn').addEventListener('click', () => {
        if (socket) socket.emit('switch_mode', 'REAL');
      });
    }

    // Sound Header Quick Toggle
    if ($('soundToggleBtn')) {
      $('soundToggleBtn').addEventListener('click', () => {
        userSettings.sfx = !userSettings.sfx;
        saveSettings();
        $('soundToggleBtn').textContent = userSettings.sfx ? '🔊' : '🔇';
        if ($('settingSfx')) $('settingSfx').checked = userSettings.sfx;
      });
    }

    // Settings Toggles
    const syncSettingsUi = () => {
      if ($('settingSfx')) $('settingSfx').checked = userSettings.sfx;
      if ($('settingBgm')) $('settingBgm').checked = userSettings.bgm;
      if ($('settingVibrate')) $('settingVibrate').checked = userSettings.vibrate;
      if ($('soundToggleBtn')) $('soundToggleBtn').textContent = userSettings.sfx ? '🔊' : '🔇';
    };
    syncSettingsUi();

    ['settingSfx', 'settingBgm', 'settingVibrate'].forEach((id) => {
      const el = $(id);
      if (!el) return;
      el.addEventListener('change', () => {
        userSettings.sfx = $('settingSfx').checked;
        userSettings.bgm = $('settingBgm').checked;
        userSettings.vibrate = $('settingVibrate').checked;
        saveSettings();
        syncSettingsUi();
        SoundEngine.syncBgm();
      });
    });
    if ($('testAudioBtn')) $('testAudioBtn').addEventListener('click', () => SoundEngine.win());

    // Records Sub-tabs
    document.querySelectorAll('.rec-tab-btn').forEach((btn) => {
      btn.addEventListener('click', () => {
        const t = btn.dataset.rectab;
        document.querySelectorAll('.rec-tab-btn').forEach((b) => b.classList.toggle('active', b === btn));
        ['history', 'active', 'mybets', 'txs'].forEach((p) => {
          const panel = $('recPanel-' + p);
          if (panel) panel.classList.toggle('active', p === t);
        });
      });
    });

    // Payment Sub-tabs, Method Switcher & Quick Chips
    document.querySelectorAll('.pay-subtab-btn').forEach((btn) => {
      btn.addEventListener('click', () => switchPaymentSubTab(btn.dataset.paytab));
    });
    document.querySelectorAll('.pay-method-btn').forEach((btn) => {
      btn.addEventListener('click', () => switchRechargeMethod(btn.dataset.paymethod));
    });
    document.querySelectorAll('.chip-amt-btn').forEach((btn) => {
      btn.addEventListener('click', () => {
        if (btn.dataset.upiapp) {
          selectedWithdrawUpiApp = btn.dataset.upiapp;
          const pills = btn.closest('.upi-app-pills');
          if (pills) pills.querySelectorAll('.chip-amt-btn').forEach((b) => b.classList.toggle('active', b === btn));
          SoundEngine.click();
          return;
        }
        const targetId = btn.dataset.target;
        const input = $(targetId);
        const rawVal = btn.dataset.val;
        if (input) {
          if (rawVal === 'ALL') {
            const avail = Math.floor(Number((currentUser && currentUser.realBalance) || 0));
            const maxW = Number(paymentConfig.maxWithdraw || 50000);
            input.value = String(Math.max(0, Math.min(avail, maxW)));
          } else {
            input.value = rawVal;
          }
          if (targetId === 'rechargeAmountInput') refreshStaticUpiBox();
        }
        const row = btn.closest('.quick-chips-row');
        if (row) row.querySelectorAll('.chip-amt-btn').forEach((b) => b.classList.toggle('active', b === btn));
        SoundEngine.click();
      });
    });
    if ($('rechargeAmountInput')) {
      $('rechargeAmountInput').addEventListener('input', refreshStaticUpiBox);
    }
    if ($('wthMethodBtnUpi')) $('wthMethodBtnUpi').addEventListener('click', () => switchWithdrawMethod('UPI'));
    if ($('wthMethodBtnBank')) $('wthMethodBtnBank').addEventListener('click', () => switchWithdrawMethod('BANK'));
    if ($('withdrawBankIfscInput')) {
      $('withdrawBankIfscInput').addEventListener('input', (e) => {
        e.target.value = String(e.target.value || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
      });
    }
    if ($('copyStaticUpiIdBtn')) {
      $('copyStaticUpiIdBtn').addEventListener('click', async () => {
        const text = ($('staticUpiIdDisplay') && $('staticUpiIdDisplay').textContent) || '';
        if (!text) return;
        try {
          await navigator.clipboard.writeText(text);
          showToast('UPI ID copied!', 'success');
        } catch {
          showToast('UPI ID: ' + text, 'info');
        }
      });
    }
    if ($('profChangePassBtn')) {
      $('profChangePassBtn').addEventListener('click', async () => {
        const currentPassword = String(($('profCurPassword') && $('profCurPassword').value) || '');
        const newPassword = String(($('profNewPassword') && $('profNewPassword').value) || '');
        if (!currentPassword || !newPassword) {
          showToast('Enter current and new password', 'error');
          return;
        }
        const btn = $('profChangePassBtn');
        btn.disabled = true;
        btn.textContent = 'Updating...';
        try {
          const res = await apiFetch('/api/auth/change-password', {
            method: 'POST',
            body: JSON.stringify({ currentPassword, newPassword })
          });
          if (!res.ok || !res.data.success) {
            showToast(res.data.message || res.data.error || 'Could not update password', 'error');
            return;
          }
          if ($('profCurPassword')) $('profCurPassword').value = '';
          if ($('profNewPassword')) $('profNewPassword').value = '';
          if (currentUser) currentUser.mustChangePassword = false;
          renderProfileView();
          showToast(res.data.message || 'Password updated successfully!', 'success');
        } finally {
          btn.disabled = false;
          btn.textContent = 'Update Password';
        }
      });
    }
    document.querySelectorAll('.btn-demo-add').forEach((btn) => {
      btn.addEventListener('click', () => {
        const amt = Number(btn.dataset.demoamt || 0);
        if (socket && amt > 0) {
          socket.emit('add_demo_balance', amt, (ack) => {
            if (ack && ack.success) showToast(`Added ${formatShortINR(amt)} Demo Coins`, 'success');
          });
        }
      });
    });

    if ($('generateUpiQrBtn')) $('generateUpiQrBtn').addEventListener('click', handleGenerateUpiQr);
    if ($('submitDepositBtn')) $('submitDepositBtn').addEventListener('click', handleSubmitUtr);

    // Deposit screenshot proof handler
    const proofFileInput = $('depositProofFileInput');
    if (proofFileInput) {
      proofFileInput.addEventListener('change', (e) => {
        const file = e.target.files && e.target.files[0];
        if (!file) {
          attachedDepositProofBase64 = null;
          if ($('depositProofPreviewWrap')) $('depositProofPreviewWrap').style.display = 'none';
          return;
        }
        if (file.size > 5 * 1024 * 1024) {
          showToast('Screenshot file size must be under 5MB', 'error');
          proofFileInput.value = '';
          return;
        }
        const reader = new FileReader();
        reader.onload = () => {
          attachedDepositProofBase64 = reader.result;
          if ($('depositProofPreviewImg')) $('depositProofPreviewImg').src = reader.result;
          if ($('depositProofPreviewWrap')) $('depositProofPreviewWrap').style.display = 'flex';
        };
        reader.onerror = () => {
          showToast('Could not read image file', 'error');
        };
        reader.readAsDataURL(file);
      });
    }

    if ($('removeProofImgBtn')) {
      $('removeProofImgBtn').addEventListener('click', () => {
        attachedDepositProofBase64 = null;
        if ($('depositProofFileInput')) $('depositProofFileInput').value = '';
        if ($('depositProofPreviewWrap')) $('depositProofPreviewWrap').style.display = 'none';
      });
    }
    if ($('rzpPayBtn')) $('rzpPayBtn').addEventListener('click', handleRazorpayPay);
    if ($('paypalPayBtn')) $('paypalPayBtn').addEventListener('click', handlePaypalPay);
    if ($('submitWithdrawBtn')) $('submitWithdrawBtn').addEventListener('click', handleSubmitWithdraw);
    if ($('copyUpiIdBtn')) {
      $('copyUpiIdBtn').addEventListener('click', async () => {
        const text = ($('upiIdDisplay') && $('upiIdDisplay').textContent) || '';
        if (!text) return;
        try {
          await navigator.clipboard.writeText(text);
          showToast('UPI ID copied!', 'success');
        } catch {
          showToast('UPI ID: ' + text, 'info');
        }
      });
    }

    // Betting Buttons
    document.querySelectorAll('.bet-btn').forEach((btn) => {
      btn.addEventListener('click', () => openBetModal(btn.dataset.type, btn.dataset.choice));
    });

    document.querySelectorAll('.unit-chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        pendingBet.unit = Number(btn.dataset.unit || 10);
        document.querySelectorAll('.unit-chip').forEach((b) => b.classList.toggle('active', b === btn));
        if ($('betCustomAmountInput')) $('betCustomAmountInput').value = String(pendingBet.unit * pendingBet.mult);
        updateBetSummary();
        SoundEngine.click();
      });
    });
    document.querySelectorAll('.mult-chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        pendingBet.mult = Number(btn.dataset.mult || 1);
        document.querySelectorAll('.mult-chip').forEach((b) => b.classList.toggle('active', b === btn));
        if ($('betCustomAmountInput')) $('betCustomAmountInput').value = String(pendingBet.unit * pendingBet.mult);
        updateBetSummary();
        SoundEngine.click();
      });
    });
    if ($('betCustomAmountInput')) $('betCustomAmountInput').addEventListener('input', updateBetSummary);
    if ($('closeBetModal')) $('closeBetModal').addEventListener('click', () => { $('betModal').style.display = 'none'; });
    if ($('confirmBetBtn')) {
      $('confirmBetBtn').addEventListener('click', () => {
        if (!socket || !socket.connected) {
          showToast('Connecting to arena...', 'error');
          return;
        }
        const amt = Number(($('betCustomAmountInput') && $('betCustomAmountInput').value) || (pendingBet.unit * pendingBet.mult));
        if (!Number.isFinite(amt) || amt <= 0) {
          showToast('Enter a valid wager amount', 'error');
          return;
        }
        $('confirmBetBtn').disabled = true;
        socket.emit('place_bet', { type: pendingBet.type, choice: pendingBet.choice, amount: amt }, (ack) => {
          $('confirmBetBtn').disabled = false;
          if (ack && ack.success) {
            $('betModal').style.display = 'none';
            SoundEngine.click();
            showToast(`Bet ${formatShortINR(amt)} placed on ${pendingBet.choice}`, 'success');
          } else {
            showToast((ack && ack.message) || 'Could not place bet', 'error');
          }
        });
      });
    }

    // Cancel Bet Delegation
    document.addEventListener('click', (e) => {
      const cBtn = e.target.closest('[data-cancel-bet]');
      if (!cBtn) return;
      pendingCancelBetId = cBtn.dataset.cancelBet;
      if ($('cancelTargetName')) $('cancelTargetName').textContent = cBtn.dataset.choice || 'Bet';
      if ($('cancelAmountTxt')) $('cancelAmountTxt').textContent = formatShortINR(cBtn.dataset.amt || 0);
      if ($('cancelConfirmModal')) $('cancelConfirmModal').style.display = 'flex';
    });
    if ($('closeCancelModal')) $('closeCancelModal').addEventListener('click', () => { $('cancelConfirmModal').style.display = 'none'; });
    if ($('abortCancelBtn')) $('abortCancelBtn').addEventListener('click', () => { $('cancelConfirmModal').style.display = 'none'; });
    if ($('executeCancelBtn')) {
      $('executeCancelBtn').addEventListener('click', () => {
        if (!pendingCancelBetId || !socket) return;
        socket.emit('cancel_bet', pendingCancelBetId, (ack) => {
          $('cancelConfirmModal').style.display = 'none';
          if (ack && ack.success) showToast('Bet cancelled & refunded', 'info');
          else showToast((ack && ack.message) || 'Cannot cancel bet', 'error');
        });
      });
    }

    // Notifications Modals
    if ($('notifModalAckBtn')) $('notifModalAckBtn').addEventListener('click', () => acknowledgeActivePopup(false));
    if ($('notifModalViewStatusBtn')) $('notifModalViewStatusBtn').addEventListener('click', () => acknowledgeActivePopup(true));
    if ($('notifBellBtn')) {
      $('notifBellBtn').addEventListener('click', () => {
        renderNotificationsInbox();
        if ($('notificationsHistoryModal')) $('notificationsHistoryModal').style.display = 'flex';
      });
    }
    if ($('drawerNotifBtn')) {
      $('drawerNotifBtn').addEventListener('click', () => {
        closeUserDrawer();
        renderNotificationsInbox();
        if ($('notificationsHistoryModal')) $('notificationsHistoryModal').style.display = 'flex';
      });
    }
    if ($('closeNotifsHistoryModal')) {
      $('closeNotifsHistoryModal').addEventListener('click', () => { $('notificationsHistoryModal').style.display = 'none'; });
    }
    if ($('markAllNotifsReadBtn')) {
      $('markAllNotifsReadBtn').addEventListener('click', async () => {
        const unreadIds = sessionNotifications.filter((n) => !n.isRead).map((n) => n.id);
        await markNotificationsAsRead(unreadIds);
        showToast('All notifications marked as read', 'success');
      });
    }

    // Blocked Modal Ack
    if ($('blockedModalAckBtn')) {
      $('blockedModalAckBtn').addEventListener('click', () => {
        if ($('blockedAlertModal')) $('blockedAlertModal').style.display = 'none';
      });
    }

    // Escape closes drawer/modals
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') closeUserDrawer();
    });
  }

  // Bootstrap
  document.addEventListener('DOMContentLoaded', () => {
    bindEvents();
    checkInitialAuth();
  });
})();
