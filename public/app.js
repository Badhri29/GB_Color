let socket = null;
let authToken = localStorage.getItem('gbcolors_token') || null;

// User State
let user = {
    userId: '',
    email: '',
    username: '',
    vipLevel: 1,
    demoBalance: 1000,
    realBalance: 0,
    mode: 'DEMO',
    betHistory: [],
    transactions: []
};

let activeBets = [];
let currentPeriod = '';
let currentSelectedBet = {
    type: 'COLOR',
    choice: 'GREEN',
    baseAmount: 10,
    multiplier: 1,
    payoutRate: 2
};
let betPendingCancelId = null;
let selectedDepositAmt = 100;

// Settings
let settings = {
    sfx: true,
    bgm: false,
    autoClose: true,
    vibrate: true
};

// Web Audio API
let audioCtx = null;
let bgmOsc = null;

function getAudioContext() {
    if (!audioCtx) {
        const AudioContext = window.AudioContext || window.webkitAudioContext;
        if (AudioContext) audioCtx = new AudioContext();
    }
    if (audioCtx && audioCtx.state === 'suspended') {
        audioCtx.resume();
    }
    return audioCtx;
}

function playTone(freq, type, duration, delay = 0) {
    if (!settings.sfx) return;
    try {
        const ctx = getAudioContext();
        if (!ctx) return;
        setTimeout(() => {
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = type;
            osc.frequency.setValueAtTime(freq, ctx.currentTime);
            gain.gain.setValueAtTime(0.12, ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration);
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.start();
            osc.stop(ctx.currentTime + duration);
        }, delay);
    } catch (e) {}
}

function playTickSound() { playTone(800, 'sine', 0.08); }
function playBetSound() {
    playTone(520, 'triangle', 0.1);
    playTone(780, 'sine', 0.15, 60);
    triggerVibrate([40, 30, 40]);
}
function playWinSound() {
    playTone(523.25, 'triangle', 0.15, 0);
    playTone(659.25, 'triangle', 0.15, 120);
    playTone(783.99, 'triangle', 0.15, 240);
    playTone(1046.50, 'sine', 0.35, 360);
    triggerVibrate([80, 50, 80, 50, 150]);
}
function playLoseSound() {
    playTone(350, 'sawtooth', 0.2, 0);
    playTone(250, 'sawtooth', 0.3, 150);
}
function triggerVibrate(pattern) {
    if (settings.vibrate && navigator.vibrate) {
        try { navigator.vibrate(pattern); } catch (e) {}
    }
}

function toggleBgm(enable) {
    settings.bgm = enable;
    const ctx = getAudioContext();
    if (!ctx) return;
    if (enable) {
        if (!bgmOsc) {
            bgmOsc = ctx.createOscillator();
            const gain = ctx.createGain();
            bgmOsc.type = 'sine';
            bgmOsc.frequency.setValueAtTime(110, ctx.currentTime);
            gain.gain.setValueAtTime(0.02, ctx.currentTime);
            bgmOsc.connect(gain);
            gain.connect(ctx.destination);
            bgmOsc.start();
        }
    } else if (bgmOsc) {
        try { bgmOsc.stop(); } catch (e) {}
        bgmOsc = null;
    }
}

// Toast Helper (global so Admin script can use it too)
const toastEl = document.getElementById('toast');
window.showToast = function (message, duration = 2600) {
    toastEl.textContent = message;
    toastEl.classList.add('show');
    setTimeout(() => { toastEl.classList.remove('show'); }, duration);
};

function formatCurrency(amount) {
    return Number(amount || 0).toLocaleString('en-IN');
}

function togglePassVis(inputId, btn) {
    const el = document.getElementById(inputId);
    if (!el) return;
    if (el.type === 'password') {
        el.type = 'text';
        btn.textContent = '🙈';
    } else {
        el.type = 'password';
        btn.textContent = '👁️';
    }
}

// ====================================================
// 1. AUTHENTICATION & STARTUP SESSION CHECK
// ====================================================
const authScreen = document.getElementById('authScreen');
const gameAppRoot = document.getElementById('gameAppRoot');
const adminPortalRoot = document.getElementById('adminPortalRoot');

const showSignInTab = document.getElementById('showSignInTab');
const showSignUpTab = document.getElementById('showSignUpTab');
const signInForm = document.getElementById('signInForm');
const signUpForm = document.getElementById('signUpForm');

showSignInTab.addEventListener('click', () => {
    showSignInTab.classList.add('active');
    showSignUpTab.classList.remove('active');
    signInForm.style.display = 'block';
    signUpForm.style.display = 'none';
});

showSignUpTab.addEventListener('click', () => {
    showSignUpTab.classList.add('active');
    showSignInTab.classList.remove('active');
    signUpForm.style.display = 'block';
    signInForm.style.display = 'none';
});

// Live Password Character Validation
const regPassInput = document.getElementById('regPassword');
function checkPasswordRules(pwd) {
    const rules = {
        len: pwd.length >= 8 && pwd.length <= 32,
        upper: /[A-Z]/.test(pwd),
        lower: /[a-z]/.test(pwd),
        num: /[0-9]/.test(pwd),
        sym: /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>/?]/.test(pwd),
        noSpace: pwd.length > 0 && !/\s/.test(pwd) && /^[\x21-\x7E]+$/.test(pwd)
    };

    const setRuleUI = (id, passed) => {
        const el = document.getElementById(id);
        if (!el) return;
        const label = el.textContent.slice(2);
        el.textContent = (passed ? '✓ ' : '○ ') + label;
        el.classList.toggle('passed', passed);
    };

    setRuleUI('ruleLen', rules.len);
    setRuleUI('ruleUpper', rules.upper);
    setRuleUI('ruleLower', rules.lower);
    setRuleUI('ruleNum', rules.num);
    setRuleUI('ruleSym', rules.sym);
    setRuleUI('ruleNoSpace', rules.noSpace);

    return Object.values(rules).every(Boolean);
}

regPassInput.addEventListener('input', (e) => checkPasswordRules(e.target.value));

// Send Email OTP
const sendOtpBtn = document.getElementById('sendOtpBtn');
const otpNoticeBox = document.getElementById('otpNoticeBox');
let otpCooldownTimer = null;

sendOtpBtn.addEventListener('click', async () => {
    const email = document.getElementById('regEmail').value.trim();
    const errEl = document.getElementById('signupErrorMsg');
    errEl.textContent = '';

    if (!email || !email.includes('@')) {
        errEl.textContent = 'Please enter a valid Email ID first.';
        return;
    }

    sendOtpBtn.disabled = true;
    sendOtpBtn.textContent = 'Sending...';

    try {
        const res = await fetch(window.apiUrl('/api/auth/send-otp'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email })
        });
        const data = await res.json();

        if (!data.success) {
            errEl.textContent = data.message;
            sendOtpBtn.disabled = false;
            sendOtpBtn.textContent = 'Send OTP';
            return;
        }

        otpNoticeBox.style.display = 'block';
        otpNoticeBox.innerHTML = `📧 Verification OTP sent to <strong>${email}</strong> from GB_Colors Admin! Please check your Inbox (or Spam folder).`;

        // 45s cooldown
        let secs = 45;
        sendOtpBtn.textContent = `Resend (${secs}s)`;
        otpCooldownTimer = setInterval(() => {
            secs--;
            if (secs <= 0) {
                clearInterval(otpCooldownTimer);
                sendOtpBtn.disabled = false;
                sendOtpBtn.textContent = 'Send OTP';
            } else {
                sendOtpBtn.textContent = `Resend (${secs}s)`;
            }
        }, 1000);
    } catch (err) {
        errEl.textContent = 'Network error sending OTP.';
        sendOtpBtn.disabled = false;
        sendOtpBtn.textContent = 'Send OTP';
    }
});

// Sign Up Submit
signUpForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const email = document.getElementById('regEmail').value.trim();
    const otp = document.getElementById('regOtp').value.trim();
    const password = document.getElementById('regPassword').value;
    const confirmPass = document.getElementById('regConfirmPassword').value;
    const errEl = document.getElementById('signupErrorMsg');
    errEl.textContent = '';

    if (!checkPasswordRules(password)) {
        errEl.textContent = 'Please satisfy all password character rules.';
        return;
    }
    if (password !== confirmPass) {
        errEl.textContent = 'Passwords do not match.';
        return;
    }

    try {
        const res = await fetch(window.apiUrl('/api/auth/signup'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, otp, password })
        });
        const data = await res.json();

        if (!data.success) {
            errEl.textContent = data.message;
            return;
        }

        authToken = data.token;
        localStorage.setItem('gbcolors_token', authToken);
        showToast(data.message);
        enterUserArena(data.user);
    } catch (err) {
        errEl.textContent = 'Registration error. Please try again.';
    }
});

// Sign In Submit (User OR Hidden Admin)
signInForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const email = document.getElementById('loginEmail').value.trim();
    const password = document.getElementById('loginPassword').value;
    const errEl = document.getElementById('loginErrorMsg');
    errEl.textContent = '';

    try {
        const res = await fetch(window.apiUrl('/api/auth/signin'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });
        const data = await res.json();

        if (!data.success) {
            errEl.textContent = data.message;
            return;
        }

        authToken = data.token;
        localStorage.setItem('gbcolors_token', authToken);
        showToast(data.message);

        if (data.role === 'ADMIN') {
            await enterAdminPortal(data.admin);
        } else {
            enterUserArena(data.user);
        }
    } catch (err) {
        errEl.textContent = 'Sign In failed. Please try again.';
    }
});

// Auto-login Check on Page Load
async function checkInitialAuth() {
    if (!authToken) {
        authScreen.style.display = 'flex';
        return;
    }

    try {
        const res = await fetch(window.apiUrl('/api/auth/me'), {
            headers: { 'Authorization': `Bearer ${authToken}` }
        });
        const data = await res.json();

        if (!data.success) {
            localStorage.removeItem('gbcolors_token');
            authToken = null;
            authScreen.style.display = 'flex';
            return;
        }

        if (data.role === 'ADMIN') {
            await enterAdminPortal(data.admin);
        } else {
            enterUserArena(data.user);
        }
    } catch (e) {
        authScreen.style.display = 'flex';
    }
}

window.performLogout = async function () {
    try {
        if (authToken) {
            await fetch(window.apiUrl('/api/auth/logout'), {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${authToken}` }
            });
        }
    } catch (e) {}

    localStorage.removeItem('gbcolors_token');
    authToken = null;
    if (socket) {
        socket.disconnect();
        socket = null;
    }

    adminPortalRoot.innerHTML = '';
    adminPortalRoot.style.display = 'none';
    gameAppRoot.style.display = 'none';
    authScreen.style.display = 'flex';
    showToast('Signed out successfully.');
};

// Mount Protected Admin Portal dynamically ONLY for Admin
async function enterAdminPortal(adminInfo) {
    authScreen.style.display = 'none';
    gameAppRoot.style.display = 'none';
    adminPortalRoot.style.display = 'block';

    // Fetch protected HTML view
    const htmlRes = await fetch(window.apiUrl('/api/admin/view'), {
        headers: { 'Authorization': `Bearer ${authToken}` }
    });
    const adminHtml = await htmlRes.text();
    adminPortalRoot.innerHTML = adminHtml;

    // Fetch protected JS script and execute
    const jsRes = await fetch(window.apiUrl('/api/admin/script'), {
        headers: { 'Authorization': `Bearer ${authToken}` }
    });
    const adminJsCode = await jsRes.text();
    eval(adminJsCode);

    // Connect authenticated admin socket
    socket = io(window.getSocketUrl(), { auth: { token: authToken } });
    if (window.initAdminDashboard) {
        window.initAdminDashboard(authToken, socket, adminInfo);
    }
}

// Enter Regular User Game Arena
function enterUserArena(userInfo) {
    if (userInfo) user = { ...user, ...userInfo };
    authScreen.style.display = 'none';
    adminPortalRoot.style.display = 'none';
    adminPortalRoot.innerHTML = '';
    gameAppRoot.style.display = 'flex';

    connectUserSocket();
}

// ====================================================
// 2. USER GAME ARENA SOCKET & UI LOGIC
// ====================================================
function connectUserSocket() {
    if (socket) socket.disconnect();
    socket = io(window.getSocketUrl(), { auth: { token: authToken } });

    socket.on('init_state', (data) => {
        user = { ...user, ...data.user };
        activeBets = data.user.activeBets || [];

        updateActiveBalanceDisplay();
        updateButtonBadges();
        renderRecentResults(data.gameState.history);
        renderHistoryTable(data.gameState.history);
        renderMyBets(user.betHistory);
        renderActiveBets(activeBets);
        renderTransactions(user.transactions);

        currentPeriod = data.gameState.period;
        document.getElementById('periodNumber').textContent = currentPeriod;
        updateTimerDisplay(data.gameState.timeLeft, data.gameState.status);
    });

    socket.on('game_tick', (data) => {
        currentPeriod = data.period;
        document.getElementById('periodNumber').textContent = data.period;
        updateTimerDisplay(data.timeLeft, data.status);
    });

    socket.on('user_updated', (updatedUser) => {
        user = { ...user, ...updatedUser };
        updateActiveBalanceDisplay();
        renderTransactions(user.transactions);
    });

    socket.on('active_bets_updated', (bets) => {
        activeBets = bets;
        renderActiveBets(activeBets);
        updateButtonBadges();
    });

    socket.on('round_result', (data) => {
        renderRecentResults(data.history);
        renderHistoryTable(data.history);

        activeBets = [];
        renderActiveBets(activeBets);
        updateButtonBadges();
    });

    socket.on('bet_settled', (data) => {
        user.demoBalance = data.demoBalance;
        user.realBalance = data.realBalance;
        updateActiveBalanceDisplay();

        user.betHistory.unshift(data.bet);
        if (user.betHistory.length > 60) user.betHistory.pop();
        renderMyBets(user.betHistory);

        showOutcomePopup(data.bet);
    });

    socket.on('connect_error', (err) => {
        if (err.message === 'AUTH_REQUIRED') {
            window.performLogout();
        }
    });
}

// DOM Elements inside User Game Arena
const activeBalanceDisplay = document.getElementById('activeBalanceDisplay');
const modeDemoBtn = document.getElementById('modeDemoBtn');
const modeRealBtn = document.getElementById('modeRealBtn');
const userProfileBtn = document.getElementById('userProfileBtn');
const userStatusText = document.getElementById('userStatusText');
const withdrawNavBtn = document.getElementById('withdrawNavBtn');

const timerSecEl = document.getElementById('timerSec');
const timerMinEl = document.getElementById('timerMin');
const countdownClockEl = document.getElementById('countdownClock');
const lockOverlayEl = document.getElementById('lockOverlay');
const recentDotsListEl = document.getElementById('recentDotsList');

const historyTableBodyEl = document.getElementById('historyTableBody');
const activeBetsListEl = document.getElementById('activeBetsList');
const activeBetsCountBadgeEl = document.getElementById('activeBetsCountBadge');
const myBetsListEl = document.getElementById('myBetsList');
const myBetsCountEl = document.getElementById('myBetsCount');
const transactionsListEl = document.getElementById('transactionsList');

function updateActiveBalanceDisplay() {
    const isReal = user.mode === 'REAL';
    const bal = isReal ? user.realBalance : user.demoBalance;
    activeBalanceDisplay.textContent = formatCurrency(bal);

    modeRealBtn.classList.toggle('active', isReal);
    modeDemoBtn.classList.toggle('active', !isReal);
    userStatusText.textContent = user.username || 'Account';
}

function updateButtonBadges() {
    document.querySelectorAll('.bet-placed-badge').forEach(badge => {
        badge.classList.remove('active');
        badge.textContent = '₹0';
    });

    const totals = {};
    activeBets.forEach(b => {
        const key = String(b.choice).toUpperCase();
        totals[key] = (totals[key] || 0) + b.amount;
    });

    for (const [choice, amt] of Object.entries(totals)) {
        const badge = document.getElementById(`badge-${choice}`);
        if (badge && amt > 0) {
            badge.textContent = `₹${formatCurrency(amt)}`;
            badge.classList.add('active');
        }
    }
}

function updateTimerDisplay(seconds) {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    timerMinEl.textContent = String(mins).padStart(2, '0');
    timerSecEl.textContent = String(secs).padStart(2, '0');

    if (seconds <= 5 && seconds > 0) {
        countdownClockEl.classList.add('timer-pulse');
        lockOverlayEl.classList.add('active');
        playTickSound();
    } else {
        countdownClockEl.classList.remove('timer-pulse');
        lockOverlayEl.classList.remove('active');
    }
}

function getNumberBadgeClass(num, colors) {
    if (num === 0) return 'bg-red-violet';
    if (num === 5) return 'bg-green-violet';
    if (colors && colors.includes('green')) return 'bg-green';
    return 'bg-red';
}

function renderRecentResults(historyList) {
    if (!historyList || !historyList.length) return;
    recentDotsListEl.innerHTML = historyList.slice(0, 7).map(item => {
        return `<span class="recent-badge ${getNumberBadgeClass(item.number, item.colors)}">${item.number}</span>`;
    }).join('');
}

function renderHistoryTable(historyList) {
    if (!historyList || !historyList.length) return;
    historyTableBodyEl.innerHTML = historyList.map(item => {
        const badgeClass = getNumberBadgeClass(item.number, item.colors);
        const colorLabels = item.colors.map(c => c.toUpperCase()).join(' + ');
        const sizeClass = item.size === 'big' ? 'tag-big' : 'tag-small';
        return `
            <tr>
                <td class="history-period-col">${item.period.slice(-4)}</td>
                <td><div class="history-num-badge ${badgeClass}">${item.number}</div></td>
                <td><span class="tag-size ${sizeClass}">${item.size.toUpperCase()}</span></td>
                <td style="font-size: 11px; font-weight: 700; color: ${item.colors.includes('green') ? '#10b981' : '#ef4444'}">${colorLabels}</td>
            </tr>
        `;
    }).join('');
}

function renderActiveBets(bets) {
    activeBetsCountBadgeEl.textContent = bets.length;
    if (!bets || bets.length === 0) {
        activeBetsListEl.innerHTML = `<div class="empty-state">No active bets in the current round.</div>`;
        return;
    }
    activeBetsListEl.innerHTML = bets.map(b => `
        <div class="bet-card-item active-bet">
            <div class="bet-card-left">
                <span class="bet-card-target">${b.type}: <strong>${b.choice}</strong> (₹${formatCurrency(b.amount)})</span>
                <span class="bet-card-period">Issue: ${b.period.slice(-4)} | Mode: ${b.mode}</span>
            </div>
            <div class="bet-card-right">
                <button class="btn-card-cancel" onclick="openCancelModal('${b.id}', '${b.choice}', ${b.amount})">Cancel Bet</button>
            </div>
        </div>
    `).join('');
}

function renderMyBets(betHistory) {
    myBetsCountEl.textContent = betHistory ? betHistory.length : 0;
    if (!betHistory || betHistory.length === 0) {
        myBetsListEl.innerHTML = `<div class="empty-state">No bets recorded yet.</div>`;
        return;
    }
    myBetsListEl.innerHTML = betHistory.map(b => {
        const statusClass = b.status === 'CANCELLED' ? 'cancelled' : (b.win ? 'won' : 'lost');
        const profitClass = b.win ? 'win' : 'lose';
        const sign = b.win ? '+' : '';
        const profitText = b.status === 'CANCELLED' ? 'CANCELLED (Refunded)' : `${sign}₹${formatCurrency(b.netProfit)}`;
        return `
            <div class="bet-card-item ${statusClass}">
                <div class="bet-card-left">
                    <span class="bet-card-target">${b.type}: <strong>${b.choice}</strong> (₹${formatCurrency(b.amount)})</span>
                    <span class="bet-card-period">Issue: ${b.period.slice(-4)} | Mode: ${b.mode || 'DEMO'}</span>
                </div>
                <div class="bet-card-right">
                    <span class="bet-profit-amt ${profitClass}">${profitText}</span>
                </div>
            </div>
        `;
    }).join('');
}

function renderTransactions(txs) {
    if (!txs || txs.length === 0) {
        transactionsListEl.innerHTML = `<div class="empty-state">No deposits or withdrawals yet.</div>`;
        return;
    }
    transactionsListEl.innerHTML = txs.map(t => {
        const isDep = t.type === 'DEPOSIT';
        const d = new Date(t.timestamp);
        const timeStr = d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        const statusColor = t.status.includes('PAID') || t.status === 'SUCCESS' ? '#10b981' : (t.status.includes('PENDING') ? '#f59e0b' : '#ef4444');
        return `
            <div class="bet-card-item ${isDep ? 'won' : 'active-bet'}">
                <div class="bet-card-left">
                    <span class="bet-card-target">${t.type} — <strong>₹${formatCurrency(t.amount)}</strong></span>
                    <span class="bet-card-period">${t.account || t.utr || ''} | ${timeStr}</span>
                </div>
                <div class="bet-card-right">
                    <span style="font-size: 11px; font-weight: 800; color: ${statusColor};">${t.status}</span>
                </div>
            </div>
        `;
    }).join('');
}

// Strict Bet Cancel Flow
const cancelConfirmModal = document.getElementById('cancelConfirmModal');
const cancelTargetName = document.getElementById('cancelTargetName');
const cancelAmountTxt = document.getElementById('cancelAmountTxt');

window.openCancelModal = function (betId, choice, amount) {
    betPendingCancelId = betId;
    cancelTargetName.textContent = choice;
    cancelAmountTxt.textContent = `₹${formatCurrency(amount)}`;
    cancelConfirmModal.classList.add('active');
};

document.getElementById('closeCancelModal').addEventListener('click', () => cancelConfirmModal.classList.remove('active'));
document.getElementById('abortCancelBtn').addEventListener('click', () => cancelConfirmModal.classList.remove('active'));
document.getElementById('executeCancelBtn').addEventListener('click', () => {
    if (!betPendingCancelId || !socket) return;
    socket.emit('cancel_bet', betPendingCancelId, (res) => {
        cancelConfirmModal.classList.remove('active');
        if (res.success) playBetSound();
        showToast(res.message);
        betPendingCancelId = null;
    });
});

// Fast 1-Tap Bet Modal
const betModalEl = document.getElementById('betModal');
const modalBetTargetEl = document.getElementById('modalBetTarget');
const summaryTotalAmtEl = document.getElementById('summaryTotalAmt');
const summaryEstPayoutEl = document.getElementById('summaryEstPayout');
const confirmBetBtnEl = document.getElementById('confirmBetBtn');

document.querySelectorAll('.bet-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        getAudioContext();
        const type = btn.getAttribute('data-type');
        const choice = btn.getAttribute('data-choice');

        currentSelectedBet.type = type;
        currentSelectedBet.choice = choice;
        currentSelectedBet.baseAmount = 10;
        currentSelectedBet.multiplier = 1;
        currentSelectedBet.payoutRate = type === 'NUMBER' ? 9 : (choice === 'VIOLET' ? 4.5 : 2);

        modalBetTargetEl.textContent = `${type}: ${choice}`;
        updateModalCalculations();

        document.querySelectorAll('#betModal .chip-btn').forEach(c => c.classList.remove('active'));
        document.querySelector('#betModal .chip-btn[data-amount="10"]')?.classList.add('active');
        document.querySelectorAll('#betModal .mult-btn').forEach(m => m.classList.remove('active'));
        document.querySelector('#betModal .mult-btn[data-mult="1"]')?.classList.add('active');

        betModalEl.classList.add('active');
    });
});

function updateModalCalculations() {
    const totalAmount = currentSelectedBet.baseAmount * currentSelectedBet.multiplier;
    const estPayout = Math.floor(totalAmount * currentSelectedBet.payoutRate);
    summaryTotalAmtEl.textContent = `₹${formatCurrency(totalAmount)}`;
    summaryEstPayoutEl.textContent = `₹${formatCurrency(estPayout)}`;
    confirmBetBtnEl.textContent = `Confirm & Place Bet (₹${formatCurrency(totalAmount)})`;
}

document.querySelectorAll('#betModal .chip-btn').forEach(chip => {
    chip.addEventListener('click', () => {
        document.querySelectorAll('#betModal .chip-btn').forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        currentSelectedBet.baseAmount = parseInt(chip.getAttribute('data-amount'), 10);
        updateModalCalculations();
    });
});

document.querySelectorAll('#betModal .mult-btn').forEach(mBtn => {
    mBtn.addEventListener('click', () => {
        document.querySelectorAll('#betModal .mult-btn').forEach(m => m.classList.remove('active'));
        mBtn.classList.add('active');
        currentSelectedBet.multiplier = parseInt(mBtn.getAttribute('data-mult'), 10);
        updateModalCalculations();
    });
});

confirmBetBtnEl.addEventListener('click', () => {
    const totalAmount = currentSelectedBet.baseAmount * currentSelectedBet.multiplier;
    socket.emit('place_bet', {
        type: currentSelectedBet.type,
        choice: currentSelectedBet.choice,
        amount: totalAmount
    }, (res) => {
        if (res.success) {
            playBetSound();
            betModalEl.classList.remove('active');
        }
        showToast(res.message);
    });
});

document.getElementById('closeBetModal').addEventListener('click', () => betModalEl.classList.remove('active'));

// Mode Switcher
modeDemoBtn.addEventListener('click', () => {
    socket.emit('switch_mode', 'DEMO', (res) => {
        if (res.success) showToast('Switched to Demo Practice Mode');
    });
});
modeRealBtn.addEventListener('click', () => {
    socket.emit('switch_mode', 'REAL', (res) => {
        if (res.success) showToast('Switched to Real Money Mode!');
    });
});

// Deposit Modal
const depositModal = document.getElementById('depositModal');
const tabDemoReload = document.getElementById('tabDemoReload');
const tabRealDeposit = document.getElementById('tabRealDeposit');
const sectionDemoReload = document.getElementById('sectionDemoReload');
const sectionRealDeposit = document.getElementById('sectionRealDeposit');

document.getElementById('walletActionBtn').addEventListener('click', () => depositModal.classList.add('active'));
document.getElementById('closeDepositModal').addEventListener('click', () => depositModal.classList.remove('active'));

tabDemoReload.addEventListener('click', () => {
    tabDemoReload.classList.add('active');
    tabRealDeposit.classList.remove('active');
    sectionDemoReload.style.display = 'block';
    sectionRealDeposit.style.display = 'none';
});
tabRealDeposit.addEventListener('click', () => {
    tabRealDeposit.classList.add('active');
    tabDemoReload.classList.remove('active');
    sectionRealDeposit.style.display = 'block';
    sectionDemoReload.style.display = 'none';
});

window.addDemoBalance = function (amt) {
    socket.emit('add_demo_balance', amt, (res) => {
        if (res.success) {
            playBetSound();
            showToast(res.message);
            depositModal.classList.remove('active');
        }
    });
};

window.setDepositAmt = function (amt, btnEl) {
    selectedDepositAmt = amt;
    document.querySelectorAll('#depAmtChips .chip-btn').forEach(b => b.classList.remove('active'));
    if (btnEl) btnEl.classList.add('active');
};

document.getElementById('submitDepositBtn').addEventListener('click', () => {
    const utr = document.getElementById('depositUtrInput').value.trim();
    if (!utr || utr.length < 8) {
        showToast('Please enter a valid 12-digit UPI UTR number!');
        return;
    }
    socket.emit('submit_deposit', { amount: selectedDepositAmt, utr, method: 'UPI' }, (res) => {
        if (res.success) {
            playWinSound();
            document.getElementById('depositUtrInput').value = '';
            depositModal.classList.remove('active');
        }
        showToast(res.message);
    });
});

// Withdrawal Modal
const withdrawModal = document.getElementById('withdrawModal');
withdrawNavBtn.addEventListener('click', () => {
    document.getElementById('withdrawAvailableBal').textContent = `₹${formatCurrency(user.realBalance)}`;
    withdrawModal.classList.add('active');
});
document.getElementById('closeWithdrawModal').addEventListener('click', () => withdrawModal.classList.remove('active'));

document.getElementById('submitWithdrawBtn').addEventListener('click', () => {
    const amount = parseInt(document.getElementById('withdrawAmountInput').value, 10);
    const upiOrBank = document.getElementById('withdrawAccountInput').value.trim();
    socket.emit('submit_withdrawal', { amount, upiOrBank }, (res) => {
        if (res.success) {
            document.getElementById('withdrawAmountInput').value = '';
            document.getElementById('withdrawAccountInput').value = '';
            withdrawModal.classList.remove('active');
        }
        showToast(res.message);
    });
});

// User Profile Modal
const profileModal = document.getElementById('profileModal');
userProfileBtn.addEventListener('click', () => {
    document.getElementById('profUsername').textContent = user.username;
    document.getElementById('profEmail').textContent = user.email;
    document.getElementById('profVipBadge').textContent = user.userId;
    document.getElementById('profDemoBal').textContent = `₹${formatCurrency(user.demoBalance)}`;
    document.getElementById('profRealBal').textContent = `₹${formatCurrency(user.realBalance)}`;
    profileModal.classList.add('active');
});
document.getElementById('closeProfileModal').addEventListener('click', () => profileModal.classList.remove('active'));
document.getElementById('userLogoutBtn').addEventListener('click', () => {
    profileModal.classList.remove('active');
    window.performLogout();
});

// Settings Modal
const settingsModal = document.getElementById('settingsModal');
document.getElementById('settingsBtn').addEventListener('click', () => settingsModal.classList.add('active'));
document.getElementById('closeSettingsModal').addEventListener('click', () => settingsModal.classList.remove('active'));

const settingSfx = document.getElementById('settingSfx');
const settingBgm = document.getElementById('settingBgm');
const settingAutoClose = document.getElementById('settingAutoClose');
const settingVibrate = document.getElementById('settingVibrate');
const soundIconEl = document.getElementById('soundIcon');

settingSfx.addEventListener('change', (e) => { settings.sfx = e.target.checked; });
settingBgm.addEventListener('change', (e) => { toggleBgm(e.target.checked); });
settingAutoClose.addEventListener('change', (e) => { settings.autoClose = e.target.checked; });
settingVibrate.addEventListener('change', (e) => { settings.vibrate = e.target.checked; });

document.getElementById('soundToggleBtn').addEventListener('click', () => {
    settings.sfx = !settings.sfx;
    soundIconEl.textContent = settings.sfx ? '🔊' : '🔇';
    settingSfx.checked = settings.sfx;
    showToast(settings.sfx ? 'Sound Unmuted' : 'Sound Muted');
});

// Outcome Popup
const resultPopupEl = document.getElementById('resultPopup');
function showOutcomePopup(bet) {
    const badgeClass = getNumberBadgeClass(bet.resultNumber, bet.resultColors);
    document.getElementById('resultNumberBall').className = `result-ball ${badgeClass}`;
    document.getElementById('resultNumberBall').textContent = bet.resultNumber;
    document.getElementById('resultPeriodTxt').textContent = `Period: ${bet.period} | Result: ${bet.resultColors.join('+').toUpperCase()}`;

    const iconEl = document.getElementById('resultIcon');
    const headEl = document.getElementById('resultHeading');
    const amtEl = document.getElementById('resultProfitAmt');

    if (bet.win) {
        playWinSound();
        iconEl.textContent = '🎉';
        headEl.textContent = 'Congratulations!';
        headEl.style.color = '#10b981';
        amtEl.textContent = `+₹${formatCurrency(bet.netProfit)}`;
        amtEl.style.color = '#10b981';
    } else {
        playLoseSound();
        iconEl.textContent = '💔';
        headEl.textContent = 'Better Luck Next Time!';
        headEl.style.color = '#ef4444';
        amtEl.textContent = `-₹${formatCurrency(bet.amount)}`;
        amtEl.style.color = '#ef4444';
    }

    resultPopupEl.classList.add('active');
    if (settings.autoClose) {
        setTimeout(() => resultPopupEl.classList.remove('active'), 4000);
    }
}
document.getElementById('closeResultBtn').addEventListener('click', () => resultPopupEl.classList.remove('active'));

// User Panel Tab Switching
document.querySelectorAll('.tab-btn').forEach(tab => {
    tab.addEventListener('click', () => {
        document.querySelectorAll('.tab-btn').forEach(t => t.classList.remove('active'));
        document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
        tab.classList.add('active');
        document.getElementById(tab.getAttribute('data-tab'))?.classList.add('active');
    });
});

// Initialize on load
checkInitialAuth();
