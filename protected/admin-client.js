(function () {
    let adminToken = null;
    let adminSocket = null;
    let latestAnalytics = null;
    let smtpFormPopulated = false;

    window.initAdminDashboard = function (token, socket, adminInfo) {
        adminToken = token;
        adminSocket = socket;

        const emailEl = document.getElementById('admHeaderEmail');
        if (emailEl && adminInfo) emailEl.textContent = adminInfo.email;

        // Setup Admin Navigation Tabs
        document.querySelectorAll('.adm-tab-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.adm-tab-btn').forEach(b => b.classList.remove('active'));
                document.querySelectorAll('.adm-tab-panel').forEach(p => p.classList.remove('active'));
                btn.classList.add('active');
                const target = btn.getAttribute('data-admtab');
                document.getElementById(target)?.classList.add('active');
            });
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

        // Logout button
        document.getElementById('admLogoutBtn')?.addEventListener('click', () => {
            window.performLogout();
        });

        // Search users filter
        document.getElementById('admUserSearch')?.addEventListener('input', () => {
            if (latestAnalytics) renderUsersTable(latestAnalytics.users);
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

        // Listen to socket updates
        adminSocket.on('admin_init', (data) => {
            renderAdminAnalytics(data.analytics);
        });

        adminSocket.on('admin_live_update', (analytics) => {
            renderAdminAnalytics(analytics);
        });

        adminSocket.on('game_tick', (tick) => {
            const pEl = document.getElementById('admPeriodNum');
            const tEl = document.getElementById('admTimeLeft');
            const sEl = document.getElementById('admRoundStatus');
            if (pEl) pEl.textContent = tick.period;
            if (tEl) tEl.textContent = `00:${String(tick.timeLeft).padStart(2, '0')}`;
            if (sEl) {
                sEl.textContent = tick.status;
                sEl.className = `status-chip ${tick.status === 'LOCKED' ? 'chip-danger' : 'chip-ok'}`;
            }
        });
    };

    window.setOutcomeMode = function (mode, forcedNum = null) {
        if (!adminSocket) return;
        adminSocket.emit('admin_set_outcome_mode', { mode, forcedNum }, (res) => {
            if (res.success) {
                window.showToast(
                    mode === 'MANUAL'
                        ? `Forced Next Winning Number to ${forcedNum}`
                        : `Outcome Engine set to ${mode.replace('_', ' ')}`
                );
            }
        });
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

    function renderAdminAnalytics(data) {
        if (!data) return;
        latestAnalytics = data;

        // Timer info & DB Status
        const pEl = document.getElementById('admPeriodNum');
        const tEl = document.getElementById('admTimeLeft');
        const sEl = document.getElementById('admRoundStatus');
        const dbEl = document.getElementById('admDbStatus');
        if (pEl) pEl.textContent = data.period;
        if (tEl) tEl.textContent = `00:${String(data.timeLeft).padStart(2, '0')}`;
        if (sEl) sEl.textContent = data.status;
        if (dbEl) {
            dbEl.textContent = data.mysqlConnected ? '🗄️ MySQL: gb_colors (Auto-Schema)' : '⚠️ Local H2 Mode (Add MySQL Pass in .env)';
            dbEl.className = `status-chip ${data.mysqlConnected ? 'chip-ok' : 'chip-warn'}`;
        }

        // KPIs
        const stats = data.financialStats;
        const netEl = document.getElementById('kpiNetProfit');
        if (netEl) {
            netEl.textContent = `${stats.totalHouseProfit < 0 ? '-' : '+'}₹${fmt(Math.abs(stats.totalHouseProfit))}`;
            netEl.style.color = stats.totalHouseProfit >= 0 ? '#10b981' : '#ef4444';
        }
        document.getElementById('kpiProfitSub').textContent =
            `Collected: ₹${fmt(stats.totalBetsCollectedReal)} | Paid: ₹${fmt(stats.totalPayoutsPaidReal)}`;
        document.getElementById('kpiTotalRecharges').textContent = `₹${fmt(stats.totalRecharges)}`;
        document.getElementById('kpiWithdrawPaidSub').textContent = `Withdrawals Paid: ₹${fmt(stats.totalWithdrawalsPaid)}`;
        document.getElementById('kpiUsersHolding').textContent = `₹${fmt(stats.totalUsersRealWallet)}`;
        document.getElementById('kpiTotalUsers').textContent = stats.totalRegisteredUsers;
        document.getElementById('kpiPendingWithdrawTxt').textContent = `Pending Withdraws: ${stats.pendingWithdrawalsCount}`;
        document.getElementById('admWithdrawBadge').textContent = stats.pendingWithdrawalsCount;

        // Live Pools
        document.getElementById('admTargetModeBadge').textContent = `Optimizing: ${data.targetMode} BETS`;
        document.getElementById('poolGreen').textContent = `₹${fmt(data.liveTotals.colors.GREEN)}`;
        document.getElementById('poolViolet').textContent = `₹${fmt(data.liveTotals.colors.VIOLET)}`;
        document.getElementById('poolRed').textContent = `₹${fmt(data.liveTotals.colors.RED)}`;
        document.getElementById('poolBig').textContent = `₹${fmt(data.liveTotals.sizes.BIG)}`;
        document.getElementById('poolSmall').textContent = `₹${fmt(data.liveTotals.sizes.SMALL)}`;

        for (let i = 0; i <= 9; i++) {
            const numEl = document.getElementById(`poolNum-${i}`);
            if (numEl) numEl.textContent = `₹${fmt(data.liveTotals.numbers[i])}`;
        }

        // Engine Status
        const btnSmart = document.getElementById('btnModeSmart');
        const btnRand = document.getElementById('btnModeRandom');
        const engineStatusEl = document.getElementById('admEngineStatus');

        btnSmart?.classList.toggle('active', data.outcomeMode === 'SMART_PROFIT');
        btnRand?.classList.toggle('active', data.outcomeMode === 'FAIR_RANDOM');

        if (data.outcomeMode === 'MANUAL') {
            engineStatusEl.textContent = `FORCED NEXT: #${data.forcedNextResult}`;
        } else if (data.outcomeMode === 'SMART_PROFIT') {
            engineStatusEl.textContent = 'SMART AUTO-PROFIT ACTIVE';
        } else {
            engineStatusEl.textContent = 'FAIR RANDOM ACTIVE';
        }

        // Candidate Projections Table
        const projBody = document.getElementById('admProjectionBody');
        if (projBody && data.projections) {
            projBody.innerHTML = data.projections.map(p => {
                const isBest = data.bestCandidates.includes(p.number);
                const profitColor = p.houseProfit >= 0 ? '#10b981' : '#ef4444';
                const sign = p.houseProfit >= 0 ? '+' : '-';
                return `
                    <tr class="${isBest ? 'row-optimal' : ''}">
                        <td><strong>#${p.number}</strong></td>
                        <td style="font-size:11px;">${p.colors.join('+').toUpperCase()}</td>
                        <td>₹${fmt(p.totalPayout)}</td>
                        <td style="color:${profitColor}; font-weight:700;">${sign}₹${fmt(Math.abs(p.houseProfit))}</td>
                        <td>
                            ${isBest
                                ? '<span class="badge-optimal">🏆 BEST PROFIT</span>'
                                : '<span class="badge-muted">Avoided</span>'
                            }
                        </td>
                    </tr>
                `;
            }).join('');
        }

        // Withdraw Requests
        renderWithdrawLists(data.withdrawRequests);

        // Users Table
        renderUsersTable(data.users);

        // Populate SMTP config once
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

    function renderWithdrawLists(requests) {
        const pending = (requests || []).filter(r => r.status === 'PENDING');
        const done = (requests || []).filter(r => r.status !== 'PENDING');

        document.getElementById('pendingQueueCount').textContent = `${pending.length} Pending`;

        const pendingEl = document.getElementById('admPendingWithdrawList');
        if (pending.length === 0) {
            pendingEl.innerHTML = `<div class="empty-state">No pending withdrawal requests.</div>`;
        } else {
            pendingEl.innerHTML = pending.map(r => `
                <div class="withdraw-req-card">
                    <div class="wth-top">
                        <div>
                            <strong>${r.email}</strong> <small>(${r.userId})</small>
                            <div class="wth-upi">Account / UPI: <strong>${r.accountDetails}</strong></div>
                            <div class="wth-time">Requested: ${fmtDate(r.requestedAt)}</div>
                        </div>
                        <div class="wth-amt">₹${fmt(r.amount)}</div>
                    </div>
                    <div class="wth-actions">
                        <button class="btn-approve" onclick="processWithdrawAction('${r.id}', 'PAID')">✅ Mark Paid & Approve</button>
                        <button class="btn-reject" onclick="processWithdrawAction('${r.id}', 'REJECTED')">❌ Reject & Refund</button>
                    </div>
                </div>
            `).join('');
        }

        const doneEl = document.getElementById('admDoneWithdrawList');
        if (done.length === 0) {
            doneEl.innerHTML = `<div class="empty-state">No completed withdrawal requests yet.</div>`;
        } else {
            doneEl.innerHTML = done.map(r => `
                <div class="withdraw-req-card done-card">
                    <div class="wth-top">
                        <div>
                            <strong>${r.email}</strong>
                            <div class="wth-upi">Account: ${r.accountDetails}</div>
                            <div class="wth-time">Processed: ${fmtDate(r.processedAt)}</div>
                        </div>
                        <div style="text-align:right;">
                            <div class="wth-amt">₹${fmt(r.amount)}</div>
                            <span class="status-chip ${r.status === 'PAID' ? 'chip-ok' : 'chip-danger'}">${r.status}</span>
                        </div>
                    </div>
                </div>
            `).join('');
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

        tbody.innerHTML = filtered.map(u => `
            <tr>
                <td>
                    <strong>${u.email}</strong><br>
                    <small style="color:#64748b;">${u.userId}</small>
                </td>
                <td style="color:#10b981; font-weight:700;">₹${fmt(u.realBalance)}</td>
                <td style="color:#94a3b8;">₹${fmt(u.demoBalance)}</td>
                <td style="color:#34d399;">+₹${fmt(u.totalWon)}</td>
                <td style="color:#f87171;">-₹${fmt(u.totalLost)}</td>
                <td>
                    ${u.lastRechargeAmt ? `<strong>₹${fmt(u.lastRechargeAmt)}</strong><br><small style="color:#64748b;">${fmtDate(u.lastRechargeAt)}</small>` : '—'}
                </td>
                <td>
                    ${u.lastWithdrawAmt ? `<strong>₹${fmt(u.lastWithdrawAmt)}</strong><br><small style="color:#64748b;">${fmtDate(u.lastWithdrawAt)}</small>` : '—'}
                </td>
                <td>
                    <button class="mini-adj-btn add" onclick="adjustUserBalance('${u.email}', 500)">+₹500</button>
                    <button class="mini-adj-btn sub" onclick="adjustUserBalance('${u.email}', -500)">-₹500</button>
                </td>
            </tr>
        `).join('');
    }
})();
