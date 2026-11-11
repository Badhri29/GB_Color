/**
 * GB_Colors Frontend Configuration
 * -----------------------------------------------------------
 * When running locally (http://localhost:7070) or served directly
 * from the backend server, leave BACKEND_URL automatic.
 *
 * When deploying the frontend to GitHub Pages (https://<user>.github.io/<repo>/),
 * set ORACLE_BACKEND_URL below to your Oracle Cloud HTTPS backend URL!
 */
(function () {
    const ORACLE_BACKEND_URL = 'https://gbcolor.duckdns.org';

    const isLocal =
        window.location.hostname === 'localhost' ||
        window.location.hostname === '127.0.0.1' ||
        window.location.hostname === '';

    // If hosted on GitHub Pages (github.io) or another static frontend host, point to Oracle Cloud backend
    const isStaticFrontendHost = window.location.hostname.endsWith('github.io');

    const baseUrl = isLocal || !isStaticFrontendHost
        ? '' // Same origin (served by Java Spring Boot directly)
        : ORACLE_BACKEND_URL.replace(/\/+$/, '');

    window.GB_CONFIG = {
        BACKEND_URL: baseUrl
    };

    window.apiUrl = function (endpointPath) {
        const cleanPath = endpointPath.startsWith('/') ? endpointPath : '/' + endpointPath;
        return baseUrl + cleanPath;
    };

    window.getSocketUrl = function () {
        return baseUrl || undefined; // undefined connects to current origin
    };
})();
