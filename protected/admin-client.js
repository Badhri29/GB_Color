(function () {
    let adminToken = null;
    let adminSocket = null;
    let latestAnalytics = null;
    let smtpFormPopulated = false;
    let gameConfigPopulated = false;
    let paymentConfigPopulated = false;
    let blockTargetEmail = '';
    let selectedBlockType = 'TEMPORARY';

    window.initAdminDashboard = function (token, socket, adminInfo) {
        adminToken = token;
        adminSocket = socket;

        const emailEl = document.getElementById('admHeaderEmail');
        const drawerEmailEl = document.getElementById('admDrawerEmail');
        if (emailEl && adminInfo) emailEl.textContent = adminInfo.email;
        if (drawerEmailEl && adminInfo) drawerEmailEl.textContent = adminInfo.email;

        function switchAdminTab(tabId) {
            document.querySelectorAll('.adm-tab-btn').forEach(b => {
                b.classList.toggle('active', b.getAttribute('data-admtab') === tabId);
            });
            document.querySelectorAll('.adm-tab-panel').forEach(p => {
                p.classList.toggle('active', p.id === tabId);
            });
        }
        window.switchAdminTab = switchAdminTab;

        document.querySelectorAll('.adm-tab-btn').forEach(btn => {
            btn.addEventListener('click', () => switchAdminTab(btn.getAttribute('data-admtab')));
        });

        // Filter Pending Requests Queue (All, Recharges, Withdrawals)
        const fAll = document.getElementById('filterPendingAllBtn');
        const fDep = document.getElementById('filterPendingDepBtn');
        const fWth = document.getElementById('filterPendingWthBtn');
        const wrapDep = document.getElementById('admPendingDepWrap');
        const wrapWth = document.getElementById('admPendingWthWrap');

        fAll?.addEventListener('click', () => {
            fAll.classList.add('active');
            fDep?.classList.remove('active');
            fWth?.classList.remove('active');
            if (wrapDep) wrapDep.style.display = 'block';
            if (wrapWth) wrapWth.style.display = 'block';
        });
        fDep?.addEventListener('click', () => {
            fDep.classList.add('active');
            fAll?.classList.remove('active');
            fWth?.classList.remove('active');
            if (wrapDep) wrapDep.style.display = 'block';
            if (wrapWth) wrapWth.style.display = 'none';
        });
        fWth?.addEventListener('click', () => {
            fWth.classList.add('active');
            fAll?.classList.remove('active');
            fDep?.classList.remove('active');
            if (wrapDep) wrapDep.style.display = 'none';
            if (wrapWth) wrapWth.style.display = 'block';
        });

        // Build 0-9 Force buttons
        const forceRow = document.getElementById('forceNumsRow');
        if (forceRow) {
            forceRow.innerHTML = Array.from({ length: 10 }, (_, i) => `
                <button class="force-num-btn" onclick="setOutcomeMode('MANUAL', ${i})">${i}</button>
            `).join('');
        }

        // Build 0-9 Number Pool grid
        const poolNumbersGrid = document.getElementById('poolNumbersGrid');
        if (poolNumbersGrid) {
            poolNumbersGrid.innerHTML = Array.from({ length: 10 }, (_, i) => `
                <div class="pool-num-box">
                    <span class="num-lbl">#${i}</span>
                    <strong id="poolNum-${i}">₹0</strong>
                </div>
            `).join('');
        }

        // Logout buttons (managed by unified header/drawer, fallback wired if present)
        document.getElementById('admLogoutBtn')?.addEventListener('click', () => window.performLogout());
        document.getElementById('admDrawerLogoutBtn')?.addEventListener('click', () => window.performLogout());

        // Admin Notifications Chat Box Wiring
        let notifScope = 'ALL';
        const scopeAllBtn = document.getElementById('admNotifScopeAll');
        const scopeUserBtn = document.getElementById('admNotifScopeUser');
        const targetWrap = document.getElementById('admNotifTargetWrap');
        scopeAllBtn?.addEventListener('click', () => {
            notifScope = 'ALL';
            scopeAllBtn.classList.add('active');
            scopeUserBtn?.classList.remove('active');
            if (targetWrap) targetWrap.style.display = 'none';
        });
        scopeUserBtn?.addEventListener('click', () => {
            notifScope = 'USER';
            scopeUserBtn.classList.add('active');
            scopeAllBtn?.classList.remove('active');
            if (targetWrap) targetWrap.style.display = 'block';
        });

        async function sendAdminChatNotification() {
            const targetEmail = document.getElementById('admNotifTargetUser')?.value || '';
            const title = document.getElementById('admNotifTitleInput')?.value?.trim() || '';
            const msgInput = document.getElementById('admNotifMsgInput');
            const message = msgInput?.value?.trim() || '';
            if (!message) {
                window.showToast('Please enter a notification message.', 'error');
                return;
            }
            if (notifScope === 'USER' && !targetEmail) {
                window.showToast('Please select a target verified user.', 'error');
                return;
            }
            const sendBtn = document.getElementById('admNotifSendBtn');
            if (sendBtn) sendBtn.disabled = true;
            try {
                const res = await fetch(window.apiUrl('/api/admin/notify'), {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${adminToken}`
                    },
                    body: JSON.stringify({
                        scope: notifScope,
                        targetEmail,
                        title,
                        message
                    })
                });
                const data = await res.json();
                window.showToast(data.message || (res.ok ? 'Notification sent!' : 'Failed to send'), res.ok ? 'success' : 'error');
                if (data.success) {
                    if (msgInput) msgInput.value = '';
                    const tIn = document.getElementById('admNotifTitleInput');
                    if (tIn) tIn.value = '';
                }
            } finally {
                if (sendBtn) sendBtn.disabled = false;
            }
        }
        document.getElementById('admNotifSendBtn')?.addEventListener('click', sendAdminChatNotification);
        document.getElementById('admNotifMsgInput')?.addEventListener('keydown', (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault();
                sendAdminChatNotification();
            }
        });

        // Search users filter
        document.getElementById('admUserSearch')?.addEventListener('input', () => {
            if (latestAnalytics) renderUsersTable(latestAnalytics.users);
        });

        // Save Game Timing & Profit Margin (Settings Tab + Inline Margin)
        async function saveGameConfig(cycleVal, closeVal, marginVal) {
            const res = await fetch(window.apiUrl('/api/admin/game-config'), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${adminToken}`
                },
                body: JSON.stringify({
                    cycleDurationSeconds: cycleVal,
                    betCloseSeconds: closeVal,
                    adminMinProfitPercent: marginVal
                })
            });
            const data = await res.json();
            window.showToast(data.message);
        }

        document.getElementById('saveGameConfigBtn')?.addEventListener('click', () => {
            const c = parseInt(document.getElementById('cfgCycleSeconds').value, 10);
            const cl = parseInt(document.getElementById('cfgBetCloseSeconds').value, 10);
            const m = parseInt(document.getElementById('cfgAdminMarginPct').value, 10);
            saveGameConfig(c, cl, m);
        });

        document.getElementById('admSaveInlineMarginBtn')?.addEventListener('click', () => {
            const c = latestAnalytics ? latestAnalytics.cycleDurationSeconds : 30;
            const cl = latestAnalytics ? latestAnalytics.betCloseSeconds : 5;
            const m = parseInt(document.getElementById('admInlineMarginInput').value, 10);
            document.getElementById('cfgAdminMarginPct').value = m;
            saveGameConfig(c, cl, m);
        });

        // Save Payment Config
        document.getElementById('savePaymentConfigBtn')?.addEventListener('click', async () => {
            const payload = {
                upiId: document.getElementById('cfgUpiId').value,
                upiPayeeName: document.getElementById('cfgUpiPayeeName').value,
                razorpayKeyId: document.getElementById('cfgRzpKeyId').value,
                razorpayKeySecret: document.getElementById('cfgRzpKeySecret').value,
                minDeposit: parseInt(document.getElementById('cfgMinDeposit').value, 10),
                maxDeposit: parseInt(document.getElementById('cfgMaxDeposit').value, 10),
                minWithdraw: parseInt(document.getElementById('cfgMinWithdraw').value, 10),
                maxWithdraw: parseInt(document.getElementById('cfgMaxWithdraw').value, 10),
                activeCurrency: document.getElementById('cfgActiveCurrency')?.value || 'INR',
                upiWebhookSecret: document.getElementById('cfgUpiWebhookSecret')?.value || '',
                smsWebhookToken: document.getElementById('cfgSmsWebhookToken')?.value || '',
                paypalClientId: document.getElementById('cfgPaypalClientId')?.value || '',
                paypalClientSecret: document.getElementById('cfgPaypalClientSecret')?.value || '',
                paypalMode: document.getElementById('cfgPaypalMode')?.value || 'sandbox'
            };
            const res = await fetch(window.apiUrl('/api/admin/payment-config'), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${adminToken}`
                },
                body: JSON.stringify(payload)
            });
            const data = await res.json();
            window.showToast(data.message || (res.ok ? 'Saved!' : 'Invalid payment config'));
        });

        // Save SMTP settings
        document.getElementById('saveSmtpBtn')?.addEventListener('click', async () => {
            const payload = {
                adminEmail: document.getElementById('cfgAdminEmail').value,
                host: document.getElementById('cfgSmtpHost').value,
                port: document.getElementById('cfgSmtpPort').value,
                authUser: document.getElementById('cfgSmtpUser').value,
                authPass: document.getElementById('cfgSmtpPass').value,
                fromName: document.getElementById('cfgFromName').value
            };

            const res = await fetch(window.apiUrl('/api/admin/smtp'), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${adminToken}`
                },
                body: JSON.stringify(payload)
            });
            const data = await res.json();
            window.showToast(data.message);
        });

        // Change Admin Password
        document.getElementById('changeAdminPassBtn')?.addEventListener('click', async () => {
            const newPassword = document.getElementById('cfgNewAdminPass').value;
            const res = await fetch(window.apiUrl('/api/admin/change-password'), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${adminToken}`
                },
                body: JSON.stringify({ newPassword })
            });
            const data = await res.json();
            window.showToast(data.message);
            if (data.success) {
                document.getElementById('cfgNewAdminPass').value = '';
            }
        });

        // Admin Block Modal Wiring
        const blockModal = document.getElementById('admBlockModal');
        const tempBtn = document.getElementById('admBlockTypeTempBtn');
        const permBtn = document.getElementById('admBlockTypePermBtn');
        const tempWrap = document.getElementById('admTempDurationWrap');

        tempBtn?.addEventListener('click', () => {
            selectedBlockType = 'TEMPORARY';
            tempBtn.classList.add('active');
            permBtn?.classList.remove('active');
            if (tempWrap) tempWrap.style.display = 'block';
        });
        permBtn?.addEventListener('click', () => {
            selectedBlockType = 'PERMANENT';
            permBtn.classList.add('active');
            tempBtn?.classList.remove('active');
            if (tempWrap) tempWrap.style.display = 'none';
        });

        document.getElementById('closeAdmBlockModal')?.addEventListener('click', () => blockModal?.classList.remove('active'));
        document.getElementById('cancelAdmBlockBtn')?.addEventListener('click', () => blockModal?.classList.remove('active'));

        document.getElementById('confirmAdmBlockBtn')?.addEventListener('click', async () => {
            const hours = parseInt(document.getElementById('admBlockHoursInput').value, 10) || 24;
            const reason = document.getElementById('admBlockReasonInput').value.trim();
            const res = await fetch(window.apiUrl('/api/admin/user/block'), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${adminToken}`
                },
                body: JSON.stringify({
                    email: blockTargetEmail,
                    blockType: selectedBlockType,
                    durationHours: hours,
                    reason
                })
            });
            const data = await res.json();
            window.showToast(data.message);
            if (data.success) {
                blockModal?.classList.remove('active');
            }
        });

        // Listen to socket updates
        adminSocket.on('admin_init', (data) => {
            if (data.analytics) renderAdminAnalytics(data.analytics);
            if (data.history) renderAdminHistoryTables(data.history);
        });

        adminSocket.on('admin_live_update', (analytics) => {
            renderAdminAnalytics(analytics);
            if (analytics && analytics.history) renderAdminHistoryTables(analytics.history);
        });

        adminSocket.on('game_tick', (tick) => {
            const pEl = document.getElementById('admPeriodNum');
            const tEl = document.getElementById('admTimeLeft');
            const sEl = document.getElementById('admRoundStatus');
            const tcEl = document.getElementById('admTimingChip');
            if (pEl) pEl.textContent = tick.period;
            if (tEl) {
                const m = Math.floor(tick.timeLeft / 60);
                const s = tick.timeLeft % 60;
                tEl.textContent = `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
            }
            if (sEl) {
                sEl.textContent = tick.status;
                sEl.className = `status-chip ${tick.status === 'LOCKED' ? 'chip-danger' : 'chip-ok'}`;
            }
            if (tcEl && tick.cycleDurationSeconds) {
                tcEl.textContent = `${tick.cycleDurationSeconds}s / Close ${tick.betCloseSeconds}s`;
            }
        });
    };

    window.applyTimingPreset = function (cycle, close) {
        const cEl = document.getElementById('cfgCycleSeconds');
        const clEl = document.getElementById('cfgBetCloseSeconds');
        if (cEl) cEl.value = cycle;
        if (clEl) clEl.value = close;
    };

    window.setBlockHours = function (hrs) {
        const el = document.getElementById('admBlockHoursInput');
        if (el) el.value = hrs;
    };

    window.openBlockUserModal = function (email) {
        blockTargetEmail = email;
        selectedBlockType = 'TEMPORARY';
        document.getElementById('admBlockTargetEmail').textContent = email;
        document.getElementById('admBlockReasonInput').value = '';
        document.getElementById('admBlockHoursInput').value = 24;
        document.getElementById('admBlockTypeTempBtn')?.classList.add('active');
        document.getElementById('admBlockTypePermBtn')?.classList.remove('active');
        document.getElementById('admTempDurationWrap').style.display = 'block';
        document.getElementById('admBlockModal')?.classList.add('active');
    };

    window.viewProofScreenshot = function (depositId) {
        const dep = (latestAnalytics && latestAnalytics.depositRequests ? latestAnalytics.depositRequests : []).find(d => d.id === depositId);
        if (!dep || !dep.proofImage) return;
        const modal = document.getElementById('admProofModal');
        const img = document.getElementById('admProofFullImg');
        const meta = document.getElementById('admProofMetaTxt');
        if (img) img.src = dep.proofImage;
        if (meta) meta.textContent = `Deposit ID: ${dep.id} | Email: ${dep.email} | Amount: ₹${fmt(dep.amount)} | UTR: ${dep.utrOrPaymentId || '—'}`;
        if (modal) modal.style.display = 'flex';
    };

    window.unblockUser = async function (email) {
        const res = await fetch(window.apiUrl('/api/admin/user/unblock'), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${adminToken}`
            },
            body: JSON.stringify({ email })
        });
        const data = await res.json();
        window.showToast(data.message);
    };

    window.setOutcomeMode = function (mode, forcedNum = null) {
        if (!adminSocket) return;
        adminSocket.emit('admin_set_outcome_mode', { mode, forcedNum }, (res) => {
            if (res && res.success) {
                window.showToast(
                    mode === 'MANUAL'
                        ? `Forced Next Winning Number to #${forcedNum}`
                        : `Outcome Engine set to ${mode.replace('_', ' ')}`
                );
            }
        });
    };

    window.processDepositAction = async function (requestId, action) {
        const res = await fetch(window.apiUrl('/api/admin/deposit/action'), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${adminToken}`
            },
            body: JSON.stringify({ requestId, action })
        });
        const data = await res.json();
        window.showToast(data.message);
    };

    window.processWithdrawAction = async function (requestId, action) {
        const res = await fetch(window.apiUrl('/api/admin/withdraw/action'), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${adminToken}`
            },
            body: JSON.stringify({ requestId, action })
        });
        const data = await res.json();
        window.showToast(data.message);
    };

    window.adjustUserBalance = async function (email, amount) {
        const res = await fetch(window.apiUrl('/api/admin/user/adjust'), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${adminToken}`
            },
            body: JSON.stringify({ email, amount })
        });
        const data = await res.json();
        window.showToast(data.message);
    };

    function fmt(n) {
        return Number(n || 0).toLocaleString('en-IN');
    }

    function fmtDate(ts) {
        if (!ts) return 'Never';
        const d = new Date(ts);
        return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }

    function getBallClass(num, colors) {
        if (num === 0) return 'bg-red-violet';
        if (num === 5) return 'bg-green-violet';
        if (colors && colors.includes('green')) return 'bg-green';
        return 'bg-red';
    }

    function renderUpcomingPreview(data) {
        const card = document.getElementById('admUpcomingPreviewCard');
        const titleEl = document.getElementById('admUpcomingTitle');
        const countEl = document.getElementById('admUpcomingCountdown');
        const bodyEl = document.getElementById('admUpcomingBody');
        if (!card || !bodyEl) return;

        if (data.status === 'LOCKED' && data.upcomingResult) {
            const u = data.upcomingResult;
            const ballClass = getBallClass(u.number, u.colors);
            const profitSign = u.houseProfit >= 0 ? '+' : '-';
            const profitColor = u.houseProfit >= 0 ? '#10b981' : '#ef4444';
            card.classList.add('locked-active');
            titleEl.textContent = '🔒 BETTING CLOSED — UPCOMING RESULT PREVIEW (BEFORE REVEAL)';
            countEl.textContent = `Reveals to Users in ${u.revealInSeconds}s`;
            countEl.className = 'upcoming-countdown-badge danger';

            bodyEl.innerHTML = `
                <div class="upcoming-grid">
                    <div class="upcoming-ball-wrap">
                        <div class="history-num-badge ${ballClass}" style="width:52px;height:52px;font-size:22px;">${u.number}</div>
                        <div>
                            <strong style="font-size:16px;">${u.colors.map(c => c.toUpperCase()).join(' + ')}</strong>
                            <div style="font-size:12px;color:#94a3b8;">Size: ${u.size.toUpperCase()} | Mode: ${u.modeUsed}</div>
                        </div>
                    </div>
                    <div class="upcoming-stat">
                        <span>Pool Collected (${u.poolType})</span>
                        <strong>₹${fmt(u.totalCollected)}</strong>
                    </div>
                    <div class="upcoming-stat">
                        <span>Payout Owed (${u.winnersCount} Wins)</span>
                        <strong style="color:#fbbf24;">₹${fmt(u.totalPayout)}</strong>
                    </div>
                    <div class="upcoming-stat">
                        <span>Expected House Profit</span>
                        <strong style="color:${profitColor};font-size:20px;">${profitSign}₹${fmt(Math.abs(u.houseProfit))}</strong>
                    </div>
                </div>
            `;
        } else {
            card.classList.remove('locked-active');
            titleEl.textContent = `🟢 BETTING OPEN — Live Cycle Forecast (Closes at final ${data.betCloseSeconds}s)`;
            countEl.textContent = `${data.timeLeft}s Left`;
            countEl.className = 'upcoming-countdown-badge';

            const poolAmt = data.liveTotals ? data.liveTotals.totalBetAmount : 0;
            if (poolAmt === 0) {
                bodyEl.innerHTML = `<span class="adm-help-txt">No bets placed yet in period <strong>${data.period}</strong>. Once betting closes at <strong>00:${String(data.betCloseSeconds).padStart(2, '0')}</strong>, the locked winning result and profit appear here before players see them.</span>`;
            } else {
                const bestList = (data.bestCandidates || []).map(n => `#${n}`).join(', ');
                const maxP = data.projectedMaxProfit || 0;
                bodyEl.innerHTML = `<span class="adm-help-txt">Current ${data.targetMode} Pool: <strong>₹${fmt(poolAmt)}</strong> | Candidate Pick(s): <strong>${bestList}</strong> | Best Available Profit: <strong style="color:#10b981;">${maxP >= 0 ? '+' : '-'}₹${fmt(Math.abs(maxP))}</strong> (Exact outcome locks when timer hits ${data.betCloseSeconds}s)</span>`;
            }
        }
    }

    function renderAdminHistoryTables(historyList) {
        const rowsHtml = (!historyList || historyList.length === 0)
            ? `<tr><td colspan="8" style="text-align:center;padding:20px;color:#64748b;">No completed rounds yet.</td></tr>`
            : historyList.map(item => {
                const badgeClass = getBallClass(item.number, item.colors);
                const colorLabels = (item.colors || []).map(c => c.toUpperCase()).join(' + ');
                const sizeClass = item.size === 'big' ? 'tag-big' : 'tag-small';
                const profit = item.houseProfit || 0;
                const pSign = profit >= 0 ? '+' : '-';
                const pColor = profit > 0 ? '#10b981' : (profit < 0 ? '#ef4444' : '#94a3b8');
                return `
                    <tr>
                        <td class="history-period-col">${item.period}</td>
                        <td><div class="history-num-badge ${badgeClass}">${item.number}</div></td>
                        <td style="font-size:11px;font-weight:700;color:${(item.colors || []).includes('green') ? '#10b981' : '#ef4444'}">${colorLabels}</td>
                        <td><span class="tag-size ${sizeClass}">${String(item.size || '').toUpperCase()}</span></td>
                        <td>₹${fmt(item.totalCollected || 0)}</td>
                        <td>₹${fmt(item.totalPayout || 0)}</td>
                        <td style="color:${pColor};font-weight:700;">${pSign}₹${fmt(Math.abs(profit))}</td>
                        <td style="font-size:11px;color:#64748b;">${fmtDate(item.timestamp)}</td>
                    </tr>
                `;
            }).join('');

        const liveBody = document.getElementById('admLiveHistoryBody');
        const fullBody = document.getElementById('admFullHistoryBody');
        if (liveBody) liveBody.innerHTML = rowsHtml;
        if (fullBody) fullBody.innerHTML = rowsHtml;
    }

    function renderAdminAnalytics(data) {
        if (!data) return;
        latestAnalytics = data;

        const pEl = document.getElementById('admPeriodNum');
        const tEl = document.getElementById('admTimeLeft');
        const sEl = document.getElementById('admRoundStatus');
        const tcEl = document.getElementById('admTimingChip');
        const dbEl = document.getElementById('admDbStatus');
        if (pEl) pEl.textContent = data.period;
        if (tEl) {
            const m = Math.floor(data.timeLeft / 60);
            const s = data.timeLeft % 60;
            tEl.textContent = `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
        }
        if (sEl) {
            sEl.textContent = data.status;
            sEl.className = `status-chip ${data.status === 'LOCKED' ? 'chip-danger' : 'chip-ok'}`;
        }
        if (tcEl) {
            tcEl.textContent = `${data.cycleDurationSeconds || 30}s / Close ${data.betCloseSeconds || 5}s`;
        }
        if (dbEl) {
            dbEl.textContent = data.mysqlConnected ? '🗄️ MySQL: gb_colors' : '⚠️ Local H2 Mode';
            dbEl.className = `status-chip ${data.mysqlConnected ? 'chip-ok' : 'chip-warn'}`;
        }

        // KPIs
        const stats = data.financialStats;
        const netEl = document.getElementById('kpiNetProfit');
        if (netEl && stats) {
            netEl.textContent = `${stats.totalHouseProfit < 0 ? '-' : '+'}₹${fmt(Math.abs(stats.totalHouseProfit))}`;
            netEl.style.color = stats.totalHouseProfit >= 0 ? '#10b981' : '#ef4444';
        }
        if (document.getElementById('kpiProfitSub') && stats) {
            document.getElementById('kpiProfitSub').textContent =
                `Collected: ₹${fmt(stats.totalBetsCollectedReal)} | Paid: ₹${fmt(stats.totalPayoutsPaidReal)}`;
        }
        if (document.getElementById('kpiTotalRecharges') && stats) {
            document.getElementById('kpiTotalRecharges').textContent = `₹${fmt(stats.totalRecharges)}`;
        }
        if (document.getElementById('kpiWithdrawPaidSub') && stats) {
            document.getElementById('kpiWithdrawPaidSub').textContent = `Withdrawals Paid: ₹${fmt(stats.totalWithdrawalsPaid)}`;
        }
        if (document.getElementById('kpiUsersHolding') && stats) {
            document.getElementById('kpiUsersHolding').textContent = `₹${fmt(stats.totalUsersRealWallet)}`;
        }
        if (document.getElementById('kpiTotalUsers') && stats) {
            document.getElementById('kpiTotalUsers').textContent = stats.totalRegisteredUsers;
        }
        const pendingDep = (stats && stats.pendingDepositsCount) || 0;
        const pendingWth = (stats && stats.pendingWithdrawalsCount) || 0;
        const totalPendingPay = pendingDep + pendingWth;
        if (document.getElementById('kpiPendingWithdrawTxt')) {
            document.getElementById('kpiPendingWithdrawTxt').textContent = `Pending: ${pendingDep} Dep | ${pendingWth} Wth`;
        }
        if (document.getElementById('admWithdrawBadge')) {
            document.getElementById('admWithdrawBadge').textContent = totalPendingPay;
        }
        const drawerBadge = document.getElementById('admDrawerPendingBadge') || document.getElementById('admDrawerPayBadge');
        if (drawerBadge) {
            drawerBadge.textContent = totalPendingPay;
            drawerBadge.style.display = totalPendingPay > 0 ? 'inline-block' : 'none';
        }

        renderUpcomingPreview(data);

        // Live Pools & Clear Mode Label
        const modeBadgeEl = document.getElementById('admTargetModeBadge');
        if (modeBadgeEl) {
            if (data.targetMode === 'REAL') {
                modeBadgeEl.textContent = `Optimizing: REAL MONEY (₹${fmt(data.liveTotals.realBetAmount)})`;
            } else if (data.targetMode === 'DEMO') {
                modeBadgeEl.textContent = `Demo Practice Pool Only (₹${fmt(data.liveTotals.demoBetAmount)})`;
            } else {
                modeBadgeEl.textContent = 'Waiting for Bets (No Active Pool)';
            }
        }
        if (document.getElementById('poolGreen') && data.liveTotals?.colors) document.getElementById('poolGreen').textContent = `₹${fmt(data.liveTotals.colors.GREEN)}`;
        if (document.getElementById('poolViolet') && data.liveTotals?.colors) document.getElementById('poolViolet').textContent = `₹${fmt(data.liveTotals.colors.VIOLET)}`;
        if (document.getElementById('poolRed') && data.liveTotals?.colors) document.getElementById('poolRed').textContent = `₹${fmt(data.liveTotals.colors.RED)}`;
        if (document.getElementById('poolBig') && data.liveTotals?.sizes) document.getElementById('poolBig').textContent = `₹${fmt(data.liveTotals.sizes.BIG)}`;
        if (document.getElementById('poolSmall') && data.liveTotals?.sizes) document.getElementById('poolSmall').textContent = `₹${fmt(data.liveTotals.sizes.SMALL)}`;

        if (data.liveTotals?.numbers) {
            for (let i = 0; i <= 9; i++) {
                const numEl = document.getElementById(`poolNum-${i}`);
                if (numEl) numEl.textContent = `₹${fmt(data.liveTotals.numbers[i])}`;
            }
        }

        // Engine Status
        const btnSmart = document.getElementById('btnModeSmart');
        const btnRand = document.getElementById('btnModeRandom');
        const engineStatusEl = document.getElementById('admEngineStatus');

        btnSmart?.classList.toggle('active', data.outcomeMode === 'SMART_PROFIT');
        btnRand?.classList.toggle('active', data.outcomeMode === 'FAIR_RANDOM');

        if (engineStatusEl) {
            if (data.outcomeMode === 'MANUAL') {
                engineStatusEl.textContent = `FORCED NEXT: #${data.forcedNextResult}`;
            } else if (data.outcomeMode === 'SMART_PROFIT') {
                engineStatusEl.textContent = `SMART PROFIT (MIN ${data.adminMinProfitPercent ?? 15}%)`;
            } else {
                engineStatusEl.textContent = 'FAIR RANDOM ACTIVE';
            }
        }

        // Projections Table
        const summaryLbl = document.getElementById('admProjSummaryLabel');
        const hasAnyPool = data.liveTotals && data.liveTotals.totalBetAmount > 0;
        if (summaryLbl) {
            const activePool = data.targetMode === 'REAL' ? data.liveTotals.realBetAmount : data.liveTotals.totalBetAmount;
            summaryLbl.textContent = hasAnyPool
                ? `Candidate Projection (0–9) — Active Pool: ₹${fmt(activePool)} | Min Admin Target: ₹${fmt(data.requiredMinProfit || 0)} (${data.adminMinProfitPercent ?? 15}%)`
                : 'Candidate Projection (0–9) — No bets placed yet';
        }

        const projBody = document.getElementById('admProjectionBody');
        if (projBody && data.projections) {
            projBody.innerHTML = data.projections.map(p => {
                const isBest = data.bestCandidates.includes(p.number);
                const isForced = data.outcomeMode === 'MANUAL' && data.forcedNextResult === p.number;
                const profitColor = p.houseProfit >= 0 ? '#10b981' : '#ef4444';
                const sign = p.houseProfit >= 0 ? '+' : '-';

                let statusBadge;
                if (!hasAnyPool) {
                    statusBadge = '<span class="badge-muted">Waiting for Bets</span>';
                } else if (isForced) {
                    statusBadge = '<span class="badge-optimal">🎯 FORCED MANUAL</span>';
                } else if (isBest) {
                    statusBadge = '<span class="badge-optimal">🏆 WILL PICK (Admin+User)</span>';
                } else if (p.meetsMargin) {
                    statusBadge = '<span class="badge-muted" style="color:#60a5fa;">✅ Meets Margin</span>';
                } else if (p.houseProfit >= 0) {
                    statusBadge = '<span class="badge-muted" style="color:#fbbf24;">⚠️ Below Margin</span>';
                } else {
                    statusBadge = '<span class="badge-muted" style="color:#f87171;">❌ House Loss</span>';
                }

                return `
                    <tr class="${(hasAnyPool && isBest) || isForced ? 'row-optimal' : ''}">
                        <td><strong>#${p.number}</strong></td>
                        <td style="font-size:11px;">${p.colors.join('+').toUpperCase()}</td>
                        <td>₹${fmt(p.totalPayout)}</td>
                        <td style="color:${profitColor}; font-weight:700;">${sign}₹${fmt(Math.abs(p.houseProfit))}</td>
                        <td>${statusBadge}</td>
                    </tr>
                `;
            }).join('');
        }

        renderPaymentQueues(data.depositRequests, data.withdrawRequests);
        renderUsersTable(data.users);
        renderAdminNotificationsPanel(data.users, data.adminMessages);

        const barForecast = document.getElementById('admBarForecastTxt');
        if (barForecast) {
            if (data.status === 'LOCKED' && data.upcomingResult) {
                const u = data.upcomingResult;
                barForecast.textContent = `LOCKED #${u.number} (${u.colors.join('+').toUpperCase()}) | Profit: ${u.houseProfit >= 0 ? '+' : '-'}₹${fmt(Math.abs(u.houseProfit))} | Reveals in ${u.revealInSeconds}s`;
            } else {
                const poolAmt = data.liveTotals ? data.liveTotals.realBetAmount : 0;
                barForecast.textContent = `Real Pool: ₹${fmt(poolAmt)} | ${data.outcomeMode} (${data.adminMinProfitPercent ?? 15}% Min)`;
            }
        }

        if (!gameConfigPopulated) {
            gameConfigPopulated = true;
            document.getElementById('cfgCycleSeconds').value = data.cycleDurationSeconds || 30;
            document.getElementById('cfgBetCloseSeconds').value = data.betCloseSeconds || 5;
            document.getElementById('cfgAdminMarginPct').value = data.adminMinProfitPercent ?? 15;
            const inlineM = document.getElementById('admInlineMarginInput');
            if (inlineM) inlineM.value = data.adminMinProfitPercent ?? 15;
        }

        if (!paymentConfigPopulated && data.paymentConfig) {
            paymentConfigPopulated = true;
            document.getElementById('cfgUpiId').value = data.paymentConfig.upiId || '';
            document.getElementById('cfgUpiPayeeName').value = data.paymentConfig.upiPayeeName || '';
            document.getElementById('cfgRzpKeyId').value = data.paymentConfig.razorpayKeyId || '';
            document.getElementById('cfgMinDeposit').value = data.paymentConfig.minDeposit ?? 100;
            document.getElementById('cfgMaxDeposit').value = data.paymentConfig.maxDeposit ?? 50000;
            document.getElementById('cfgMinWithdraw').value = data.paymentConfig.minWithdraw ?? 200;
            document.getElementById('cfgMaxWithdraw').value = data.paymentConfig.maxWithdraw ?? 50000;
            if (document.getElementById('cfgActiveCurrency') && data.paymentConfig.activeCurrency) {
                document.getElementById('cfgActiveCurrency').value = data.paymentConfig.activeCurrency;
            }
            if (document.getElementById('cfgUpiWebhookSecret') && data.paymentConfig.upiWebhookSecret) {
                document.getElementById('cfgUpiWebhookSecret').value = data.paymentConfig.upiWebhookSecret;
            }
            if (document.getElementById('cfgSmsWebhookToken') && data.paymentConfig.smsWebhookToken) {
                document.getElementById('cfgSmsWebhookToken').value = data.paymentConfig.smsWebhookToken;
            }
            if (document.getElementById('cfgPaypalClientId') && data.paymentConfig.paypalClientId) {
                document.getElementById('cfgPaypalClientId').value = data.paymentConfig.paypalClientId;
            }
            if (document.getElementById('cfgPaypalMode') && data.paymentConfig.paypalMode) {
                document.getElementById('cfgPaypalMode').value = data.paymentConfig.paypalMode;
            }
            if (data.paymentConfig.paypalEnabled && document.getElementById('cfgPaypalClientSecret')) {
                document.getElementById('cfgPaypalClientSecret').value = '********';
            }
            if (data.paymentConfig.razorpayEnabled) {
                document.getElementById('cfgRzpKeySecret').value = '********';
            }
        }
        const rzpBadge = document.getElementById('admRzpStatusBadge');
        if (rzpBadge && data.paymentConfig) {
            rzpBadge.textContent = data.paymentConfig.razorpayEnabled ? 'Razorpay + UPI Live' : 'UPI QR Active (Add RZP Keys)';
            rzpBadge.className = `status-chip ${data.paymentConfig.razorpayEnabled ? 'chip-ok' : 'chip-warn'}`;
        }

        if (!smtpFormPopulated && data.smtpStatus) {
            smtpFormPopulated = true;
            document.getElementById('cfgAdminEmail').value = data.smtpStatus.adminEmail || '';
            document.getElementById('cfgSmtpHost').value = data.smtpStatus.host || 'smtp.gmail.com';
            document.getElementById('cfgSmtpPort').value = data.smtpStatus.port || 587;
            document.getElementById('cfgSmtpUser').value = data.smtpStatus.authUser || '';
            document.getElementById('cfgFromName').value = data.smtpStatus.fromName || 'GB_Colors';
            if (data.smtpStatus.isConfigured) {
                document.getElementById('cfgSmtpPass').value = '********';
            }
        }
        const smtpBadge = document.getElementById('smtpConfigStatusBadge');
        if (smtpBadge && data.smtpStatus) {
            smtpBadge.textContent = data.smtpStatus.isConfigured ? 'LIVE SMTP READY' : 'SANDBOX MODE (Add Gmail)';
            smtpBadge.className = `status-chip ${data.smtpStatus.isConfigured ? 'chip-ok' : 'chip-warn'}`;
        }
    }

    function renderPaymentQueues(deposits, withdrawals) {
        const pendingDeps = (deposits || []).filter(d => d.status === 'PENDING' && d.method !== 'RAZORPAY');
        const pendingWths = (withdrawals || []).filter(r => r.status === 'PENDING');

        const doneDeps = (deposits || []).filter(d => d.status !== 'PENDING').map(d => ({
            kind: 'DEPOSIT',
            email: d.email,
            amount: d.amount,
            detail: `${d.method} | UTR/ID: ${d.utrOrPaymentId || d.orderId || '—'}`,
            status: d.status,
            processedAt: d.processedAt || d.requestedAt
        }));
        const doneWths = (withdrawals || []).filter(w => w.status !== 'PENDING').map(w => ({
            kind: 'WITHDRAW',
            email: w.email,
            amount: w.amount,
            detail: `Account: ${w.accountDetails}`,
            status: w.status,
            processedAt: w.processedAt || w.requestedAt
        }));
        const allDone = [...doneDeps, ...doneWths].sort((a, b) => (b.processedAt || 0) - (a.processedAt || 0));

        document.getElementById('pendingDepositQueueCount').textContent = `${pendingDeps.length} Pending`;
        document.getElementById('pendingQueueCount').textContent = `${pendingWths.length} Pending`;

        const depEl = document.getElementById('admPendingDepositList');
        if (pendingDeps.length === 0) {
            depEl.innerHTML = `<div class="empty-state">No pending UPI deposit verifications.</div>`;
        } else {
            depEl.innerHTML = pendingDeps.map(d => `
                <div class="withdraw-req-card">
                    <div class="wth-top">
                        <div>
                            <strong>${d.email}</strong> <small>(${d.userId})</small>
                            <div class="wth-upi">${d.method} UTR: <strong>${d.utrOrPaymentId || '—'}</strong></div>
                            <div class="wth-time">Requested: ${fmtDate(d.requestedAt)}</div>
                            ${d.proofImage ? `<button type="button" class="btn-copy-mini" style="margin-top:6px;background:rgba(56,189,248,0.2);color:#38bdf8;border:1px solid rgba(56,189,248,0.5);font-weight:700;" onclick="viewProofScreenshot('${d.id}')">📷 View Proof Screenshot</button>` : ''}
                        </div>
                        <div class="wth-amt" style="color:#34d399;">+₹${fmt(d.amount)}</div>
                    </div>
                    <div class="wth-actions">
                        <button class="btn-approve" onclick="processDepositAction('${d.id}', 'APPROVED')">✅ Verify &amp; Credit</button>
                        <button class="btn-reject" onclick="processDepositAction('${d.id}', 'REJECTED')">❌ Reject</button>
                    </div>
                </div>
            `).join('');
        }

        const pendingEl = document.getElementById('admPendingWithdrawList');
        if (pendingWths.length === 0) {
            pendingEl.innerHTML = `<div class="empty-state">No pending withdrawal requests.</div>`;
        } else {
            pendingEl.innerHTML = pendingWths.map(r => {
                const isBank = r.withdrawMethod === 'BANK' || Boolean(r.bankAccountNumber);
                const detailsHtml = isBank
                    ? `<div class="wth-upi" style="display:flex;flex-direction:column;gap:3px;">
                        <span><strong class="status-pill neutral">🏦 BANK IMPS/NEFT</strong> ${r.bankName ? `<strong>${esc(r.bankName)}</strong>` : ''}</span>
                        <span>Holder: <strong>${esc(r.accountHolderName || '—')}</strong></span>
                        <span>A/C: <strong>${esc(r.bankAccountNumber || '—')}</strong> <button type="button" class="btn-copy-mini" onclick="copyTextQuick('${esc(r.bankAccountNumber || '')}', 'Account No')">Copy</button></span>
                        <span>IFSC: <strong>${esc(r.ifscCode || '—')}</strong> <button type="button" class="btn-copy-mini" onclick="copyTextQuick('${esc(r.ifscCode || '')}', 'IFSC')">Copy</button></span>
                       </div>`
                    : `<div class="wth-upi">
                        <span class="status-pill info">📱 UPI</span>
                        <strong>${esc(r.upiId || r.accountDetails)}</strong>
                        ${r.upiId ? `<button type="button" class="btn-copy-mini" onclick="copyTextQuick('${esc(r.upiId)}', 'UPI ID')">Copy</button>` : ''}
                        ${r.accountHolderName ? `<div>Name: <strong>${esc(r.accountHolderName)}</strong></div>` : ''}
                       </div>`;
                return `
                    <div class="withdraw-req-card">
                        <div class="wth-top">
                            <div>
                                <strong>${esc(r.email)}</strong> <small>(${esc(r.userId)})</small>
                                ${detailsHtml}
                                <div class="wth-time">Requested: ${fmtDate(r.requestedAt)}</div>
                            </div>
                            <div class="wth-amt">₹${fmt(r.amount)}</div>
                        </div>
                        <div class="wth-actions">
                            <button class="btn-approve" onclick="processWithdrawAction('${r.id}', 'PAID')">✅ Mark Paid &amp; Notify</button>
                            <button class="btn-reject" onclick="processWithdrawAction('${r.id}', 'REJECTED')">❌ Reject &amp; Refund</button>
                        </div>
                    </div>
                `;
            }).join('');
        }

        const doneEl = document.getElementById('admDoneWithdrawList');
        if (allDone.length === 0) {
            doneEl.innerHTML = `<div class="empty-state">No completed payment requests yet.</div>`;
        } else {
            doneEl.innerHTML = allDone.slice(0, 40).map(r => {
                const ok = r.status === 'PAID' || r.status === 'APPROVED';
                return `
                    <div class="withdraw-req-card done-card">
                        <div class="wth-top">
                            <div>
                                <span class="status-chip" style="margin-right:6px;">${r.kind}</span>
                                <strong>${r.email}</strong>
                                <div class="wth-upi">${r.detail}</div>
                                <div class="wth-time">Processed: ${fmtDate(r.processedAt)}</div>
                            </div>
                            <div style="text-align:right;">
                                <div class="wth-amt">₹${fmt(r.amount)}</div>
                                <span class="status-chip ${ok ? 'chip-ok' : 'chip-danger'}">${r.status}</span>
                            </div>
                        </div>
                    </div>
                `;
            }).join('');
        }
    }

    function renderUsersTable(usersList) {
        const searchVal = (document.getElementById('admUserSearch')?.value || '').trim().toLowerCase();
        const filtered = (usersList || []).filter(u =>
            u.email.toLowerCase().includes(searchVal) ||
            u.userId.toLowerCase().includes(searchVal)
        );

        const tbody = document.getElementById('admUsersTableBody');
        if (!tbody) return;

        if (filtered.length === 0) {
            tbody.innerHTML = `<tr><td colspan="8" style="text-align:center; padding: 24px; color:#64748b;">No users found.</td></tr>`;
            return;
        }

        tbody.innerHTML = filtered.map(u => {
            const isTemp = u.blockType === 'TEMPORARY';
            const isPerm = u.blockType === 'PERMANENT';
            const isBlocked = isTemp || isPerm;
            const statusHtml = isPerm
                ? `<span class="status-chip chip-danger" title="${u.blockReason || ''}">PERM BLOCKED</span>`
                : (isTemp
                    ? `<span class="status-chip chip-warn" title="${u.blockReason || ''}">TEMP BLOCKED<br><small>Until ${fmtDate(u.blockedUntil)}</small></span>`
                    : `<span class="status-chip chip-ok">ACTIVE</span>`);

            const blockBtnHtml = isBlocked
                ? `<button class="mini-adj-btn add" onclick="unblockUser('${u.email}')">🔓 Unblock</button>`
                : `<button class="mini-adj-btn sub" onclick="openBlockUserModal('${u.email}')">🚫 Block</button>`;

            return `
                <tr>
                    <td>
                        <strong>${esc(u.email)}</strong> <span class="status-chip chip-ok" style="font-size:9px;padding:1px 5px;">Verified</span><br>
                        <small style="color:#64748b;">${esc(u.userId)}</small>
                    </td>
                    <td>${statusHtml}</td>
                    <td style="color:#10b981; font-weight:700;">₹${fmt(u.realBalance)}</td>
                    <td style="color:#94a3b8;">₹${fmt(u.demoBalance)}</td>
                    <td>
                        <span style="color:#34d399;">+₹${fmt(u.totalWon)}</span><br>
                        <span style="color:#f87171;">-₹${fmt(u.totalLost)}</span>
                    </td>
                    <td>
                        ${u.lastRechargeAmt ? `<strong>₹${fmt(u.lastRechargeAmt)}</strong><br><small style="color:#64748b;">${fmtDate(u.lastRechargeAt)}</small>` : '—'}
                    </td>
                    <td>
                        ${u.lastWithdrawAmt ? `<strong>₹${fmt(u.lastWithdrawAmt)}</strong><br><small style="color:#64748b;">${fmtDate(u.lastWithdrawAt)}</small>` : '—'}
                    </td>
                    <td style="white-space:nowrap;">
                        <button class="mini-adj-btn add" onclick="adjustUserBalance('${u.email}', 500)">+₹500</button>
                        <button class="mini-adj-btn sub" onclick="adjustUserBalance('${u.email}', -500)">-₹500</button>
                        ${blockBtnHtml}
                    </td>
                </tr>
            `;
        }).join('');
    }

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    window.copyTextQuick = async function (txt, label) {
        if (!txt) return;
        try {
            await navigator.clipboard.writeText(txt);
            window.showToast(`${label || 'Copied'}: ${txt}`, 'success');
        } catch {
            window.showToast(`${label || 'Value'}: ${txt}`, 'info');
        }
    };

    function renderAdminNotificationsPanel(usersList, messages) {
        const selectEl = document.getElementById('admNotifTargetUser');
        if (selectEl && Array.isArray(usersList)) {
            const currentVal = selectEl.value;
            const optionsHtml = ['<option value="">-- Choose a verified user --</option>']
                .concat(usersList.map(u => `<option value="${esc(u.email)}">${esc(u.email)} (${esc(u.userId)})</option>`))
                .join('');
            if (selectEl.innerHTML !== optionsHtml) {
                selectEl.innerHTML = optionsHtml;
                if (currentVal && usersList.some(u => u.email === currentVal)) {
                    selectEl.value = currentVal;
                }
            }
        }

        const feedEl = document.getElementById('admSentNotifsFeed');
        if (!feedEl) return;
        if (!Array.isArray(messages) || messages.length === 0) {
            feedEl.innerHTML = '<div class="empty-state">No Admin notifications sent yet.</div>';
            return;
        }
        feedEl.innerHTML = messages.map(m => {
            const isDirect = m.scope === 'USER';
            const badge = isDirect
                ? `<span class="status-chip chip-warn">👤 Direct → ${esc(m.targetEmail)}</span>`
                : `<span class="status-chip chip-ok">📢 Broadcast → ${m.recipientCount} User(s)</span>`;
            return `
                <div class="withdraw-req-card" style="border-left-color:${isDirect ? '#38bdf8' : '#10b981'};">
                    <div class="wth-top">
                        <div>
                            ${badge}
                            <div style="font-weight:700;margin-top:6px;">${esc(m.title)}</div>
                            <div style="font-size:13px;color:#cbd5e1;margin-top:3px;">${esc(m.message)}</div>
                            <div class="wth-time">${fmtDate(m.createdAt)}</div>
                        </div>
                    </div>
                </div>
            `;
        }).join('');
    }
})();
