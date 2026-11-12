/**
 * GB_Colors Lightweight Native WebSocket Client Adapter
 * Compatible with Java Spring WebSocket (/ws) while keeping the familiar
 * io(url, { auth: { token } }) + socket.on / socket.emit(ev, data, cb) API.
 */
(function () {
    window.io = function (baseUrl, options) {
        const token = options && options.auth && options.auth.token ? String(options.auth.token) : '';
        const listeners = {};
        const pendingCallbacks = {};
        const sendQueue = [];

        let reqSeq = 0;
        let ws = null;
        let manuallyDisconnected = false;
        let reconnectTimer = null;
        let authFailedNotified = false;

        function buildWsUrl() {
            let protocol;
            let host;
            if (!baseUrl) {
                protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                host = window.location.host;
            } else {
                const parsed = new URL(baseUrl, window.location.href);
                protocol = parsed.protocol === 'https:' ? 'wss:' : 'ws:';
                host = parsed.host;
            }
            return `${protocol}//${host}/ws?token=${encodeURIComponent(token)}`;
        }

        function dispatch(eventName, payload) {
            const handlers = listeners[eventName];
            if (!handlers || handlers.length === 0) return;
            for (const fn of handlers) {
                try {
                    fn(payload);
                } catch (err) {
                    console.error(`WebSocket handler error [${eventName}]:`, err);
                }
            }
        }

        function openSocket() {
            if (manuallyDisconnected) return;
            try {
                ws = new WebSocket(buildWsUrl());
            } catch (err) {
                if (!manuallyDisconnected) {
                    reconnectTimer = setTimeout(openSocket, 2500);
                }
                return;
            }

            ws.onopen = function () {
                client.connected = true;
                while (sendQueue.length > 0 && ws && ws.readyState === WebSocket.OPEN) {
                    ws.send(sendQueue.shift());
                }
                dispatch('connect');
            };

            ws.onmessage = function (ev) {
                try {
                    const msg = JSON.parse(ev.data);
                    if (msg && msg.type === '__ack__' && msg.reqId && pendingCallbacks[msg.reqId]) {
                        const cb = pendingCallbacks[msg.reqId];
                        delete pendingCallbacks[msg.reqId];
                        cb(msg.payload);
                        return;
                    }
                    if (msg && msg.event) {
                        if (msg.event === 'connect_error') {
                            authFailedNotified = true;
                        }
                        dispatch(msg.event, msg.data);
                    }
                } catch (err) {
                    console.error('Invalid WebSocket frame:', err);
                }
            };

            ws.onclose = function (ev) {
                client.connected = false;
                if (manuallyDisconnected) return;
                dispatch('disconnect', ev);
                if (ev && ev.code === 4001) {
                    if (!authFailedNotified) {
                        authFailedNotified = true;
                        dispatch('connect_error', { message: 'AUTH_REQUIRED' });
                    }
                    return;
                }
                reconnectTimer = setTimeout(openSocket, 2500);
            };
        }

        const client = {
            connected: false,
            on: function (eventName, fn) {
                if (!listeners[eventName]) {
                    listeners[eventName] = [];
                }
                listeners[eventName].push(fn);
                return client;
            },
            emit: function (eventName, data, callback) {
                if (typeof data === 'function' && callback === undefined) {
                    callback = data;
                    data = null;
                }
                let reqId = null;
                if (typeof callback === 'function') {
                    reqId = 'req_' + (++reqSeq);
                    pendingCallbacks[reqId] = callback;
                }
                const frame = JSON.stringify({ event: eventName, data: data === undefined ? null : data, reqId });
                if (ws && ws.readyState === WebSocket.OPEN) {
                    ws.send(frame);
                } else {
                    sendQueue.push(frame);
                }
                return client;
            },
            disconnect: function () {
                manuallyDisconnected = true;
                client.connected = false;
                if (reconnectTimer) {
                    clearTimeout(reconnectTimer);
                    reconnectTimer = null;
                }
                if (ws) {
                    try {
                        ws.close(1000, 'CLIENT_LOGOUT');
                    } catch (e) {}
                    ws = null;
                }
            }
        };

        // Wait one microtask so caller attaches synchronous .on(...) handlers before connect
        Promise.resolve().then(openSocket);
        return client;
    };
})();
